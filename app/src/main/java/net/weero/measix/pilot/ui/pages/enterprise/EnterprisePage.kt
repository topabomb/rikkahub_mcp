package net.weero.measix.pilot.ui.pages.enterprise

import android.content.ClipData
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Building03
import me.rerere.hugeicons.stroke.Copy01
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
import net.weero.measix.pilot.data.enterprise.EnterpriseDataResetMode
import net.weero.measix.pilot.data.enterprise.PlatformBudgetCapability
import net.weero.measix.pilot.data.enterprise.PlatformBudgetCapabilityView
import net.weero.measix.pilot.data.enterprise.PlatformBudgetMode
import net.weero.measix.pilot.data.enterprise.PlatformBudgetStatus
import net.weero.measix.pilot.data.enterprise.PlatformBudgetPeriod
import net.weero.measix.pilot.data.enterprise.PlatformUsageCompleteness
import net.weero.measix.pilot.data.enterprise.PlatformUsageMeter
import net.weero.measix.pilot.service.EnterpriseResetPath
import net.weero.measix.pilot.service.EnterpriseConfigurationDefaultKind
import net.weero.measix.pilot.service.EnterpriseConfigurationDetailsUiModel
import net.weero.measix.pilot.service.EnterpriseConfigurationPolicyKind
import net.weero.measix.pilot.service.EnterpriseConfigurationReferenceState
import net.weero.measix.pilot.service.EnterpriseConfigurationResourceFactKind
import net.weero.measix.pilot.service.EnterpriseConfigurationResourceKind
import net.weero.measix.pilot.service.EnterpriseConfigurationResourceUiModel
import net.weero.measix.pilot.service.portal.PortalWebView
import net.weero.measix.pilot.service.portal.PortalNativeActions
import net.weero.measix.pilot.service.portal.PortalNativePrompt
import net.weero.measix.pilot.service.portal.PortalCaptureOperation
import net.weero.measix.pilot.service.portal.PortalCapturePhase
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.core.content.ContextCompat
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dokar.sonner.ToastType
import net.weero.measix.pilot.ui.components.ui.CardGroup
import net.weero.measix.pilot.ui.adaptive.LocalAdaptiveLayoutInfo
import net.weero.measix.pilot.ui.context.LocalNavController
import net.weero.measix.pilot.ui.context.LocalToaster
import org.koin.androidx.compose.koinViewModel
import java.text.DateFormat
import java.util.Date
import net.weero.measix.pilot.utils.base64Encode
import net.weero.measix.pilot.utils.ImageUtils

private data class StarterPresentation(
    val selection: RealmSelection,
    val portal: PortalPresentation? = null,
)

private enum class EnterpriseConfigurationDetailsSection { DEFAULTS, POLICIES }

