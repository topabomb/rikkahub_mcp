package net.weero.measix.pilot.ui.pages.enterprise

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.Building03
import me.rerere.hugeicons.stroke.User
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.g00fy2.quickie.QRResult
import io.github.g00fy2.quickie.ScanQRCode
import kotlinx.coroutines.*
import net.weero.measix.pilot.R
import net.weero.measix.pilot.Screen
import net.weero.measix.pilot.data.enterprise.EnterpriseSessionPhase
import net.weero.measix.pilot.data.enterprise.RealmAccess
import net.weero.measix.pilot.data.enterprise.RealmSelection
import net.weero.measix.pilot.service.EnterpriseOverview
import net.weero.measix.pilot.service.LocalEnterpriseScenario
import net.weero.measix.pilot.service.portal.PortalWebView
import net.weero.measix.pilot.service.portal.PortalNativeActions
import net.weero.measix.pilot.service.portal.PortalNativePrompt
import net.weero.measix.pilot.service.portal.PortalCaptureOperation
import net.weero.measix.pilot.service.portal.PortalCapturePhase
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import net.weero.measix.pilot.ui.components.ui.QRCode
import net.weero.measix.pilot.ui.context.LocalNavController
import org.koin.androidx.compose.koinViewModel
import java.text.DateFormat
import java.util.Date
import net.weero.measix.pilot.utils.base64Encode

private data class ExperiencePresentation(
    val selection: RealmSelection,
    val mode: EnterpriseExperienceMode,
    val portal: PortalPresentation? = null,
)

