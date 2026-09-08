package net.weero.measix.pilot.ui.pages.enterprise

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
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
import net.weero.measix.pilot.service.portal.PortalWebView
import net.weero.measix.pilot.ui.components.ui.QRCode
import net.weero.measix.pilot.ui.context.LocalNavController
import org.koin.androidx.compose.koinViewModel
import java.text.DateFormat
import java.util.Date

@Composable
internal fun EnterpriseSpaceButton(modifier: Modifier = Modifier, vm: EnterpriseVM = koinViewModel()) {
    val state by vm.overview.collectAsStateWithLifecycle()
    val nav = LocalNavController.current
    TextButton(onClick = { nav.navigate(Screen.Enterprise) { launchSingleTop = true } }, modifier = modifier) {
        Text(
            if (state?.selection?.access is RealmAccess.Enterprise) state?.enterpriseName ?: stringResource(R.string.enterprise_space)
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
    val exit by vm.exitRequest.collectAsStateWithLifecycle()
    val example by vm.exampleCode.collectAsStateWithLifecycle()
    val portal by vm.portal.collectAsStateWithLifecycle()
    val nav = LocalNavController.current
    var paste by remember { mutableStateOf(false) }
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
            key(approved.id) { EnterprisePortal(approved, vm, Modifier.fillMaxSize().padding(padding)) }
        } else {
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.enterprise_current_space,
                    if (inEnterprise) state?.enterpriseName ?: stringResource(R.string.enterprise_space) else stringResource(R.string.enterprise_personal)),
                    style = MaterialTheme.typography.titleLarge)
                if (state == null || busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(phaseText(state?.phase)))
                state?.userName?.let { Text(it) }
                if (state?.isLocal == true || state?.access == null) Text(stringResource(R.string.enterprise_local_notice))
                state?.generation?.let { Text(stringResource(R.string.enterprise_generation, it)) }
                state?.lastSyncMillis?.let { Text(stringResource(R.string.enterprise_last_sync, DateFormat.getDateTimeInstance().format(Date(it)))) }
                if (error != null || state?.failure != null || state?.exitFailure != null) {
                    Text(stringResource(error ?: R.string.enterprise_failure), color = MaterialTheme.colorScheme.error)
                }
                if (state?.exitFailure != null) {
                    Button(onClick = vm::retryExit, enabled = !working) { Text(stringResource(R.string.application_recovery_retry)) }
                }
                if (state?.failure != null) {
                    TextButton(onClick = vm::requestExit, enabled = !busy) { Text(stringResource(R.string.enterprise_exit)) }
                }
                if (state?.access == null && state != null && state?.failure == null) {
                    Button(onClick = vm::joinExample, enabled = !busy) { Text(stringResource(R.string.enterprise_join_example)) }
                    OutlinedButton(onClick = { paste = true }, enabled = !busy) { Text(stringResource(R.string.enterprise_join_paste)) }
                    OutlinedButton(onClick = { scanner.launch(null) }, enabled = !busy) { Text(stringResource(R.string.enterprise_join_scan)) }
                    TextButton(onClick = vm::showExampleCode, enabled = !busy) { Text(stringResource(R.string.enterprise_example_code)) }
                }
                if (state?.access != null) {
                    if (inEnterprise || ready) {
                        Button(onClick = { vm.switchSpace(openChat) }, enabled = !busy) {
                            Text(stringResource(if (inEnterprise) R.string.enterprise_switch_personal else R.string.enterprise_switch_enterprise))
                        }
                        Text(stringResource(R.string.enterprise_switch_notice))
                    }
                    if (inEnterprise && ready) {
                        OutlinedButton(onClick = vm::showPortal, enabled = !busy) { Text(stringResource(R.string.enterprise_portal)) }
                    }
                    OutlinedButton(onClick = vm::synchronize, enabled = !busy && state?.phase != EnterpriseSessionPhase.REAUTH_REQUIRED) {
                        Text(stringResource(R.string.enterprise_sync))
                    }
                    TextButton(onClick = vm::requestExit, enabled = !busy) { Text(stringResource(R.string.enterprise_exit)) }
                }
                Button(onClick = openChat, enabled = !busy && state?.selection != null) { Text(stringResource(R.string.enterprise_open_chat)) }
            }
        }
    }
    if (paste) AlertDialog(onDismissRequest = { paste = false; enrollment = "" },
        title = { Text(stringResource(R.string.enterprise_join_paste)) },
        text = { OutlinedTextField(enrollment, { enrollment = it }, minLines = 4, maxLines = 8) },
        confirmButton = { TextButton(onClick = { val text = enrollment; enrollment = ""; paste = false; vm.join(text) }, enabled = enrollment.isNotBlank()) {
            Text(stringResource(R.string.enterprise_join_submit))
        } }, dismissButton = { TextButton(onClick = { paste = false; enrollment = "" }) { Text(stringResource(R.string.cancel)) } })
    if (exit != null) AlertDialog(onDismissRequest = vm::dismissExit,
        title = { Text(stringResource(R.string.enterprise_exit)) },
        text = { Text(stringResource(R.string.enterprise_exit_confirm, exit?.enterpriseName ?: stringResource(R.string.enterprise_space))) },
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
    if (page != null) AndroidView(factory = { page.view }, modifier = modifier)
    else Box(modifier) { CircularProgressIndicator() }
}
