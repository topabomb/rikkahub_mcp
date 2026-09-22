package net.weero.measix.pilot.ui.components.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import coil3.compose.AsyncImage

/** Orchelm identifies enterprise-owned spaces and resources; tint follows the surrounding Material role. */
@Composable
fun OrchelmLogo(
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    AsyncImage(
        model = "file:///android_asset/icons/orchelm-mono.svg",
        contentDescription = contentDescription,
        colorFilter = ColorFilter.tint(color),
        modifier = modifier,
    )
}