@Composable
internal fun EnterpriseSpaceButton(modifier: Modifier = Modifier, vm: EnterpriseVM = koinViewModel()) {
    val state by vm.overview.collectAsStateWithLifecycle()
    val nav = LocalNavController.current
    val access = state?.selection?.access
    TextButton(onClick = { nav.navigate(Screen.Enterprise) { launchSingleTop = true } }, modifier = modifier) {
        Icon(if (access is RealmAccess.Enterprise) HugeIcons.Building03 else HugeIcons.User,
            contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            if (access is RealmAccess.Enterprise) {
                val name = state?.enterpriseName ?: stringResource(R.string.enterprise_space)
                if (access.scope.authority.isLocal) stringResource(R.string.enterprise_local_badge, name) else name
            }
            else stringResource(R.string.enterprise_personal),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun EnterprisePage(vm: EnterpriseVM = koinViewModel()) {
    val state by vm.overview.collectAsStateWithLifecycle()
    val working by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val sources by vm.sources.collectAsStateWithLifecycle()
    val localConfiguration by vm.localConfiguration.collectAsStateWithLifecycle()
    val localFeed by vm.localFeed.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        vm.importConfiguration(context, uri)
    }
    val exit by vm.exitRequest.collectAsStateWithLifecycle()
    val example by vm.exampleCode.collectAsStateWithLifecycle()
    val portal by vm.portal.collectAsStateWithLifecycle()
    val nav = LocalNavController.current
    var paste by remember { mutableStateOf(false) }
    var localManagement by remember { mutableStateOf(false) }
    var experience by remember { mutableStateOf<ExperiencePresentation?>(null) }
    LaunchedEffect(state?.selection, portal) {
        experience?.let {
            if (it.selection != state?.selection || (it.portal != null && it.portal != portal)) experience = null
        }
    }
    var scenarios by remember { mutableStateOf<EnterpriseOverview?>(null) }
    // Enrollment text contains a credential and is deliberately not saved in Activity state.
    var enrollment by remember { mutableStateOf("") }
    val scanner = rememberLauncherForActivityResult(ScanQRCode()) { result ->
        when (result) {
            is QRResult.QRSuccess -> vm.join(result.content.rawValue.orEmpty())
            QRResult.QRUserCanceled -> Unit
            else -> vm.scanFailed()
        }
    }
    val busy = working || state?.switching == true || state?.phase == EnterpriseSessionPhase.CLOSING
    LaunchedEffect(state?.access) { if (state?.access != null) vm.dismissSources() }
    LaunchedEffect(state?.selection) {
        localManagement = false
        if (localConfiguration?.selection != state?.selection) vm.dismissLocalConfiguration()
        if (localFeed?.selection != state?.selection) vm.dismissLocalFeed()
    }
    LaunchedEffect(state?.selection, state?.access, state?.phase) {
        if (scenarios?.selection != state?.selection || scenarios?.access != state?.access ||
            state?.phase == EnterpriseSessionPhase.CLOSING) scenarios = null
    }
    val inEnterprise = state?.selection?.access is RealmAccess.Enterprise
    val ready = state?.phase in setOf(EnterpriseSessionPhase.READY, EnterpriseSessionPhase.OFFLINE)
    val openChat = { nav.clearAndNavigate(Screen.Startup()) }
    // Going back also resolves a fresh chat lease, including after enrollment or automatic sign-out.
    BackHandler { if (portal != null) vm.dismissPortal(portal!!) else if (!busy) openChat() }

    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(if (portal != null) R.string.enterprise_portal else R.string.enterprise_spaces)) },
            navigationIcon = { IconButton(enabled = portal != null || !busy,
                onClick = { if (portal != null) vm.dismissPortal(portal!!) else openChat() }) {
                Icon(HugeIcons.ArrowLeft01, stringResource(R.string.back))
            } })
    }) { padding ->
        val approved = portal
        if (approved != null) {
            Column(Modifier.fillMaxSize().padding(padding)) {
                TextButton(onClick = {
                    experience = ExperiencePresentation(approved.selection, EnterpriseExperienceMode.STARTERS, approved)
                }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.enterprise_start_conversation)) }
                key(approved.id) { EnterprisePortal(approved, vm, Modifier.weight(1f).fillMaxWidth()) }
            }
        } else {
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                EnterpriseSection(stringResource(R.string.enterprise_current_space,
                    if (inEnterprise) state?.enterpriseName ?: stringResource(R.string.enterprise_space) else stringResource(R.string.enterprise_personal)),
                    icon = if (inEnterprise) HugeIcons.Building03 else HugeIcons.User,
                ) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = openChat, enabled = !busy && state?.selection != null) {
                            Text(stringResource(R.string.enterprise_open_chat))
                        }
                        if (inEnterprise && ready) {
                            OutlinedButton(onClick = vm::showPortal, enabled = !busy) { Text(stringResource(R.string.enterprise_portal)) }
                            TextButton(onClick = {
                                state?.selection?.let { experience = ExperiencePresentation(it, EnterpriseExperienceMode.ASSISTANTS) }
                            }, enabled = !busy) { Text(stringResource(R.string.enterprise_assistants)) }
                        }
                    }
                    if (state?.access != null && (inEnterprise || ready)) {
                        TextButton(onClick = { vm.switchSpace(openChat) }, enabled = !busy) {
                            Text(stringResource(if (inEnterprise) R.string.enterprise_switch_personal else R.string.enterprise_switch_enterprise))
                        }
                        Text(stringResource(R.string.enterprise_switch_notice), style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (state == null || busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                val feedback = remember { BringIntoViewRequester() }
                Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().bringIntoViewRequester(feedback)) {
                    if (error != null || state?.failure != null || state?.exitFailure != null) {
                        Text(stringResource(error ?: R.string.enterprise_failure), color = MaterialTheme.colorScheme.error)
                    }
                    notice?.takeIf { it != R.string.enterprise_expiry_scheduled || state?.access != null }
                        ?.let { Text(stringResource(it)) }
                }
                LaunchedEffect(error, notice) {
                    if (error != null || notice != null) feedback.bringIntoView()
                }
                if (state?.exitFailure != null) {
                    Button(onClick = vm::retryExit, enabled = !working) { Text(stringResource(R.string.application_recovery_retry)) }
                }
                if (state?.failure != null) {
                    TextButton(onClick = vm::requestExit, enabled = !busy) { Text(stringResource(R.string.enterprise_exit)) }
                }
                if (state?.access != null) {
                    EnterpriseSection(stringResource(R.string.enterprise_connected_enterprise,
                        state?.enterpriseName ?: stringResource(R.string.enterprise_space))) {
                        state?.userName?.let { Text(it) }
                        Text(stringResource(phaseText(state?.phase)), color = MaterialTheme.colorScheme.primary)
                        if (state?.phase == EnterpriseSessionPhase.CONFIGURATION_PENDING) {
                            Text(stringResource(R.string.enterprise_pending_next_step), style = MaterialTheme.typography.bodySmall)
                        }
                        if (state?.isLocal == true) Text(stringResource(R.string.enterprise_local_notice), style = MaterialTheme.typography.bodySmall)
                        state?.generation?.let { Text(stringResource(R.string.enterprise_generation, it), style = MaterialTheme.typography.bodySmall) }
                        state?.lastSyncMillis?.let { Text(stringResource(R.string.enterprise_last_sync, DateFormat.getDateTimeInstance().format(Date(it))), style = MaterialTheme.typography.bodySmall) }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = vm::synchronize, enabled = !busy && state?.phase != EnterpriseSessionPhase.REAUTH_REQUIRED) {
                                Text(stringResource(R.string.enterprise_sync))
                            }
                            TextButton(onClick = vm::requestExit, enabled = !busy) { Text(stringResource(R.string.enterprise_exit)) }
                        }
                    }
                }
                if (state?.selection != null) {
                    EnterpriseSection(stringResource(R.string.enterprise_join_options)) {
                        Text(stringResource(R.string.enterprise_enrollment_notice), style = MaterialTheme.typography.bodySmall)
                        val canJoin = !busy && state?.access == null && state?.failure == null
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { scanner.launch(null) }, enabled = canJoin) {
                                Text(stringResource(R.string.enterprise_join_scan))
                            }
                            OutlinedButton(onClick = { paste = true }, enabled = canJoin) {
                                Text(stringResource(R.string.enterprise_join_paste))
                            }
                        }
                        if (state?.access != null) {
                            Text(stringResource(R.string.enterprise_join_requires_exit), style = MaterialTheme.typography.bodySmall)
                        } else {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = vm::joinExample, enabled = canJoin) { Text(stringResource(R.string.enterprise_join_example)) }
                                TextButton(onClick = vm::showSources, enabled = canJoin) { Text(stringResource(R.string.enterprise_installed_sources)) }
                            }
                        }
                    }
                }
                if (state?.selection != null) {
                    EnterpriseSection(stringResource(R.string.enterprise_import_configuration)) {
                        Text(stringResource(R.string.enterprise_import_notice), style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = { if (vm.beginImport()) filePicker.launch(arrayOf("*/*")) }, enabled = !busy) {
                            Text(stringResource(R.string.enterprise_select_configuration_file))
                        }
                    }
                    if (state?.failure == null && (state?.access == null || state?.isLocal == true)) {
                        EnterpriseSection(stringResource(R.string.enterprise_local_management)) {
                            Text(stringResource(R.string.enterprise_local_management_notice), style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { localManagement = !localManagement }) {
                                Text(stringResource(if (localManagement) R.string.enterprise_management_collapse else R.string.enterprise_management_expand))
                            }
                            if (localManagement) {
                                if (state?.access != null && state?.isLocal == true) {
                                    if (!inEnterprise) Text(stringResource(R.string.enterprise_management_enter_space), style = MaterialTheme.typography.bodySmall)
                                    OutlinedButton(onClick = vm::showLocalConfiguration, enabled = !busy && inEnterprise) {
                                        Text(stringResource(R.string.enterprise_local_configuration))
                                    }
                                    OutlinedButton(onClick = vm::showLocalFeed, enabled = !busy && inEnterprise && ready) {
                                        Text(stringResource(R.string.enterprise_feed_editor))
                                    }
                                }
                                OutlinedButton(onClick = { scenarios = state }, enabled = !busy) {
                                    Text(stringResource(R.string.enterprise_local_scenarios))
                                }
                                if (state?.access == null) TextButton(onClick = vm::showExampleCode, enabled = !busy) {
                                    Text(stringResource(R.string.enterprise_example_code))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    localConfiguration?.let { original ->
        EnterpriseLocalConfigurationEditor(original, busy, error, notice,
            onChange = { vm.changeLocalConfiguration(original, it) },
            onRefresh = vm::showLocalConfiguration, onDismiss = vm::dismissLocalConfiguration)
    }
    localFeed?.let { original ->
        EnterpriseLocalFeedEditor(original, busy, error, notice,
            onChange = { vm.changeLocalFeed(original, it) },
            onRefresh = vm::showLocalFeed, onDismiss = vm::dismissLocalFeed)
    }
    scenarios?.let { original ->
        AlertDialog(onDismissRequest = { scenarios = null },
            title = { Text(stringResource(R.string.enterprise_local_scenarios)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.enterprise_local_scenarios_notice))
                if (original.access == null) {
                    Text(stringResource(R.string.enterprise_pending_example_notice))
                    Text(stringResource(R.string.enterprise_clear_example_requires_session))
                    OutlinedButton(onClick = { scenarios = null; vm.joinPendingExample() }, enabled = !busy) {
                        Text(stringResource(R.string.enterprise_join_pending_example))
                    }
                } else {
                    val connected = original.phase == EnterpriseSessionPhase.READY
                    OutlinedButton(onClick = {
                        scenarios = null
                        vm.runLocalScenario(original, if (connected) LocalEnterpriseScenario.DISCONNECT else LocalEnterpriseScenario.RECONNECT)
                    }, enabled = !busy && original.phase in setOf(EnterpriseSessionPhase.READY, EnterpriseSessionPhase.OFFLINE)) {
                        Text(stringResource(if (connected) R.string.enterprise_simulate_disconnect else R.string.enterprise_simulate_reconnect))
                    }
                    OutlinedButton(onClick = { scenarios = null; vm.runLocalScenario(original, LocalEnterpriseScenario.EXPIRE_SOON) }, enabled = !busy) {
                        Text(stringResource(R.string.enterprise_simulate_expiry))
                    }
                    TextButton(onClick = { scenarios = null; vm.runLocalScenario(original, LocalEnterpriseScenario.REVOKE) }, enabled = !busy) {
                        Text(stringResource(R.string.enterprise_simulate_revocation))
                    }
                    Text(stringResource(R.string.enterprise_clear_example_requires_session), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { scenarios = null; vm.requestExampleDataRemoval() }, enabled = !busy) {
                        Text(stringResource(R.string.enterprise_clear_example))
                    }
                }
            } }, confirmButton = { TextButton(onClick = { scenarios = null }) { Text(stringResource(R.string.cancel)) } })
    }
    sources?.let { installed ->
        AlertDialog(onDismissRequest = vm::dismissSources,
            title = { Text(stringResource(R.string.enterprise_installed_sources)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                installed.forEach { source ->
                    TextButton(onClick = { vm.joinInstalled(source) }, enabled = !busy) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(source.enterpriseName, style = MaterialTheme.typography.titleMedium)
                            Text(source.userName)
                        }
                    }
                }
            } }, confirmButton = { TextButton(onClick = vm::dismissSources) { Text(stringResource(R.string.cancel)) } })
    }
    experience?.let { original ->
        key(original) {
            EnterpriseExperienceDialog(original.selection, original.mode,
                onOpenDraft = { request ->
                    original.portal?.let(vm::dismissPortal)
                    experience = null
                    nav.navigate(Screen.Chat(request.request, text = request.text.base64Encode())) {
                        popUpTo(Screen.Enterprise) { inclusive = true }
                    }
                }, onDismiss = { experience = null })
        }
    }
    if (paste) AlertDialog(onDismissRequest = { paste = false; enrollment = "" },
        title = { Text(stringResource(R.string.enterprise_join_paste)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.enterprise_enrollment_notice), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(enrollment, { enrollment = it }, minLines = 4, maxLines = 8)
        } },
        confirmButton = { TextButton(onClick = { val text = enrollment; enrollment = ""; paste = false; vm.join(text) }, enabled = enrollment.isNotBlank()) {
            Text(stringResource(R.string.enterprise_join_submit))
        } }, dismissButton = { TextButton(onClick = { paste = false; enrollment = "" }) { Text(stringResource(R.string.cancel)) } })
    if (exit != null) AlertDialog(onDismissRequest = vm::dismissExit,
        title = { Text(stringResource(if (exit?.clearExampleData == true) R.string.enterprise_clear_example else R.string.enterprise_exit)) },
        text = { Text(stringResource(if (exit?.clearExampleData == true) R.string.enterprise_clear_example_confirm else R.string.enterprise_exit_confirm,
            exit?.enterpriseName ?: stringResource(R.string.enterprise_space))) },
        confirmButton = { TextButton(onClick = { vm.confirmExit(openChat) }) { Text(stringResource(R.string.confirm)) } },
        dismissButton = { TextButton(onClick = vm::dismissExit) { Text(stringResource(R.string.cancel)) } })
    example?.let { code ->
        val label = stringResource(R.string.enterprise_example_code)
        AlertDialog(onDismissRequest = vm::dismissExampleCode, title = { Text(label) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.enterprise_example_code_notice))
                QRCode(code, Modifier.fillMaxWidth().aspectRatio(1f).clearAndSetSemantics { contentDescription = label },
                    color = Color.Black, backgroundColor = Color.White)
                SelectionContainer { Text(code, style = MaterialTheme.typography.bodySmall) }
            } }, confirmButton = { TextButton(onClick = vm::dismissExampleCode) { Text(stringResource(R.string.update_card_close)) } })
    }
}