@Composable
internal fun EnterpriseSpaceButton(
    modifier: Modifier = Modifier,
    vm: EnterpriseVM = koinViewModel(),
) {
    val state by vm.overview.collectAsStateWithLifecycle()
    val nav = LocalNavController.current
    val access = state?.selection?.access
    val label = if (access is RealmAccess.Enterprise) {
        state?.enterpriseName ?: stringResource(R.string.enterprise_space)
    } else stringResource(R.string.enterprise_personal)
    val open = { nav.navigate(Screen.Enterprise) { launchSingleTop = true } }
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 40.dp) {
        Surface(
            onClick = open,
            modifier = modifier,
            shape = MaterialTheme.shapes.medium,
            color = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.primary,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 40.dp)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    if (access is RealmAccess.Enterprise) HugeIcons.Building03 else HugeIcons.User,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    HugeIcons.ArrowRight01,
                    contentDescription = stringResource(R.string.enterprise_current_space, label),
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
internal fun EnterpriseSpaceSettingsCard(modifier: Modifier = Modifier, vm: EnterpriseVM = koinViewModel()) {
    val state by vm.overview.collectAsStateWithLifecycle()
    val nav = LocalNavController.current
    val access = state?.selection?.access
    val label = if (access is RealmAccess.Enterprise) {
        state?.enterpriseName ?: stringResource(R.string.enterprise_space)
    } else stringResource(R.string.enterprise_personal)
    CardGroup(modifier = modifier, title = { Text(stringResource(R.string.enterprise_spaces)) }) {
        item(
            onClick = { nav.navigate(Screen.Enterprise) { launchSingleTop = true } },
            leadingContent = {
                Icon(if (access is RealmAccess.Enterprise) HugeIcons.Building03 else HugeIcons.User, contentDescription = null)
            },
            headlineContent = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = { Text(stringResource(R.string.enterprise_spaces_desc)) },
            trailingContent = { Icon(HugeIcons.ArrowRight01, contentDescription = null) },
        )
    }
}

@Composable
internal fun EnterprisePage(openUsage: Boolean = false, vm: EnterpriseVM = koinViewModel()) {
    val state by vm.overview.collectAsStateWithLifecycle()
    val working by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val toaster = LocalToaster.current
    val pageScope = rememberCoroutineScope()
    val copiedText = stringResource(R.string.copied)
    val copyText = stringResource(R.string.copy)
    val exit by vm.exitRequest.collectAsStateWithLifecycle()
    val portal by vm.portal.collectAsStateWithLifecycle()
    val joinConfirmation by vm.joinConfirmation.collectAsStateWithLifecycle()
    val addressEditor by vm.addressEditor.collectAsStateWithLifecycle()
    val resetChoice by vm.resetChoice.collectAsStateWithLifecycle()
    val resetConfirmation by vm.resetConfirmation.collectAsStateWithLifecycle()
    val budgets by vm.budgets.collectAsStateWithLifecycle()
    val configurationDetails by vm.configurationDetails.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val nav = LocalNavController.current
    var initialSelection by remember { mutableStateOf<RealmSelection?>(null) }
    var initialSelectionCaptured by remember { mutableStateOf(false) }
    var usageDestinationOpened by remember { mutableStateOf(false) }
    LaunchedEffect(state) {
        if (!initialSelectionCaptured && state != null) {
            initialSelection = state?.selection
            initialSelectionCaptured = true
        }
    }
    var paste by remember { mutableStateOf(false) }
    var configurationDetailsOpen by rememberSaveable { mutableStateOf(false) }
    var configurationDetailsSection by rememberSaveable {
        mutableStateOf<EnterpriseConfigurationDetailsSection?>(null)
    }
    var configurationResourceKind by rememberSaveable {
        mutableStateOf<EnterpriseConfigurationResourceKind?>(null)
    }
    var configurationResourceKey by rememberSaveable { mutableStateOf<String?>(null) }
    var starterPicker by remember { mutableStateOf<StarterPresentation?>(null) }
    LaunchedEffect(state?.selection, portal) {
        starterPicker?.let {
            if (it.selection != state?.selection || (it.portal != null && it.portal != portal)) starterPicker = null
        }
    }
    LaunchedEffect(configurationDetails) {
        if (configurationDetails == null) {
            configurationDetailsOpen = false
            configurationDetailsSection = null
            configurationResourceKind = null
            configurationResourceKey = null
        } else if (configurationResourceKey != null) {
            val itemExists = configurationDetails?.resources
                ?.firstOrNull { it.kind == configurationResourceKind }
                ?.items
                ?.any { it.key == configurationResourceKey } == true
            if (!itemExists) configurationResourceKey = null
        }
    }
    // Enrollment text contains a credential and is deliberately not saved in Activity state.
    var enrollment by remember { mutableStateOf("") }
    val scanner = rememberLauncherForActivityResult(ScanQRCode()) { result ->
        when (result) {
            is QRResult.QRSuccess -> vm.join(result.content.rawValue.orEmpty())
            QRResult.QRUserCanceled -> Unit
            else -> vm.scanFailed()
        }
    }
    val imageScanner = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        pageScope.launch {
            try {
                val content = withContext(Dispatchers.IO) { ImageUtils.decodeQRCodeFromUri(context, uri) }
                if (content.isNullOrBlank()) vm.scanFailed() else vm.join(content)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                vm.scanFailed()
            }
        }
    }
    val busy = working || state?.switching == true || state?.phase == EnterpriseSessionPhase.CLOSING
    val inEnterprise = state?.selection?.access is RealmAccess.Enterprise
    val ready = state?.phase in setOf(EnterpriseSessionPhase.READY, EnterpriseSessionPhase.OFFLINE)
    val openChat = { nav.clearAndNavigate(Screen.Startup()) }
    val leavePage = {
        if (initialSelectionCaptured && state?.selection != initialSelection) openChat() else nav.popBackStack()
    }
    val closeConfigurationDetailsLayer = {
        when {
            configurationResourceKey != null -> configurationResourceKey = null
            configurationResourceKind != null -> configurationResourceKind = null
            configurationDetailsSection != null -> configurationDetailsSection = null
            else -> configurationDetailsOpen = false
        }
    }
    BackHandler(enabled = portal != null) { vm.dismissPortal(requireNotNull(portal)) }
    BackHandler(enabled = portal == null && configurationDetailsOpen) { closeConfigurationDetailsLayer() }
    BackHandler(enabled = portal == null && !configurationDetailsOpen &&
        initialSelectionCaptured && state?.selection != initialSelection) { leavePage() }
    LaunchedEffect(state?.selection, state?.access) {
        if (state?.access != null) vm.refreshBudgets()
    }
    LaunchedEffect(openUsage, state?.selection) {
        if (openUsage && !usageDestinationOpened && state?.selection?.access is RealmAccess.Enterprise) {
            usageDestinationOpened = true
            vm.showUsagePortal()
        }
    }
    val budgetInFlight = budgets?.value?.items?.sumOf { it.inFlightRequests } ?: 0L
    LaunchedEffect(state?.selection, state?.access, budgetInFlight) {
        while (budgetInFlight > 0) {
            kotlinx.coroutines.delay(30_000)
            vm.refreshBudgets()
        }
    }
    DisposableEffect(lifecycleOwner, state?.access) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && state?.access != null) vm.refreshBudgets()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(when {
            portal != null -> R.string.enterprise_portal
            configurationDetailsOpen -> R.string.enterprise_configuration_details_title
            else -> R.string.enterprise_spaces
        })) },
            navigationIcon = { IconButton(enabled = portal != null || configurationDetailsOpen || !busy,
                onClick = {
                    when {
                        portal != null -> vm.dismissPortal(portal!!)
                        configurationDetailsOpen -> closeConfigurationDetailsLayer()
                        else -> leavePage()
                    }
                }) {
                Icon(HugeIcons.ArrowLeft01, stringResource(R.string.back))
            } })
    }) { padding ->
        val approved = portal
        if (approved != null) {
            Column(Modifier.fillMaxSize().padding(padding)) {
                TextButton(onClick = {
                    starterPicker = StarterPresentation(approved.selection, approved)
                }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.enterprise_start_conversation)) }
                key(approved.id) { EnterprisePortal(approved, vm, Modifier.weight(1f).fillMaxWidth()) }
            }
        } else if (configurationDetailsOpen && configurationDetails != null) {
            EnterpriseConfigurationDetailsPage(
                details = requireNotNull(configurationDetails),
                section = configurationDetailsSection,
                resourceKind = configurationResourceKind,
                resourceKey = configurationResourceKey,
                onSection = { configurationDetailsSection = it },
                onResourceKind = {
                    configurationDetailsSection = null
                    configurationResourceKind = it
                    configurationResourceKey = null
                },
                onResource = { configurationResourceKey = it },
                onCopyAddress = { origin ->
                    pageScope.launch {
                        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Enterprise address", origin)))
                    }
                    toaster.show(copiedText, type = ToastType.Success)
                },
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        } else {
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                EnterpriseSection(stringResource(R.string.enterprise_current_space,
                    if (inEnterprise) state?.enterpriseName ?: stringResource(R.string.enterprise_space) else stringResource(R.string.enterprise_personal)),
                    icon = if (inEnterprise) HugeIcons.Building03 else HugeIcons.User,
                ) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (inEnterprise && ready) {
                            Button(onClick = {
                                state?.selection?.let { starterPicker = StarterPresentation(it) }
                            }, enabled = !busy) { Text(stringResource(R.string.enterprise_start_conversation)) }
                            OutlinedButton(onClick = vm::showPortal, enabled = !busy) { Text(stringResource(R.string.enterprise_portal)) }
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
                if (state?.exitReason == net.weero.measix.pilot.data.enterprise.EnterpriseExitReason.IDENTITY_DELETED) {
                    EnterpriseSection(stringResource(R.string.enterprise_identity_deleted)) {
                        Text(stringResource(R.string.enterprise_identity_deleted_detail), color = MaterialTheme.colorScheme.error)
                    }
                }
                val cachedSyncFailure = state?.enrollmentRecoveryFailure?.takeIf {
                    state?.access != null && state?.generation != null
                }
                val pageRecoveryFailure = state?.enrollmentRecoveryFailure.takeIf { cachedSyncFailure == null }
                val feedback = remember { BringIntoViewRequester() }
                Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().bringIntoViewRequester(feedback)) {
                    if (error != null || state?.failure != null || state?.exitFailure != null ||
                        pageRecoveryFailure != null || state?.recoveryLogoutFailure != null) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                stringResource(error?.resource ?: if (state?.recoveryLogoutFailure != null)
                                    R.string.enterprise_logout_unconfirmed else R.string.enterprise_failure,
                                    *error?.arguments.orEmpty().toTypedArray()),
                                color = MaterialTheme.colorScheme.error,
                            )
                            listOfNotNull(error?.detail, state?.failure, state?.exitFailure?.reason,
                                pageRecoveryFailure, state?.recoveryLogoutFailure)
                                .distinct()
                                .forEach { detail ->
                                    SelectionContainer {
                                        Text(
                                            detail,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                        }
                    }
                    notice?.let { Text(stringResource(it)) }
                }
                LaunchedEffect(error, notice) {
                    if (error != null || notice != null) feedback.bringIntoView()
                }
                if (state?.exitFailure != null) {
                    Button(onClick = vm::retryExit, enabled = !working) { Text(stringResource(R.string.application_recovery_retry)) }
                }
                // Reset formats every enterprise realm this device holds; the entry stays reachable in every state.
                state?.resetPath?.let { path ->
                    TextButton(onClick = vm::showReset, enabled = !busy && state?.reset == null) {
                        Text(stringResource(if (path == EnterpriseResetPath.STORAGE_FAILURE)
                            R.string.enterprise_reset_repair else R.string.enterprise_reset_title))
                    }
                }
                if (state?.failure != null) {
                    TextButton(onClick = vm::requestExit, enabled = !busy) { Text(stringResource(R.string.enterprise_exit)) }
                }
                state?.reset?.let { reset ->
                    if (reset.failure != null) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(R.string.enterprise_reset_failed), color = MaterialTheme.colorScheme.error)
                            reset.failure.let { SelectionContainer { Text(it, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error) } }
                            Button(onClick = vm::retryReset, enabled = !working) { Text(stringResource(R.string.application_recovery_retry)) }
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text(stringResource(R.string.enterprise_reset_progress), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (state?.access != null) {
                    EnterpriseSection(stringResource(R.string.enterprise_connected_enterprise,
                        state?.enterpriseName ?: stringResource(R.string.enterprise_space))) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically) {
                            state?.userName?.let { Text(it, modifier = Modifier.weight(1f)) }
                            Text(stringResource(phaseText(state?.phase)), color = MaterialTheme.colorScheme.primary)
                        }
                        if (state?.phase == EnterpriseSessionPhase.CONFIGURATION_PENDING) {
                            Text(stringResource(R.string.enterprise_pending_next_step), style = MaterialTheme.typography.bodySmall)
                        }
                        cachedSyncFailure?.let { detail ->
                            Text(
                                stringResource(R.string.enterprise_sync_failed_cached),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            SelectionContainer {
                                Text(
                                    detail,
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = vm::synchronize, enabled = !busy && state?.phase != EnterpriseSessionPhase.REAUTH_REQUIRED) {
                                Text(stringResource(R.string.enterprise_sync))
                            }
                            TextButton(onClick = vm::requestExit, enabled = !busy) { Text(stringResource(R.string.enterprise_exit)) }
                        }
                        state?.platformOrigin?.let { origin ->
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(stringResource(R.string.enterprise_address), style = MaterialTheme.typography.labelMedium)
                                    SelectionContainer { Text(origin, style = MaterialTheme.typography.bodySmall) }
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(onClick = vm::editAddress, enabled = !busy) {
                                        Text(stringResource(R.string.edit))
                                    }
                                    IconButton(onClick = {
                                        pageScope.launch {
                                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Enterprise address", origin)))
                                        }
                                        toaster.show(copiedText, type = ToastType.Success)
                                    }) {
                                        Icon(HugeIcons.Copy01, contentDescription = copyText)
                                    }
                                }
                            }
                        }
                        if (configurationDetails != null) {
                            OutlinedButton(
                                onClick = { configurationDetailsOpen = true },
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.enterprise_configuration_details_open))
                            }
                        }
                    }
                    EnterpriseBudgetSection(budgets, vm::refreshBudgets, vm::showUsagePortal)
                }
                if (state?.selection != null && state?.access == null) {
                    EnterpriseSection(stringResource(R.string.enterprise_join_options)) {
                        Text(stringResource(R.string.enterprise_enrollment_notice), style = MaterialTheme.typography.bodySmall)
                        val canJoin = !busy && state?.failure == null
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { scanner.launch(null) }, enabled = canJoin) {
                                Text(stringResource(R.string.enterprise_join_scan))
                            }
                            OutlinedButton(onClick = {
                                imageScanner.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            }, enabled = canJoin) {
                                Text(stringResource(R.string.enterprise_join_scan_gallery))
                            }
                            OutlinedButton(onClick = { paste = true }, enabled = canJoin) {
                                Text(stringResource(R.string.enterprise_join_paste))
                            }
                        }
                    }
                }
            }
        }
    }
    starterPicker?.let { original ->
        key(original) {
            EnterpriseStarterPicker(original.selection,
                onOpenDraft = { request ->
                    original.portal?.let(vm::dismissPortal)
                    starterPicker = null
                    nav.navigate(Screen.Chat(request.request, text = request.text.base64Encode())) {
                        popUpTo(Screen.Enterprise) { inclusive = true }
                    }
                }, onDismiss = { starterPicker = null })
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
    addressEditor?.let { editor ->
        var origin by remember(editor) { mutableStateOf(editor.origin) }
        AlertDialog(
            onDismissRequest = vm::dismissAddressEditor,
            title = { Text(stringResource(R.string.enterprise_address_edit_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        stringResource(R.string.enterprise_address_edit_supporting),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = origin,
                        onValueChange = { origin = it },
                        label = { Text(stringResource(R.string.enterprise_address)) },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.changeAddress(origin) }, enabled = !busy && origin.isNotBlank()) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = vm::dismissAddressEditor) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
    joinConfirmation?.let { confirmation ->
        AlertDialog(onDismissRequest = vm::dismissJoin,
            title = { Text(stringResource(R.string.enterprise_join_submit)) },
            text = { Text(stringResource(R.string.enterprise_platform_confirm, confirmation.platformOrigin)) },
            confirmButton = { TextButton(onClick = vm::confirmJoin, enabled = !busy) { Text(stringResource(R.string.confirm)) } },
            dismissButton = { TextButton(onClick = vm::dismissJoin) { Text(stringResource(R.string.cancel)) } })
    }
    resetChoice?.let { path ->
        val storageFailure = path == EnterpriseResetPath.STORAGE_FAILURE
        AlertDialog(onDismissRequest = vm::dismissReset,
            title = { Text(stringResource(if (storageFailure) R.string.enterprise_reset_repair else R.string.enterprise_reset_title)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.enterprise_reset_option_notice))
                OutlinedButton(onClick = { vm.requestReset(EnterpriseDataResetMode.KEEP_HISTORY) },
                    enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.enterprise_reset_keep_history))
                }
                OutlinedButton(onClick = { vm.requestReset(EnterpriseDataResetMode.CLEAR_ALL) },
                    enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.enterprise_reset_clear_all))
                }
            } }, confirmButton = { TextButton(onClick = vm::dismissReset) { Text(stringResource(R.string.cancel)) } })
    }
    resetConfirmation?.let { confirmation ->
        val destructive = confirmation.request.mode == EnterpriseDataResetMode.CLEAR_ALL
        val storageFailure = confirmation.path == EnterpriseResetPath.STORAGE_FAILURE
        AlertDialog(onDismissRequest = vm::dismissReset,
            title = { Text(stringResource(when (confirmation.request.mode) {
                EnterpriseDataResetMode.CLEAR_ALL -> R.string.enterprise_reset_clear_all
                EnterpriseDataResetMode.KEEP_HISTORY -> if (storageFailure) R.string.enterprise_reset_repair
                    else R.string.enterprise_reset_keep_history
            })) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(when (confirmation.request.mode) {
                    EnterpriseDataResetMode.CLEAR_ALL -> R.string.enterprise_reset_clear_all_confirm
                    EnterpriseDataResetMode.KEEP_HISTORY -> if (storageFailure) R.string.enterprise_reset_repair_confirm
                        else R.string.enterprise_reset_keep_history_confirm
                }))
                Text(stringResource(R.string.enterprise_reset_option_notice), style = MaterialTheme.typography.bodySmall)
            } },
            confirmButton = { Button(onClick = { vm.confirmReset(openChat) }, enabled = !busy,
                colors = if (destructive) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError) else ButtonDefaults.buttonColors()) {
                Text(stringResource(R.string.confirm))
            } }, dismissButton = { TextButton(onClick = vm::dismissReset) { Text(stringResource(R.string.cancel)) } })
    }
    if (exit != null) AlertDialog(onDismissRequest = vm::dismissExit,
        title = { Text(stringResource(R.string.enterprise_exit)) },
        text = { Text(stringResource(R.string.enterprise_exit_confirm,
            exit?.enterpriseName ?: stringResource(R.string.enterprise_space))) },
        confirmButton = { TextButton(onClick = { vm.confirmExit(openChat) }) { Text(stringResource(R.string.confirm)) } },
        dismissButton = { TextButton(onClick = vm::dismissExit) { Text(stringResource(R.string.cancel)) } })
}

