package net.weero.measix.pilot.ui.pages.assistant.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.weero.measix.pilot.R
import net.weero.measix.pilot.Screen
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.ui.components.nav.BackButton
import net.weero.measix.pilot.ui.components.ui.UIAvatar
import net.weero.measix.pilot.ui.context.LocalNavController
import net.weero.measix.pilot.ui.context.LocalToaster
import net.weero.measix.pilot.ui.hooks.heroAnimation
import net.weero.measix.pilot.ui.theme.CustomColors
import net.weero.measix.pilot.utils.plus
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import com.dokar.sonner.ToastType

@Composable
fun AssistantDetailPage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(
        parameters = {
            parametersOf(id)
        }
    )
    val assistant = vm.assistant.collectAsStateWithLifecycle().value
    val navController = LocalNavController.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(
                        text = assistant.name.ifBlank {
                            stringResource(R.string.assistant_page_default_assistant)
                        },
                        maxLines = 1,
                    )
                },
                navigationIcon = {
                    BackButton()
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor
    ) { innerPadding ->
        if (assistant.id.toString() != id) {
            Text(stringResource(R.string.sub_assistant_reason_assistant_not_found), Modifier.padding(innerPadding))
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                AssistantHeader(
                    assistant = assistant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 16.dp)
                )
            }

            item {
                AssistantSettingsSectionList(modifier = Modifier.padding(horizontal = 8.dp)) { section ->
                    navController.navigate(when (section) {
                        AssistantSettingsSection.BASIC -> Screen.AssistantBasic(id)
                        AssistantSettingsSection.PROMPT -> Screen.AssistantPrompt(id)
                        AssistantSettingsSection.EXTENSIONS -> Screen.AssistantInjections(id)
                        AssistantSettingsSection.MEMORY -> Screen.AssistantMemory(id)
                        AssistantSettingsSection.REQUEST -> Screen.AssistantRequest(id)
                        AssistantSettingsSection.MCP -> Screen.AssistantMcp(id)
                        AssistantSettingsSection.LOCAL_TOOLS -> Screen.AssistantLocalTool(id)
                    })
                }
            }
        }
    }
}

@Composable
internal fun AssistantLockedChangeEffect(vm: AssistantDetailVM) {
    val toaster = LocalToaster.current
    val message = stringResource(R.string.configuration_change_rejected, "{reason}")
    LaunchedEffect(vm) {
        vm.lockedSettingsChanges.collect { error ->
            toaster.show(message.replace("{reason}", error.reason), type = ToastType.Error)
        }
    }
}

@Composable
private fun AssistantHeader(
    assistant: Assistant,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        UIAvatar(
            value = assistant.avatar,
            name = assistant.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) },
            onUpdate = null,
            modifier = Modifier
                .size(64.dp)
                .heroAnimation("assistant_${assistant.id}")
        )

        if (assistant.description.isNotBlank()) {
            Text(
                text = assistant.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
