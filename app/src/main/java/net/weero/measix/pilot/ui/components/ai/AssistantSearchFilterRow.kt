package net.weero.measix.pilot.ui.components.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Search01
import net.weero.measix.pilot.R

/** Shared compact search/type-filter control for assistant collections. */
@Composable
fun AssistantSearchFilterRow(
    query: String,
    onQueryChange: (String) -> Unit,
    showSubAssistants: Boolean,
    onShowSubAssistantsChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    stacked: Boolean = false,
    showSubAssistantFilter: Boolean = true,
) {
    if (stacked) {
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AssistantSearchField(query, onQueryChange, Modifier.fillMaxWidth())
            if (showSubAssistantFilter) {
                SubAssistantFilterChip(
                    selected = showSubAssistants,
                    onSelectedChange = onShowSubAssistantsChange,
                )
            }
        }
        return
    }
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AssistantSearchField(query, onQueryChange, Modifier.weight(1f))
        if (showSubAssistantFilter) {
            SubAssistantFilterChip(
                selected = showSubAssistants,
                onSelectedChange = onShowSubAssistantsChange,
                modifier = Modifier.height(56.dp),
            )
        }
    }
}

@Composable
private fun AssistantSearchField(query: String, onQueryChange: (String) -> Unit, modifier: Modifier) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier,
        placeholder = { Text(stringResource(R.string.assistant_page_search_placeholder)) },
        leadingIcon = { Icon(HugeIcons.Search01, contentDescription = null) },
        trailingIcon = {
            if (query.isNotBlank()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(HugeIcons.Cancel01, contentDescription = stringResource(R.string.clear_search))
                }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
    )
}