@Composable
private fun EnterpriseSection(title: String, icon: ImageVector? = null, content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(Modifier.widthIn(max = 720.dp).fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                icon?.let { Icon(it, contentDescription = null, modifier = Modifier.size(20.dp)) }
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            content()
        }
    }
}

private fun phaseText(phase: EnterpriseSessionPhase?): Int = when (phase) {
    null -> R.string.enterprise_working
    EnterpriseSessionPhase.SIGNED_OUT -> R.string.enterprise_signed_out
    EnterpriseSessionPhase.CONFIGURATION_PENDING -> R.string.enterprise_pending
    EnterpriseSessionPhase.READY -> R.string.enterprise_ready
    EnterpriseSessionPhase.OFFLINE -> R.string.enterprise_offline
    EnterpriseSessionPhase.CLOSING -> R.string.enterprise_closing
    EnterpriseSessionPhase.REAUTH_REQUIRED -> R.string.enterprise_reauth
}

@Composable
private fun EnterprisePortal(original: PortalPresentation, vm: EnterpriseVM, modifier: Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var host by remember { mutableStateOf<PortalWebView?>(null) }
    DisposableEffect(original.id, lifecycle, context) {
        val opening = scope.launch {
            var acquired: PortalWebView? = null
            var failure: Exception? = null
            try {
                acquired = vm.openPortal(context, original) { vm.dismissPortal(original) }
                ensureActive()
                if (vm.portal.value == original && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) host = acquired
            } catch (error: Exception) { failure = error }
            if (acquired != null && host !== acquired) {
                try {
                    withContext(NonCancellable) { acquired.close(); acquired.document.awaitClosed() }
                } catch (cleanup: Exception) {
                    if (failure == null) failure = cleanup else if (failure !== cleanup) failure.addSuppressed(cleanup)
                }
            }
            if (failure is CancellationException) throw failure
            if (failure != null) vm.portalFailed(original)
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { opening.cancel(); host?.close(); vm.dismissPortal(original) }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); opening.cancel(); host?.close() }
    }
    val page = host
    if (page != null) {
        AndroidView(factory = { page.view }, modifier = modifier)
        page.document.native?.let { PortalNativeControls(it) }
    }
    else Box(modifier) { CircularProgressIndicator() }
}

