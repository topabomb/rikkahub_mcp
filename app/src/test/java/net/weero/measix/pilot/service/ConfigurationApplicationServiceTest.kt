package net.weero.measix.pilot.service

import android.content.Context
import android.content.ContextWrapper
import androidx.datastore.core.FileStorage
import androidx.datastore.core.Serializer
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesFileSerializer
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import net.weero.measix.pilot.AppScope
import net.weero.measix.pilot.data.ai.mcp.McpCommonOptions
import net.weero.measix.pilot.data.ai.mcp.McpServerConfig
import net.weero.measix.pilot.data.configuration.*
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.SettingsLockedException
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.datastore.UserSettingsDocument
import net.weero.measix.pilot.data.datastore.UserSettingsMigration
import net.weero.measix.pilot.data.enterprise.*
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import io.mockk.coEvery
import io.mockk.mockk
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.repository.ConversationRepository
import net.weero.measix.pilot.service.runtime.*
import net.weero.measix.pilot.service.workspace.WorkspaceQueryService
import kotlin.uuid.Uuid
import me.rerere.common.configuration.ConfigurationReference
import io.mockk.every
import kotlinx.coroutines.flow.flowOf

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConfigurationApplicationServiceTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `chat field commands preserve personal definitions and fixed enterprise bindings`() = runTest {
        val env = environment()
        try {
            env.initialize()
            val target = env.chatTarget()
            val model = exampleEnterprisePackage().identity.reference("mdl_chat")
            env.commands.changeAssistantPreference(target, AssistantPreferenceChange.Model(model))
            env.commands.changeAssistantPreference(target, AssistantPreferenceChange.Search(AssistantSearchMode.LOCAL))
            val saved = env.diskDocument()
            assertEquals(env.assistant, saved.configuration.assistants.single { it.id == env.assistant.id })
            assertEquals(model, saved.preferences.assistantUsage(env.access.scope, env.assistant.id)!!.chatModelId!!.value)
            assertTrue(saved.preferences.assistantUsage(env.access.scope, env.assistant.id)!!.enableWebSearch!!.value)
            val fixed = exampleEnterprisePackage().configuration.assistants.first()
            val fixedTarget = env.chatTarget(exampleEnterprisePackage().identity.reference(fixed.id))
            expectCommandFailure { env.commands.changeAssistantPreference(fixedTarget, AssistantPreferenceChange.Model(env.model.id)) }
            if (fixed.mcpServerIds.isNotEmpty()) expectCommandFailure {
                env.commands.changeAssistantPreference(fixedTarget,
                    AssistantPreferenceChange.Mcp(exampleEnterprisePackage().identity.reference(fixed.mcpServerIds.first()), false))
            }
            assertEquals(JsonInstant.encodeToString(saved), JsonInstant.encodeToString(env.diskDocument()))
        } finally { env.scope.cancel() }
    }

    @Test
    fun `closed page and changed assistant reject queued field writes without touching preferences`() = runTest {
        val env = environment()
        try {
            env.initialize()
            val target = env.chatTarget()
            val before = env.diskDocument()
            env.headers[target.conversation.conversationId] = env.headers.getValue(target.conversation.conversationId)
                .copy(assistantId = ConfigurationReference.random())
            expectCommandFailure { env.commands.changeAssistantPreference(target, AssistantPreferenceChange.Search(AssistantSearchMode.LOCAL)) }
            val page = env.chatTarget()
            env.pageOpen = false
            expectCommandFailure { env.commands.changeAssistantPreference(page, AssistantPreferenceChange.Workspace(null)) }
            assertEquals(JsonInstant.encodeToString(before), JsonInstant.encodeToString(env.diskDocument()))
        } finally { env.scope.cancel() }
    }

    @Test
    fun `follow domain default and inherit shared assistant definition are distinct selections`() = runTest {
        val env = environment()
        try {
            env.initialize()
            val target = env.chatTarget()
            env.commands.changeAssistantPreference(target, AssistantPreferenceChange.Model(null))
            val followsDefault = env.queries.observeCurrent().first()
            assertEquals(exampleEnterprisePackage().identity.reference("mdl_chat"), followsDefault.assistantModel(env.assistant.id).reference)
            assertNotNull(env.diskDocument().preferences.assistantUsage(env.access.scope, env.assistant.id)!!.chatModelId)
            env.commands.changeAssistantPreference(target, AssistantPreferenceChange.InheritModel)
            val inherits = env.queries.observeCurrent().first()
            assertEquals(env.model.id, inherits.assistantModel(env.assistant.id).reference)
            assertNull(env.diskDocument().preferences.assistantUsage(env.access.scope, env.assistant.id))
        } finally { env.scope.cancel() }
    }

    @Test
    fun `personal field commit survives immediate unrelated aggregate edit and durable reread`() = runTest {
        val env = environment()
        try {
            env.initialize()
            env.sessions.selectPersonalFixture()
            val target = env.chatTarget()
            env.commands.changeAssistantPreference(target, AssistantPreferenceChange.Search(AssistantSearchMode.LOCAL))
            env.settings.updateLocal { it.copy(dynamicColor = false) }
            val saved = env.diskDocument().personalSettings()
            assertTrue(saved.assistants.single { it.id == env.assistant.id }.enableWebSearch)
            assertFalse(saved.dynamicColor)
            assertEquals(saved.assistants.single { it.id == env.assistant.id }, env.settings.snapshotLocal().assistants.single { it.id == env.assistant.id })
        } finally { env.scope.cancel() }
    }

    @Test
    fun `cancelled aggregate write holds the settings boundary through actual ack before the next edit`() = runTest {
        val env = environment()
        try {
            env.initialize()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            env.intercept = { entered.complete(Unit); release.await() }
            val first = launch { env.settings.updateLocal { it.copy(dynamicColor = false) } }
            entered.await()
            first.cancel()
            val second = launch { env.settings.updateLocal { it.copy(launchCount = 42) } }
            runCurrent()
            assertFalse(first.isCompleted)
            assertFalse(second.isCompleted)
            env.intercept = null
            release.complete(Unit)
            first.join()
            second.join()
            assertTrue(first.isCancelled)
            val saved = env.diskDocument().personalSettings()
            assertFalse(saved.dynamicColor)
            assertEquals(42, saved.launchCount)
        } finally { env.scope.cancel() }
    }

    @Test
    fun `workspace failure reports the durable directory reset and retry preserves unrelated fields`() = runTest {
        val env = environment()
        try {
            env.initialize()
            val target = env.chatTarget(cwd = "/old/path")
            val workspace = Uuid.random()
            coEvery { env.workspaces.getWorkspace(workspace.toString()) } returns mockk()
            val before = env.diskDocument()
            env.intercept = { throw IOException("injected preference write failure") }
            try {
                env.commands.changeAssistantPreference(target, AssistantPreferenceChange.Workspace(workspace))
                fail("Expected explicit partial result")
            } catch (_: WorkspacePreferenceException) { }
            assertNull(env.headers.getValue(target.conversation.conversationId).workspaceCwd)
            assertEquals(JsonInstant.encodeToString(before), JsonInstant.encodeToString(env.diskDocument()))
            env.intercept = null
            env.commands.changeAssistantPreference(target, AssistantPreferenceChange.Workspace(workspace))
            assertEquals(workspace, env.diskDocument().preferences.assistantUsage(env.access.scope, env.assistant.id)!!.workspaceId!!.value)
            assertEquals(JsonInstant.encodeToString(before.configuration), JsonInstant.encodeToString(env.diskDocument().configuration))
        } finally { env.scope.cancel() }
    }

    private suspend fun expectCommandFailure(action: suspend () -> Unit) {
        try { action(); fail("Expected rejected command") }
        catch (_: IllegalArgumentException) { }
        catch (_: IllegalStateException) { }
    }

    @Test
    fun `cancelled field writer retains the original conversation lock until preference ack`() = runTest {
        val env = environment()
        try {
            env.initialize()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            env.intercept = { entered.complete(Unit); release.await() }
            val writer = launch { env.commands.changeAssistantPreference(env.target, AssistantPreferenceChange.Search(AssistantSearchMode.LOCAL)) }
            entered.await()
            writer.cancel()
            var acquired = false
            val next = launch {
                env.coordinator.withRootHeaders(env.access.scope, listOf(env.target.conversation.conversationId)) { acquired = true }
            }
            runCurrent()
            assertFalse(acquired)
            release.complete(Unit)
            writer.join(); next.join()
            assertTrue(acquired)
            assertTrue(env.diskDocument().preferences.assistantUsage(env.access.scope, env.assistant.id)!!.enableWebSearch!!.value)
        } finally { env.scope.cancel() }
    }

    @Test
    fun `conversation query retains the original snapshot when its assistant is removed and permits a model repair`() = runTest {
        val env = environment()
        try {
            env.initialize()
            val lease = env.draftView()
            env.commands.changeAssistantPreference(ConversationAssistantTarget(lease.commandTarget, env.assistant.id),
                AssistantPreferenceChange.Search(AssistantSearchMode.BUILT_IN))
            val initial = env.chatQuery.conversationUiModel(lease).first { it?.configuration?.assistant != null }!!
            assertEquals(env.assistant.id, initial.configuration!!.assistant!!.id)
            env.settings.updateLocal { it.copy(providers = emptyList()) }
            val missingModel = env.chatQuery.conversationUiModel(lease).first { it?.configuration?.model == null }!!
            assertNotNull(missingModel.configuration!!.assistant)
            assertTrue(missingModel.configuration!!.canChangeModel)
            assertTrue(missingModel.configuration!!.builtInSearchEnabled)
            assertFalse(missingModel.configuration!!.transportCapabilities.builtInSearch)
            lease.requireOpen()
            env.settings.updateLocal { it.copy(assistants = it.assistants.filterNot { assistant -> assistant.id == env.assistant.id }) }
            val missingAssistant = env.chatQuery.conversationUiModel(lease).first { it?.configuration?.assistant == null }!!
            assertEquals(initial.snapshot, missingAssistant.snapshot)
            assertEquals(ConfigurationUnavailableReason.REFERENCE_MISSING, missingAssistant.configuration!!.assistantUnavailableReason)
            lease.requireOpen()
            lease.close()
        } finally { env.scope.cancel() }
    }

    @Test
    fun `rendered model catalog retains overrides and rejects an old selection after leaving and returning`() = runTest {
        val env = environment()
        try {
            env.initialize()
            suspend fun catalog() = (env.queries.observeModelCatalog().first { it is ModelCatalogReadState.Available }
                as ModelCatalogReadState.Available).catalog
            val initial = catalog()
            val selection = requireNotNull(initial.selection)
            assertNull(initial.storedSelections.chatModelId)
            assertEquals(exampleEnterprisePackage().identity.reference("mdl_chat"), initial.selections.chatModelId)
            env.commands.selectResource(selection, ResourceSelectionSlot.CHAT_MODEL, env.model.id)
            env.commands.setModelFavorite(selection, env.model.id, true)
            val packet = exampleEnterprisePackage()
            env.sessions.synchronize(env.access, packet.copy(configuration = packet.configuration.copy(
                generation = 2, policy = packet.configuration.policy.copy(allowLocalProviders = false))))
            val restricted = catalog()
            assertEquals(env.model.id, restricted.storedSelections.chatModelId)
            assertEquals(ConfigurationUnavailableReason.USER_CATEGORY_NOT_ALLOWED, restricted.find(env.model.id)!!.unavailableReason)
            env.commands.setModelFavorite(selection, env.model.id, false)
            env.commands.selectResource(selection, ResourceSelectionSlot.CHAT_MODEL, null)
            assertNull(catalog().storedSelections.chatModelId)
            assertEquals(initial.selections.chatModelId, catalog().selections.chatModelId)
            env.sessions.selectPersonalFixture()
            env.sessions.selectEnterpriseFixture()
            val before = JsonInstant.encodeToString(env.document())
            try {
                env.commands.setSuggestionEnabled(selection, false)
                fail("old rendered selection accepted after returning")
            } catch (error: EnterpriseConfigurationException) {
                assertEquals("enterprise_selection_revoked", error.message)
            }
            assertEquals(before, JsonInstant.encodeToString(env.document()))
            assertEquals(env.model.id, env.document().preferences.forScope(ConfigurationScope.Personal).chatModelId)
        } finally { env.scope.cancel() }
    }

    @Test
    fun `model purpose capability uses overwrite transport in both directory and atomic selection`() = runTest {
        val env = environment()
        try {
            env.initialize()
            env.sessions.selectPersonalFixture()
            val supported = Model(modelId = "supported", type = me.rerere.ai.provider.ModelType.IMAGE,
                providerOverwrite = ProviderSetting.OpenAI())
            val unsupported = Model(modelId = "unsupported", type = me.rerere.ai.provider.ModelType.IMAGE,
                providerOverwrite = ProviderSetting.Google())
            env.settings.updateLocal { it.copy(providers = listOf(
                ProviderSetting.Google(models = listOf(supported)), ProviderSetting.OpenAI(models = listOf(unsupported, env.model)))) }
            val catalog = (env.queries.observeModelCatalog().first { it is ModelCatalogReadState.Available }
                as ModelCatalogReadState.Available).catalog
            val selection = requireNotNull(catalog.selection)
            assertTrue(catalog.find(supported.id)!!.canSelect)
            assertEquals(ConfigurationUnavailableReason.RESOURCE_CAPABILITY_MISMATCH, catalog.find(unsupported.id)!!.unavailableReason)
            env.commands.selectResource(selection, ResourceSelectionSlot.IMAGE_MODEL, supported.id)
            val before = JsonInstant.encodeToString(env.document())
            for ((slot, reference) in listOf(ResourceSelectionSlot.IMAGE_MODEL to unsupported.id,
                ResourceSelectionSlot.ATTACHMENT_INSPECTION_MODEL to env.model.id)) {
                try {
                    env.commands.selectResource(selection, slot, reference)
                    fail("unsupported model selection accepted")
                } catch (_: SettingsLockedException) { }
                assertEquals(before, JsonInstant.encodeToString(env.document()))
            }
        } finally { env.scope.cancel() }
    }

    @Test
    fun `scoped write persists only a preference and personal editing retains it`() = runTest {
        val env = environment()
        try {
            env.initialize()
            val packet = exampleEnterprisePackage()
            val selected = packet.identity.reference("mdl_chat")
            env.commands.changeAssistantPreference(env.target, AssistantPreferenceChange.Model(selected))
            env.commands.changeAssistantPreference(env.target, AssistantPreferenceChange.Search(AssistantSearchMode.LOCAL))
            val durable = env.document()
            assertEquals(env.model.id, durable.configuration.assistants.single { it.id == env.assistant.id }.chatModelId)
            assertEquals(selected, durable.preferences.assistantUsage(packet.identity.scope, env.assistant.id)!!.chatModelId!!.value)
            val enterprise = env.queries.observeCurrent().first()
            assertEquals(packet.identity.scope, enterprise.scope)
            assertEquals(selected, enterprise.assistantModel(env.assistant.id).reference)
            env.settings.updateLocal { settings ->
                settings.copy(assistants = settings.assistants.map { if (it.id == env.assistant.id) it.copy(name = "Renamed") else it })
            }
            val afterEdit = env.queries.observeCurrent().first()
            assertEquals("Renamed", afterEdit.assistants[env.assistant.id]!!.name)
            assertEquals(selected, afterEdit.assistantModel(env.assistant.id).reference)
            env.sessions.selectPersonalFixture()
            val personal = env.queries.observeCurrent().first()
            assertEquals(ConfigurationScope.Personal, personal.scope)
            assertEquals(env.model.id, personal.assistantModel(env.assistant.id).reference)
        } finally { env.scope.cancel() }
    }

    @Test
    fun `policy change waits for an owned preference transaction and prevents a later forbidden selection`() = runTest {
        val env = environment()
        try {
            env.initialize()
            val packet = exampleEnterprisePackage()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            env.intercept = {
                entered.complete(Unit)
                release.await()
            }
            val writer = launch {
                env.commands.changeAssistantPreference(env.target, AssistantPreferenceChange.Mcp(env.mcp.id, true))
            }
            entered.await()
            val current = env.sessions.state.value as EnterpriseState.Available
            val policyUpdate = launch {
                env.sessions.synchronize(RealmAccess.Enterprise(packet.identity.scope, current.manifest.session!!.id),
                    packet.copy(configuration = packet.configuration.copy(generation = 2, policy = packet.configuration.policy.copy(allowLocalMcp = false))))
            }
            runCurrent()
            assertFalse(writer.isCompleted)
            assertFalse(policyUpdate.isCompleted)
            assertTrue((env.sessions.state.value as EnterpriseState.Available).configuration!!.policy.allowLocalMcp)
            release.complete(Unit)
            writer.join()
            policyUpdate.join()
            env.intercept = null
            assertFalse((env.sessions.state.value as EnterpriseState.Available).configuration!!.policy.allowLocalMcp)
            val before = env.document()
            // First clear the selection, then try to select the now forbidden user service again.
            env.commands.changeAssistantPreference(env.target, AssistantPreferenceChange.Mcp(env.mcp.id, false))
            val cleared = env.document()
            try {
                env.commands.changeAssistantPreference(env.target, AssistantPreferenceChange.Mcp(env.mcp.id, true))
                fail("Forbidden selection must be rejected")
            } catch (_: SettingsLockedException) { }
            assertEquals(JsonInstant.encodeToString(cleared), JsonInstant.encodeToString(env.document()))
            assertEquals(JsonInstant.encodeToString(before.configuration), JsonInstant.encodeToString(cleared.configuration))
        } finally { env.scope.cancel() }
    }

    @Test
    fun `same enterprise different user has independent usage and wrong principal cannot write`() = runTest {
        val env = environment()
        try {
            env.initialize()
            val alice = exampleEnterprisePackage()
            env.commands.changeAssistantPreference(env.target, AssistantPreferenceChange.Search(AssistantSearchMode.LOCAL))
            env.sessions.finishExit(env.sessions.beginExit(requireNotNull(env.sessions.captureExitRequest())))
            val bob = alice.copy(identity = alice.identity.copy(userId = "bob"))
            env.sessions.enrollFixture(bob)
            assertNull(env.document().preferences.assistantUsage(bob.identity.scope, env.assistant.id))
            try {
                env.commands.changeAssistantPreference(env.target, AssistantPreferenceChange.Search(AssistantSearchMode.OFF))
                fail("Wrong principal must be rejected")
            } catch (error: EnterpriseConfigurationException) {
                assertEquals("enterprise_data_access_unavailable", error.reason)
            }
            assertTrue(env.document().preferences.assistantUsage(alice.identity.scope, env.assistant.id)!!.enableWebSearch!!.value)
        } finally { env.scope.cancel() }
    }

    @Test
    fun `policy tightening still allows removing invalid MCP choices one at a time`() = runTest {
        val env = environment()
        try {
            env.initialize()
            val second = McpServerConfig.StreamableHTTPServer(commonOptions = McpCommonOptions(name = "Second"), url = "https://example.invalid/second")
            env.settings.updateLocal { it.copy(mcpServers = it.mcpServers + second) }
            val packet = exampleEnterprisePackage()
            env.commands.changeAssistantPreference(env.target, AssistantPreferenceChange.Mcp(env.mcp.id, true))
            env.commands.changeAssistantPreference(env.target, AssistantPreferenceChange.Mcp(second.id, true))
            val current = env.sessions.state.value as EnterpriseState.Available
            env.sessions.synchronize(RealmAccess.Enterprise(packet.identity.scope, current.manifest.session!!.id),
                packet.copy(configuration = packet.configuration.copy(generation = 2, policy = packet.configuration.policy.copy(allowLocalMcp = false))))
            env.commands.changeAssistantPreference(env.target, AssistantPreferenceChange.Mcp(env.mcp.id, false))
            assertEquals(setOf(second.id), env.document().preferences.assistantUsage(packet.identity.scope, env.assistant.id)!!.mcpServers!!.value)
            env.commands.changeAssistantPreference(env.target, AssistantPreferenceChange.Mcp(second.id, false))
            assertTrue(env.document().preferences.assistantUsage(packet.identity.scope, env.assistant.id)!!.mcpServers!!.value.isEmpty())
        } finally { env.scope.cancel() }
    }

    private fun TestScope.environment(): Environment = Environment(temporary.newFolder(), AppScope(StandardTestDispatcher(testScheduler)))

    @Test
    fun `gateway preference follows published policy without changing configuration or losing the user choice`() = runTest {
        val env = environment()
        try {
            env.initialize()
            val packet = exampleEnterprisePackage()
            val reference = packet.identity.reference("twg_example")
            val key = ConfigurationKey(ConfigurationCategory.GATEWAY, reference)
            assertEquals(ResolvedGatewayEnablement(true, true), env.queries.read(env.access).catalog.getValue(key).gatewayEnablement)
            val manifest = (env.sessions.state.value as EnterpriseState.Available).manifest
            val beforeStaleWrite = env.document()
            try {
                env.commands.setGatewayEnabled(env.selection.copy(revision = env.selection.revision - 1), reference, false)
                fail("An old rendered directory cannot mutate Gateway preferences")
            } catch (error: EnterpriseConfigurationException) { assertEquals("enterprise_selection_revoked", error.reason) }
            assertEquals(JsonInstant.encodeToString(beforeStaleWrite), JsonInstant.encodeToString(env.document()))
            env.commands.setGatewayEnabled(env.selection, reference, false)
            assertEquals(manifest, (env.sessions.state.value as EnterpriseState.Available).manifest)
            assertEquals(ResolvedGatewayEnablement(false, true), env.queries.read(env.access).catalog.getValue(key).gatewayEnablement)
            assertFalse(env.queries.read(env.access).access(ConfigurationCategory.GATEWAY, reference).canExecute)

            val required = packet.copy(configuration = packet.configuration.copy(generation = 2,
                gateways = packet.configuration.gateways.map { if (it.id == reference.id) it.copy(enablement = GatewayEnablementPolicy.REQUIRED) else it }))
            env.sessions.synchronize(env.access, required)
            val requiredItem = env.queries.read(env.access).catalog.getValue(key)
            assertEquals(ResolvedGatewayEnablement(true, false), requiredItem.gatewayEnablement)
            assertTrue(requiredItem.access.requiredEnabled)
            assertFalse(requiredItem.access.canEditDefinition)
            val before = env.document()
            for (gateway in listOf(reference, reference.copy(id = "gw_missing"),
                reference.copy(authority = reference.authority.copy(sourceNamespace = "local:other")))) {
                try {
                    env.commands.setGatewayEnabled(env.selection, gateway, false)
                    fail("Required, missing and foreign gateways must reject preference writes")
                } catch (_: SettingsLockedException) { }
            }
            assertEquals(JsonInstant.encodeToString(before), JsonInstant.encodeToString(env.document()))
            assertFalse(before.preferences.gateway(packet.identity.scope, reference)!!.enabled)
            env.sessions.synchronize(env.access, packet.copy(configuration = packet.configuration.copy(generation = 3)))
            assertEquals(ResolvedGatewayEnablement(false, true), env.queries.read(env.access).catalog.getValue(key).gatewayEnablement)
        } finally { env.scope.cancel() }
    }

    @Test
    fun `same gateway has separate user choices and an old session cannot mutate any preference after reentry`() = runTest {
        val env = environment()
        try {
            env.initialize()
            val alice = exampleEnterprisePackage()
            val reference = alice.identity.reference("twg_example")
            env.commands.setGatewayEnabled(env.selection, reference, false)
            env.sessions.finishExit(env.sessions.beginExit(requireNotNull(env.sessions.captureExitRequest())))
            val bob = alice.copy(identity = alice.identity.copy(userId = "bob"))
            env.sessions.enrollFixture(bob)
            val bobAccess = env.sessions.captureRealmAccess(bob.identity.scope) as RealmAccess.Enterprise
            assertNull(env.document().preferences.gateway(bob.identity.scope, reference))
            assertTrue(env.queries.read(bobAccess).catalog.getValue(ConfigurationKey(ConfigurationCategory.GATEWAY, reference)).gatewayEnablement!!.enabled)
            env.commands.setGatewayEnabled(requireNotNull(env.sessions.observeSelectedRealmSelection().first()), reference, true)
            assertFalse(env.document().preferences.gateway(alice.identity.scope, reference)!!.enabled)
            env.sessions.finishExit(env.sessions.beginExit(requireNotNull(env.sessions.captureExitRequest())))
            env.sessions.enrollFixture(alice)
            val before = env.document()
            val staleActions: List<suspend () -> Unit> = listOf(
                { env.commands.setGatewayEnabled(env.selection, reference, true) },
                { env.commands.selectResource(env.selection, ResourceSelectionSlot.CHAT_MODEL, env.model.id) },
                { env.commands.setModelFavorite(env.selection, env.model.id, true) },
                { env.commands.setSuggestionEnabled(env.selection, false) },
                { env.commands.changeAssistantPreference(env.target, AssistantPreferenceChange.Search(AssistantSearchMode.OFF)) },
            )
            staleActions.forEach { action ->
                try { action(); fail("Reentry must not revive the original session") }
                catch (error: EnterpriseConfigurationException) { assertEquals("enterprise_data_access_unavailable", error.reason) }
            }
            assertEquals(JsonInstant.encodeToString(before), JsonInstant.encodeToString(env.document()))
            env.commands.setGatewayEnabled(requireNotNull(env.sessions.observeSelectedRealmSelection().first()), reference, true)
            assertTrue(env.document().preferences.gateway(alice.identity.scope, reference)!!.enabled)
        } finally { env.scope.cancel() }
    }

    @Test
    fun `realm resource choices retain invalid references and reject replacements that do not satisfy policy or capability`() = runTest {
        val env = environment()
        try {
            env.initialize()
            val packet = exampleEnterprisePackage()
            val scope = packet.identity.scope
            val commands = env.commands
            commands.selectResource(env.selection, ResourceSelectionSlot.CHAT_MODEL, env.model.id)
            commands.selectResource(env.selection, ResourceSelectionSlot.ASSISTANT, env.assistant.id)
            commands.setModelFavorite(env.selection, env.model.id, true)
            val ownConfiguration = JsonInstant.encodeToString(env.document().configuration)
            val applied = env.sessions.state.value as EnterpriseState.Available
            env.sessions.synchronize(RealmAccess.Enterprise(packet.identity.scope, applied.manifest.session!!.id),
                packet.copy(configuration = packet.configuration.copy(generation = 2, policy = packet.configuration.policy.copy(allowLocalProviders = false))))
            val restricted = env.queries.observeCurrent().first()
            assertEquals(env.model.id, restricted.selection(ResourceSelectionSlot.CHAT_MODEL).reference)
            assertEquals(ConfigurationUnavailableReason.USER_CATEGORY_NOT_ALLOWED, restricted.selection(ResourceSelectionSlot.CHAT_MODEL).unavailableReason)
            // An invalid old choice does not prevent editing an unrelated setting or removing a favorite.
            commands.setSuggestionEnabled(env.selection, false)
            commands.setModelFavorite(env.selection, env.model.id, false)
            commands.selectResource(env.selection, ResourceSelectionSlot.CHAT_MODEL, null)
            assertEquals(packet.identity.reference("mdl_chat"), env.queries.observeCurrent().first().selection(ResourceSelectionSlot.CHAT_MODEL).reference)
            for (reference in listOf(env.model.id, packet.identity.reference("mdl_image"))) {
                try {
                    commands.selectResource(env.selection, ResourceSelectionSlot.CHAT_MODEL, reference)
                    fail("A forbidden or incompatible model must not be selected")
                } catch (_: SettingsLockedException) { }
            }
            assertNull(env.document().preferences.forScope(scope).chatModelId)
            commands.selectResource(env.selection, ResourceSelectionSlot.IMAGE_MODEL, packet.identity.reference("mdl_image"))
            assertTrue(env.queries.observeCurrent().first().selection(ResourceSelectionSlot.IMAGE_MODEL).isAvailable)
            assertEquals(env.model.id, env.document().preferences.forScope(ConfigurationScope.Personal).chatModelId)
            assertEquals(ownConfiguration, JsonInstant.encodeToString(env.document().configuration))
        } finally { env.scope.cancel() }
    }

    @Test
    fun `preference commit failure leaves durable choices and query unchanged and releases authorization lock`() = runTest {
        val env = environment()
        try {
            env.initialize()
            val scope = exampleEnterprisePackage().identity.scope
            val original = JsonInstant.encodeToString(env.document())
            env.intercept = { throw IOException("simulated preference commit failure") }
            try {
                env.commands.selectResource(env.selection, ResourceSelectionSlot.CHAT_MODEL, env.model.id)
                fail("Storage failure must be reported")
            } catch (_: IOException) { }
            assertEquals(original, JsonInstant.encodeToString(env.document()))
            assertEquals(original, JsonInstant.encodeToString(env.diskDocument()))
            assertNotEquals(env.model.id, env.queries.observeCurrent().first().selection(ResourceSelectionSlot.CHAT_MODEL).reference)
            env.intercept = null
            env.sessions.selectPersonalFixture()
            env.sessions.selectEnterpriseFixture()
            env.selection = requireNotNull(env.sessions.observeSelectedRealmSelection().first())
            env.commands.selectResource(env.selection, ResourceSelectionSlot.CHAT_MODEL, env.model.id)
            assertEquals(env.model.id, env.document().preferences.forScope(scope).chatModelId)
        } finally { env.scope.cancel() }
    }

    @Test
    fun `cancellation during actual preference write holds authorization until commit then permits session exit`() = runTest {
        val env = environment()
        try {
            env.initialize()
            val scope = exampleEnterprisePackage().identity.scope
            val original = JsonInstant.encodeToString(env.diskDocument())
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            env.intercept = {
                entered.complete(Unit)
                release.await()
            }
            val writer = launch {
                env.commands.changeAssistantPreference(env.target, AssistantPreferenceChange.Search(AssistantSearchMode.LOCAL))
            }
            entered.await()
            writer.cancel()
            val exit = launch { env.sessions.finishExit(env.sessions.beginExit(requireNotNull(env.sessions.captureExitRequest()))) }
            runCurrent()
            assertFalse(writer.isCompleted)
            assertFalse(exit.isCompleted)
            assertEquals(original, JsonInstant.encodeToString(env.diskDocument()))
            assertNotEquals(ConfigurationScope.Personal, (env.sessions.state.value as EnterpriseState.Available).manifest.selectedScope)
            release.complete(Unit)
            writer.join()
            exit.join()
            env.intercept = null
            assertTrue(writer.isCancelled)
            assertTrue(env.document().preferences.assistantUsage(scope, env.assistant.id)!!.enableWebSearch!!.value)
            assertTrue(env.diskDocument().preferences.assistantUsage(scope, env.assistant.id)!!.enableWebSearch!!.value)
            assertEquals(ConfigurationScope.Personal, env.queries.observeCurrent().first().scope)
        } finally { env.scope.cancel() }
    }

    private class Environment(root: File, val scope: AppScope) {
        var intercept: (suspend () -> Unit)? = null
        private val preferencesFile = File(root, "settings.preferences_pb")
        private val preferences = PreferenceDataStoreFactory.create(
            storage = FileStorage(object : Serializer<Preferences> by PreferencesFileSerializer {
                override suspend fun writeTo(t: Preferences, output: OutputStream) {
                    intercept?.invoke()
                    PreferencesFileSerializer.writeTo(t, output)
                }
            }) { preferencesFile },
            corruptionHandler = null,
            migrations = listOf(UserSettingsMigration()), scope = scope,
        )
        private val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
        }
        val settings = SettingsStore(context, scope, dataStore = preferences)
        val sessions = EnterpriseSessionController(EnterpriseAppliedStore(File(root, "enterprise")))
        private val gate = ApplicationRecoveryGate()
        lateinit var access: RealmAccess.Enterprise
        lateinit var target: ConversationAssistantTarget
        lateinit var selection: RealmSelection
        val headers = linkedMapOf<Uuid, ConversationHeader>()
        private val repository = mockk<ConversationRepository>()
        private val locks = ConversationOperationLocks()
        private val registry = ConversationRuntimeRegistry(scope, repository, locks)
        val coordinator = ConversationCommandCoordinator(registry, repository, gate, locks)
        val workspaces = mockk<WorkspaceQueryService>()
        val commands = ConfigurationApplicationService(settings, sessions, gate, coordinator, workspaces)
        private val preview = mockk<ConversationAttachmentPreviewProjector>()
        val chatQuery = ConversationQueryService(repository, registry, mockk(), mockk(), preview, sessions, gate, settings, mockk())
        val queries = ConfigurationQueryService(settings, sessions, gate)
        val model = Model(modelId = "personal")
        val mcp = McpServerConfig.StreamableHTTPServer(commonOptions = McpCommonOptions(name = "Mine"), url = "https://example.invalid")
        val assistant = Assistant(name = "User", chatModelId = model.id)
        var pageOpen = true

        init {
            every { preview.lifecycleChanges() } returns flowOf(Unit)
            coEvery { preview.project(any(), any()) } returns emptyMap()
            coEvery { repository.getConversationHeader(any()) } answers { headers[firstArg()] }
            coEvery { repository.commit(any()) } answers {
                val mutation = (firstArg<ConversationWrite>() as ConversationWrite.Mutate).mutation
                val before = headers.getValue(mutation.conversationId)
                val cwd = requireNotNull(mutation.headerPatch).workspaceCwd
                headers[before.id] = before.copy(workspaceCwd = if (cwd is OptionalString.Set) cwd.value else before.workspaceCwd)
                true
            }
        }

        suspend fun draftView(): ConversationViewLease {
            val selected = requireNotNull(sessions.observeSelectedRealmSelection().first())
            val conversation = Conversation.ofId(Uuid.random(), assistant.id).copy(scope = selected.access.scope, newConversation = true)
            registry.installDraft(conversation)
            return ConversationViewLease(conversation.id, selected.access, selected.revision) {}
        }

        suspend fun chatTarget(assistantId: ConfigurationReference = assistant.id, cwd: String? = null): ConversationAssistantTarget {
            val selected = requireNotNull(sessions.observeSelectedRealmSelection().first())
            val conversation = Conversation.ofId(Uuid.random(), assistantId).copy(scope = selected.access.scope, workspaceCwd = cwd)
            headers[conversation.id] = conversation.toSnapshot().header
            return ConversationAssistantTarget(ConversationCommandTarget(conversation.id, selected) { check(pageOpen) }, assistantId)
        }

        suspend fun initialize() {
            settings.userSettings.first { !it.init }
            settings.updateLocal { Settings(providers = listOf(ProviderSetting.OpenAI(models = listOf(model))),
                assistants = listOf(assistant), mcpServers = listOf(mcp), assistantId = assistant.id, chatModelId = model.id) }
            sessions.enrollFixture(exampleEnterprisePackage())
            access = sessions.captureRealmAccess(exampleEnterprisePackage().identity.scope) as RealmAccess.Enterprise
            selection = requireNotNull(sessions.observeSelectedRealmSelection().first())
            gate.ready()
            target = chatTarget()
        }

        suspend fun document(): UserSettingsDocument = JsonInstant.decodeFromString(preferences.data.first()[SettingsStore.USER_SETTINGS]!!)

        suspend fun diskDocument(): UserSettingsDocument = preferencesFile.inputStream().use {
            JsonInstant.decodeFromString(PreferencesFileSerializer.readFrom(it)[SettingsStore.USER_SETTINGS]!!)
        }
    }
}
