package net.weero.measix.pilot.service

import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import net.weero.measix.pilot.AppScope
import net.weero.measix.pilot.data.configuration.ConfigurationResolver
import net.weero.measix.pilot.data.configuration.ResolvedConfiguration
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.datastore.UserSettingsDocument
import net.weero.measix.pilot.data.enterprise.*
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.repository.ConversationRepository
import net.weero.measix.pilot.service.runtime.*
import net.weero.measix.pilot.utils.JsonInstant
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
class StarterOpeningConcurrencyTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `in flight choices serialize and a stale choice cannot append text or replace the winner`() = runTest {
        val fixture = fixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var firstRead = true
        fixture.beforeConfiguration = {
            if (firstRead) { firstRead = false; entered.complete(Unit); release.await() }
        }
        val tokenA = Uuid.random()
        val tokenB = Uuid.random()
        try {
            val choiceA = async { fixture.select(fixture.a, null, tokenA) }
            entered.await()
            val choiceB = async { runCatching { fixture.select(fixture.b, null, tokenB) } }
            runCurrent()
            assertFalse(choiceA.isCompleted)
            assertFalse(choiceB.isCompleted)
            assertEquals("original input", fixture.text)
            assertNull(fixture.runtime.durable.opening)
            release.complete(Unit)
            choiceA.await()
            assertTrue(choiceB.await().exceptionOrNull() is ConversationCommandConflictException)
            assertEquals(fixture.a, fixture.runtime.durable.opening!!.definition)
            assertEquals(tokenA, fixture.runtime.durable.draftOpeningSelectionToken)
            assertEquals("original input" + fixture.a.prompt, fixture.text)
            fixture.select(fixture.b, tokenA, tokenB)
            assertEquals(fixture.b, fixture.runtime.durable.opening!!.definition)
            assertEquals(tokenB, fixture.runtime.durable.draftOpeningSelectionToken)
            assertEquals("original input" + fixture.a.prompt + fixture.b.prompt, fixture.text)
            coVerify(exactly = 0) { fixture.repository.commit(any()) }
        } finally { release.complete(Unit); fixture.close() }
    }

    @Test fun `selecting a v4 prompt clears a v5 draft opening without losing input or rewriting its original definition`() = runTest {
        val fixture = fixture()
        val token = Uuid.random()
        try {
            fixture.select(fixture.a, null, token)
            val original = requireNotNull(fixture.runtime.durable.opening)
            val configuration = fixture.packet.configuration.copy(generation = fixture.packet.configuration.generation + 1,
                starters = fixture.packet.configuration.starters.map { it.copy(openingSnapshot = null) })
            val candidate = fixture.packet.copy(configuration = configuration).toCandidate().let { value ->
                val execution = value.execution as EnterpriseExecution.Platform
                value.copy(execution = execution.copy(snapshotSchemaVersion = 4L,
                    snapshotHash = "sha256:" + "b".repeat(64),
                    connection = execution.connection.copy(discovery = execution.connection.discovery.copy(supportedSnapshotSchemaVersions = listOf(4L)))))
            }
            fixture.sessions.synchronize(fixture.target.conversation.selection.access as RealmAccess.Enterprise, candidate)
            fixture.select(fixture.a.copy(openingSnapshot = null), token, Uuid.random())
            assertNull(fixture.runtime.durable.opening)
            assertNull(fixture.runtime.durable.draftOpeningSelectionToken)
            assertEquals("original input" + fixture.a.prompt + fixture.a.prompt, fixture.text)
            assertEquals(fixture.a, original.definition)
            assertNotEquals(candidate.execution.let { it as EnterpriseExecution.Platform }.snapshotHash, original.snapshotHash)
            coVerify(exactly = 0) { fixture.repository.commit(any()) }
        } finally { fixture.close() }
    }

    private suspend fun TestScope.fixture(): Fixture {
        val original = exampleEnterprisePackage()
        val a = original.configuration.starters.first { it.enabled }
        val b = a.copy(id = "str_00000000-0000-4000-8000-000000000099", title = "Choice B", prompt = "prompt B")
        val packet = original.copy(configuration = original.configuration.copy(starters = listOf(a, b)))
        val sessions = EnterpriseSessionController(enterpriseTestStore(temporary.newFolder())).apply { enrollFixture(packet) }
        return Fixture(packet, sessions, requireNotNull(sessions.readPresentation().selection), AppScope(StandardTestDispatcher(testScheduler))).apply { initialize() }
    }

    private class Fixture(val packet: EnterprisePackage, val sessions: EnterpriseSessionController,
        selection: RealmSelection, private val scope: AppScope) : AutoCloseable {
        val a = packet.configuration.starters[0]
        val b = packet.configuration.starters[1]
        val repository = mockk<ConversationRepository>()
        private val settings = mockk<SettingsStore>()
        private val locks = ConversationOperationLocks()
        private val registry = ConversationRuntimeRegistry(scope, repository, locks)
        private val gate = ApplicationRecoveryGate().apply { ready() }
        private val commands = ConversationCommandCoordinator(registry, repository, gate, locks)
        private val assistant = packet.identity.reference(a.assistantId)
        private val id = Uuid.random()
        lateinit var runtime: ConversationRuntime
            private set
        private lateinit var lease: ConversationRuntimeLease
        suspend fun initialize() {
            runtime = registry.installDraft(Conversation.ofId(id, assistant, newConversation = true).copy(scope = packet.identity.scope))
            lease = registry.acquireRegisteredRuntime(id, runtime)
        }
        val target = ConversationAssistantTarget(ConversationCommandTarget(id,
            selection) {}, assistant)
        private val application = ConversationApplicationService(settings, repository, mockk(), registry, commands, gate,
            mockk(), mockk(), mockk(), mockk(), mockk(), JsonInstant, mockk(), mockk(), sessions, mockk())
        var text = "original input"
        var beforeConfiguration: suspend () -> Unit = {}
        init {
            coEvery { settings.withResolvedConfiguration<Any?>(any(), any(), any()) } coAnswers {
                beforeConfiguration()
                thirdArg<suspend (ResolvedConfiguration) -> Any?>()(ConfigurationResolver.resolve(
                    UserSettingsDocument.empty(), packet.identity.scope, sessions.state.value))
            }
        }
        suspend fun select(starter: EnterpriseStarter, expected: Uuid?, token: Uuid) = application.selectDraftStarter(
            target, packet.identity.reference(starter.id), expected, token) { text += it }
        override fun close() { lease.close(); scope.cancel() }
    }
}
