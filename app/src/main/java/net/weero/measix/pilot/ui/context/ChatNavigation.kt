package net.weero.measix.pilot.ui.context

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.common.configuration.ConfigurationReference
import net.weero.measix.pilot.R
import net.weero.measix.pilot.Screen
import net.weero.measix.pilot.service.ConversationApplicationService
import net.weero.measix.pilot.service.ConversationOpenRequest
import net.weero.measix.pilot.service.ConversationQueryService
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

class ChatNavigation internal constructor(
    private val create: (ConfigurationReference?, String?, List<Uri>) -> Unit,
    private val open: (Uuid, Uuid?) -> Unit,
    private val select: (ConfigurationReference, Boolean) -> Unit,
) {
    fun newChat(assistantId: ConfigurationReference? = null, initText: String? = null, initFiles: List<Uri> = emptyList()) =
        create(assistantId, initText, initFiles)

    fun existingChat(chatId: Uuid, nodeId: Uuid? = null) = open(chatId, nodeId)
    fun selectAssistant(assistantId: ConfigurationReference, createNew: Boolean) = select(assistantId, createNew)
}

/** Click handlers retain the rendered realm; delayed navigation never recaptures a newer session. */
@Composable
fun rememberChatNavigation(navigator: Navigator = LocalNavController.current): ChatNavigation {
    val query = koinInject<ConversationQueryService>()
    val application = koinInject<ConversationApplicationService>()
    val access by remember(query) { query.observeCurrentAccess() }.collectAsStateWithLifecycle(null)
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val failure = stringResource(R.string.error_title_operation)
    val capturedAccess = access
    return remember(navigator, capturedAccess, application, scope, toaster, failure) {
        ChatNavigation(
            create = { assistant, text, files ->
                val original = capturedAccess
                if (original == null) toaster.show(failure, type = ToastType.Error) else scope.launch {
                    try {
                        val request = application.newDraftRequest(original, assistant)
                        navigator.clearAndNavigate(Screen.Chat(request, text, files.map(Uri::toString)))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        toaster.show(failure, type = ToastType.Error)
                    }
                }
            },
            open = { id, node ->
                val original = capturedAccess
                if (original == null) toaster.show(failure, type = ToastType.Error) else {
                    navigator.clearAndNavigate(Screen.Chat(ConversationOpenRequest.OpenExisting(id, original), nodeId = node?.toString()))
                }
            },
            select = { assistant, createNew ->
                val original = capturedAccess
                if (original == null) toaster.show(failure, type = ToastType.Error) else scope.launch {
                    try {
                        val request = application.selectAssistantRequest(original, assistant, createNew)
                        navigator.clearAndNavigate(Screen.Chat(request))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        toaster.show(failure, type = ToastType.Error)
                    }
                }
            },
        )
    }
}
