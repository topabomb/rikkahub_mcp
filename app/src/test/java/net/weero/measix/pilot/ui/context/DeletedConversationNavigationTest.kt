package net.weero.measix.pilot.ui.context

import androidx.navigation3.runtime.NavKey
import me.rerere.common.configuration.ConfigurationReference
import net.weero.measix.pilot.Screen
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import me.rerere.common.configuration.EnterpriseAuthority
import net.weero.measix.pilot.data.enterprise.RealmAccess
import net.weero.measix.pilot.data.enterprise.RealmSelection
import net.weero.measix.pilot.service.ConversationContinuation
import net.weero.measix.pilot.service.ConversationDeletionReceipt
import net.weero.measix.pilot.service.ConversationOpenRequest
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class DeletedConversationNavigationTest {
    @Test fun `history replacement preserves its current page and touches only the deleted original Session`() {
        val id = Uuid.random()
        val assistant = ConfigurationReference.random()
        val scope = ConfigurationScope.Enterprise(EnterpriseAuthority("dep_navigation"), "user")
        val access = RealmAccess.Enterprise(scope, "original")
        val original = Screen.Chat(ConversationOpenRequest.OpenExisting(id, access))
        val otherSession = Screen.Chat(ConversationOpenRequest.OpenExisting(id, RealmAccess.Enterprise(scope, "replacement")))
        val otherId = Screen.Chat(ConversationOpenRequest.OpenExisting(Uuid.random(), access))
        val stack = mutableListOf<NavKey>(original, otherSession, otherId, Screen.History)
        val next = ConversationOpenRequest.NewDraft(Uuid.random(), access, assistant)
        val nav = Navigator(stack)
        nav.replaceDeletedConversation(ConversationDeletionReceipt(id, assistant, RealmSelection(access, 1)), ConversationContinuation(next))
        assertEquals(listOf(Screen.Chat(next), otherSession, otherId, Screen.History), stack)
        nav.popBackStack()
        assertEquals(otherId, stack.last())
    }

    @Test fun `unavailable original assistant leads to space selection without silently substituting a default`() {
        val request = ConversationOpenRequest.OpenExisting(Uuid.random(), RealmAccess.Personal)
        val stack = mutableListOf<NavKey>(Screen.Chat(request), Screen.History)
        val nav = Navigator(stack)
        nav.replaceDeletedConversation(ConversationDeletionReceipt(request.id, ConfigurationReference.random(), RealmSelection(request.access, 1)),
            ConversationContinuation(null, "conversation_assistant_unavailable"))
        nav.popBackStack()
        assertEquals(Screen.Enterprise, stack.last())
        assertFalse(nav.isCurrentConversation(request))
    }
}
