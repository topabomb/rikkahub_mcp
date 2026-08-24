package net.weero.measix.pilot.ui.components.ai

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import net.weero.measix.pilot.R
import net.weero.measix.pilot.ui.components.ui.SubAssistantAvatarMark

/** Shared type filter used by assistant management and assistant selection. */
@Composable
fun SubAssistantFilterChip(
    selected: Boolean,
    onSelectedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    FilterChip(
        selected = selected,
        onClick = { onSelectedChange(!selected) },
        label = { Text(stringResource(R.string.assistant_picker_show_sub_assistants)) },
        leadingIcon = {
            Box(
                modifier = Modifier.size(18.dp),
                contentAlignment = Alignment.Center,
            ) {
                SubAssistantAvatarMark()
            }
        },
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
    )
}
