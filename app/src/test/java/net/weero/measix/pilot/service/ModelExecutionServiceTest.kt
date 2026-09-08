package net.weero.measix.pilot.service

import android.content.Context
import android.content.ContextWrapper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.ProviderSetting
import me.rerere.common.configuration.ConfigurationReference
import net.weero.measix.pilot.AppScope
import net.weero.measix.pilot.data.ai.subassistant.SubAssistantRunSpecResolution
import net.weero.measix.pilot.data.ai.subassistant.resolveSubAssistantRunSpec
import net.weero.measix.pilot.data.ai.tools.local.LocalToolOption
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.datastore.UserSettingsMigration
import net.weero.measix.pilot.data.datastore.getChatModel
import net.weero.measix.pilot.data.configuration.AssistantUsagePreferences
import net.weero.measix.pilot.data.configuration.UsageValue
import net.weero.measix.pilot.data.enterprise.*
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.service.runtime.ConversationRuntime
import net.weero.measix.pilot.service.runtime.ModelRequestTarget
import net.weero.measix.pilot.service.runtime.toSnapshot
import net.weero.measix.pilot.test.testModelExecutionService
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ModelExecutionServiceTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `search choices reach captured requests while enterprise overrides preserve personal definitions`() = runBlocking {
        environment { env ->
            val originalModel = env.model.copy(tools = setOf(BuiltInTools.Search, BuiltInTools.UrlContext))
            env.settings.updateLocal { it.copy(
                providers = listOf(env.provider.copy(useResponseApi = true, models = listOf(originalModel))),
                assistants = listOf(env.assistant.copy(builtInSearch = true)),
            ) }
            val personal = env.capture(RealmAccess.Personal)
            assertEquals(originalModel.tools, personal.model.model.tools)
            assertEquals(personal.model.model.tools, personal.userSettings.getChatModel(personal.assistant)!!.tools)
            env.sessions.enrollFixture(exampleEnterprisePackage())
            val access = env.sessions.captureSelectedRealmAccess() as RealmAccess.Enterprise
            env.preferences.edit { stored ->
                val document = net.weero.measix.pilot.utils.JsonInstant.decodeFromString<net.weero.measix.pilot.data.datastore.UserSettingsDocument>(stored[SettingsStore.USER_SETTINGS]!!)
                stored[SettingsStore.USER_SETTINGS] = net.weero.measix.pilot.utils.JsonInstant.encodeToString(document.copy(
                    preferences = document.preferences.withAssistantUsage(access.scope,
                        AssistantUsagePreferences(env.assistant.id, builtInSearch = UsageValue(false), enableWebSearch = UsageValue(true)))))
            }
            val enterprise = env.capture(access)
            assertTrue(enterprise.assistant.enableWebSearch)
            assertEquals(setOf(BuiltInTools.UrlContext), enterprise.model.model.tools)
            assertEquals(enterprise.model.model.tools,
                env.service.read(access).configuration.availableChatModel(enterprise.assistant)!!.tools)
            val restoredPersonal = env.capture(RealmAccess.Personal)
            assertEquals(personal.model.model.tools, restoredPersonal.model.model.tools)
            assertEquals(originalModel.tools, restoredPersonal.userSettings.providers.single { it.id == env.provider.id }.models.single().tools)
        }
    }

    @Test fun `search unavailable on actual transport fails before request admission and retains user choice`() = runBlocking {
        environment { env ->
            val enabled = env.assistant.copy(builtInSearch = true)
            val originalModel = env.model.copy(providerOverwrite = ProviderSetting.Claude())
            env.settings.updateLocal { it.copy(
                assistants = listOf(enabled),
                providers = listOf(env.provider.copy(useResponseApi = true, models = listOf(originalModel))),
            ) }
            try { env.capture(RealmAccess.Personal); fail("unsupported search was silently accepted") }
            catch (error: IllegalStateException) { assertEquals("model_builtin_search_not_supported", error.message) }
            assertEquals(true, env.service.read(RealmAccess.Personal).configuration.assistants[enabled.id]!!.builtInSearch)
            env.settings.updateLocal { it.copy(assistants = listOf(enabled.copy(builtInSearch = false))) }
            assertFalse(BuiltInTools.Search in env.capture(RealmAccess.Personal).model.model.tools)
        }
    }

    @Test fun `personal request freezes wire shape and reads credentials from its original live owner`() = runBlocking {
        environment { env ->
            val captured = env.capture(RealmAccess.Personal)
            env.settings.updateLocal { settings -> settings.copy(providers = listOf(env.provider.copy(
                baseUrl = "https://replacement.test/v2", apiKey = "rotated", models = listOf(env.model.copy(modelId = "replacement")),
            ))) }
            val target = captured.model.executionLease.execute { it as ModelRequestTarget.Remote }
            val provider = target.provider as ProviderSetting.OpenAI
            assertEquals(env.provider.baseUrl, provider.baseUrl)
            assertEquals("rotated", provider.apiKey)
            assertEquals(env.model.modelId, captured.model.model.modelId)
            assertNotEquals(captured.model.userRevision, env.service.read(RealmAccess.Personal).userRevision)
            env.settings.updateLocal { it.copy(providers = emptyList()) }
            rejected { captured.model.executionLease.execute { fail("removed owner reached I/O") } }
        }
    }

    @Test fun `enterprise policy is rechecked on the next request without changing the personal definition`() = runBlocking {
        environment { env ->
            val packet = exampleEnterprisePackage()
            env.sessions.enrollFixture(packet)
            val access = env.sessions.captureSelectedRealmAccess() as RealmAccess.Enterprise
            val captured = env.capture(access)
            assertTrue(captured.model.executionLease.execute { it } is ModelRequestTarget.Remote)
            env.sessions.synchronize(access, packet.copy(configuration = packet.configuration.copy(
                generation = 2, policy = packet.configuration.policy.copy(allowLocalProviders = false))))
            rejected { captured.model.executionLease.execute { fail("revoked provider reached I/O") } }
            val personal = env.capture(RealmAccess.Personal)
            assertTrue(personal.model.executionLease.execute { it } is ModelRequestTarget.Remote)
        }
    }

    @Test fun `private revision stays with the original turn and exit revokes it before reenrollment`() = runBlocking {
        environment { env ->
            val base = exampleEnterprisePackage()
            val first = base.copy(runtimeBindings = base.runtimeBindings.map {
                if (it.resourceId == "mdl_chat") it.copy(protocol = EnterpriseRuntimeProtocol.OPENAI_CHAT,
                    endpoint = "https://first.test/v1", credential = "private, opaque") else it
            })
            env.sessions.enrollFixture(first)
            val access = env.sessions.captureSelectedRealmAccess() as RealmAccess.Enterprise
            val id = first.identity.reference(first.configuration.defaults.assistantId!!)
            val captured = env.capture(access, id)
            env.sessions.synchronize(access, first.copy(runtimeBindings = first.runtimeBindings.map {
                if (it.resourceId == "mdl_chat") it.copy(endpoint = "https://second.test/v1", credential = "second") else it
            }))
            val target = captured.model.executionLease.execute { it as ModelRequestTarget.Remote }
            assertEquals("https://first.test/v1", (target.provider as ProviderSetting.OpenAI).baseUrl)
            assertEquals("", target.provider.apiKey)
            assertEquals(2, env.root.resolve("enterprise/revisions").listFiles()!!.size)
            val exit = env.sessions.beginExit(requireNotNull(env.sessions.captureExitRequest()))
            rejected { captured.model.executionLease.execute { fail("closing session reached I/O") } }
            env.releaseAll()
            env.sessions.finishExit(exit)
            env.sessions.enrollFixture(first)
            rejected { env.service.read(access) }
            rejected { captured.model.executionLease.execute { fail("old turn revived") } }
        }
    }

    @Test fun `child caller revocation is stopped at request admission without waiting for an observer`() = runBlocking {
        environment { env ->
            val child = env.assistant.copy(id = ConfigurationReference.random(), name = "Child", allowAsSubAssistant = true)
            val caller = env.assistant.copy(localTools = listOf(LocalToolOption.AssistantDelegation), allowedSubAssistantIds = setOf(child.id))
            env.settings.updateLocal { it.copy(assistants = listOf(caller, child)) }
            val configuration = env.service.read(RealmAccess.Personal).configuration
            val spec = (resolveSubAssistantRunSpec(configuration::availableChatModel, caller, child) as SubAssistantRunSpecResolution.Ready).spec
            val captured = env.capture(RealmAccess.Personal, child.id, ChildModelAdmission(caller.id, spec))
            env.settings.updateLocal { it.copy(assistants = listOf(caller.copy(allowedSubAssistantIds = emptySet()), child)) }
            try { captured.model.executionLease.execute { fail("revoked caller reached I/O") }; fail("revocation accepted") }
            catch (error: CancellationException) { assertEquals("target_access_revoked", error.message) }
        }
    }

    private suspend fun environment(block: suspend (Environment) -> Unit) {
        val env = Environment(temporary.newFolder())
        try {
            env.settings.effectiveSettings.first { !it.settings.init }
            env.settings.updateLocal { Settings(assistants = listOf(env.assistant), providers = listOf(env.provider), chatModelId = env.model.id) }
            env.sessions.recover()
            env.gate.ready()
            block(env)
        } finally { env.releaseAll(); env.scope.coroutineContext[Job]!!.cancelAndJoin() }
    }

    private class Environment(val root: File) {
        val scope = AppScope(Dispatchers.Default)
        val model = Model(modelId = "original")
        val provider = ProviderSetting.OpenAI(baseUrl = "https://original.test/v1", apiKey = "first", models = listOf(model))
        val assistant = Assistant(name = "Personal", chatModelId = model.id, enableMemory = false)
        private val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = root.resolve("files").apply { mkdirs() }
        }
        val preferences = PreferenceDataStoreFactory.create(
            migrations = listOf(UserSettingsMigration()), scope = scope, produceFile = { root.resolve("settings.preferences_pb") })
        val settings = SettingsStore(context, scope, dataStore = preferences)
        val sessions = EnterpriseSessionController(EnterpriseAppliedStore(root.resolve("enterprise")))
        val gate = ApplicationRecoveryGate()
        val service = testModelExecutionService(settings, sessions, gate)
        private val owners = mutableListOf<Triple<ConversationRuntime, Uuid, Job>>()
        suspend fun capture(access: RealmAccess, id: ConfigurationReference = assistant.id, child: ChildModelAdmission? = null): CapturedTurnConfiguration {
            val conversation = Conversation(assistantId = id, scope = access.scope, messageNodes = emptyList(),
                parentConversationId = Uuid.random().takeIf { child != null })
            val runtime = ConversationRuntime(conversation.id, conversation.toSnapshot(), scope, {})
            val turn = Uuid.random()
            val worker = Job()
            runtime.installTurnWorker(turn, worker)
            owners += Triple(runtime, turn, worker)
            return service.captureTurn(access, runtime, turn, worker, id, child)
        }
        suspend fun releaseAll() {
            owners.forEach { (runtime, turn, worker) -> worker.cancel(); runtime.releaseTurnWorker(turn, worker, false) }
            owners.clear()
        }
    }

    private suspend fun rejected(block: suspend () -> Unit) {
        try { block(); fail("expected rejected request") }
        catch (_: EnterpriseConfigurationException) { }
        catch (_: IllegalStateException) { }
    }
}
