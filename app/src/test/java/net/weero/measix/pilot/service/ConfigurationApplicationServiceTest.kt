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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConfigurationApplicationServiceTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `scoped write persists only a preference and personal editing retains it`() = runTest {
        val env = environment()
        try {
            env.initialize()
            val packet = exampleEnterprisePackage()
            val selected = packet.identity.reference("mdl_chat")
            env.commands.updateAssistantUsage(env.access, env.assistant.id) {
                AssistantUsagePreferences(env.assistant.id, chatModelId = UsageValue(selected), enableWebSearch = UsageValue(true))
            }
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
                env.commands.updateAssistantUsage(env.access, env.assistant.id) {
                    AssistantUsagePreferences(env.assistant.id, mcpServers = UsageValue(setOf(env.mcp.id)))
                }
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
            env.commands.updateAssistantUsage(env.access, env.assistant.id) { it!!.copy(mcpServers = UsageValue(emptySet())) }
            val cleared = env.document()
            try {
                env.commands.updateAssistantUsage(env.access, env.assistant.id) { it!!.copy(mcpServers = UsageValue(setOf(env.mcp.id))) }
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
            env.commands.updateAssistantUsage(env.access, env.assistant.id) {
                AssistantUsagePreferences(env.assistant.id, temperature = UsageValue(0.2f))
            }
            env.sessions.finishExit(env.sessions.beginExit(requireNotNull(env.sessions.captureExitRequest())))
            val bob = alice.copy(identity = alice.identity.copy(userId = "bob"))
            env.sessions.enrollFixture(bob)
            assertNull(env.document().preferences.assistantUsage(bob.identity.scope, env.assistant.id))
            try {
                env.commands.updateAssistantUsage(env.access, env.assistant.id) { null }
                fail("Wrong principal must be rejected")
            } catch (error: EnterpriseConfigurationException) {
                assertEquals("enterprise_data_access_unavailable", error.reason)
            }
            assertEquals(0.2f, env.document().preferences.assistantUsage(alice.identity.scope, env.assistant.id)!!.temperature!!.value)
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
            env.commands.updateAssistantUsage(env.access, env.assistant.id) {
                AssistantUsagePreferences(env.assistant.id, mcpServers = UsageValue(setOf(env.mcp.id, second.id)))
            }
            val current = env.sessions.state.value as EnterpriseState.Available
            env.sessions.synchronize(RealmAccess.Enterprise(packet.identity.scope, current.manifest.session!!.id),
                packet.copy(configuration = packet.configuration.copy(generation = 2, policy = packet.configuration.policy.copy(allowLocalMcp = false))))
            env.commands.updateAssistantUsage(env.access, env.assistant.id) { it!!.copy(mcpServers = UsageValue(setOf(second.id))) }
            assertEquals(setOf(second.id), env.document().preferences.assistantUsage(packet.identity.scope, env.assistant.id)!!.mcpServers!!.value)
            env.commands.updateAssistantUsage(env.access, env.assistant.id) { it!!.copy(mcpServers = UsageValue(emptySet())) }
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
            val reference = packet.identity.reference("gw_optional")
            val key = ConfigurationKey(ConfigurationCategory.GATEWAY, reference)
            assertEquals(ResolvedGatewayEnablement(true, true), env.queries.read(env.access).catalog.getValue(key).gatewayEnablement)
            val manifest = (env.sessions.state.value as EnterpriseState.Available).manifest
            env.commands.setGatewayEnabled(env.access, reference, false)
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
                    env.commands.setGatewayEnabled(env.access, gateway, false)
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
            val reference = alice.identity.reference("gw_optional")
            env.commands.setGatewayEnabled(env.access, reference, false)
            env.sessions.finishExit(env.sessions.beginExit(requireNotNull(env.sessions.captureExitRequest())))
            val bob = alice.copy(identity = alice.identity.copy(userId = "bob"))
            env.sessions.enrollFixture(bob)
            val bobAccess = env.sessions.captureRealmAccess(bob.identity.scope) as RealmAccess.Enterprise
            assertNull(env.document().preferences.gateway(bob.identity.scope, reference))
            assertTrue(env.queries.read(bobAccess).catalog.getValue(ConfigurationKey(ConfigurationCategory.GATEWAY, reference)).gatewayEnablement!!.enabled)
            env.commands.setGatewayEnabled(bobAccess, reference, true)
            assertFalse(env.document().preferences.gateway(alice.identity.scope, reference)!!.enabled)
            env.sessions.finishExit(env.sessions.beginExit(requireNotNull(env.sessions.captureExitRequest())))
            env.sessions.enrollFixture(alice)
            val before = env.document()
            val staleActions: List<suspend () -> Unit> = listOf(
                { env.commands.setGatewayEnabled(env.access, reference, true) },
                { env.commands.selectResource(env.access, ResourceSelectionSlot.CHAT_MODEL, env.model.id) },
                { env.commands.setModelFavorite(env.access, env.model.id, true) },
                { env.commands.setSuggestionEnabled(env.access, false) },
                { env.commands.updateAssistantUsage(env.access, env.assistant.id) { null } },
            )
            staleActions.forEach { action ->
                try { action(); fail("Reentry must not revive the original session") }
                catch (error: EnterpriseConfigurationException) { assertEquals("enterprise_data_access_unavailable", error.reason) }
            }
            assertEquals(JsonInstant.encodeToString(before), JsonInstant.encodeToString(env.document()))
            val newAccess = env.sessions.captureRealmAccess(alice.identity.scope) as RealmAccess.Enterprise
            env.commands.setGatewayEnabled(newAccess, reference, true)
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
            commands.selectResource(env.access, ResourceSelectionSlot.CHAT_MODEL, env.model.id)
            commands.selectResource(env.access, ResourceSelectionSlot.ASSISTANT, env.assistant.id)
            commands.setModelFavorite(env.access, env.model.id, true)
            val ownConfiguration = JsonInstant.encodeToString(env.document().configuration)
            val applied = env.sessions.state.value as EnterpriseState.Available
            env.sessions.synchronize(RealmAccess.Enterprise(packet.identity.scope, applied.manifest.session!!.id),
                packet.copy(configuration = packet.configuration.copy(generation = 2, policy = packet.configuration.policy.copy(allowLocalProviders = false))))
            val restricted = env.queries.observeCurrent().first()
            assertEquals(env.model.id, restricted.selection(ResourceSelectionSlot.CHAT_MODEL).reference)
            assertEquals(ConfigurationUnavailableReason.USER_CATEGORY_NOT_ALLOWED, restricted.selection(ResourceSelectionSlot.CHAT_MODEL).unavailableReason)
            // An invalid old choice does not prevent editing an unrelated setting or removing a favorite.
            commands.setSuggestionEnabled(env.access, false)
            commands.setModelFavorite(env.access, env.model.id, false)
            commands.selectResource(env.access, ResourceSelectionSlot.CHAT_MODEL, null)
            assertEquals(packet.identity.reference("mdl_chat"), env.queries.observeCurrent().first().selection(ResourceSelectionSlot.CHAT_MODEL).reference)
            for (reference in listOf(env.model.id, packet.identity.reference("mdl_image"))) {
                try {
                    commands.selectResource(env.access, ResourceSelectionSlot.CHAT_MODEL, reference)
                    fail("A forbidden or incompatible model must not be selected")
                } catch (_: SettingsLockedException) { }
            }
            assertNull(env.document().preferences.forScope(scope).chatModelId)
            commands.selectResource(env.access, ResourceSelectionSlot.IMAGE_MODEL, packet.identity.reference("mdl_image"))
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
                env.commands.selectResource(env.access, ResourceSelectionSlot.CHAT_MODEL, env.model.id)
                fail("Storage failure must be reported")
            } catch (_: IOException) { }
            assertEquals(original, JsonInstant.encodeToString(env.document()))
            assertEquals(original, JsonInstant.encodeToString(env.diskDocument()))
            assertNotEquals(env.model.id, env.queries.observeCurrent().first().selection(ResourceSelectionSlot.CHAT_MODEL).reference)
            env.intercept = null
            env.sessions.selectPersonalFixture()
            env.commands.selectResource(env.access, ResourceSelectionSlot.CHAT_MODEL, env.model.id)
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
                env.commands.updateAssistantUsage(env.access, env.assistant.id) {
                    AssistantUsagePreferences(env.assistant.id, temperature = UsageValue(0.2f))
                }
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
            assertEquals(0.2f, env.document().preferences.assistantUsage(scope, env.assistant.id)!!.temperature!!.value)
            assertEquals(0.2f, env.diskDocument().preferences.assistantUsage(scope, env.assistant.id)!!.temperature!!.value)
            assertEquals(ConfigurationScope.Personal, env.queries.observeCurrent().first().scope)
        } finally { env.scope.cancel() }
    }

    @Test
    fun `cancellation during preference transform abandons the mutation before writer handoff`() = runTest {
        val env = environment()
        try {
            env.initialize()
            val original = JsonInstant.encodeToString(env.document())
            val writer = launch {
                val caller = currentCoroutineContext()
                env.commands.updateAssistantUsage(env.access, env.assistant.id) {
                    caller.cancel()
                    AssistantUsagePreferences(env.assistant.id, temperature = UsageValue(0.2f))
                }
            }
            writer.join()
            assertTrue(writer.isCancelled)
            assertEquals(original, JsonInstant.encodeToString(env.document()))
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
        val commands = ConfigurationApplicationService(settings, sessions, gate)
        val queries = ConfigurationQueryService(settings, sessions, gate)
        val model = Model(modelId = "personal")
        val mcp = McpServerConfig.StreamableHTTPServer(commonOptions = McpCommonOptions(name = "Mine"), url = "https://example.invalid")
        val assistant = Assistant(name = "User", chatModelId = model.id)

        suspend fun initialize() {
            settings.effectiveSettings.first { !it.settings.init }
            settings.updateLocal { Settings(providers = listOf(ProviderSetting.OpenAI(models = listOf(model))),
                assistants = listOf(assistant), mcpServers = listOf(mcp), assistantId = assistant.id, chatModelId = model.id) }
            sessions.enrollFixture(exampleEnterprisePackage())
            access = sessions.captureRealmAccess(exampleEnterprisePackage().identity.scope) as RealmAccess.Enterprise
            gate.ready()
        }

        suspend fun document(): UserSettingsDocument = JsonInstant.decodeFromString(preferences.data.first()[SettingsStore.USER_SETTINGS]!!)

        suspend fun diskDocument(): UserSettingsDocument = preferencesFile.inputStream().use {
            JsonInstant.decodeFromString(PreferencesFileSerializer.readFrom(it)[SettingsStore.USER_SETTINGS]!!)
        }
    }
}
