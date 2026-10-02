package net.weero.measix.pilot.ui.context

import androidx.compose.runtime.compositionLocalOf
import androidx.navigation3.runtime.NavKey
import net.weero.measix.pilot.Screen

class Navigator(private val backStack: MutableList<NavKey>) {
    fun isCurrentConversation(request: net.weero.measix.pilot.service.ConversationOpenRequest): Boolean =
        (backStack.lastOrNull() as? Screen.Chat)?.request == request

    /** Replace only deleted chat entries from the original principal; history remains in place. */
    internal fun replaceDeletedConversation(
        receipt: net.weero.measix.pilot.service.ConversationDeletionReceipt,
        continuation: net.weero.measix.pilot.service.ConversationContinuation,
    ) {
        backStack.indices.forEach { index ->
            val chat = backStack[index] as? Screen.Chat ?: return@forEach
            if (chat.request.id == receipt.conversationId && chat.request.access == receipt.selection.access) {
                backStack[index] = continuation.request?.let { Screen.Chat(it) } ?: Screen.Enterprise
            }
        }
    }

    fun navigate(screen: Screen, builder: NavigateOptionsBuilder.() -> Unit = {}) {
        val options = NavigateOptionsBuilder().apply(builder)

        options.popUpToScreen?.let { target ->
            val targetIndex = backStack.indexOfLast { it == target }
            if (targetIndex != -1) {
                val removeFromIndex = if (options.popUpToInclusive) targetIndex else targetIndex + 1
                repeat(backStack.size - removeFromIndex) {
                    backStack.removeLastOrNull()
                }
            }
        }

        if (options.launchSingleTop && backStack.lastOrNull() == screen) {
            return
        }

        backStack.add(screen)
    }

    fun clearAndNavigate(screen: Screen) {
        backStack.clear()
        backStack.add(screen)
    }

    fun popBackStack() {
        if (backStack.size > 1) backStack.removeLastOrNull()
    }
}

class NavigateOptionsBuilder {
    internal var popUpToScreen: Screen? = null
    internal var popUpToInclusive: Boolean = false
    var launchSingleTop: Boolean = false

    fun popUpTo(screen: Screen, builder: PopUpToBuilder.() -> Unit = {}) {
        val options = PopUpToBuilder().apply(builder)
        popUpToScreen = screen
        popUpToInclusive = options.inclusive
    }
}

class PopUpToBuilder {
    var inclusive: Boolean = false
}

val LocalNavController = compositionLocalOf<Navigator> {
    error("No Navigator provided")
}
