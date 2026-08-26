@file:SuppressLint("ClickableViewAccessibility")

package net.weero.measix.pilot.ui.pages.extensions.workspace

import android.annotation.SuppressLint
import android.graphics.Typeface
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import com.termux.view.TerminalView
import net.weero.measix.pilot.R
import net.weero.measix.pilot.service.workspace.WorkspaceTerminalViewport
import net.weero.measix.pilot.service.workspace.WorkspaceTerminalReadiness
import net.weero.measix.pilot.service.workspace.WorkspaceTerminalScreenUiModel
import net.weero.measix.pilot.service.workspace.WorkspaceTerminalTabUiModel
import net.weero.measix.pilot.service.workspace.WorkspaceTerminalViewClient
import net.weero.measix.pilot.ui.components.nav.BackButton
import net.weero.measix.pilot.ui.context.LocalToaster
import net.weero.measix.pilot.ui.theme.ColorMode
import net.weero.measix.pilot.ui.theme.MeasixTheme
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun WorkspaceTerminalPage(id: String) {
    val vm: WorkspaceTerminalVM = koinViewModel(parameters = { parametersOf(id) })
    val state by vm.state.collectAsStateWithLifecycle()
    val commandError by vm.commandError.collectAsStateWithLifecycle()
    val toaster = LocalToaster.current
    val commandErrorMessage = when (val error = commandError) {
        is WorkspaceTerminalCommandError.LimitReached -> {
            stringResource(R.string.workspace_terminal_limit_reached, error.maximum)
        }
        WorkspaceTerminalCommandError.NotReady -> stringResource(R.string.workspace_terminal_not_installed)
        WorkspaceTerminalCommandError.Unexpected -> stringResource(R.string.workspace_terminal_command_failed)
        null -> null
    }

    LaunchedEffect(commandError, commandErrorMessage) {
        val message = commandErrorMessage ?: return@LaunchedEffect
        toaster.show(message, type = ToastType.Error)
        vm.consumeCommandError()
    }

    MeasixTheme(colorMode = ColorMode.DARK) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            state.name?.let { stringResource(R.string.workspace_terminal_title_with_name, it) }
                                ?: stringResource(R.string.workspace_terminal_title),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = { BackButton() },
                )
            },
        ) { padding ->
            WorkspaceTerminalContent(
                state = state,
                contentPadding = padding,
                bindViewport = vm::bindViewport,
                unbindViewport = vm::unbindViewport,
                writeTerminal = vm::write,
                onCreate = vm::create,
                onSelect = vm::select,
                onClose = vm::close,
                onRename = vm::rename,
                onReorder = vm::reorder,
            )
        }
    }
}

@Composable
private fun WorkspaceTerminalContent(
    state: WorkspaceTerminalScreenUiModel,
    contentPadding: PaddingValues,
    bindViewport: (String, WorkspaceTerminalViewport) -> Boolean,
    unbindViewport: (String, WorkspaceTerminalViewport) -> Unit,
    writeTerminal: (String, String) -> Unit,
    onCreate: () -> Unit,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onReorder: (List<String>) -> Unit,
) {
    val selected = state.terminal.tabs.firstOrNull { it.id == state.terminal.selectedTabId }
    Surface(Modifier.fillMaxSize().padding(contentPadding).imePadding(), color = Color.Black) {
        Column(Modifier.fillMaxSize()) {
            WorkspaceTerminalTabs(
                tabs = state.terminal.tabs,
                selectedId = selected?.id,
                canCreate = state.canCreateTerminal,
                onCreate = onCreate,
                onSelect = onSelect,
                onClose = onClose,
                onRename = onRename,
                onReorder = onReorder,
            )
            if (selected == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (state.shellReady) stringResource(R.string.workspace_terminal_loading)
                        else stringResource(R.string.workspace_terminal_not_installed),
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                    )
                }
            } else {
                WorkspaceTerminalView(selected, bindViewport, unbindViewport, writeTerminal)
            }
        }
    }
}

