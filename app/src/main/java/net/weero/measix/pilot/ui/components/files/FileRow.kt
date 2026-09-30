package net.weero.measix.pilot.ui.components.files

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.MoreVertical
import net.weero.measix.pilot.R
import net.weero.measix.pilot.ui.theme.CustomColors

/** Presentation only. Each caller owns identity, available actions and selection semantics. */
@Composable
internal fun FileRow(
    name: String, detail: String, directory: Boolean, selected: Boolean, selecting: Boolean,
    onOpen: () -> Unit, onSelect: (() -> Unit)?,
    thumbnail: (@Composable () -> Unit)? = null,
    compact: Boolean = false,
    actions: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().combinedClickable(
        onClick = { if (selecting && onSelect != null) onSelect() else onOpen() }, onLongClick = onSelect,
    ), shape = if (compact) androidx.compose.ui.graphics.RectangleShape else MaterialTheme.shapes.medium,
        colors = if (compact) CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
        else CustomColors.cardColorsOnSurfaceContainer) {
        Row(Modifier.fillMaxWidth().heightIn(min = if (compact) 64.dp else 0.dp).padding(start = if (compact) 12.dp else 16.dp,
            top = if (compact) 4.dp else 12.dp, bottom = if (compact) 4.dp else 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (selecting && onSelect != null) Checkbox(selected, { onSelect() })
            else if (thumbnail != null) thumbnail()
            else Icon(if (directory) HugeIcons.Folder01 else HugeIcons.File02, null, Modifier.size(22.dp),
                tint = if (directory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 4.dp)) {
                Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (!selecting) Box {
                IconButton({ expanded = true }) { Icon(HugeIcons.MoreVertical, stringResource(R.string.more_options)) }
                DropdownMenu(expanded, { expanded = false }) { actions { expanded = false } }
            }
        }
    }
}
