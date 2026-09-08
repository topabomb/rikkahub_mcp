package net.weero.measix.pilot.ui.components.ai

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.navigation3.runtime.NavKey
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import kotlinx.coroutines.CompletableDeferred
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import net.weero.measix.pilot.AppScope
import net.weero.measix.pilot.R
import net.weero.measix.pilot.Screen
import net.weero.measix.pilot.data.configuration.ResourceSelectionSlot
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.datastore.UserSettingsMigration
import net.weero.measix.pilot.data.enterprise.EnterpriseAppliedStore
import net.weero.measix.pilot.data.enterprise.EnterprisePackageCodec
import net.weero.measix.pilot.data.enterprise.EnterpriseSessionController
import net.weero.measix.pilot.data.enterprise.LocalEnterpriseSource
import net.weero.measix.pilot.service.ApplicationRecoveryGate
import net.weero.measix.pilot.service.ConfigurationApplicationService
import net.weero.measix.pilot.service.ConfigurationQueryService
import net.weero.measix.pilot.service.ModelCatalogReadState
import net.weero.measix.pilot.ui.context.LocalNavController
import net.weero.measix.pilot.ui.context.Navigator
import net.weero.measix.pilot.ui.adaptive.LocalAdaptiveLayoutInfo
import net.weero.measix.pilot.ui.adaptive.rememberAdaptiveLayoutInfo
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class ModelCatalogAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun managedChoiceAndDisabledPersonalChoiceRenderAndFailedSubmissionCanRetry() = runBlocking {
        val app = compose.activity.applicationContext
        val root = File(app.noBackupFilesDir, "model-catalog-test-${Uuid.random()}").apply { check(mkdirs()) }
        val scope = AppScope(Dispatchers.Default)
        try {
            val preferences = PreferenceDataStoreFactory.create(migrations = listOf(UserSettingsMigration()), scope = scope,
                produceFile = { File(root, "settings.preferences_pb") })
            val context = object : ContextWrapper(app) {
                override fun getApplicationContext(): Context = this
                override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
            }
            val settings = SettingsStore(context, scope, dataStore = preferences)
            settings.effectiveSettings.first { !it.settings.init }
            val personal = Model(modelId = "personal-test", displayName = "Personal test model")
            settings.updateLocal { Settings(providers = listOf(ProviderSetting.OpenAI(name = "Personal provider", models = listOf(personal)))) }
            val source = app.assets.open(LocalEnterpriseSource.EXAMPLE_ASSET).use(EnterprisePackageCodec::decode)
            val packet = source.copy(configuration = source.configuration.copy(policy = source.configuration.policy.copy(allowLocalProviders = false)))
            val sessions = EnterpriseSessionController(EnterpriseAppliedStore(File(root, "enterprise")))
            sessions.recover()
            sessions.enrollLocal(packet.identity, { packet.identity }, { packet })
            val gate = ApplicationRecoveryGate().apply { ready() }
            val queries = ConfigurationQueryService(settings, sessions, gate)
            val commands = ConfigurationApplicationService(settings, sessions, gate)
            val catalog = (queries.observeModelCatalog().first { it is ModelCatalogReadState.Available }
                as ModelCatalogReadState.Available).catalog
            val managed = catalog.groups.single { it.userProviderId == null }.models.first { it.model.type == ModelType.CHAT }.model
            lateinit var picker: ModelListState
            val reject = AtomicBoolean(true)
            val submissionStarted = CompletableDeferred<Unit>()
            val releaseSubmission = CompletableDeferred<Unit>()
            val attempts = AtomicInteger()
            val backStack = mutableListOf<NavKey>()
            compose.setContent {
                MaterialTheme {
                    CompositionLocalProvider(LocalNavController provides Navigator(backStack),
                        LocalAdaptiveLayoutInfo provides rememberAdaptiveLayoutInfo()) {
                        picker = rememberModelListState(null, catalog, ModelType.CHAT)
                        ModelSelectorButton(picker)
                        ModelListSheet(picker, onSelect = { model ->
                            attempts.incrementAndGet()
                            if (reject.get()) {
                                submissionStarted.complete(Unit)
                                releaseSubmission.await()
                                throw IOException("test_write_failure")
                            }
                            commands.selectResource(requireNotNull(catalog.selection), ResourceSelectionSlot.CHAT_MODEL, model.id)
                        }, configurationCommands = commands, configurationQueries = queries)
                    }
                }
            }
            compose.onNodeWithText(app.getString(R.string.model_list_select_model)).performClick()
            compose.onNodeWithText(personal.displayName).performScrollTo().assertIsNotEnabled()
            compose.onNodeWithContentDescription(app.getString(R.string.edit)).performClick()
            compose.runOnIdle {
                assertFalse(picker.visible)
                assertEquals(Screen.SettingProviderDetail(catalog.groups.single { it.userProviderId != null }.userProviderId.toString()), backStack.single())
            }
            compose.onNodeWithText(app.getString(R.string.model_list_select_model)).performClick()
            compose.onNodeWithText(managed.displayName).performScrollTo().performClick()
            submissionStarted.await()
            compose.onNodeWithText(managed.displayName).assertIsNotEnabled().performTouchInput { click() }
            compose.runOnIdle { assertEquals(1, attempts.get()) }
            releaseSubmission.complete(Unit)
            compose.waitUntil(5_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("test_write_failure")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("test_write_failure").assertExists()
            compose.runOnIdle { assertTrue(picker.visible) }
            val before = (queries.observeModelCatalog().first { it is ModelCatalogReadState.Available }
                as ModelCatalogReadState.Available).catalog
            assertNull(before.storedSelections.chatModelId)
            reject.set(false)
            compose.onNodeWithText(managed.displayName).performScrollTo().performClick()
            compose.waitUntil(5_000) { !picker.visible }
            val after = (queries.observeModelCatalog().first { it is ModelCatalogReadState.Available }
                as ModelCatalogReadState.Available).catalog
            assertEquals(managed.id, after.storedSelections.chatModelId)
            assertEquals(2, attempts.get())
            assertFalse(settings.effectiveSettings.value.settings.favoriteModels.contains(managed.id))
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
            root.deleteRecursively()
        }
    }
}
