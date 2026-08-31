package net.weero.measix.pilot.service

import io.mockk.every
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.UIMessage
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.repository.ConversationRepository
import net.weero.measix.pilot.data.repository.FolderRepository
import net.weero.measix.pilot.service.runtime.ConversationRuntime
import net.weero.measix.pilot.service.runtime.ConversationRuntimeRegistry
import net.weero.measix.pilot.service.runtime.ConversationRuntimeState
import net.weero.measix.pilot.service.runtime.ConversationTransition
import net.weero.measix.pilot.service.runtime.StartTurn
import net.weero.measix.pilot.service.runtime.TurnHandle
import net.weero.measix.pilot.service.runtime.resolveConversationPresentation
import net.weero.measix.pilot.service.runtime.toSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationQueryServiceTest {
    @Test
    fun `runtime failure remains a diagnostic read state`() = runTest {
        val error = IllegalStateException("corrupt message payload")
        val state = MutableStateFlow<ConversationRuntimeState>(ConversationRuntimeState.Failed(error))
        val service = service(state)

        val observed = service.observeConversation(Uuid.random()).first()

        assertTrue(observed is ConversationReadState.Failed)
        assertSame(error, (observed as ConversationReadState.Failed).error)
    }

    @Test
    fun `ready runtime projects its live snapshot without a nullable fallback`() = runTest {
        val conversation = Conversation.ofId(Uuid.random(), Uuid.random())
        val runtime = mockk<ConversationRuntime>()
        every { runtime.snapshot } returns MutableStateFlow(conversation.toSnapshot())
        val service = service(MutableStateFlow(ConversationRuntimeState.Ready(runtime)))

        val observed = service.observeConversation(conversation.id).first()

        assertTrue(observed is ConversationReadState.Ready)
        assertEquals(conversation.id, (observed as ConversationReadState.Ready).snapshot.conversationId)
    }

    @Test
    fun `query resubscription retains owner observations even when no presentation collector was present`() = runTest {
        val initial = Conversation.ofId(Uuid.random(), Uuid.random()).toSnapshot()
        val runtime = ConversationRuntime(initial.conversationId, initial, backgroundScope, onIdle = {})
        val start = StartTurn(Uuid.random(), Uuid.random(), false, 1)
        runtime.publishCommitted(initial, start, ConversationTransition.apply(initial, start))
        val handle = TurnHandle(initial.conversationId, 1, start.turnId, start.assistantMessageId)
        val registry = mockk<ConversationRuntimeRegistry>()
        every { registry.getConversationUiFlow(initial.conversationId) } returns runtime.snapshot.map {
            it to resolveConversationPresentation(null, it)
        }
        val attachments = mockk<ConversationAttachmentPreviewProjector>()
        every { attachments.lifecycleChanges() } returns flowOf(Unit)
        coEvery { attachments.project(any()) } returns emptyMap()
        val service = ConversationQueryService(mockk(), registry, mockk(), mockk(), attachments)
        val query = service.conversationUiModel(initial.conversationId)
        val known = UIMessage.assistant("reply").copy(
            id = start.assistantMessageId,
            usage = TokenUsage(latestRequestContextTokens = 20_000, latestRequestCacheReadInputTokens = 15_000),
        )
        runtime.applyStreamingDelta(handle, listOf(known))
        val first = query.first().presentation.activeContextCache!!

        val newer = known.copy(usage = TokenUsage(latestRequestContextTokens = 30_000, latestRequestCacheReadInputTokens = 0))
        runtime.applyStreamingDelta(handle, listOf(newer))
        val unknown = known.copy(usage = TokenUsage(latestRequestContextTokens = 40_000, latestRequestCacheReadInputTokens = null))
        runtime.applyStreamingDelta(handle, listOf(unknown))
        val second = query.first()

        assertEquals(20_000L, first.value.contextTokens)
        assertEquals(30_000L, second.presentation.activeContextCache?.value?.contextTokens)
        assertEquals(0L, second.presentation.activeContextCache?.value?.cacheReadInputTokens)
        assertNull(second.snapshot.activeTurn?.messages?.last()?.usage?.latestRequestCacheReadInputTokens)
        assertEquals(second.presentation.activeContextCache, SubAssistantDetailReader(service).activeContextCache(second.snapshot))
        val otherConversation = Conversation.ofId(Uuid.random(), Uuid.random()).toSnapshot()
        val otherActive = ConversationTransition.apply(otherConversation, StartTurn(Uuid.random(), Uuid.random(), false, 1))
        assertNull(SubAssistantDetailReader(service).activeContextCache(otherActive))
    }

    private fun service(state: MutableStateFlow<ConversationRuntimeState>): ConversationQueryService {
        val registry = mockk<ConversationRuntimeRegistry>()
        every { registry.observeRuntimeState(any()) } returns state
        return ConversationQueryService(
            repository = mockk<ConversationRepository>(relaxed = true),
            runtimeRegistry = registry,
            folderRepository = mockk<FolderRepository>(relaxed = true),
            titleCoordinator = mockk<ConversationTitleCoordinator>(relaxed = true),
            attachmentPreviewProjector = mockk(relaxed = true),
        )
    }
}
