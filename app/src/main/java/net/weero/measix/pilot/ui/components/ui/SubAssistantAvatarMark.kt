package net.weero.measix.pilot.ui.components.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.HierarchySquare03
import net.weero.measix.pilot.R

/** Canonical sub-assistant mark shared by avatars and the assistant type filter. */
@Composable
fun SubAssistantAvatarMark(
    modifier: Modifier = Modifier,
    size: Dp = 14.dp,
) {
    val description = stringResource(R.string.assistant_page_sub_assistant_tag)
    Surface(
        modifier = modifier.size(size),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shadowElevation = 1.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = HugeIcons.HierarchySquare03,
                contentDescription = description,
                modifier = Modifier.size(size * 0.68f),
            )
        }
    }
}
