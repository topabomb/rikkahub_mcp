package net.weero.measix.pilot.ui.pages.share.handler

import androidx.compose.runtime.getValue
import net.weero.measix.pilot.utils.plus
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.dokar.sonner.ToastType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import net.weero.measix.pilot.R
import net.weero.measix.pilot.Screen
import net.weero.measix.pilot.data.enterprise.RealmAccess
import net.weero.measix.pilot.ui.components.ai.configurationUnavailableText
import net.weero.measix.pilot.ui.context.LocalNavController
import net.weero.measix.pilot.ui.context.LocalToaster
import net.weero.measix.pilot.utils.base64Encode
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun ShareHandlerPage(text: String, image: String?) {
    val vm: ShareHandlerVM = koinViewModel(parameters = { parametersOf(text) })
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val failure = stringResource(R.string.error_title_operation)
    val original = catalog
    var submitting by remember(original?.selection) { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.share_handler_page_title))
                }
            )
        }
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = it + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(if (original == null) stringResource(R.string.configuration_reason_not_ready)
                else stringResource(R.string.enterprise_current_space, stringResource(
                    if (original.selection.access == RealmAccess.Personal) R.string.enterprise_personal else R.string.enterprise_space)))
            }
            item {
                Card {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Text(
                            text = vm.shareText,
                            maxLines = 5,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall
                        )

                        image?.let {
                            AsyncImage(
                                model = it,
                                contentDescription = null,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }

            items(original?.resources.orEmpty(), key = { it.key.reference.toString() }) { resource ->
                Surface(
                    onClick = {
                        if (original != null && !submitting) {
                            submitting = true
                            scope.launch {
                                try {
                                    val request = vm.newDraft(original.selection, resource.key.reference)
                                    navController.clearAndNavigate(Screen.Chat(request, vm.shareText.base64Encode(),
                                        image?.let { listOf(it.toUri().toString()) } ?: emptyList()))
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { toaster.show(failure, type = ToastType.Error) }
                                finally { submitting = false }
                            }
                        }
                    },
                    enabled = resource.access.canSelect && !submitting,
                    tonalElevation = 4.dp,
                    shape = MaterialTheme.shapes.medium
                ) {
                    ListItem {
                        Column {
                            Text(
                                text = resource.name.ifEmpty {
                                    stringResource(R.string.assistant_page_default_assistant)
                                },
                                maxLines = 1,
                            )
                            resource.access.unavailableReason?.let {
                                Text(configurationUnavailableText(it), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}