@Composable
private fun EnterpriseConfigurationDetailsPage(
    details: EnterpriseConfigurationDetailsUiModel,
    section: EnterpriseConfigurationDetailsSection?,
    resourceKind: EnterpriseConfigurationResourceKind?,
    resourceKey: String?,
    onSection: (EnterpriseConfigurationDetailsSection) -> Unit,
    onResourceKind: (EnterpriseConfigurationResourceKind) -> Unit,
    onResource: (String) -> Unit,
    onCopyAddress: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val group = details.resources.firstOrNull { it.kind == resourceKind }
    val resource = group?.items?.firstOrNull { it.key == resourceKey }
    val adaptive = LocalAdaptiveLayoutInfo.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    var rootLeft by remember { mutableStateOf(0.dp) }
    var rootTop by remember { mutableStateOf(0.dp) }
    BoxWithConstraints(
        modifier.onGloballyPositioned { coordinates ->
            val position = coordinates.positionInWindow()
            with(density) {
                rootLeft = position.x.toDp()
                rootTop = position.y.toDp()
            }
        },
    ) {
        val verticalHinge = adaptive.primaryVerticalHingeBounds
        val leftUnavailable = verticalHinge?.let { (it.rightDp.dp - rootLeft).coerceAtLeast(0.dp) }
        val paneWidth = leftUnavailable?.let { (maxWidth - it).coerceAtLeast(1.dp) }
        val paneHeight = if (adaptive.isTabletop) {
            adaptive.primaryHorizontalHingeBounds?.let { (it.topDp.dp - rootTop).coerceAtLeast(1.dp) }
        } else null
        Column(
            Modifier
                .testTag("enterprise-configuration-details-content")
                .align(if (verticalHinge == null) Alignment.TopCenter else Alignment.TopEnd)
                .then(if (paneWidth == null) Modifier.fillMaxWidth() else Modifier.width(paneWidth))
                .then(if (paneHeight == null) Modifier.fillMaxHeight() else Modifier.height(paneHeight))
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                resource != null -> EnterpriseConfigurationResourceDetails(resource, requireNotNull(resourceKind))
                group != null -> EnterpriseConfigurationResourceGroup(group, onResource)
                section == EnterpriseConfigurationDetailsSection.DEFAULTS -> EnterpriseConfigurationDefaults(details)
                section == EnterpriseConfigurationDetailsSection.POLICIES -> EnterpriseConfigurationPolicies(details)
                else -> EnterpriseConfigurationDetailsOverview(details, onSection, onResourceKind, onCopyAddress)
            }
        }
    }
}