@Composable
internal fun PortalNativeControls(actions: PortalNativeActions) {
    val capture by actions.capture.collectAsStateWithLifecycle()
    capture?.let { operation -> key(operation) { PortalCaptureDialog(operation) { actions.cancelCapture(operation) } } }
    val prompt by actions.prompt.collectAsStateWithLifecycle()
    val original = prompt ?: return
    AlertDialog(
        onDismissRequest = { actions.decide(original, false) },
        title = { Text(stringResource(when (original) {
            is PortalNativePrompt.Logout -> R.string.enterprise_exit
            is PortalNativePrompt.External -> R.string.enterprise_external_open
        })) },
        text = { Text(when (original) {
            is PortalNativePrompt.Logout -> stringResource(R.string.enterprise_exit_confirm, original.enterpriseName)
            is PortalNativePrompt.External -> stringResource(R.string.enterprise_external_confirm, original.url.toASCIIString())
        }) },
        confirmButton = { TextButton(onClick = { actions.decide(original, true) }) { Text(stringResource(R.string.confirm)) } },
        dismissButton = { TextButton(onClick = { actions.decide(original, false) }) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun PortalCaptureDialog(operation: PortalCaptureOperation, cancel: () -> Unit) {
    val context = LocalContext.current
    val phase by operation.phase.collectAsStateWithLifecycle()
    val preview by operation.preview.collectAsStateWithLifecycle()
    val photo = operation.permission == Manifest.permission.CAMERA
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), operation::permissionResult)
    LaunchedEffect(operation) {
        if (ContextCompat.checkSelfPermission(context, operation.permission) == PackageManager.PERMISSION_GRANTED) operation.permissionResult(true)
        else permission.launch(operation.permission)
    }
    DisposableEffect(operation) { onDispose { cancel() } }
    Dialog(onDismissRequest = cancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 640.dp).fillMaxWidth(0.9f), shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(if (photo) R.string.enterprise_take_photo else R.string.enterprise_record_audio),
                    style = MaterialTheme.typography.titleLarge)
                preview?.let { view -> AndroidView(factory = { view }, modifier = Modifier.fillMaxWidth().height(320.dp)) }
                Text(stringResource(R.string.enterprise_media_notice))
                if (phase in setOf(PortalCapturePhase.PERMISSION, PortalCapturePhase.PREPARING, PortalCapturePhase.FINISHED) ||
                    (photo && phase == PortalCapturePhase.CAPTURING)) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (!photo && phase == PortalCapturePhase.CAPTURING) Text(stringResource(R.string.enterprise_recording))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = cancel) { Text(stringResource(R.string.cancel)) }
                    if (phase == PortalCapturePhase.READY) Button(onClick = operation::start) {
                        Text(stringResource(if (photo) R.string.enterprise_take_photo else R.string.enterprise_start_recording))
                    }
                    if (!photo && phase == PortalCapturePhase.CAPTURING) Button(onClick = operation::stop) {
                        Text(stringResource(R.string.enterprise_stop_recording))
                    }
                }
            }
        }
    }
}
