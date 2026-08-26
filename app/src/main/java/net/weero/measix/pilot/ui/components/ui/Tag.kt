package net.weero.measix.pilot.ui.components.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.weero.measix.pilot.R
import net.weero.measix.pilot.data.datastore.EffectiveSettingsSnapshot
import net.weero.measix.pilot.data.datastore.ManagedConfigurationRecordKind
import net.weero.measix.pilot.data.datastore.ManagedConfigurationState
import net.weero.measix.pilot.data.datastore.SettingsValueSource
import net.weero.measix.pilot.ui.theme.extendColors
import kotlin.uuid.Uuid

enum class TagType {
    DEFAULT,
    SUCCESS,
    ERROR,
    WARNING,
    INFO
}

@Composable
fun Tag(
    modifier: Modifier = Modifier,
    type: TagType = TagType.DEFAULT,
    onClick: (() -> Unit)? = null,
    children: @Composable RowScope.() -> Unit
) {
    val background = when (type) {
        TagType.SUCCESS -> MaterialTheme.extendColors.green2
        TagType.ERROR -> MaterialTheme.extendColors.red2
        TagType.WARNING -> MaterialTheme.extendColors.orange2
        TagType.INFO -> MaterialTheme.extendColors.blue2
        else -> MaterialTheme.colorScheme.tertiaryContainer
    }
    val textColor = when (type) {
        TagType.SUCCESS -> MaterialTheme.extendColors.gray8
        TagType.ERROR -> MaterialTheme.extendColors.red8
        TagType.WARNING -> MaterialTheme.extendColors.orange8
        TagType.INFO -> MaterialTheme.extendColors.blue8
        else -> MaterialTheme.colorScheme.onTertiaryContainer
    }
    ProvideTextStyle(MaterialTheme.typography.labelSmall.copy(color = textColor)) {
        Row(
            modifier = modifier
                .clip(RoundedCornerShape(50))
                .background(background)
                .let {
                    if (onClick != null) {
                        it.clickable { onClick() }
                    } else {
                        it
                    }
                }
                .padding(horizontal = 6.dp, vertical = 1.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            children()
        }
    }
}

/** Read-only provenance and lock context for a record rendered by its original settings page. */
@Composable
internal fun ManagedRecordStatus(
    snapshot: EffectiveSettingsSnapshot,
    kind: ManagedConfigurationRecordKind,
    id: Uuid,
    modifier: Modifier = Modifier,
) {
    if (snapshot.managedState == ManagedConfigurationState.ABSENT) return
    ManagedSourceAndLockStatus(
        source = snapshot.access.sourceOf(kind, id),
        lockReason = snapshot.access.reasonFor("records/${kind.settingsPath}/$id"),
        modifier = modifier,
    )
}

/** Read-only provenance and lock context for an effective named selection. */
@Composable
internal fun ManagedDefaultStatus(
    snapshot: EffectiveSettingsSnapshot,
    path: String,
    modifier: Modifier = Modifier,
) {
    if (snapshot.managedState == ManagedConfigurationState.ABSENT) return
    ManagedSourceAndLockStatus(
        source = snapshot.access.sourceOfDefault(path),
        lockReason = snapshot.access.reasonFor(path),
        modifier = modifier,
    )
}

@Composable
private fun ManagedSourceAndLockStatus(
    source: SettingsValueSource,
    lockReason: String?,
    modifier: Modifier,
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Tag(
            type = when (source) {
                SettingsValueSource.BUILT_IN -> TagType.DEFAULT
                SettingsValueSource.LOCAL -> TagType.INFO
                SettingsValueSource.MANAGED -> TagType.WARNING
            },
        ) {
            Text(
                stringResource(
                    when (source) {
                        SettingsValueSource.BUILT_IN -> R.string.managed_configuration_source_builtin
                        SettingsValueSource.LOCAL -> R.string.managed_configuration_source_local
                        SettingsValueSource.MANAGED -> R.string.managed_configuration_source_managed
                    },
                ),
            )
        }
        lockReason?.let { reason ->
            Tag(type = TagType.WARNING) { Text(reason) }
        }
    }
}

@Composable
@Preview(showBackground = true)
private fun TagPreview() {
    Column(
        modifier = Modifier.padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Tag(type = TagType.SUCCESS) {
            Text("测试")
        }
        Tag(type = TagType.ERROR) {
            Text("测试")
        }
        Tag(type = TagType.WARNING) {
            Text("测试")
        }
        Tag(type = TagType.INFO) {
            Text("测试")
        }
        Tag(type = TagType.DEFAULT) {
            Text("测试")
        }
    }
}