@Composable
private fun EnterpriseConfigurationDetailsOverview(
    details: EnterpriseConfigurationDetailsUiModel,
    onSection: (EnterpriseConfigurationDetailsSection) -> Unit,
    onResourceKind: (EnterpriseConfigurationResourceKind) -> Unit,
    onCopyAddress: (String) -> Unit,
) {
    Text(details.enterpriseName, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth())
    if (details.phase == EnterpriseSessionPhase.OFFLINE) {
        Text(
            stringResource(R.string.enterprise_configuration_details_offline),
            color = MaterialTheme.colorScheme.tertiary,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(),
        )
    }
    ElevatedCard(
        onClick = { onSection(EnterpriseConfigurationDetailsSection.DEFAULTS) },
        modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(),
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.enterprise_configuration_defaults_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(
                        R.string.enterprise_configuration_defaults_summary,
                        details.defaults.count { it.state == EnterpriseConfigurationReferenceState.AVAILABLE },
                        details.defaults.count { it.state == EnterpriseConfigurationReferenceState.UNSET },
                        details.defaults.count { it.state == EnterpriseConfigurationReferenceState.UNAVAILABLE },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Icon(HugeIcons.ArrowRight01, contentDescription = null)
        }
    }
    ElevatedCard(
        onClick = { onSection(EnterpriseConfigurationDetailsSection.POLICIES) },
        modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(),
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.enterprise_configuration_policies_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(
                        R.string.enterprise_configuration_policies_summary,
                        details.policies.count { it.allowed },
                        details.policies.size,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Icon(HugeIcons.ArrowRight01, contentDescription = null)
        }
    }
    EnterpriseSection(stringResource(R.string.enterprise_configuration_resources_title)) {
        details.resources.forEachIndexed { index, resourceGroup ->
            if (index > 0) HorizontalDivider()
            Surface(onClick = { onResourceKind(resourceGroup.kind) }, color = Color.Transparent) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(stringResource(configurationResourceKindResource(resourceGroup.kind)), modifier = Modifier.weight(1f))
                    Text(resourceGroup.itemCount.toString(), style = MaterialTheme.typography.labelLarge)
                    Icon(HugeIcons.ArrowRight01, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
    EnterpriseSection(stringResource(R.string.enterprise_configuration_diagnostics_title)) {
        ConfigurationValueRow(
            stringResource(R.string.enterprise_configuration_status),
            stringResource(phaseText(details.phase)),
        )
        ConfigurationValueRow(
            stringResource(R.string.enterprise_configuration_generation),
            details.generation.toString(),
        )
        ConfigurationValueRow(
            stringResource(R.string.enterprise_configuration_last_sync),
            details.lastSyncMillis?.let { DateFormat.getDateTimeInstance().format(Date(it)) }
                ?: stringResource(R.string.enterprise_configuration_not_available),
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.enterprise_address), style = MaterialTheme.typography.labelMedium)
                SelectionContainer { Text(details.platformOrigin, style = MaterialTheme.typography.bodySmall) }
            }
            IconButton(onClick = { onCopyAddress(details.platformOrigin) }) {
                Icon(HugeIcons.Copy01, contentDescription = stringResource(R.string.copy))
            }
        }
    }
}

