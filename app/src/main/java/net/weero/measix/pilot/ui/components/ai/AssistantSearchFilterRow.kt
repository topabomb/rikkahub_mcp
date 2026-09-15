package net.weero.measix.pilot.ui.components.ai

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
    showSubAssistantFilter: Boolean = true,
) {
    AssistantSearchField(
        query = query,
        onQueryChange = onQueryChange,
        showSubAssistants = showSubAssistants,
        onShowSubAssistantsChange = onShowSubAssistantsChange,
        showSubAssistantFilter = showSubAssistantFilter,
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun AssistantSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    showSubAssistants: Boolean,
    onShowSubAssistantsChange: (Boolean) -> Unit,
    showSubAssistantFilter: Boolean,
    modifier: Modifier,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier,
        placeholder = { Text(stringResource(R.string.assistant_page_search_placeholder)) },
        leadingIcon = { Icon(HugeIcons.Search01, contentDescription = null) },
        trailingIcon = {
            Row(
                modifier = Modifier.padding(end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (query.isNotBlank()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(HugeIcons.Cancel01, contentDescription = stringResource(R.string.clear_search))
                    }
                }
                if (showSubAssistantFilter) {
                    SubAssistantFilterButton(
                        selected = showSubAssistants,
                        onSelectedChange = onShowSubAssistantsChange,
                        modifier = Modifier.size(40.dp),
                    )
                }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
    )
}
