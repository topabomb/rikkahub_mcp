package net.weero.measix.pilot.service.runtime

import me.rerere.ai.ui.UIMessage
import me.rerere.common.configuration.ConfigurationReference
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.enterprise.EnterpriseStarter
import net.weero.measix.pilot.data.enterprise.EnterpriseStarterOpeningSnapshot
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.model.ConversationOpening
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationDraftOpeningTransitionTest {
    private val assistant = ConfigurationReference.parse("managed~dep_example~assistant_one") as ConfigurationReference.Enterprise
    private val opening = ConversationOpening(assistant = assistant, releaseId = "release", generation = 1,
        snapshotHash = "a".repeat(64), definition = EnterpriseStarter("starter_one", "assistant_one", "Task", "original prompt",
            openingSnapshot = EnterpriseStarterOpeningSnapshot(1, "original system", emptyList())))
    private val draft = Conversation.ofId(Uuid.random()).copy(assistantId = assistant, newConversation = true,
        scope = ConfigurationScope.Enterprise(assistant.authority, "user_one")).toSnapshot()

    @Test fun `draft selection is nonpersistent idempotent and stale cleanup cannot clear newer choice`() {
        val tokenA = Uuid.random()
        val bindA = BindDraftOpening(opening, null, tokenA)
        val planned = ConversationTransition.plan(draft, bindA, 100)
        assertTrue(planned is ConversationChange.DraftOnly)
        val selectedA = planned.snapshot
        assertEquals(selectedA, ConversationTransition.apply(selectedA, bindA))
        val openingB = opening.copy(definition = opening.definition.copy(id = "starter_two", prompt = "second prompt"))
        val tokenB = Uuid.random()
        val selectedB = ConversationTransition.apply(selectedA, BindDraftOpening(openingB, tokenA, tokenB))
        assertThrows(ConversationCommandConflictException::class.java) {
            ConversationTransition.apply(selectedB, BindDraftOpening(null, tokenA))
        }
        assertThrows(ConversationCommandConflictException::class.java) { ConversationTransition.apply(selectedB, bindA) }
        assertEquals(openingB, selectedB.opening)
        assertEquals(tokenB, selectedB.draftOpeningSelectionToken)
        val cleared = ConversationTransition.apply(selectedB, BindDraftOpening(null, tokenB))
        assertNull(cleared.opening)
        assertNull(cleared.draftOpeningSelectionToken)
    }

    @Test fun `first append materializes the opening and user in the same snapshot and clears only selection token`() {
        val selected = ConversationTransition.apply(draft, BindDraftOpening(opening, null))
        val user = UIMessage.user("edited prompt")
        val change = ConversationTransition.plan(selected, AppendUserMessage(user), 100) as ConversationChange.Durable
        val write = change.write as ConversationWrite.MaterializeDraft
        assertEquals(change.snapshot, write.snapshot)
        assertFalse(write.snapshot.header.newConversation)
        assertEquals(opening, write.snapshot.opening)
        assertEquals("original prompt", write.snapshot.opening!!.definition.prompt)
        assertEquals(user, write.snapshot.nodes.single().currentMessage)
        assertNull(write.snapshot.draftOpeningSelectionToken)
        assertThrows(ConversationCommandConflictException::class.java) {
            ConversationTransition.apply(write.snapshot, BindDraftOpening(null, null))
        }
    }

    @Test fun `changing draft assistant clears binding while ready moves retain historical opening`() {
        val selected = ConversationTransition.apply(draft, BindDraftOpening(opening, null))
        val other = ConfigurationReference.parse("managed~dep_example~assistant_two")
        val movedDraft = ConversationTransition.apply(selected, MoveToAssistant(other))
        assertNull(movedDraft.opening)
        assertNull(movedDraft.draftOpeningSelectionToken)
        val ready = ConversationTransition.apply(selected, AppendUserMessage(UIMessage.user("send")))
        val movedReady = ConversationTransition.apply(ready, MoveToAssistant(other))
        assertEquals(opening, movedReady.opening)
    }

    @Test fun `opening selection rejects wrong assistant or realm without changing draft`() {
        val personal = draft.copy(header = draft.header.copy(scope = ConfigurationScope.Personal))
        assertThrows(IllegalArgumentException::class.java) { ConversationTransition.apply(personal, BindDraftOpening(opening, null)) }
        val other = draft.copy(header = draft.header.copy(assistantId = ConfigurationReference.parse("managed~dep_example~assistant_other")))
        assertThrows(IllegalArgumentException::class.java) { ConversationTransition.apply(other, BindDraftOpening(opening, null)) }
        assertNull(draft.opening)
        assertTrue(draft.nodes.isEmpty())
    }
}
