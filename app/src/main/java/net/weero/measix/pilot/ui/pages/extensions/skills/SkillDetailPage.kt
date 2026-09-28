package net.weero.measix.pilot.ui.pages.extensions.skills

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.key
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.text.selection.SelectionContainer
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import net.weero.measix.pilot.data.files.SkillContentReadResult
import net.weero.measix.pilot.utils.userVisibleDiagnostic
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import net.weero.measix.pilot.ui.components.ui.FileEditorState
import net.weero.measix.pilot.ui.components.ui.FileTextEditor
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import net.weero.measix.pilot.R
import net.weero.measix.pilot.data.files.SkillFile
import net.weero.measix.pilot.data.files.SkillFileDeleteResult
import net.weero.measix.pilot.data.files.SkillFileNode
import net.weero.measix.pilot.data.files.SkillFileSaveResult
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.FilePen
import com.composables.icons.lucide.FileText
import com.composables.icons.lucide.Folder
import com.composables.icons.lucide.FolderOpen
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Trash2
import net.weero.measix.pilot.ui.components.nav.BackButton
import net.weero.measix.pilot.ui.components.ui.ConfirmDialog
import net.weero.measix.pilot.ui.context.LocalToaster
import net.weero.measix.pilot.ui.theme.CustomColors
import net.weero.measix.pilot.utils.plus
import org.koin.androidx.compose.koinViewModel

@Composable
fun SkillDetailPage(skillName: String) {
    key(skillName) { SkillDetailContent(skillName) }
}

@Composable
private fun SkillDetailContent(skillName: String) {
    val vm = koinViewModel<SkillDetailVM>()
    LaunchedEffect(skillName) { vm.init(skillName) }

    val tree by vm.tree.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val toaster = LocalToaster.current

    val scope = rememberCoroutineScope()
    var busy by remember(skillName) { mutableStateOf(false) }
    var diagnostic by remember(skillName) { mutableStateOf<String?>(null) }
    val failure by vm.failure.collectAsStateWithLifecycle()
    fun operation(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                android.util.Log.e("SkillDetailPage", "Skill operation failed", error)
                diagnostic = error.userVisibleDiagnostic()
            } finally { busy = false }
        }
    }
    var editingFile by remember(skillName) { mutableStateOf<SkillFileEditor?>(null) }
    var showAddDialog by rememberSaveable(skillName) { mutableStateOf(false) }
    var deleteTarget by remember(skillName) { mutableStateOf<SkillFile?>(null) }
    val deleteFailedMsg = stringResource(R.string.skill_detail_page_delete_failed)
    val deleteProtectedMsg = stringResource(R.string.skill_detail_page_delete_skill_file_forbidden)
    val loadFailedMsg = stringResource(R.string.skill_detail_page_load_failed)
    val saveErrorMessages = mapOf(
        SkillFileSaveResult.NOT_FOUND to stringResource(R.string.skill_detail_page_skill_not_found),
        SkillFileSaveResult.INVALID_PATH to stringResource(R.string.skill_detail_page_invalid_path),
        SkillFileSaveResult.INVALID_SKILL to stringResource(R.string.skill_detail_page_invalid_skill),
        SkillFileSaveResult.NAME_MISMATCH to stringResource(R.string.skill_detail_page_name_mismatch, skillName),
    )

    val scrollState = rememberScrollState()
    var previousScrollOffset by remember { mutableIntStateOf(0) }
    val fabVisible by remember {
        derivedStateOf {
            val delta = scrollState.value - previousScrollOffset
            previousScrollOffset = scrollState.value
            delta <= 0
        }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(skillName) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        floatingActionButton = {
            AnimatedVisibility(
                visible = fabVisible,
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut(),
            ) {
                FloatingActionButton(onClick = { if (!busy) showAddDialog = true }) {
                    Icon(Lucide.Plus, contentDescription = stringResource(R.string.skill_detail_page_new_file))
                }
            }
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(innerPadding + PaddingValues(8.dp)),
        ) {
            FileTree(
                nodes = tree,
                depth = 0,
                onEdit = { skillFile ->
                    operation {
                        when (val result = vm.readFile(skillName, skillFile)) {
                            is SkillContentReadResult.Success -> editingFile = SkillFileEditor(skillFile, result.content)
                            is SkillContentReadResult.ReadFailure -> throw result.cause
                            else -> toaster.show("$loadFailedMsg (${result.javaClass.simpleName})")
                        }
                    }
                },
                onDelete = { if (!busy) deleteTarget = it },
            )
        }
    }

    editingFile?.let { editor ->
        EditFileDialog(
            busy = busy,
            skillFile = editor.skillFile,
            initialContent = editor.content,
            onDismiss = { editingFile = null },
            onConfirm = { content ->
                operation {
                    val result = vm.saveFile(skillName, editor.skillFile.relativePath, content)
                    if (result == SkillFileSaveResult.SUCCESS) editingFile = null
                    else toaster.show(saveErrorMessages.getValue(result))
                }
            },
        )
    }

    if (showAddDialog) {
        AddFileDialog(
            busy = busy,
            onDismiss = { showAddDialog = false },
            onConfirm = { fileName, content ->
                operation {
                    val result = vm.saveFile(skillName, fileName, content)
                    if (result == SkillFileSaveResult.SUCCESS) showAddDialog = false
                    else toaster.show(saveErrorMessages.getValue(result))
                }
            },
        )
    }

    ConfirmDialog(
        show = deleteTarget != null,
        title = stringResource(R.string.skill_detail_page_delete_file),
        confirmText = stringResource(R.string.delete),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            deleteTarget?.let { skillFile ->
                operation {
                    when (val result = vm.deleteFile(skillName, skillFile)) {
                        SkillFileDeleteResult.SUCCESS -> deleteTarget = null
                        SkillFileDeleteResult.PROTECTED_SKILL_FILE -> toaster.show(deleteProtectedMsg)
                        else -> toaster.show("$deleteFailedMsg (${result.name})")
                    }
                }
            }
        },
        onDismiss = { if (!busy) deleteTarget = null },
    ) {
        Text(stringResource(R.string.skill_detail_page_delete_confirm, deleteTarget?.relativePath ?: ""))
    }
    (diagnostic ?: failure?.userVisibleDiagnostic())?.let { detail ->
        AlertDialog(
            onDismissRequest = { diagnostic = null; vm.dismissFailure() },
            text = { SelectionContainer { Text(detail, Modifier.verticalScroll(rememberScrollState())) } },
            confirmButton = { TextButton(onClick = { diagnostic = null; vm.dismissFailure() }) { Text(stringResource(R.string.confirm)) } },
        )
    }

}

