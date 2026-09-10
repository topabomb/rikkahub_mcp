package net.weero.measix.pilot.service

import android.content.Context
import android.content.ContextWrapper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
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
import net.weero.measix.pilot.data.configuration.ModelSelectionRole
import net.weero.measix.pilot.data.configuration.UsageValue
import net.weero.measix.pilot.data.enterprise.*
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.service.runtime.ConversationRuntime
import net.weero.measix.pilot.service.runtime.ModelRequestTarget
import net.weero.measix.pilot.service.runtime.generateText
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

    @Test fun `model barrier stops original owner before syncing and failed cleanup never reopens the capture`() = runBlocking {
        for (failCleanup in listOf(false, true)) environment { env ->
            val packet = exampleEnterprisePackage()
            val authorityRoot = env.root.resolve("source")
            val source = LocalEnterpriseSource({ EnterprisePackageCodec.encode(packet).inputStream() }, env.sessions,
                LocalEnrollmentAuthority(authorityRoot),
                { EnterprisePackageCodec.json.encodeToString(EnterpriseIdentity.serializer(), packet.identity).byteInputStream() },
                LocalEnterpriseConfigurationStore(authorityRoot))
            source.enrollExample()
            val access = env.sessions.captureSelectedRealmAccess() as RealmAccess.Enterprise
            val providers = io.mockk.mockk<me.rerere.ai.provider.ProviderManager>()
            env.service = ModelExecutionService(env.settings, env.sessions, env.gate, providers, source, env.scope,
                EnterpriseSynchronizationService(env.sessions, source, env.scope))
            val stopped = CompletableDeferred<Unit>()
            val permitCleanup = CompletableDeferred<Unit>()
            val cleanupAttempted = CompletableDeferred<Unit>()
            val captured = env.capture(access, packet.identity.reference(packet.configuration.defaults.assistantId!!), stop = {
                stopped.complete(Unit)
                permitCleanup.await()
                if (failCleanup) {
                    cleanupAttempted.complete(Unit)
                    error("injected model release failure")
                }
                env.releaseAll()
                cleanupAttempted.complete(Unit)
            })
            val candidate = requireNotNull(source.candidate(access.scope))
            source.changeConfiguration(access.scope, candidate.revision) { it }
            try {
                captured.model.requests.execute { it.generateText(providers,
                    listOf(me.rerere.ai.core.ModelRequestMessage.user("hello")),
                    me.rerere.ai.provider.TextGenerationParams(captured.model.model)) }
                fail("stale model emitted")
            } catch (_: ManagedSnapshotRequired) { }
            withTimeout(10_000) { stopped.await() }
            assertEquals(1L, (env.sessions.state.value as EnterpriseState.Available).manifest.applied!!.generation)
            permitCleanup.complete(Unit)
            withTimeout(10_000) { cleanupAttempted.await() }
            if (failCleanup) assertEquals(1L, (env.sessions.state.value as EnterpriseState.Available).manifest.applied!!.generation)
            else withTimeout(10_000) { env.sessions.state.first { (it as? EnterpriseState.Available)?.manifest?.applied?.generation == 2L } }
            try { captured.model.requests.execute { fail("closed capture replayed") }; fail("old capture reopened") }
            catch (error: IllegalStateException) { assertEquals("model_execution_lease_closed", error.message) }
            io.mockk.verify { providers wasNot io.mockk.Called }
        }
    }

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
            val target = captured.model.requests.execute { it as ModelRequestTarget.Remote }
            val provider = target.provider as ProviderSetting.OpenAI
            assertEquals(env.provider.baseUrl, provider.baseUrl)
            assertEquals("rotated", provider.apiKey)
            assertEquals(env.model.modelId, captured.model.model.modelId)
            assertNotEquals(captured.model.userRevision, env.service.read(RealmAccess.Personal).userRevision)
            env.settings.updateLocal { it.copy(providers = emptyList()) }
            rejected { captured.model.requests.execute { fail("removed owner reached I/O") } }
        }
    }

    @Test fun `enterprise policy is rechecked on the next request without changing the personal definition`() = runBlocking {
        environment { env ->
            val packet = exampleEnterprisePackage()
            env.sessions.enrollFixture(packet)
            val access = env.sessions.captureSelectedRealmAccess() as RealmAccess.Enterprise
            val captured = env.capture(access)
            assertTrue(captured.model.requests.execute { it } is ModelRequestTarget.Remote)
            env.sessions.synchronize(access, packet.copy(configuration = packet.configuration.copy(
                generation = 2, policy = packet.configuration.policy.copy(allowLocalProviders = false))))
            rejected { captured.model.requests.execute { fail("revoked provider reached I/O") } }
            val personal = env.capture(RealmAccess.Personal)
            assertTrue(personal.model.requests.execute { it } is ModelRequestTarget.Remote)
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
            val target = captured.model.requests.execute { it as ModelRequestTarget.Remote }
            assertEquals("https://first.test/v1", (target.provider as ProviderSetting.OpenAI).baseUrl)
            assertEquals("", target.provider.apiKey)
            assertEquals(2, env.root.resolve("enterprise/revisions").listFiles()!!.size)
            val exit = env.sessions.beginExit(requireNotNull(env.sessions.captureExitRequest()))
            rejected { captured.model.requests.execute { fail("closing session reached I/O") } }
            env.releaseAll()
            env.sessions.finishExit(exit)
            env.sessions.enrollFixture(first)
            rejected { env.service.read(access) }
            rejected { captured.model.requests.execute { fail("old turn revived") } }
        }
    }

    @Test fun `child caller revocation is stopped at request admission without waiting for an observer`() = runBlocking {
        environment { env ->
            val child = env.assistant.copy(id = ConfigurationReference.random(), name = "Child", allowAsSubAssistant = true)
            val caller = env.assistant.copy(localTools = listOf(LocalToolOption.AssistantDelegation), allowedSubAssistantIds = setOf(child.id))
            val vision = env.model.copy(inputModalities = listOf(me.rerere.ai.provider.Modality.IMAGE))
            env.settings.updateLocal { it.copy(assistants = listOf(caller, child),
                providers = listOf(env.provider.copy(models = listOf(vision))), attachmentInspectionModelId = vision.id) }
            val configuration = env.service.read(RealmAccess.Personal).configuration
            val spec = (resolveSubAssistantRunSpec(configuration::availableChatModel, caller, child) as SubAssistantRunSpecResolution.Ready).spec
            val captured = env.capture(RealmAccess.Personal, child.id, ChildModelAdmission(caller.id, spec))
            env.settings.updateLocal { it.copy(assistants = listOf(caller.copy(allowedSubAssistantIds = emptySet()), child)) }
            for (requests in listOf(captured.model.requests, requireNotNull(captured.inspectionModel).requests)) {
                try { requests.execute { fail("revoked caller reached I/O") }; fail("revocation accepted") }
                catch (error: CancellationException) { assertEquals("target_access_revoked", error.message) }
            }
        }
    }

    @Test fun `auxiliary fallback uses original assistant and never replaces an explicit invalid choice`() = runBlocking {
        environment { env ->
            val other = env.model.copy(id = ConfigurationReference.random(), modelId = "global-other")
            env.settings.updateLocal { it.copy(providers = listOf(env.provider.copy(models = listOf(env.model, other))),
                chatModelId = other.id, fastModelId = net.weero.measix.pilot.data.datastore.DEFAULT_AUTO_MODEL_ID,
                compressModelId = net.weero.measix.pilot.data.datastore.DEFAULT_AUTO_MODEL_ID) }
            for (role in listOf(ModelSelectionRole.TITLE, ModelSelectionRole.SUGGESTION, ModelSelectionRole.COMPRESS)) {
                assertEquals(env.model.id, env.captureAuxiliary(RealmAccess.Personal, role).model.model.id)
            }
            val missing = ConfigurationReference.random()
            env.settings.updateLocal { it.copy(titleModelId = missing) }
            rejected { env.captureAuxiliary(RealmAccess.Personal, ModelSelectionRole.TITLE) }
            env.settings.updateLocal { it.copy(titleModelId = null, fastModelId = missing) }
            rejected { env.captureAuxiliary(RealmAccess.Personal, ModelSelectionRole.TITLE) }
            env.settings.updateLocal { it.copy(compressModelId = missing) }
            rejected { env.captureAuxiliary(RealmAccess.Personal, ModelSelectionRole.COMPRESS) }
        }
    }

    @Test fun `enterprise auxiliary slot differs from fixed chat binding and retains its original private revision`() = runBlocking {
        environment { env ->
            val base = exampleEnterprisePackage()
            val title = base.configuration.models.first { it.id == "mdl_chat" }.copy(id = "mdl_title", modelId = "title-original")
            val binding = base.runtimeBindings.first { it.resourceId == "mdl_chat" }.copy(resourceId = title.id,
                protocol = EnterpriseRuntimeProtocol.OPENAI_CHAT, endpoint = "https://title-original.test/v1", credential = "original")
            val packet = base.copy(configuration = base.configuration.copy(models = base.configuration.models + title,
                defaults = base.configuration.defaults.copy(titleModelId = title.id)), runtimeBindings = base.runtimeBindings + binding)
            env.sessions.enrollFixture(packet)
            val access = env.sessions.captureSelectedRealmAccess() as RealmAccess.Enterprise
            val assistant = packet.identity.reference(packet.configuration.defaults.assistantId!!)
            val captured = env.captureAuxiliary(access, ModelSelectionRole.TITLE, assistant)
            assertEquals(packet.identity.reference(title.id), captured.model.model.id)
            assertNotEquals(captured.assistant.chatModelId, captured.model.model.id)
            env.sessions.synchronize(access, packet.copy(configuration = packet.configuration.copy(generation = 2,
                models = packet.configuration.models.map { if (it.id == title.id) it.copy(modelId = "title-replacement") else it }),
                runtimeBindings = packet.runtimeBindings.map { if (it.resourceId == title.id) it.copy(endpoint = "https://replacement.test/v1") else it }))
            val target = captured.model.requests.execute { it as ModelRequestTarget.Remote }
            assertEquals("title-original", captured.model.model.modelId)
            assertEquals("https://title-original.test/v1", (target.provider as ProviderSetting.OpenAI).baseUrl)
            val exit = env.sessions.beginExit(requireNotNull(env.sessions.captureExitRequest()))
            rejected { captured.model.requests.execute { fail("closing auxiliary reached I/O") } }
            env.releaseAll()
            env.sessions.finishExit(exit)
            env.sessions.enrollFixture(packet)
            rejected { captured.model.requests.execute { fail("old auxiliary revived") } }
        }
    }

    @Test fun `enterprise explicit automatic user reference does not fall back to enterprise defaults`() = runBlocking {
        environment { env ->
            val packet = exampleEnterprisePackage()
            env.sessions.enrollFixture(packet)
            val access = env.sessions.captureSelectedRealmAccess() as RealmAccess.Enterprise
            env.preferences.edit { stored ->
                val document = net.weero.measix.pilot.utils.JsonInstant.decodeFromString<net.weero.measix.pilot.data.datastore.UserSettingsDocument>(stored[SettingsStore.USER_SETTINGS]!!)
                stored[SettingsStore.USER_SETTINGS] = net.weero.measix.pilot.utils.JsonInstant.encodeToString(document.copy(
                    preferences = document.preferences.withSelections(access.scope,
                        document.preferences.forScope(access.scope).copy(titleModelId = net.weero.measix.pilot.data.datastore.DEFAULT_AUTO_MODEL_ID))))
            }
            rejected { env.captureAuxiliary(access, ModelSelectionRole.TITLE) }
        }
    }

    @Test fun `chat capture rejects an assistant changed after worker registration before acquiring bindings`() = runBlocking {
        environment { env ->
            val other = env.assistant.copy(id = ConfigurationReference.random())
            env.settings.updateLocal { it.copy(assistants = listOf(env.assistant, other)) }
            val conversation = Conversation(assistantId = env.assistant.id, messageNodes = emptyList())
            val runtime = ConversationRuntime(conversation.id, conversation.toSnapshot(), env.scope, {})
            val turn = Uuid.random()
            val worker = Job()
            runtime.installTurnWorker(turn, worker)
            runtime.publishCommitted(net.weero.measix.pilot.service.runtime.MoveToAssistant(other.id),
                runtime.durable.copy(header = runtime.durable.header.copy(assistantId = other.id)))
            try {
                rejected { env.service.captureTurn(RealmAccess.Personal, runtime, turn, worker, other.id) { } }
                assertFalse(runtime.hasExecutionLeases)
                assertNull(runtime.captureAndRequestStop("assistant_removed", other.id))
                assertTrue(worker.isActive)
                assertNotNull(runtime.captureAndRequestStop("assistant_removed", env.assistant.id))
            } finally { worker.cancel(); runtime.releaseTurnWorker(turn, worker, false) }
        }
    }

    @Test fun `inspection shares the original capture and private binding without changing fixed chat`() = runBlocking {
        environment { env ->
            val base = exampleEnterprisePackage()
            val vision = base.configuration.models.first { it.id == "mdl_chat" }.copy(id = "mdl_inspection",
                modelId = "vision-original", inputModalities = listOf(me.rerere.ai.provider.Modality.IMAGE))
            val binding = base.runtimeBindings.first { it.resourceId == "mdl_chat" }.copy(resourceId = vision.id,
                protocol = EnterpriseRuntimeProtocol.OPENAI_CHAT, endpoint = "https://vision-original.test/v1", credential = "vision")
            val packet = base.copy(configuration = base.configuration.copy(models = base.configuration.models + vision,
                defaults = base.configuration.defaults.copy(attachmentInspectionModelId = vision.id)), runtimeBindings = base.runtimeBindings + binding)
            env.sessions.enrollFixture(packet)
            val access = env.sessions.captureSelectedRealmAccess() as RealmAccess.Enterprise
            val captured = env.capture(access, packet.identity.reference(packet.configuration.defaults.assistantId!!))
            val inspection = requireNotNull(captured.inspectionModel)
            assertEquals(captured.model.userRevision, inspection.userRevision)
            assertEquals(captured.model.enterpriseVersion, inspection.enterpriseVersion)
            assertNotEquals(captured.model.model.id, inspection.model.id)
            env.sessions.synchronize(access, packet.copy(configuration = packet.configuration.copy(generation = 2,
                models = packet.configuration.models.map { if (it.id == vision.id) it.copy(modelId = "replacement") else it }),
                runtimeBindings = packet.runtimeBindings.map { if (it.resourceId == vision.id) it.copy(endpoint = "https://replacement.test/v1") else it }))
            val target = inspection.requests.execute { it as ModelRequestTarget.Remote }
            assertEquals("https://vision-original.test/v1", (target.provider as ProviderSetting.OpenAI).baseUrl)
            assertEquals("vision-original", inspection.model.modelId)
            env.releaseAll()
            rejected { inspection.requests.execute { fail("released Turn inspection reached I/O") } }
            env.sessions.finishExit(env.sessions.beginExit(requireNotNull(env.sessions.captureExitRequest())))
        }
    }

    @Test fun `inspection policy revocation blocks borrowed requests but does not change personal configuration`() = runBlocking {
        environment { env ->
            val vision = env.model.copy(id = ConfigurationReference.random(), inputModalities = listOf(me.rerere.ai.provider.Modality.IMAGE))
            env.settings.updateLocal { it.copy(providers = listOf(env.provider.copy(models = listOf(env.model, vision))), attachmentInspectionModelId = vision.id) }
            val packet = exampleEnterprisePackage()
            env.sessions.enrollFixture(packet)
            val access = env.sessions.captureSelectedRealmAccess() as RealmAccess.Enterprise
            env.preferences.edit { stored ->
                val document = net.weero.measix.pilot.utils.JsonInstant.decodeFromString<net.weero.measix.pilot.data.datastore.UserSettingsDocument>(stored[SettingsStore.USER_SETTINGS]!!)
                stored[SettingsStore.USER_SETTINGS] = net.weero.measix.pilot.utils.JsonInstant.encodeToString(document.copy(
                    preferences = document.preferences.withSelections(access.scope,
                        document.preferences.forScope(access.scope).copy(attachmentInspectionModelId = vision.id))))
            }
            val captured = env.capture(access, packet.identity.reference(packet.configuration.defaults.assistantId!!))
            val inspection = requireNotNull(captured.inspectionModel)
            inspection.requests.execute { assertTrue(it is ModelRequestTarget.Remote) }
            env.sessions.synchronize(access, packet.copy(configuration = packet.configuration.copy(generation = 2,
                policy = packet.configuration.policy.copy(allowLocalProviders = false))))
            rejected { inspection.requests.execute { fail("revoked inspection reached I/O") } }
            assertNotNull(env.capture(RealmAccess.Personal).inspectionModel)
            assertNull(env.capture(access, captured.assistant.id).inspectionModel)
        }
    }

    @Test fun `failure preparing inspection retains the registered owner until binding cleanup`() = runBlocking {
        environment { env ->
            val base = exampleEnterprisePackage()
            val vision = base.configuration.models.first { it.id == "mdl_chat" }.copy(id = "mdl_inspection",
                inputModalities = listOf(me.rerere.ai.provider.Modality.IMAGE))
            val binding = base.runtimeBindings.first { it.resourceId == "mdl_chat" }.copy(resourceId = vision.id,
                protocol = EnterpriseRuntimeProtocol.OPENAI_CHAT, endpoint = "https://vision.test/v1", credential = "vision")
            val packet = base.copy(configuration = base.configuration.copy(models = base.configuration.models + vision,
                defaults = base.configuration.defaults.copy(attachmentInspectionModelId = vision.id)), runtimeBindings = base.runtimeBindings + binding)
            val providers = io.mockk.mockk<me.rerere.ai.provider.ProviderManager>()
            val provider = io.mockk.mockk<me.rerere.ai.provider.Provider<ProviderSetting>>()
            io.mockk.every { providers.getProviderByType(any<ProviderSetting>()) } returns provider
            io.mockk.every { provider.requestMediaCapabilities(any(), any()) } returns me.rerere.ai.provider.RequestMediaCapabilities.NONE
            env.service = ModelExecutionService(env.settings, env.sessions, env.gate, providers, io.mockk.mockk(), env.scope, io.mockk.mockk())
            env.sessions.enrollFixture(packet)
            val access = env.sessions.captureSelectedRealmAccess() as RealmAccess.Enterprise
            rejected { env.capture(access, packet.identity.reference(packet.configuration.defaults.assistantId!!)) }
            val exit = env.sessions.beginExit(requireNotNull(env.sessions.captureExitRequest()))
            rejected { env.sessions.finishExit(exit) }
            env.releaseAll()
            env.sessions.finishExit(exit)
        }
    }

    @Test fun `page image captures explicit original model while Turn image borrows its owner`() = runBlocking {
        environment { env ->
            val image = env.model.copy(id = ConfigurationReference.random(), modelId = "image-original", type = me.rerere.ai.provider.ModelType.IMAGE)
            val other = image.copy(id = ConfigurationReference.random(), modelId = "image-other")
            env.settings.updateLocal { it.copy(providers = listOf(env.provider.copy(models = listOf(env.model, image, other))),
                imageGenerationModelId = image.id, assistants = listOf(env.assistant.copy(localTools = listOf(LocalToolOption.TextToImage)))) }
            val worker = Job()
            var owner: net.weero.measix.pilot.service.runtime.ModelExecutionLease? = null
            try {
                val page = env.service.capturePageImage(requireNotNull(env.sessions.observeSelectedRealmSelection().first()), worker, image.id, stopRequest = {}) { owner = it }
                val turn = env.capture(RealmAccess.Personal)
                val tool = requireNotNull(turn.imageModel)
                assertEquals(turn.model.userRevision, tool.userRevision)
                env.settings.updateLocal { it.copy(imageGenerationModelId = other.id,
                    providers = listOf(env.provider.copy(baseUrl = "https://replacement.test/v1", apiKey = "rotated", models = listOf(env.model, image, other)))) }
                for (captured in listOf(page, tool)) {
                    assertEquals(image.id, captured.model.id)
                    val target = captured.requests.execute { it as ModelRequestTarget.Remote }
                    assertEquals(env.provider.baseUrl, (target.provider as ProviderSetting.OpenAI).baseUrl)
                    assertEquals("rotated", target.provider.apiKey)
                }
                env.settings.updateLocal { it.copy(assistants = listOf(env.assistant.copy(localTools = emptyList()))) }
                rejected { tool.requests.execute { fail("revoked image tool reached I/O") } }
                page.requests.execute { Unit }
            } finally { worker.cancel(); owner?.release() }
        }
    }

    @Test fun `enterprise image page uses image protocol and failed capture keeps its release owner`() = runBlocking {
        environment { env ->
            val base = exampleEnterprisePackage()
            val image = base.configuration.models.first { it.id == "mdl_chat" }.copy(id = "mdl_test_image", modelId = "enterprise-image",
                type = me.rerere.ai.provider.ModelType.IMAGE)
            val binding = base.runtimeBindings.first { it.resourceId == "mdl_chat" }.copy(resourceId = image.id,
                protocol = EnterpriseRuntimeProtocol.OPENAI_IMAGES, endpoint = "https://image.test/v1", credential = "image credential")
            val packet = base.copy(configuration = base.configuration.copy(models = base.configuration.models + image), runtimeBindings = base.runtimeBindings + binding)
            env.sessions.enrollFixture(packet)
            val access = env.sessions.captureSelectedRealmAccess() as RealmAccess.Enterprise
            val worker = Job()
            val owners = mutableListOf<net.weero.measix.pilot.service.runtime.ModelExecutionLease>()
            try {
                val page = env.service.capturePageImage(requireNotNull(env.sessions.observeSelectedRealmSelection().first()), worker, packet.identity.reference(image.id), stopRequest = {}) { owners += it }
                val target = page.requests.execute { it as ModelRequestTarget.Remote }
                assertEquals("https://image.test/v1", (target.provider as ProviderSetting.OpenAI).baseUrl)
                assertTrue(target.credentials is me.rerere.ai.provider.RequestCredentials.Fixed)
                rejected { env.service.capturePageImage(requireNotNull(env.sessions.observeSelectedRealmSelection().first()), worker, packet.identity.reference("mdl_chat"), stopRequest = {}) { owners += it } }
                assertEquals(2, owners.size)
            } finally { worker.cancel(); owners.forEach { it.release() } }
            env.sessions.finishExit(env.sessions.beginExit(requireNotNull(env.sessions.captureExitRequest())))
        }
    }

    @Test fun `page selection stays revoked after realm roundtrip while Turn image keeps original access`() = runBlocking {
        environment { env ->
            val image = env.model.copy(id = ConfigurationReference.random(), type = me.rerere.ai.provider.ModelType.IMAGE)
            env.settings.updateLocal { it.copy(providers = listOf(env.provider.copy(models = listOf(env.model, image))),
                imageGenerationModelId = image.id, assistants = listOf(env.assistant.copy(localTools = listOf(LocalToolOption.TextToImage)))) }
            val original = requireNotNull(env.sessions.observeSelectedRealmSelection().first())
            val worker = Job()
            val owners = mutableListOf<net.weero.measix.pilot.service.runtime.ModelExecutionLease>()
            try {
                val page = env.service.capturePageImage(original, worker, image.id, stopRequest = {}) { owners += it }
                val tool = requireNotNull(env.capture(RealmAccess.Personal).imageModel)
                env.sessions.enrollFixture(exampleEnterprisePackage())
                env.sessions.selectPersonalFixture()
                rejected { page.requests.execute { fail("old page survived realm roundtrip") } }
                rejected { env.service.capturePageImage(original, worker, image.id, stopRequest = {}) { owners += it } }
                tool.requests.execute { Unit }
            } finally { worker.cancel(); owners.forEach { it.release() } }
        }
    }

    private suspend fun environment(block: suspend (Environment) -> Unit) {
        val env = Environment(temporary.newFolder())
        try {
            env.settings.userSettings.first { !it.init }
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
        var service = testModelExecutionService(settings, sessions, gate)
        private val owners = mutableListOf<Triple<ConversationRuntime, Uuid, Job>>()
        private val auxiliary = mutableListOf<Pair<ConversationRuntime, Job>>()
        suspend fun captureAuxiliary(access: RealmAccess, role: ModelSelectionRole,
            id: ConfigurationReference = assistant.id): CapturedModelConfiguration {
            val conversation = Conversation(assistantId = id, scope = access.scope, messageNodes = emptyList())
            val runtime = ConversationRuntime(conversation.id, conversation.toSnapshot(), scope, {})
            val worker = Job()
            runtime.registerAuxiliaryWorker(access, worker)
            auxiliary += runtime to worker
            return service.captureAuxiliary(access, runtime, worker, id, role)
        }
        suspend fun capture(access: RealmAccess, id: ConfigurationReference = assistant.id, child: ChildModelAdmission? = null,
            stop: suspend () -> Unit = {}): CapturedModelConfiguration {
            val conversation = Conversation(assistantId = id, scope = access.scope, messageNodes = emptyList(),
                parentConversationId = Uuid.random().takeIf { child != null })
            val runtime = ConversationRuntime(conversation.id, conversation.toSnapshot(), scope, {})
            val turn = Uuid.random()
            val worker = Job()
            runtime.installTurnWorker(turn, worker)
            owners += Triple(runtime, turn, worker)
            return service.captureTurn(access, runtime, turn, worker, id, child, stop)
        }
        suspend fun releaseAll() {
            owners.forEach { (runtime, turn, worker) -> worker.cancel(); runtime.releaseTurnWorker(turn, worker, false) }
            owners.clear()
            auxiliary.forEach { (runtime, worker) -> worker.cancel(); runtime.releaseAuxiliaryModels(listOf(worker)) }
            auxiliary.clear()
        }
    }

    private suspend fun rejected(block: suspend () -> Unit) {
        try { block(); fail("expected rejected request") }
        catch (_: EnterpriseConfigurationException) { }
        catch (_: IllegalStateException) { }
    }
}
