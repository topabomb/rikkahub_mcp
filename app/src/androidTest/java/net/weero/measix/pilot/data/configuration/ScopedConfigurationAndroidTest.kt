package net.weero.measix.pilot.data.configuration

import net.weero.measix.pilot.data.enterprise.selectPersonalFixture

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
import me.rerere.ai.provider.BuiltInTools
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
        val model = Model(modelId = "personal", tools = setOf(BuiltInTools.Search))
        val provider = ProviderSetting.OpenAI(apiKey = "device-test-user-key", models = listOf(model), useResponseApi = true)
        val assistant = Assistant(name = "My assistant", chatModelId = model.id, builtInSearch = true)
        try {
            withEnvironment(app, root) { env ->
                env.settings.updateLocal {
                    Settings(providers = listOf(provider), assistants = listOf(assistant), assistantId = assistant.id, chatModelId = model.id)
                }
                env.sessions.enrollLocal(packet.identity, { packet.identity }, { packet })
                val selection = requireNotNull(env.sessions.observeSelectedRealmSelection().first())
                env.commands.selectResource(selection, ResourceSelectionSlot.ASSISTANT, assistant.id)
                env.commands.selectResource(selection, ResourceSelectionSlot.CHAT_MODEL, packet.identity.reference("mdl_chat"))
                val target = env.assistantTarget(assistant.id)
                env.commands.changeAssistantPreference(target, AssistantPreferenceChange.Model(packet.identity.reference("mdl_chat")))
                env.commands.changeAssistantPreference(target, AssistantPreferenceChange.Search(AssistantSearchMode.LOCAL))
                env.commands.setGatewayEnabled(selection, packet.identity.reference("twg_example"), false)
            }
            withEnvironment(app, root) { env ->
                val enterprise = env.queries.observeCurrent().first()
                assertEquals(packet.identity.scope, enterprise.scope)
                assertEquals(assistant.id, enterprise.selection(ResourceSelectionSlot.ASSISTANT).reference)
                assertEquals(packet.identity.reference("mdl_chat"), enterprise.assistantModel(assistant.id).reference)
                assertFalse(BuiltInTools.Search in enterprise.availableChatModel(enterprise.assistants.getValue(assistant.id))!!.tools)
                assertTrue(enterprise.assistants.getValue(assistant.id).enableWebSearch)
                val document = env.document()
                assertFalse(document.preferences.gateway(packet.identity.scope, packet.identity.reference("twg_example"))!!.enabled)
                val gateway = enterprise.catalog.getValue(ConfigurationKey(ConfigurationCategory.GATEWAY, packet.identity.reference("twg_example")))
                assertEquals(ResolvedGatewayEnablement(false, true), gateway.gatewayEnablement)
                assertEquals(provider, document.configuration.providers.single { it.id == provider.id })
                assertEquals(model.id, document.configuration.assistants.single { it.id == assistant.id }.chatModelId)
                assertEquals(true, document.configuration.assistants.single { it.id == assistant.id }.builtInSearch)
                assertFalse(document.configuration.assistants.any { it.id is me.rerere.common.configuration.ConfigurationReference.Enterprise })
                env.sessions.selectPersonalFixture()
                assertEquals(model.id, env.queries.observeCurrent().first().assistantModel(assistant.id).reference)
                val personal = env.queries.observeCurrent().first()
                assertTrue(BuiltInTools.Search in personal.availableChatModel(personal.assistants.getValue(assistant.id))!!.tools)
                env.sessions.finishExit(env.sessions.beginExit(requireNotNull(env.sessions.captureExitRequest())))
            }
            withEnvironment(app, root) { env ->
                assertEquals(ConfigurationScope.Personal, env.queries.observeCurrent().first().scope)
                assertEquals(packet.identity.reference("mdl_chat"), env.document().preferences.forScope(packet.identity.scope).chatModelId)
                val bob = packet.copy(identity = packet.identity.copy(userId = "bob"))
                env.sessions.enrollLocal(bob.identity, { bob.identity }, { bob })
                assertNull(env.document().preferences.assistantUsage(bob.identity.scope, assistant.id))
                assertNull(env.document().preferences.gateway(bob.identity.scope, bob.identity.reference("twg_example")))
                assertTrue(env.queries.observeCurrent().first().catalog.getValue(
                    ConfigurationKey(ConfigurationCategory.GATEWAY, bob.identity.reference("twg_example"))).gatewayEnablement!!.enabled)
                assertEquals(model.id, env.queries.observeCurrent().first().assistantModel(assistant.id).reference)
            }
        } finally { root.deleteRecursively() }
    }

    @Test
    fun enterpriseToolCreatedDefinitionAndGrantCommitTogetherAndSurviveReopening() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val root = File(app.noBackupFilesDir, "assistant-management-test-${Uuid.random()}").apply { check(mkdirs()) }
        val packet = app.assets.open(LocalEnterpriseSource.EXAMPLE_ASSET).use(EnterprisePackageCodec::decode)
        val caller = packet.identity.reference(packet.configuration.assistants.first().id)
        val child = net.weero.measix.pilot.data.ai.subassistant.buildToolCreatedAssistant("Device child", "Research", "Find evidence")
        try {
            withEnvironment(app, root) { env ->
                env.sessions.enrollLocal(packet.identity, { packet.identity }, { packet })
                val access = env.sessions.captureRealmAccess(packet.identity.scope)
                env.sessions.withRealmAccess(access) {
                    env.settings.changeAssistantPreference(access.scope, env.sessions.state.value, caller,
                        AssistantPreferenceChange.LocalTool(net.weero.measix.pilot.data.ai.tools.local.LocalToolOption.AssistantManagement, true), {}) { it() }
                    env.settings.manageAssistant(access.scope, env.sessions.state.value, caller,
                        net.weero.measix.pilot.data.datastore.AssistantManagementChange.Create(child),
                        { env.sessions.requirePublishedRealmAccess(access) }) { _, _, commit -> commit() }
                }
            }
            withEnvironment(app, root) { env ->
                val saved = env.document()
                assertEquals(child.id, saved.configuration.assistants.single { it.id == child.id }.id)
                assertEquals(setOf(child.id), saved.preferences.assistantUsage(packet.identity.scope, caller)?.additionalSubAssistantIds)
                assertFalse(saved.configuration.assistants.any { it.id is me.rerere.common.configuration.ConfigurationReference.Enterprise })
                assertTrue(saved.configuration.assistants.none { child.id in it.allowedSubAssistantIds })
                val current = env.queries.observeCurrent().first()
                assertTrue(net.weero.measix.pilot.data.ai.subassistant.SubAssistantAccessPolicy.canAccess(
                    current.assistants.getValue(caller), current.assistants.getValue(child.id)))
                val access = env.sessions.captureRealmAccess(packet.identity.scope)
                env.sessions.withRealmAccess(access) {
                    env.settings.manageAssistant(access.scope, env.sessions.state.value, caller,
                        net.weero.measix.pilot.data.datastore.AssistantManagementChange.Delete(child.id),
                        { env.sessions.requirePublishedRealmAccess(access) }) { _, _, commit -> commit() }
                }
            }
            withEnvironment(app, root) { env ->
                val saved = env.document()
                assertFalse(saved.configuration.assistants.any { it.id == child.id })
                assertTrue(saved.preferences.scopes.all { scoped -> scoped.assistantUsage.none {
                    it.assistantId == child.id || child.id in it.additionalSubAssistantIds
                } })
                assertEquals(listOf(child.id), saved.internalState.pendingAssistantDeletions.map { it.assistantId })
            }
        } finally { root.deleteRecursively() }
    }

    private suspend fun withEnvironment(app: Context, root: File, block: suspend (Environment) -> Unit) {
        val env = Environment(app, root)
        try {
            env.settings.userSettings.first { !it.init }
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
        private val repository = io.mockk.mockk<net.weero.measix.pilot.data.repository.ConversationRepository>()
        private val locks = net.weero.measix.pilot.service.runtime.ConversationOperationLocks()
        private val registry = net.weero.measix.pilot.service.runtime.ConversationRuntimeRegistry(scope, repository, locks)
        private val coordinator = net.weero.measix.pilot.service.runtime.ConversationCommandCoordinator(registry, repository, gate, locks)
        val commands = ConfigurationApplicationService(settings, sessions, gate, coordinator, io.mockk.mockk())
        suspend fun assistantTarget(id: me.rerere.common.configuration.ConfigurationReference): net.weero.measix.pilot.service.ConversationAssistantTarget {
            val selection = requireNotNull(sessions.observeSelectedRealmSelection().first())
            val draft = net.weero.measix.pilot.data.model.Conversation(assistantId = id, scope = selection.access.scope, newConversation = true, messageNodes = emptyList())
            registry.installDraft(draft)
            return net.weero.measix.pilot.service.ConversationAssistantTarget(
                net.weero.measix.pilot.service.ConversationCommandTarget(draft.id, selection) {}, id)
        }
        val queries = ConfigurationQueryService(settings, sessions, gate)
        suspend fun document(): UserSettingsDocument = JsonInstant.decodeFromString(preferences.data.first()[SettingsStore.USER_SETTINGS]!!)
    }
}