private data class SkillFileEditor(
    val skillFile: SkillFile,
    val content: String,
)

@Composable
private fun FileTree(
    nodes: List<SkillFileNode>,
    depth: Int,
    onEdit: (SkillFile) -> Unit,
    onDelete: (SkillFile) -> Unit,
) {
    nodes.fastForEach { node ->
        when (node) {
            is SkillFileNode.FileNode -> FileItem(
                skillFile = node.skillFile,
                depth = depth,
                onEdit = { onEdit(node.skillFile) },
                onDelete = { onDelete(node.skillFile) },
            )

            is SkillFileNode.DirNode -> DirItem(
                node = node,
                depth = depth,
                onEdit = onEdit,
                onDelete = onDelete,
            )
        }
    }
}

@Composable
private fun FileItem(
    skillFile: SkillFile,
    depth: Int,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = (16 + depth * 20).dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Lucide.FileText,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = skillFile.name,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp),
            )
            Text(
                text = "${skillFile.sizeBytes} B",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = Lucide.FilePen,
                    contentDescription = stringResource(R.string.edit),
                    modifier = Modifier.size(16.dp),
                )
            }
            if (skillFile.relativePath != "SKILL.md") {
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = Lucide.Trash2,
                        contentDescription = stringResource(R.string.delete),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun DirItem(
    node: SkillFileNode.DirNode,
    depth: Int,
    onEdit: (SkillFile) -> Unit,
    onDelete: (SkillFile) -> Unit,
) {
    var expanded by rememberSaveable(node.relativePath) { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(start = (16 + depth * 20).dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = if (expanded) Lucide.ChevronDown else Lucide.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Icon(
                    imageVector = if (expanded) Lucide.FolderOpen else Lucide.Folder,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.tertiary,
                )
                Text(
                    text = node.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column {
                    FileTree(
                        nodes = node.children,
                        depth = depth + 1,
                        onEdit = onEdit,
                        onDelete = onDelete,
                    )
                }
            }
        }
    }
}

@Composable
internal fun EditFileDialog(
    busy: Boolean,
    skillFile: SkillFile,
    initialContent: String,
    onDismiss: () -> Unit,
    onConfirm: (content: String) -> Unit,
) {
    val contentState = remember(skillFile.relativePath) {
        FileEditorState(initialContent)
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(skillFile.relativePath, fontFamily = FontFamily.Monospace) },
        text = {
            FileTextEditor(
                state = contentState,
                enabled = !busy,
                label = stringResource(R.string.skill_detail_page_content),
                minLines = 10,
                maxLines = 20,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(contentState.snapshot()) }, enabled = !busy) { Text(stringResource(R.string.skill_detail_page_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
internal fun AddFileDialog(
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (fileName: String, content: String) -> Unit,
) {
    var fileName by rememberSaveable { mutableStateOf("") }
    val contentState = remember { FileEditorState() }
    val fileNameError = fileName.isNotBlank() && (fileName.contains('\\'))

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.skill_detail_page_new_file)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = fileName,
                    onValueChange = { fileName = it },
                    label = { Text(stringResource(R.string.skill_detail_page_file_name)) },
                    placeholder = { Text("examples/basic.md", fontFamily = FontFamily.Monospace) },
                    supportingText = {
                        if (fileNameError) Text(
                            stringResource(R.string.skill_detail_page_file_name_invalid),
                            color = MaterialTheme.colorScheme.error,
                        )
                    },
                    isError = fileNameError,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )
                FileTextEditor(
                    state = contentState,
                    enabled = !busy,
                    label = stringResource(R.string.skill_detail_page_content),
                    minLines = 6,
                    maxLines = 14,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(fileName.trim(), contentState.snapshot()) },
                enabled = !busy && fileName.isNotBlank() && !fileNameError,
            ) {
                Text(stringResource(R.string.skill_detail_page_create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.cancel)) }
        },
    )
}
