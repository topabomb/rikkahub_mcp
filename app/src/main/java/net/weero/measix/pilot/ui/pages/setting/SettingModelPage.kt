package net.weero.measix.pilot.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiBrain01
import me.rerere.hugeicons.stroke.AiEditing
import me.rerere.hugeicons.stroke.ArrowRight01
import net.weero.measix.pilot.R
import net.weero.measix.pilot.data.configuration.ResourceSelectionSlot
import net.weero.measix.pilot.data.enterprise.RealmAccess
import net.weero.measix.pilot.service.DefaultModelBehavior
import net.weero.measix.pilot.service.ModelCatalogReadState
import net.weero.measix.pilot.service.ModelCatalogUiModel
import net.weero.measix.pilot.ui.components.ai.ModelListSheet
import net.weero.measix.pilot.ui.components.ai.configurationUnavailableText
import net.weero.measix.pilot.ui.components.ai.rememberModelListState
import net.weero.measix.pilot.ui.components.nav.BackButton
import net.weero.measix.pilot.ui.components.ui.CardGroup
import net.weero.measix.pilot.ui.theme.CustomColors
import net.weero.measix.pilot.utils.plus
import org.koin.androidx.compose.koinViewModel

@Composable
fun SettingModelPage(vm: SettingVM = koinViewModel(), modelVM: ModelSettingsVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val catalog by modelVM.catalog.collectAsStateWithLifecycle()
    val error by modelVM.error.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val pagerState = rememberPagerState { 2 }
    val scope = rememberCoroutineScope()
    Scaffold(
        containerColor = CustomColors.topBarColors.containerColor,
        topBar = { LargeFlexibleTopAppBar(
            title = { Text(stringResource(R.string.setting_model_page_title)) },
            navigationIcon = { BackButton() }, scrollBehavior = scrollBehavior, colors = CustomColors.topBarColors,
        ) },
        bottomBar = { BottomAppBar(containerColor = CustomColors.cardColorsOnSurfaceContainer.containerColor) {
            NavigationBarItem(selected = pagerState.currentPage == 0,
                onClick = { scope.launch { pagerState.animateScrollToPage(0) } },
                icon = { Icon(HugeIcons.AiBrain01, null) },
                label = { Text(stringResource(R.string.setting_model_page_tab_model)) })
            NavigationBarItem(selected = pagerState.currentPage == 1,
                onClick = { scope.launch { pagerState.animateScrollToPage(1) } },
                icon = { Icon(HugeIcons.AiEditing, null) },
                label = { Text(stringResource(R.string.setting_model_page_tab_prompt)) })
        } },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    ) { padding ->
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            if (page == 1) PromptSettingsPage(settings = settings, vm = vm, contentPadding = padding)
            else when (val state = catalog) {
                ModelCatalogReadState.Loading -> Column(Modifier.padding(padding).padding(16.dp)) { CircularProgressIndicator() }
                is ModelCatalogReadState.Available -> ModelSettingsPage(state.catalog, modelVM,
                    error?.takeIf { it.selection == state.catalog.selection }?.detail, padding)
                is ModelCatalogReadState.Unavailable -> Text(state.detail, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(padding).padding(16.dp))
            }
        }
    }
}

private data class ModelSlotUi(val slot: ResourceSelectionSlot, val title: Int, val description: Int)
private val modelSlots = listOf(
    ModelSlotUi(ResourceSelectionSlot.CHAT_MODEL, R.string.setting_model_page_chat_model, R.string.setting_model_page_chat_model_desc),
    ModelSlotUi(ResourceSelectionSlot.FAST_MODEL, R.string.setting_model_page_fast_model, R.string.setting_model_page_fast_model_desc),
    ModelSlotUi(ResourceSelectionSlot.TITLE_MODEL, R.string.setting_model_page_title_model, R.string.setting_model_page_title_model_desc),
    ModelSlotUi(ResourceSelectionSlot.SUGGESTION_MODEL, R.string.setting_model_page_suggestion_model, R.string.setting_model_page_suggestion_model_desc),
    ModelSlotUi(ResourceSelectionSlot.IMAGE_MODEL, R.string.setting_model_page_image_generation_model, R.string.setting_model_page_image_generation_model_desc),
    ModelSlotUi(ResourceSelectionSlot.ATTACHMENT_INSPECTION_MODEL, R.string.setting_model_page_attachment_inspection_model, R.string.setting_model_page_attachment_inspection_model_desc),
    ModelSlotUi(ResourceSelectionSlot.COMPRESS_MODEL, R.string.setting_model_page_compress_model, R.string.setting_model_page_compress_model_desc),
)

@Composable
private fun ModelSettingsPage(catalog: ModelCatalogUiModel, vm: ModelSettingsVM, error: String?, padding: PaddingValues) {
    val selection = requireNotNull(catalog.selection)
    LazyColumn(contentPadding = padding + PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text(stringResource(if (selection.access is RealmAccess.Enterprise)
            R.string.configuration_scope_enterprise else R.string.configuration_scope_personal)) }
        if (error != null) item { Text(error, color = MaterialTheme.colorScheme.error) }
        modelSlots.forEach { item -> item(key = item.slot.name) {
            if (item.slot == ResourceSelectionSlot.SUGGESTION_MODEL) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.setting_model_page_enable_suggestion), modifier = Modifier.weight(1f))
                    Switch(checked = catalog.selections.enableSuggestion,
                        onCheckedChange = { vm.enableSuggestion(selection, it) })
                }
            }
            ModelSettingItem(item, catalog, vm)
        } }
    }
}

@Composable
private fun ModelSettingItem(item: ModelSlotUi, catalog: ModelCatalogUiModel, vm: ModelSettingsVM) {
    val selection = requireNotNull(catalog.selection)
    val reference = item.slot.reference(catalog.selections)
    val candidates = catalog.forSlot(item.slot)
    val state = rememberModelListState(reference, candidates, requireNotNull(item.slot.modelRole).type)
    val stored = item.slot.reference(catalog.storedSelections)
    val defaultBehavior = catalog.defaultBehavior(item.slot)
    val unavailable = catalog.roleSelections[item.slot]?.unavailableReason.takeIf { defaultBehavior == null }
    Column {
        CardGroup(title = { Text(stringResource(item.title)) }) {
            item(onClick = state::open, headlineContent = {
                Text(state.currentModel?.displayName ?: stringResource(when (defaultBehavior) {
                    DefaultModelBehavior.FOLLOW_CHAT -> R.string.setting_model_page_follow_chat_model
                    DefaultModelBehavior.FOLLOW_FAST -> R.string.configuration_follow_fast_model
                    DefaultModelBehavior.DISABLED -> R.string.chat_readiness_memory_disabled
                    else -> R.string.chat_readiness_model_not_configured
                }))
            }, trailingContent = { Icon(HugeIcons.ArrowRight01, null) })
        }
        if (selection.access == RealmAccess.Personal) {
            Text(stringResource(item.description), style = MaterialTheme.typography.bodySmall)
        }
        if (stored == null && selection.access is RealmAccess.Enterprise) {
            Text(stringResource(R.string.configuration_inherited), style = MaterialTheme.typography.bodySmall)
        }
        unavailable?.let { Text(configurationUnavailableText(it), color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall) }
        if (stored != null && !(selection.access == RealmAccess.Personal && defaultBehavior != null)) TextButton(onClick = { vm.reset(selection, item.slot) }) {
            Text(stringResource(R.string.configuration_restore_default))
        }
        ModelListSheet(state, onSelect = { model -> vm.select(selection, item.slot, model.id) })
    }
}