@Composable
private fun EnterpriseConfigurationDefaults(details: EnterpriseConfigurationDetailsUiModel) {
    EnterpriseSection(stringResource(R.string.enterprise_configuration_defaults_title)) {
        details.defaults.forEachIndexed { index, value ->
            if (index > 0) HorizontalDivider()
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(configurationDefaultKindResource(value.kind)), style = MaterialTheme.typography.labelLarge)
                when (value.state) {
                    EnterpriseConfigurationReferenceState.AVAILABLE -> {
                        Text(requireNotNull(value.displayName))
                    }
                    EnterpriseConfigurationReferenceState.UNSET -> Text(
                        stringResource(R.string.enterprise_configuration_unset),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    EnterpriseConfigurationReferenceState.UNAVAILABLE -> {
                        value.displayName?.let { Text(it) }
                        Text(
                            stringResource(R.string.enterprise_configuration_reference_unavailable),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EnterpriseConfigurationPolicies(details: EnterpriseConfigurationDetailsUiModel) {
    EnterpriseSection(stringResource(R.string.enterprise_configuration_policies_title)) {
        details.policies.forEachIndexed { index, value ->
            if (index > 0) HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(configurationPolicyKindResource(value.kind)), modifier = Modifier.weight(1f))
                Text(
                    stringResource(if (value.allowed) R.string.enterprise_configuration_allowed else R.string.enterprise_configuration_not_allowed),
                    color = if (value.allowed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun EnterpriseConfigurationResourceGroup(
    group: net.weero.measix.pilot.service.EnterpriseConfigurationResourceGroupUiModel,
    onResource: (String) -> Unit,
) {
    Text(
        stringResource(configurationResourceKindResource(group.kind)),
        style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(),
    )
    if (group.redactedItemCount > 0) {
        EnterpriseSection(stringResource(R.string.enterprise_configuration_resources_title)) {
            Text(stringResource(R.string.enterprise_configuration_redacted_resource_count, group.redactedItemCount))
        }
    } else if (group.items.isEmpty()) {
        EnterpriseSection(stringResource(R.string.enterprise_configuration_resources_title)) {
            Text(stringResource(R.string.enterprise_configuration_no_resources))
        }
    } else group.items.forEach { item ->
        ElevatedCard(onClick = { onResource(item.key) }, modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(item.displayName, style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(if (item.enabled) R.string.enterprise_configuration_enabled else R.string.enterprise_configuration_disabled),
                        color = if (item.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Icon(HugeIcons.ArrowRight01, contentDescription = null)
            }
        }
    }
}

@Composable
private fun EnterpriseConfigurationResourceDetails(
    resource: EnterpriseConfigurationResourceUiModel,
    kind: EnterpriseConfigurationResourceKind,
) {
    Text(
        resource.displayName,
        style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(),
    )
    EnterpriseSection(stringResource(R.string.enterprise_configuration_resource_details_title)) {
        ConfigurationValueRow(
            stringResource(R.string.enterprise_configuration_resource_status),
            stringResource(if (resource.enabled) R.string.enterprise_configuration_enabled else R.string.enterprise_configuration_disabled),
        )
        resource.facts.forEach { value ->
            ConfigurationValueRow(stringResource(configurationResourceFactResource(value.kind)), value.value)
        }
    }
}

@Composable
private fun ConfigurationValueRow(label: String, value: String) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        SelectionContainer { Text(value, style = MaterialTheme.typography.bodySmall) }
    }
}

private fun configurationDefaultKindResource(kind: EnterpriseConfigurationDefaultKind): Int = when (kind) {
    EnterpriseConfigurationDefaultKind.ASSISTANT -> R.string.enterprise_configuration_default_assistant
    EnterpriseConfigurationDefaultKind.CHAT_MODEL -> R.string.enterprise_configuration_default_chat_model
    EnterpriseConfigurationDefaultKind.FAST_MODEL -> R.string.enterprise_configuration_default_fast_model
    EnterpriseConfigurationDefaultKind.TITLE_MODEL -> R.string.enterprise_configuration_default_title_model
    EnterpriseConfigurationDefaultKind.IMAGE_GENERATION -> R.string.enterprise_configuration_default_image_generation
    EnterpriseConfigurationDefaultKind.ATTACHMENT_INSPECTION_MODEL -> R.string.enterprise_configuration_default_attachment_inspection_model
    EnterpriseConfigurationDefaultKind.SUGGESTION_MODEL -> R.string.enterprise_configuration_default_suggestion_model
    EnterpriseConfigurationDefaultKind.COMPRESS_MODEL -> R.string.enterprise_configuration_default_compress_model
    EnterpriseConfigurationDefaultKind.TTS -> R.string.enterprise_configuration_default_tts
    EnterpriseConfigurationDefaultKind.ASR -> R.string.enterprise_configuration_default_asr
}

private fun configurationPolicyKindResource(kind: EnterpriseConfigurationPolicyKind): Int = when (kind) {
    EnterpriseConfigurationPolicyKind.LOCAL_PROVIDERS -> R.string.enterprise_configuration_policy_local_providers
    EnterpriseConfigurationPolicyKind.LOCAL_TTS -> R.string.enterprise_configuration_policy_local_tts
    EnterpriseConfigurationPolicyKind.LOCAL_ASR -> R.string.enterprise_configuration_policy_local_asr
    EnterpriseConfigurationPolicyKind.LOCAL_MCP -> R.string.enterprise_configuration_policy_local_mcp
    EnterpriseConfigurationPolicyKind.LOCAL_ASSISTANTS -> R.string.enterprise_configuration_policy_local_assistants
}

private fun configurationResourceKindResource(kind: EnterpriseConfigurationResourceKind): Int = when (kind) {
    EnterpriseConfigurationResourceKind.PROVIDER -> R.string.enterprise_configuration_resource_providers
    EnterpriseConfigurationResourceKind.CHAT_MODEL -> R.string.enterprise_configuration_resource_chat_models
    EnterpriseConfigurationResourceKind.IMAGE_GENERATOR -> R.string.enterprise_configuration_resource_image_generators
    EnterpriseConfigurationResourceKind.TTS -> R.string.enterprise_configuration_resource_tts
    EnterpriseConfigurationResourceKind.ASR -> R.string.enterprise_configuration_resource_asr
    EnterpriseConfigurationResourceKind.MCP -> R.string.enterprise_configuration_resource_mcp
    EnterpriseConfigurationResourceKind.ASSISTANT -> R.string.enterprise_configuration_resource_assistants
    EnterpriseConfigurationResourceKind.STARTER -> R.string.enterprise_configuration_resource_starters
    EnterpriseConfigurationResourceKind.MEMORY_SEED -> R.string.enterprise_configuration_resource_memory_seeds
    EnterpriseConfigurationResourceKind.GATEWAY -> R.string.enterprise_configuration_resource_gateways
}

private fun configurationResourceFactResource(kind: EnterpriseConfigurationResourceFactKind): Int = when (kind) {
    EnterpriseConfigurationResourceFactKind.PROVIDER -> R.string.enterprise_configuration_fact_provider
    EnterpriseConfigurationResourceFactKind.MAX_IMAGES -> R.string.enterprise_configuration_fact_max_images
    EnterpriseConfigurationResourceFactKind.ALLOWED_SIZES -> R.string.enterprise_configuration_fact_allowed_sizes
    EnterpriseConfigurationResourceFactKind.ASSISTANT -> R.string.enterprise_configuration_fact_assistant
    EnterpriseConfigurationResourceFactKind.DESCRIPTION -> R.string.enterprise_configuration_fact_description
}

@Composable
private fun EnterpriseBudgetSection(
    state: EnterpriseBudgetPresentation?,
    onRefresh: () -> Unit,
    onDetails: () -> Unit,
) {
    EnterpriseSection(stringResource(R.string.enterprise_budget_title)) {
        when {
            state == null || state.loading && state.value == null -> {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(R.string.enterprise_budget_loading), style = MaterialTheme.typography.bodySmall)
            }
            state.value != null -> {
                if (state.stale) Text(stringResource(R.string.enterprise_budget_stale),
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                state.value.items.forEach { item -> EnterpriseBudgetCapabilityCard(item) }
                Text(stringResource(R.string.enterprise_budget_as_of, formatBudgetTime(state.value.asOf)),
                    style = MaterialTheme.typography.labelSmall)
            }
        }
        state?.failure?.let {
            SelectionContainer { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onRefresh, enabled = state?.loading != true) {
                Text(stringResource(R.string.enterprise_budget_refresh))
            }
            TextButton(onClick = onDetails) { Text(stringResource(R.string.enterprise_budget_details)) }
        }
    }
}

@Composable
private fun EnterpriseBudgetCapabilityCard(item: PlatformBudgetCapabilityView) {
    val capability = stringResource(when (item.capability) {
        PlatformBudgetCapability.MODEL -> R.string.enterprise_budget_capability_model
        PlatformBudgetCapability.TTS -> R.string.enterprise_budget_capability_tts
        PlatformBudgetCapability.ASR -> R.string.enterprise_budget_capability_asr
        PlatformBudgetCapability.MCP -> R.string.enterprise_budget_capability_mcp
        PlatformBudgetCapability.IMAGE_GENERATION -> R.string.enterprise_budget_capability_image_generation
    })
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(capability, style = MaterialTheme.typography.titleSmall)
                Text(stringResource(when {
                    item.mode == PlatformBudgetMode.UNLIMITED -> R.string.enterprise_budget_unlimited
                    item.status == PlatformBudgetStatus.EXHAUSTED -> R.string.enterprise_budget_exhausted
                    item.status == PlatformBudgetStatus.PENDING_RECONCILIATION -> R.string.enterprise_budget_pending
                    else -> R.string.enterprise_budget_available
                }), color = when (item.status) {
                    PlatformBudgetStatus.EXHAUSTED -> MaterialTheme.colorScheme.error
                    PlatformBudgetStatus.PENDING_RECONCILIATION -> MaterialTheme.colorScheme.tertiary
                    PlatformBudgetStatus.AVAILABLE -> MaterialTheme.colorScheme.primary
                })
            }
            if (item.mode == PlatformBudgetMode.LIMITED) item.limits.forEach { limit ->
                Text(stringResource(R.string.enterprise_budget_limit,
                    stringResource(budgetPeriodResource(limit.period)), stringResource(budgetMeterResource(limit.meter)),
                    limit.used, limit.reserved, limit.limit, limit.remaining),
                    style = MaterialTheme.typography.bodySmall)
                limit.resetAt?.let { Text(stringResource(R.string.enterprise_budget_reset_at, formatBudgetTime(it)),
                    style = MaterialTheme.typography.labelSmall) }
            }
            if (item.usageMeters.isNotEmpty()) {
                Text(stringResource(R.string.enterprise_budget_usage_total), style = MaterialTheme.typography.labelSmall)
                item.usageMeters.forEach { usage ->
                    Text(
                        stringResource(
                            R.string.enterprise_budget_usage_meter,
                            stringResource(budgetMeterResource(usage.meter)),
                            usage.quantity,
                            stringResource(budgetCompletenessResource(usage.completeness)),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (item.inFlightRequests > 0) Text(stringResource(R.string.enterprise_budget_in_flight, item.inFlightRequests),
                style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun budgetPeriodResource(value: PlatformBudgetPeriod): Int = when (value) {
    PlatformBudgetPeriod.DAY -> R.string.enterprise_budget_period_day
    PlatformBudgetPeriod.WEEK -> R.string.enterprise_budget_period_week
    PlatformBudgetPeriod.MONTH -> R.string.enterprise_budget_period_month
    PlatformBudgetPeriod.LIFETIME -> R.string.enterprise_budget_period_lifetime
}

private fun budgetMeterResource(value: PlatformUsageMeter): Int = when (value) {
    PlatformUsageMeter.REQUESTS -> R.string.enterprise_budget_meter_requests
    PlatformUsageMeter.REQUESTED_IMAGES -> R.string.enterprise_budget_meter_requested_images
    PlatformUsageMeter.INPUT_TOKENS -> R.string.enterprise_budget_meter_input_tokens
    PlatformUsageMeter.OUTPUT_TOKENS -> R.string.enterprise_budget_meter_output_tokens
    PlatformUsageMeter.CACHED_TOKENS -> R.string.enterprise_budget_meter_cached_tokens
    PlatformUsageMeter.TOTAL_TOKENS -> R.string.enterprise_budget_meter_total_tokens
    PlatformUsageMeter.CHARACTERS -> R.string.enterprise_budget_meter_characters
    PlatformUsageMeter.AUDIO_SECONDS -> R.string.enterprise_budget_meter_audio_seconds
}

private fun budgetCompletenessResource(value: PlatformUsageCompleteness): Int = when (value) {
    PlatformUsageCompleteness.EXACT -> R.string.enterprise_budget_completeness_exact
    PlatformUsageCompleteness.PARTIAL -> R.string.enterprise_budget_completeness_partial
    PlatformUsageCompleteness.UNKNOWN -> R.string.enterprise_budget_completeness_unknown
}

private fun formatBudgetTime(value: String): String = try {
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(
        Date.from(java.time.Instant.parse(value)))
} catch (_: java.time.format.DateTimeParseException) { value }

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
                acquired = vm.openPortal(context, original) { vm.portalClosed(original, it) }
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
            if (failure != null) vm.portalFailed(original, failure)
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
