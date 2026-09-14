package net.weero.measix.pilot.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiBrain01
import me.rerere.hugeicons.stroke.AiEditing
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Undo02
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
        if (selection.access is RealmAccess.Enterprise) item {
            Text(stringResource(R.string.configuration_scope_enterprise), style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary)
        }
        error?.let { detail -> item { Text(detail, color = MaterialTheme.colorScheme.error) } }
        item("conversationModels") {
            ModelSettingsGroup(
                title = stringResource(R.string.setting_model_group_conversation),
                items = modelSlots.filter { it.slot == ResourceSelectionSlot.CHAT_MODEL ||
                    it.slot == ResourceSelectionSlot.SUGGESTION_MODEL && catalog.selections.enableSuggestion },
                catalog = catalog,
                vm = vm,
                suggestionToggle = true,
            )
        }
        item("backgroundModels") {
            ModelSettingsGroup(
                title = stringResource(R.string.setting_model_group_background),
                items = modelSlots.filter { it.slot in setOf(ResourceSelectionSlot.FAST_MODEL,
                    ResourceSelectionSlot.TITLE_MODEL, ResourceSelectionSlot.COMPRESS_MODEL) },
                catalog = catalog,
                vm = vm,
            )
        }
        item("mediaModels") {
            ModelSettingsGroup(
                title = stringResource(R.string.setting_model_group_media),
                items = modelSlots.filter { it.slot in setOf(ResourceSelectionSlot.IMAGE_MODEL,
                    ResourceSelectionSlot.ATTACHMENT_INSPECTION_MODEL) },
                catalog = catalog,
                vm = vm,
            )
        }
    }
}

@Composable
private fun ModelSettingsGroup(
    title: String,
    items: List<ModelSlotUi>,
    catalog: ModelCatalogUiModel,
    vm: ModelSettingsVM,
    suggestionToggle: Boolean = false,
) {
    val selection = requireNotNull(catalog.selection)
    val rows = items.map { item ->
        val candidates = catalog.forSlot(item.slot)
        val state = rememberModelListState(item.slot.reference(catalog.selections), candidates,
            requireNotNull(item.slot.modelRole).type)
        ModelSettingRow(item, state, item.slot.reference(catalog.storedSelections), catalog.defaultBehavior(item.slot),
            catalog.roleSelections[item.slot]?.unavailableReason.takeIf { catalog.defaultBehavior(item.slot) == null })
    }
    CardGroup(title = { Text(title) }) {
        rows.forEach { row ->
            val canReset = row.stored != null && !(selection.access == RealmAccess.Personal && row.defaultBehavior != null)
            item(
                onClick = row.state::open,
                headlineContent = { Text(stringResource(row.item.title)) },
                supportingContent = {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            stringResource(row.item.description),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        row.unavailable?.let { Text(configurationUnavailableText(it), color = MaterialTheme.colorScheme.error) }
                    }
                },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            row.state.currentModel?.displayName ?: stringResource(when (row.defaultBehavior) {
                                DefaultModelBehavior.FOLLOW_CHAT -> R.string.setting_model_page_follow_chat_model
                                DefaultModelBehavior.FOLLOW_FAST -> R.string.configuration_follow_fast_model
                                DefaultModelBehavior.DISABLED -> R.string.chat_readiness_memory_disabled
                                else -> R.string.chat_readiness_model_not_configured
                            }),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 132.dp),
                        )
                        if (canReset) IconButton(
                            onClick = { vm.reset(selection, row.item.slot) },
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(HugeIcons.Undo02, contentDescription = stringResource(R.string.configuration_restore_default))
                        } else {
                            Icon(HugeIcons.ArrowRight01, contentDescription = null)
                        }
                    }
                },
            )
            if (suggestionToggle && row.item.slot == ResourceSelectionSlot.CHAT_MODEL) item(
                headlineContent = { Text(stringResource(R.string.setting_model_page_enable_suggestion)) },
                trailingContent = { Switch(checked = catalog.selections.enableSuggestion,
                    onCheckedChange = { vm.enableSuggestion(selection, it) }) },
            )
        }
    }
    rows.forEach { row ->
        ModelListSheet(row.state, onSelect = { model -> vm.select(selection, row.item.slot, model.id) })
    }
}

private data class ModelSettingRow(
    val item: ModelSlotUi,
    val state: net.weero.measix.pilot.ui.components.ai.ModelListState,
    val stored: me.rerere.common.configuration.ConfigurationReference?,
    val defaultBehavior: DefaultModelBehavior?,
    val unavailable: net.weero.measix.pilot.data.configuration.ConfigurationUnavailableReason?,
)
