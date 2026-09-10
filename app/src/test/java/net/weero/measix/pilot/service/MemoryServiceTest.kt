package net.weero.measix.pilot.service

import android.content.Context
import android.content.ContextWrapper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import me.rerere.common.configuration.ConfigurationReference
import net.weero.measix.pilot.AppScope
import net.weero.measix.pilot.data.ai.tools.local.LocalToolOption
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.datastore.UserSettingsMigration
import net.weero.measix.pilot.data.enterprise.EnterpriseAppliedStore
import net.weero.measix.pilot.data.enterprise.RealmAccess
import net.weero.measix.pilot.data.enterprise.EnterpriseSessionController
import net.weero.measix.pilot.data.enterprise.exampleEnterprisePackage
import net.weero.measix.pilot.data.enterprise.enrollFixture
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.model.AssistantMemory
import net.weero.measix.pilot.data.model.MemoryAddress
import net.weero.measix.pilot.data.model.MemoryOwner
import net.weero.measix.pilot.data.repository.MemoryRepository
import net.weero.measix.pilot.service.runtime.toSnapshot
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MemoryServiceTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `open editor retains its owner while the live page follows global mode changes`() = runTest {
        environment { env ->
            val personal = ConfigurationScope.Personal
            val local = MemoryAddress(personal, MemoryOwner.Assistant(env.target.id))
            val shared = MemoryAddress(personal, MemoryOwner.RealmShared)
            env.rows(local).value = listOf(AssistantMemory(1, "local"))
            env.rows(shared).value = listOf(AssistantMemory(2, "shared"))
            val view = MutableStateFlow(MemoryView.Loading)
            val observer = env.scope.launch { env.memory.observe(personal, env.target.id).collect { view.value = it } }
            val original = view.first { it.records.isNotEmpty() }.records.single()
            env.settings.updateLocal { it.copy(assistants = it.assistants.map { a -> if (a.id == env.target.id) a.copy(useGlobalMemory = true) else a }) }
            assertEquals("shared", view.first { it.access?.address == shared }.records.single().content)
            expectRejected { env.memory.update(original.copy(content = "wrong namespace")) }
            coVerify(exactly = 0) { env.repository.update(any(), any(), any()) }
            env.settings.updateLocal { it.copy(assistants = it.assistants.map { a -> if (a.id == env.target.id) a.copy(useGlobalMemory = false) else a }) }
            assertEquals("local", view.first { it.access?.address == local }.records.single().content)
            observer.cancelAndJoin()
        }
    }

    @Test
    fun `logout clears old subscription and same principal login cannot revive its records or editor`() = runTest {
        environment { env ->
            val packet = exampleEnterprisePackage()
            env.sessions.enrollFixture(packet)
            val address = MemoryAddress(packet.identity.scope, MemoryOwner.Assistant(env.target.id))
            env.rows(address).value = listOf(AssistantMemory(10, "enterprise"))
            val view = MutableStateFlow(MemoryView.Loading)
            val observer = env.scope.launch { env.memory.observe(packet.identity.scope, env.target.id).collect { view.value = it } }
            val old = view.first { it.records.isNotEmpty() }.records.single()
            env.sessions.finishExit(env.sessions.beginExit(requireNotNull(env.sessions.captureExitRequest())))
            view.first { it.unavailableReason != null }
            env.sessions.enrollFixture(packet)
            expectRejected { env.memory.delete(old) }
            coVerify(exactly = 0) { env.repository.delete(any(), any()) }
            // This fixture uses real IO and wall-clock sessions, as does its existing AppScope collector.
            val fresh = kotlinx.coroutines.withContext(Dispatchers.Default) {
                env.memory.observe(packet.identity.scope, env.target.id).first { it.access != null }
            }
            assertEquals("enterprise", fresh.records.single().content)
            assertNotEquals(old.access, fresh.access)
            assertTrue(view.value.records.isEmpty())
            observer.cancelAndJoin()
        }
    }

    @Test
    fun `delayed subscription retains original session instead of capturing a new login`() = runTest {
        environment { env ->
            val packet = exampleEnterprisePackage()
            env.sessions.enrollFixture(packet)
            val original = env.sessions.captureRealmAccess(packet.identity.scope)
            val pending = env.memory.observe(original, env.target.id)
            env.sessions.finishExit(env.sessions.beginExit(requireNotNull(env.sessions.captureExitRequest())))
            env.sessions.enrollFixture(packet)
            val result = pending.first()
            assertNull(result.access)
            assertTrue(result.records.isEmpty())
            assertEquals("memory_access_unavailable", result.unavailableReason)
            coVerify(exactly = 0) { env.repository.read(any()) }
        }
    }

    @Test
    fun `inspection reads only target local memory in the captured realm and honors policy changes`() = runTest {
        environment { env ->
            val packet = exampleEnterprisePackage()
            env.sessions.enrollFixture(packet)
            val realm = env.configurations.captureAccess(packet.identity.scope)
            val local = MemoryAddress(packet.identity.scope, MemoryOwner.Assistant(env.target.id))
            env.rows(local).value = listOf(AssistantMemory(10, "enterprise-only"))
            env.rows(MemoryAddress(ConfigurationScope.Personal, MemoryOwner.Assistant(env.target.id))).value = listOf(AssistantMemory(11, "personal-only"))
            assertEquals(listOf("enterprise-only"), env.memory.inspect(realm, env.caller.id, env.target.id).memories.map { it.content })
            env.settings.updateLocal { it.copy(assistants = it.assistants.map { a -> if (a.id == env.target.id) a.copy(useGlobalMemory = true) else a }) }
            assertTrue(env.memory.inspect(realm, env.caller.id, env.target.id).memories.isEmpty())
            env.sessions.synchronize(realm as RealmAccess.Enterprise, packet.copy(configuration = packet.configuration.copy(
                generation = packet.configuration.generation + 1, policy = packet.configuration.policy.copy(allowLocalAssistants = false),
            )))
            assertEquals(realm, env.sessions.captureRealmAccess(packet.identity.scope))
            expectRejected { env.memory.inspect(realm, env.caller.id, env.target.id) }
        }
    }

    @Test
    fun `execution capture rejects disabled memory and rechecks a later mode change`() = runTest {
        environment { env ->
            val realm = env.configurations.captureAccess(ConfigurationScope.Personal)
            assertNull(env.memory.captureExecution(realm, env.target.copy(enableMemory = false)))
            val access = requireNotNull(env.memory.captureExecution(realm, env.target))
            env.settings.updateLocal { it.copy(assistants = it.assistants.map { a -> if (a.id == env.target.id) a.copy(enableMemory = false) else a }) }
            assertFalse(env.memory.isAllowed(access))
            expectRejected { env.memory.add(access, "late tool") }
            coVerify(exactly = 0) { env.repository.add(any(), any()) }
        }
    }

    private suspend fun environment(block: suspend (Environment) -> Unit) {
        val env = Environment(temporary.newFolder())
        try {
            env.settings.userSettings.first { !it.init }
            env.settings.updateLocal { Settings(assistants = listOf(env.caller, env.target), assistantId = env.caller.id) }
            env.sessions.recover()
            env.gate.ready()
            block(env)
        } finally { env.scope.coroutineContext[Job]!!.cancelAndJoin() }
    }

    @Test
    fun `late old namespace emission fails closed without terminating subsequent configuration observation`() = runTest {
        environment { env ->
            val realm = net.weero.measix.pilot.data.enterprise.RealmAccess.Personal
            val configurationEvents = MutableStateFlow(env.configurations.read(realm))
            val delayedSettings = mockk<SettingsStore>()
            every { delayedSettings.observeConfiguration(any(), any()) } returns configurationEvents
            coEvery { delayedSettings.withResolvedConfiguration<Any?>(any(), any(), any()) } coAnswers {
                env.settings.withResolvedConfiguration(firstArg(), secondArg(), thirdArg())
            }
            val local = MemoryAddress(ConfigurationScope.Personal, MemoryOwner.Assistant(env.target.id))
            val shared = MemoryAddress(ConfigurationScope.Personal, MemoryOwner.RealmShared)
            val oldRows = kotlinx.coroutines.flow.MutableSharedFlow<List<AssistantMemory>>()
            every { env.repository.observe(local) } returns oldRows
            env.rows(shared).value = listOf(AssistantMemory(2, "new shared namespace"))
            val memory = MemoryService(env.repository, delayedSettings, env.sessions, env.gate, env.conversations)
            val view = MutableStateFlow(MemoryView.Loading)
            val observer = env.scope.launch { memory.observe(ConfigurationScope.Personal, env.target.id).collect { view.value = it } }
            oldRows.subscriptionCount.first { it > 0 }
            env.settings.updateLocal { it.copy(assistants = it.assistants.map { a -> if (a.id == env.target.id) a.copy(useGlobalMemory = true) else a }) }
            oldRows.emit(listOf(AssistantMemory(1, "late old namespace")))
            view.first { it.unavailableReason != null }
            configurationEvents.value = env.configurations.read(realm)
            assertEquals("new shared namespace", view.first { it.access?.address == shared }.records.single().content)
            observer.cancelAndJoin()
        }
    }

    @Test
    fun `tool card deletion is bound to durable result and its conversation rather than UI result id`() = runTest {
        environment { env ->
            val tool = me.rerere.ai.ui.UIMessagePart.Tool(
                localCallId = kotlin.uuid.Uuid.random(), stepId = kotlin.uuid.Uuid.random(), providerCallId = "call",
                toolName = "memory_tool", input = """{"action":"create","content":"note"}""",
                output = listOf(me.rerere.ai.ui.UIMessagePart.Text("""{"id":42}""")), resultStatus = me.rerere.ai.ui.ToolResultStatus.COMPLETED,
            )
            val message = me.rerere.ai.ui.UIMessage.assistant("").copy(parts = listOf(tool))
            val conversation = net.weero.measix.pilot.data.model.Conversation(assistantId = env.target.id,
                messageNodes = listOf(net.weero.measix.pilot.data.model.MessageNode(messages = listOf(message))))
            var snapshot = conversation.toSnapshot()
            coEvery { env.conversations.aggregateSnapshot(conversation.id) } answers { snapshot }
            val address = MemoryAddress(ConfigurationScope.Personal, MemoryOwner.Assistant(env.target.id))
            coEvery { env.repository.findToolResult(ConfigurationScope.Personal, 42) } returns (address to AssistantMemory(42, "note"))
            coEvery { env.repository.delete(address, 42) } returns Unit
            val locator = me.rerere.ai.core.ToolCallLocator(message.id, tool.stepId, tool.localCallId)
            assertNull(env.memory.captureToolRecord(conversation.id, locator.copy(localCallId = kotlin.uuid.Uuid.random())))
            val record = requireNotNull(env.memory.captureToolRecord(conversation.id, locator))
            assertTrue(record.matches(conversation.id, locator))
            assertFalse(record.matches(kotlin.uuid.Uuid.random(), locator))
            snapshot = conversation.copy(messageNodes = listOf(net.weero.measix.pilot.data.model.MessageNode(messages = listOf(
                message.copy(parts = listOf(tool.copy(output = listOf(me.rerere.ai.ui.UIMessagePart.Text("""{"id":999}"""))))),
            )))).toSnapshot()
            expectRejected { env.memory.deleteToolRecord(record) }
            coVerify(exactly = 0) { env.repository.delete(any(), any()) }
            snapshot = conversation.toSnapshot()
            env.memory.deleteToolRecord(record)
            coVerify(exactly = 1) { env.repository.delete(address, 42) }
        }
    }

    private class Environment(root: File) {
        val scope = AppScope(Dispatchers.Default)
        val target = Assistant(id = ConfigurationReference.random(), name = "Target", enableMemory = true, allowAsSubAssistant = true)
        val caller = Assistant(name = "Caller", localTools = listOf(LocalToolOption.AssistantManagement), allowedSubAssistantIds = setOf(target.id))
        private val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
        }
        private val preferences = PreferenceDataStoreFactory.create(migrations = listOf(UserSettingsMigration()), scope = scope,
            produceFile = { File(root, "settings.preferences_pb") })
        val settings = SettingsStore(context, scope, dataStore = preferences)
        val sessions = EnterpriseSessionController(EnterpriseAppliedStore(File(root, "enterprise")))
        val gate = ApplicationRecoveryGate()
        val configurations = ConfigurationQueryService(settings, sessions, gate)
        val repository = mockk<MemoryRepository>()
        private val namespaces = java.util.concurrent.ConcurrentHashMap<MemoryAddress, MutableStateFlow<List<AssistantMemory>>>()
        fun rows(address: MemoryAddress) = namespaces.getOrPut(address) { MutableStateFlow(emptyList()) }
        init {
            every { repository.observe(any()) } answers { rows(firstArg()) }
            coEvery { repository.read(any()) } answers { rows(firstArg()).value }
        }
        val conversations = mockk<ConversationQueryService>()
        val memory = MemoryService(repository, settings, sessions, gate, conversations)
    }

    private suspend fun expectRejected(operation: suspend () -> Unit) {
        try { operation(); fail("expected rejected memory access") }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: net.weero.measix.pilot.data.enterprise.EnterpriseConfigurationException) { }
        catch (_: IllegalStateException) { }
    }
}
