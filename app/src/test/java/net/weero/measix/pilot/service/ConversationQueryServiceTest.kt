package net.weero.measix.pilot.service

import me.rerere.common.configuration.ConfigurationReference
import io.mockk.every
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.repository.ConversationRepository
import net.weero.measix.pilot.data.repository.FolderRepository
import net.weero.measix.pilot.service.runtime.ConversationRuntime
import net.weero.measix.pilot.service.runtime.ConversationRuntimeRegistry
import net.weero.measix.pilot.service.runtime.ConversationRuntimeState
import net.weero.measix.pilot.service.runtime.ConversationRuntimeSnapshot
import net.weero.measix.pilot.service.runtime.toSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import net.weero.measix.pilot.data.enterprise.EnterpriseAppliedStore
import net.weero.measix.pilot.data.enterprise.EnterpriseSessionController
import net.weero.measix.pilot.data.enterprise.RealmAccess
import net.weero.measix.pilot.data.enterprise.RealmSelection
import net.weero.measix.pilot.utils.userVisibleDiagnostic
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConversationQueryServiceTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test
    fun `runtime failure remains a diagnostic read state`() = runTest {
        val error = IllegalStateException("corrupt message payload")
        val state = MutableStateFlow<ConversationRuntimeState>(ConversationRuntimeState.Failed(error))
        val service = service(state)

        val observed = service.observeConversation(ConversationViewLease(Uuid.random(), RealmAccess.Personal, 0) {}).first()

        assertTrue(observed is ConversationReadState.Failed)
        assertSame(error, (observed as ConversationReadState.Failed).error)
    }

    @Test
    fun `ready runtime projects its live snapshot without a nullable fallback`() = runTest {
        val conversation = Conversation.ofId(Uuid.random(), ConfigurationReference.random())
        val runtime = mockk<ConversationRuntime>()
        every { runtime.snapshot } returns MutableStateFlow(
            ConversationRuntimeSnapshot(durable = conversation.toSnapshot(), stream = null),
        )
        val service = service(MutableStateFlow(ConversationRuntimeState.Ready(runtime)))

        val observed = service.observeConversation(ConversationViewLease(conversation.id, RealmAccess.Personal, 0) {}).first()

        assertTrue(observed is ConversationReadState.Ready)
        assertEquals(conversation.id, (observed as ConversationReadState.Ready).snapshot.conversationId)
    }

    @Test fun `source read failure preserves the original exception and leaves an authorized page open`() = runTest {
        val service = service(MutableStateFlow(ConversationRuntimeState.Loading))
        val lease = ConversationViewLease(Uuid.random(), RealmAccess.Personal, 0) {}
        val failure = java.io.IOException("conversation index unreadable", IllegalStateException("row detail"))
        try {
            service.observeForView(lease, "") { flow<String> { throw failure } }.first()
            fail("Expected the original read failure")
        } catch (caught: java.io.IOException) {
            assertFailurePreserved(failure, caught)
        }
        assertFalse(lease.closed.value)
    }

    @Test fun `conversation subscription failure is a diagnostic read state without revoking its page`() = runTest {
        val failure = java.io.IOException("runtime subscription failed", IllegalArgumentException("runtime detail"))
        val registry = mockk<ConversationRuntimeRegistry>()
        every { registry.observeRuntimeState(any()) } throws failure
        val service = service(MutableStateFlow(ConversationRuntimeState.Loading), registry)
        val lease = ConversationViewLease(Uuid.random(), RealmAccess.Personal, 0) {}
        val observed = service.observeConversation(lease).first()
        assertFailurePreserved(failure, (observed as ConversationReadState.Failed).error)
        assertFalse(lease.closed.value)
    }

    @Test fun `source cancellation propagates without closing the page`() = runTest {
        val service = service(MutableStateFlow(ConversationRuntimeState.Loading))
        val lease = ConversationViewLease(Uuid.random(), RealmAccess.Personal, 0) {}
        val entered = CompletableDeferred<Unit>()
        val exited = CompletableDeferred<Unit>()
        val observed = mutableListOf<ConversationReadState>()
        val collector = launch {
            service.observeForView<ConversationReadState>(lease, ConversationReadState.Missing) {
                flow {
                    emit(ConversationReadState.Loading)
                    entered.complete(Unit)
                    try { awaitCancellation() } finally { exited.complete(Unit) }
                }
            }.collect { observed += it }
        }
        entered.await()
        collector.cancelAndJoin()
        exited.await()
        assertTrue(collector.isCancelled)
        assertEquals(listOf(ConversationReadState.Loading), observed)
        assertTrue(observed.none { it is ConversationReadState.Failed })
        assertFalse(lease.closed.value)
    }

    @Test fun `unexpected authorization recheck failure keeps the source error and does not revoke the page`() = runTest {
        val sourceFailure = java.io.IOException("source failed")
        val validationFailure = java.io.IOException("authorization store unreadable")
        val sessions = mockk<EnterpriseSessionController>()
        every { sessions.observeSelectedRealmSelection() } returns flowOf(RealmSelection(RealmAccess.Personal, 0))
        every { sessions.selectionRevision } returns MutableStateFlow(0L)
        every { sessions.requirePublishedRealmAccess(any()) } returns Unit
        var validations = 0
        coEvery { sessions.withSelectedRealmAccess<Any?>(any(), any()) } coAnswers {
            if (validations++ > 0) throw validationFailure
            secondArg<suspend () -> Any?>().invoke()
        }
        val service = service(MutableStateFlow(ConversationRuntimeState.Loading), sessions = sessions)
        val lease = ConversationViewLease(Uuid.random(), RealmAccess.Personal, 0) {}
        try {
            service.observeForView(lease, "") { flow<String> { throw sourceFailure } }.first()
            fail("Expected the original source error")
        } catch (caught: java.io.IOException) {
            assertFailurePreserved(sourceFailure, caught)
            val suppressed = generateSequence(caught as Throwable) { it.cause }
                .flatMap { it.suppressed.asSequence() }.single()
            assertEquals(validationFailure.javaClass, suppressed.javaClass)
            assertEquals(validationFailure.message, suppressed.message)
        }
        assertFalse(lease.closed.value)
    }

    private fun assertFailurePreserved(expected: Throwable, actual: Throwable) {
        // Coroutine stack recovery may copy the exception, but must retain its diagnosis.
        assertEquals(expected.javaClass, actual.javaClass)
        assertEquals(expected.message, actual.message)
        expected.cause?.let { cause ->
            assertTrue(generateSequence(actual.cause) { it.cause }.any {
                it.javaClass == cause.javaClass && it.message == cause.message
            })
        }
        assertTrue(actual.userVisibleDiagnostic().contains(expected.userVisibleDiagnostic()))
    }

    private suspend fun service(
        state: MutableStateFlow<ConversationRuntimeState>,
        registry: ConversationRuntimeRegistry = mockk<ConversationRuntimeRegistry>().also {
            every { it.observeRuntimeState(any()) } returns state
        },
        sessions: EnterpriseSessionController? = null,
    ): ConversationQueryService {
        return ConversationQueryService(
            repository = mockk<ConversationRepository>(relaxed = true),
            runtimeRegistry = registry,
            folderRepository = mockk<FolderRepository>(relaxed = true),
            titleCoordinator = mockk<ConversationTitleCoordinator>(relaxed = true),
            attachmentPreviewProjector = mockk(relaxed = true),
            sessions = sessions ?: EnterpriseSessionController(
                net.weero.measix.pilot.data.enterprise.enterpriseTestStore(temporary.newFolder())).apply { recover() },
            recoveryGate = ApplicationRecoveryGate().also { it.ready() },
            settings = mockk(),
            coordinator = mockk(),
            artifacts = mockk(),
        )
    }
}