@Composable
private fun WorkspaceTerminalTabs(
    tabs: List<WorkspaceTerminalTabUiModel>,
    selectedId: String?,
    canCreate: Boolean,
    onCreate: () -> Unit,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onReorder: (List<String>) -> Unit,
) {
    var renameTab by remember { mutableStateOf<WorkspaceTerminalTabUiModel?>(null) }
    var renameText by remember { mutableStateOf("") }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEachIndexed { index, tab ->
            val tabTitle = tab.customTitle
                ?: stringResource(R.string.workspace_terminal_tab_title, tab.number)
            Row(
                Modifier.background(
                    if (tab.id == selectedId) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(8.dp),
                ).combinedClickable(
                    onClick = { onSelect(tab.id) },
                    onLongClick = {
                        renameTab = tab
                        renameText = tab.customTitle.orEmpty()
                    },
                ).padding(start = 6.dp, top = 7.dp, bottom = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (index > 0) {
                    Text("‹", Modifier.clickable {
                        onReorder(tabs.map { it.id }.toMutableList().apply {
                            add(index - 1, removeAt(index))
                        })
                    }.padding(horizontal = 5.dp))
                }
                Text(tabTitle, maxLines = 1)
                if (index < tabs.lastIndex) {
                    Text("›", Modifier.clickable {
                        onReorder(tabs.map { it.id }.toMutableList().apply {
                            add(index + 1, removeAt(index))
                        })
                    }.padding(horizontal = 5.dp))
                }
                Spacer(Modifier.width(4.dp))
                Text(
                    "×",
                    Modifier.clickable { onClose(tab.id) }.padding(horizontal = 8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            "+",
            Modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                .clickable(enabled = canCreate, onClick = onCreate)
                .padding(horizontal = 14.dp, vertical = 7.dp),
            color = if (canCreate) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
        )
    }
    renameTab?.let { tab ->
        AlertDialog(
            onDismissRequest = { renameTab = null },
            title = { Text(stringResource(R.string.common_rename)) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onRename(tab.id, renameText)
                    renameTab = null
                }) { Text(stringResource(R.string.common_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { renameTab = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

@Composable
private fun WorkspaceTerminalView(
    tab: WorkspaceTerminalTabUiModel,
    bindViewport: (String, WorkspaceTerminalViewport) -> Boolean,
    unbindViewport: (String, WorkspaceTerminalViewport) -> Unit,
    writeTerminal: (String, String) -> Unit,
) {
    if (tab.readiness != WorkspaceTerminalReadiness.READY) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.workspace_terminal_loading),
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            )
        }
        return
    }

    val context = LocalContext.current
    val textSize = with(LocalDensity.current) { 12.sp.roundToPx() }
    val typeface = remember(context) { ResourcesCompat.getFont(context, R.font.jetbrains_mono) ?: Typeface.MONOSPACE }
    var controlDown by remember(tab.id) { mutableStateOf(false) }
    var altDown by remember(tab.id) { mutableStateOf(false) }
    val viewClient = remember(tab.id) { WorkspaceTerminalViewClient(context) }
    viewClient.controlDown = controlDown
    viewClient.altDown = altDown
    var boundView by remember(tab.id) { mutableStateOf<TerminalView?>(null) }

    DisposableEffect(tab.id, boundView) {
        onDispose {
            boundView?.let { unbindViewport(tab.id, WorkspaceTerminalViewport(it)) }
            viewClient.terminalView = null
        }
    }

    Column(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            factory = { viewContext ->
                TerminalView(viewContext, null).apply {
                    isFocusable = true
                    isFocusableInTouchMode = true
                    setTextSize(textSize)
                    setTypeface(typeface)
                    setTerminalViewClient(viewClient)
                    viewClient.terminalView = this
                    boundView = this
                    bindViewport(tab.id, WorkspaceTerminalViewport(this))
                    setOnTouchListener { view, event ->
                        if (event.action == MotionEvent.ACTION_UP) {
                            view.performClick()
                            viewClient.focusAndShowKeyboard()
                        }
                        false
                    }
                    post { viewClient.focusAndShowKeyboard() }
                }
            },
            update = { view ->
                view.setTextSize(textSize)
                view.setTypeface(typeface)
                view.setTerminalViewClient(viewClient)
                viewClient.terminalView = view
                boundView = view
                bindViewport(tab.id, WorkspaceTerminalViewport(view))
            },
        )
        TerminalExtraKeysBar(
            controlDown,
            altDown,
            { controlDown = !controlDown },
            { altDown = !altDown },
        ) { writeTerminal(tab.id, it) }
    }
}

@Composable
private fun TerminalExtraKeysBar(
    controlDown: Boolean,
    altDown: Boolean,
    onControlToggle: () -> Unit,
    onAltToggle: () -> Unit,
    onSendText: (String) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
            .horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TerminalExtraKey("ESC") { onSendText("\u001B") }
        TerminalExtraKey("TAB") { onSendText("\t") }
        TerminalExtraKey("CTRL", controlDown, onControlToggle)
        TerminalExtraKey("ALT", altDown, onAltToggle)
        listOf("-", "/", "|").forEach { key -> TerminalExtraKey(key) { onSendText(key) } }
        TerminalExtraKey("←") { onSendText("\u001B[D") }
        TerminalExtraKey("↓") { onSendText("\u001B[B") }
        TerminalExtraKey("↑") { onSendText("\u001B[A") }
        TerminalExtraKey("→") { onSendText("\u001B[C") }
        TerminalExtraKey("HOME") { onSendText("\u001B[H") }
        TerminalExtraKey("END") { onSendText("\u001B[F") }
    }
}

@Composable
private fun TerminalExtraKey(label: String, selected: Boolean = false, onClick: () -> Unit) {
    Text(
        label,
        Modifier.background(
            if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
            RoundedCornerShape(6.dp),
        ).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        style = MaterialTheme.typography.labelMedium,
        color = if (selected) MaterialTheme.colorScheme.onPrimary
        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
    )
}
