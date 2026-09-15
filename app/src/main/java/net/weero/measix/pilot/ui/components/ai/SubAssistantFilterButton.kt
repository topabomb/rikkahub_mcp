package net.weero.measix.pilot.ui.components.ai

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Hierarchy
import net.weero.measix.pilot.R
import net.weero.measix.pilot.ui.components.ui.Tooltip

/** Shared type filter used by assistant management and assistant selection. */
@Composable
fun SubAssistantFilterButton(
    selected: Boolean,
    onSelectedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val description = stringResource(R.string.assistant_picker_show_sub_assistants)
    Tooltip(tooltip = { Text(description) }) {
        FilledTonalIconToggleButton(
            checked = selected,
            onCheckedChange = onSelectedChange,
            modifier = modifier,
            shape = RoundedCornerShape(10.dp),
            colors = IconButtonDefaults.filledTonalIconToggleButtonColors(
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                checkedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                checkedContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ),
        ) {
            Icon(
                imageVector = HugeIcons.Hierarchy,
                contentDescription = description,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}
