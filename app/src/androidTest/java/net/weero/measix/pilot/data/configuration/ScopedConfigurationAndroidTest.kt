package net.weero.measix.pilot.data.configuration

import android.content.Context
import android.content.ContextWrapper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import net.weero.measix.pilot.AppScope
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.datastore.UserSettingsDocument
import net.weero.measix.pilot.data.datastore.UserSettingsMigration
import net.weero.measix.pilot.data.enterprise.EnterpriseAppliedStore
import net.weero.measix.pilot.data.enterprise.EnterprisePackageCodec
import net.weero.measix.pilot.data.enterprise.EnterpriseSessionController
import net.weero.measix.pilot.data.enterprise.LocalEnterpriseSource
import net.weero.measix.pilot.data.enterprise.reference
import net.weero.measix.pilot.data.enterprise.RealmAccess
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.service.ApplicationRecoveryGate
import net.weero.measix.pilot.service.ConfigurationApplicationService
import net.weero.measix.pilot.service.ConfigurationQueryService
import net.weero.measix.pilot.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class ScopedConfigurationAndroidTest {
    @Test
    fun enterpriseChoicesSurviveBothStoresReopeningWithoutCopyingUserDefinitionsOrCredentials() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val root = File(app.noBackupFilesDir, "scoped-configuration-test-${Uuid.random()}").apply { check(mkdirs()) }
        val packet = app.assets.open(LocalEnterpriseSource.EXAMPLE_ASSET).use(EnterprisePackageCodec::decode)
        val model = Model(modelId = "personal")
        val provider = ProviderSetting.OpenAI(apiKey = "device-test-user-key", models = listOf(model))
        val assistant = Assistant(name = "My assistant", chatModelId = model.id)
        try {
            withEnvironment(app, root) { env ->
                env.settings.updateLocal {
                    Settings(providers = listOf(provider), assistants = listOf(assistant), assistantId = assistant.id, chatModelId = model.id)
                }
                env.sessions.enrollLocal(packet.identity, { packet.identity }, { packet })
                val access = env.sessions.captureRealmAccess(packet.identity.scope) as RealmAccess.Enterprise
                env.commands.selectResource(access, ResourceSelectionSlot.ASSISTANT, assistant.id)
                env.commands.selectResource(access, ResourceSelectionSlot.CHAT_MODEL, packet.identity.reference("mdl_chat"))
                env.commands.updateAssistantUsage(access, assistant.id) {
                    AssistantUsagePreferences(assistant.id, chatModelId = UsageValue(packet.identity.reference("mdl_chat")))
                }
                env.commands.setGatewayEnabled(access, packet.identity.reference("gw_optional"), false)
            }
            withEnvironment(app, root) { env ->
                val enterprise = env.queries.observeCurrent().first()
                assertEquals(packet.identity.scope, enterprise.scope)
                assertEquals(assistant.id, enterprise.selection(ResourceSelectionSlot.ASSISTANT).reference)
                assertEquals(packet.identity.reference("mdl_chat"), enterprise.assistantModel(assistant.id).reference)
                val document = env.document()
                assertFalse(document.preferences.gateway(packet.identity.scope, packet.identity.reference("gw_optional"))!!.enabled)
                val gateway = enterprise.catalog.getValue(ConfigurationKey(ConfigurationCategory.GATEWAY, packet.identity.reference("gw_optional")))
                assertEquals(ResolvedGatewayEnablement(false, true), gateway.gatewayEnablement)
                assertEquals(provider, document.configuration.providers.single { it.id == provider.id })
                assertEquals(model.id, document.configuration.assistants.single { it.id == assistant.id }.chatModelId)
                assertFalse(document.configuration.assistants.any { it.id is me.rerere.common.configuration.ConfigurationReference.Enterprise })
                env.sessions.switchToPersonal()
                assertEquals(model.id, env.queries.observeCurrent().first().assistantModel(assistant.id).reference)
                env.sessions.finishExit(requireNotNull(env.sessions.beginExit()))
            }
            withEnvironment(app, root) { env ->
                assertEquals(ConfigurationScope.Personal, env.queries.observeCurrent().first().scope)
                assertEquals(packet.identity.reference("mdl_chat"), env.document().preferences.forScope(packet.identity.scope).chatModelId)
                val bob = packet.copy(identity = packet.identity.copy(userId = "bob"))
                env.sessions.enrollLocal(bob.identity, { bob.identity }, { bob })
                assertNull(env.document().preferences.assistantUsage(bob.identity.scope, assistant.id))
                assertNull(env.document().preferences.gateway(bob.identity.scope, bob.identity.reference("gw_optional")))
                assertTrue(env.queries.observeCurrent().first().catalog.getValue(
                    ConfigurationKey(ConfigurationCategory.GATEWAY, bob.identity.reference("gw_optional"))).gatewayEnablement!!.enabled)
                assertEquals(model.id, env.queries.observeCurrent().first().assistantModel(assistant.id).reference)
            }
        } finally { root.deleteRecursively() }
    }

    private suspend fun withEnvironment(app: Context, root: File, block: suspend (Environment) -> Unit) {
        val env = Environment(app, root)
        try {
            env.settings.effectiveSettings.first { !it.settings.init }
            env.sessions.recover()
            env.gate.ready()
            block(env)
        } finally { env.scope.coroutineContext[Job]!!.cancelAndJoin() }
    }

    private class Environment(app: Context, root: File) {
        val scope = AppScope(Dispatchers.Default)
        private val preferences = PreferenceDataStoreFactory.create(
            migrations = listOf(UserSettingsMigration()), scope = scope,
            produceFile = { File(root, "settings.preferences_pb") },
        )
        private val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
        }
        val settings = SettingsStore(context, scope, dataStore = preferences)
        val sessions = EnterpriseSessionController(EnterpriseAppliedStore(File(root, "enterprise")))
        val gate = ApplicationRecoveryGate()
        val commands = ConfigurationApplicationService(settings, sessions, gate)
        val queries = ConfigurationQueryService(settings, sessions, gate)
        suspend fun document(): UserSettingsDocument = JsonInstant.decodeFromString(preferences.data.first()[SettingsStore.USER_SETTINGS]!!)
    }
}
