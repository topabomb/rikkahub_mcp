package net.weero.measix.pilot.ui.components.ai

import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastFilter
import androidx.compose.ui.util.fastForEach
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ModelType
import me.rerere.common.configuration.ConfigurationReference
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Brain02
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Favourite
import me.rerere.hugeicons.stroke.Image03
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Text
import me.rerere.hugeicons.stroke.Tick02
import me.rerere.hugeicons.stroke.Tools
import net.weero.measix.pilot.R
import net.weero.measix.pilot.Screen
import net.weero.measix.pilot.service.ConfigurationApplicationService
import net.weero.measix.pilot.service.ConfigurationQueryService
import net.weero.measix.pilot.service.ModelCatalogUiModel
import net.weero.measix.pilot.service.ModelChoiceUiModel
import net.weero.measix.pilot.service.ModelGroupUiModel
import net.weero.measix.pilot.service.ConversationConfigurationUiModel
import net.weero.measix.pilot.data.configuration.AssistantModelPreferenceMode
import net.weero.measix.pilot.data.configuration.AssistantPreferenceChange
import net.weero.measix.pilot.data.configuration.ConfigurationUnavailableReason
import net.weero.measix.pilot.data.enterprise.RealmAccess
import net.weero.measix.pilot.ui.adaptive.AdaptiveModal
import net.weero.measix.pilot.ui.components.ui.AutoAIIcon
import net.weero.measix.pilot.ui.components.ui.Tag
import net.weero.measix.pilot.ui.components.ui.TagType
import net.weero.measix.pilot.ui.components.ui.icons.HeartIcon
import net.weero.measix.pilot.ui.context.LocalNavController
import net.weero.measix.pilot.ui.theme.extendColors
import net.weero.measix.pilot.utils.toDp
import net.weero.measix.pilot.utils.userVisibleDiagnostic
import org.koin.compose.koinInject

class ModelListState internal constructor(
    modelId: ConfigurationReference?,
    catalog: ModelCatalogUiModel,
    type: ModelType,
) {
    var modelId by mutableStateOf(modelId)
        private set
    internal var catalog by mutableStateOf(catalog)
        private set
    var type by mutableStateOf(type)
        private set
    var visible by mutableStateOf(false)
        private set
    val currentModel: Model? get() = catalog.find(modelId)?.model?.takeIf { it.type == type }
    internal val filteredGroups: List<ModelGroupUiModel> get() = catalog.selectableGroups(type)

    fun open() { visible = true }
    fun close() { visible = false }

    internal fun update(modelId: ConfigurationReference?, catalog: ModelCatalogUiModel, type: ModelType) {
        if (this.catalog.selection != catalog.selection) close()
        this.modelId = modelId
        this.catalog = catalog
        this.type = type
    }
}

@Composable
internal fun rememberModelListState(
    modelId: ConfigurationReference?,
    catalog: ModelCatalogUiModel,
    type: ModelType,
): ModelListState = remember { ModelListState(modelId, catalog, type) }.also { it.update(modelId, catalog, type) }

/**
 * 只渲染触发按钮：sheet 的可见性由调用方在同一处显式组合。
 *
 * 按钮不能自己组合 [ModelListSheet]：聊天输入区的 action row 会随 IME 目标与布局分支
 * 离开组合，sheet 若随按钮一起消失，搜索框刚获得焦点就会关闭。
 */
@Composable
fun ModelSelectorButton(
    state: ModelListState,
    modifier: Modifier = Modifier,
    onlyIcon: Boolean = false,
    onClear: (() -> Unit)? = null,
    placeholder: String? = null,
    onOpen: (() -> Unit)? = null,
) {
    val model = state.currentModel
    val unresolvedReference = state.modelId?.takeIf { model == null }

    if (!onlyIcon) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = {
                    onOpen?.invoke()
                    state.open()
                },
                modifier = modifier
            ) {
                model?.modelId?.let {
                    AutoAIIcon(
                        it, Modifier
                            .padding(end = 4.dp)
                            .size(36.dp),
                        color = Color.Transparent
                    )
                }
                Text(
                    text = model?.displayName
                        ?: unresolvedReference?.toString()
                        ?: placeholder
                        ?: stringResource(R.string.model_list_select_model),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (onClear != null && model != null) {
                IconButton(
                    onClick = onClear
                ) {
                    Icon(
                        imageVector = HugeIcons.Cancel01,
                        contentDescription = "Clear"
                    )
                }
            }
        }
    } else {
        IconButton(
            onClick = {
                onOpen?.invoke()
                state.open()
            },
        ) {
            if (model != null) {
                AutoAIIcon(
                    modifier = Modifier.size(36.dp),
                    name = model.modelId,
                    color = Color.Transparent
                )
            } else {
                Icon(
                    imageVector = HugeIcons.Brain02,
                    contentDescription = stringResource(R.string.setting_model_page_chat_model),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

internal data class ModelSelectionAction(
    val label: String,
    val selected: Boolean,
    val unavailableReason: ConfigurationUnavailableReason? = null,
    val commit: suspend () -> Unit,
)

internal data class AssistantModelSelectionUi(
    val actions: List<ModelSelectionAction>,
    val selectedModelId: ConfigurationReference?,
    val modeLabel: String?,
)

internal fun AssistantModelSelectionUi.quickRestoreAssistantDefault(): ModelSelectionAction? =
    actions.firstOrNull()?.takeUnless { it.selected }

/** One presentation owns the distinction between definition inheritance, space inheritance and an explicit model. */
@Composable
internal fun assistantModelSelectionUi(
    configuration: ConversationConfigurationUiModel,
    commit: suspend (AssistantPreferenceChange) -> Unit,
): AssistantModelSelectionUi {
    val assistant = requireNotNull(configuration.assistant)
    val preference = requireNotNull(configuration.modelPreference)
    val notConfigured = stringResource(R.string.chat_readiness_model_not_configured)
    fun reason(reference: ConfigurationReference?): ConfigurationUnavailableReason? {
        if (reference == null) return ConfigurationUnavailableReason.REFERENCE_MISSING
        val choice = configuration.modelCatalog.find(reference)
        if (choice != null) return choice.unavailableReason
        return configuration.modelCatalog.unresolvedModels[reference]
            ?: ConfigurationUnavailableReason.REFERENCE_MISSING
    }
    fun name(reference: ConfigurationReference?): String = configuration.modelCatalog.find(reference)?.model?.displayName
        ?: notConfigured
    val enterprise = configuration.modelCatalog.selection?.access is RealmAccess.Enterprise
    if (!enterprise) {
        return AssistantModelSelectionUi(
            actions = listOf(ModelSelectionAction(
                label = stringResource(R.string.assistant_page_follow_default_model),
                selected = assistant.chatModelId == null,
                unavailableReason = reason(preference.spaceDefaultReference),
            ) { commit(AssistantPreferenceChange.Model(null)) }),
            selectedModelId = assistant.chatModelId,
            modeLabel = null,
        )
    }
    val assistantDefaultReference = preference.definitionReference ?: preference.spaceDefaultReference
    return AssistantModelSelectionUi(
        actions = listOf(
            ModelSelectionAction(
                label = stringResource(R.string.assistant_model_use_assistant_default, name(assistantDefaultReference)),
                selected = preference.mode == AssistantModelPreferenceMode.ASSISTANT_DEFAULT,
                unavailableReason = reason(assistantDefaultReference),
            ) { commit(AssistantPreferenceChange.InheritModel) },
            ModelSelectionAction(
                label = stringResource(R.string.assistant_model_use_space_default, name(preference.spaceDefaultReference)),
                selected = preference.mode == AssistantModelPreferenceMode.SPACE_DEFAULT,
                unavailableReason = reason(preference.spaceDefaultReference),
            ) { commit(AssistantPreferenceChange.Model(null)) },
        ),
        selectedModelId = configuration.modelSelection.reference.takeIf {
            preference.mode == AssistantModelPreferenceMode.EXPLICIT
        },
        modeLabel = stringResource(when (preference.mode) {
            AssistantModelPreferenceMode.ASSISTANT_DEFAULT -> R.string.assistant_model_mode_assistant_default
            AssistantModelPreferenceMode.SPACE_DEFAULT -> R.string.assistant_model_mode_space_default
            AssistantModelPreferenceMode.EXPLICIT -> R.string.assistant_model_mode_explicit
        }),
    )
}

@Composable
internal fun ModelListSheet(
    state: ModelListState,
    onSelect: suspend (Model) -> Unit,
    additionalActions: List<ModelSelectionAction> = emptyList(),
    compactAction: ModelSelectionAction? = null,
    selectedModelId: ConfigurationReference? = state.modelId,
    selectedModelUsesDefault: Boolean = false,
    configurationCommands: ConfigurationApplicationService = koinInject(),
    configurationQueries: ConfigurationQueryService = koinInject(),
) {
    if (!state.visible) return
    val scope = rememberCoroutineScope()
    var selectionError by remember(state.catalog.selection) { mutableStateOf<String?>(null) }
    var submitting by remember(state.catalog.selection) { mutableStateOf(false) }

    fun dismiss() {
        state.close()
    }

    fun submit(commit: suspend () -> Unit) {
        if (submitting) return
        submitting = true
        selectionError = null
        scope.launch {
            try {
                commit()
                dismiss()
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e("ModelList", "Model selection failed", error)
                selectionError = error.userVisibleDiagnostic()
            } finally {
                submitting = false
            }
        }
    }

    AdaptiveModal(
        onDismissRequest = {
            state.close()
        },
    ) {
        Column(
            modifier = Modifier
                .padding(8.dp)
                .fillMaxWidth()
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.model_list_select_model),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                IconButton(onClick = ::dismiss) {
                    Icon(HugeIcons.Cancel01, contentDescription = stringResource(R.string.update_card_close))
                }
            }
            selectionError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            additionalActions.forEach { action ->
                val enabled = !submitting && action.unavailableReason == null
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .clickable(enabled = enabled) { submit(action.commit) }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = action.selected, enabled = enabled,
                        onClick = null)
                    Column(modifier = Modifier.padding(start = 8.dp)) {
                        Text(action.label)
                        action.unavailableReason?.let {
                            Text(configurationUnavailableText(it), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
            compactAction?.let { action ->
                TextButton(
                    enabled = !submitting && action.unavailableReason == null,
                    onClick = { submit(action.commit) },
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text(action.label)
                }
            }
            ModelList(
                currentModel = selectedModelId,
                providers = state.filteredGroups,
                catalog = state.catalog,
                configurationCommands = configurationCommands,
                configurationQueries = configurationQueries,
                modelType = state.type,
                selectionEnabled = !submitting,
                selectedModelUsesDefault = selectedModelUsesDefault,
                onSelect = { model -> submit { onSelect(model) } },
                onDismiss = {
                    dismiss()
                }
            )
        }
    }
}

@Composable
private fun ColumnScope.ModelList(
    currentModel: ConfigurationReference? = null,
    providers: List<ModelGroupUiModel>,
    catalog: ModelCatalogUiModel,
    configurationCommands: ConfigurationApplicationService,
    configurationQueries: ConfigurationQueryService,
    modelType: ModelType,
    selectionEnabled: Boolean,
    selectedModelUsesDefault: Boolean,
    onSelect: (Model) -> Unit,
    onDismiss: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val navController = LocalNavController.current
    var commandError by remember(catalog.selection) { mutableStateOf<String?>(null) }
    suspend fun command(action: suspend () -> Unit) {
        try { action(); commandError = null }
        catch (error: kotlinx.coroutines.CancellationException) { throw error }
        catch (error: Exception) {
            Log.e("ModelList", "Model catalog command failed", error)
            commandError = error.userVisibleDiagnostic()
        }
    }
    val favoriteFlow = remember(catalog) {
        if (catalog.selection == null) configurationQueries.observePersonalModelFavorites()
        else kotlinx.coroutines.flow.flowOf(catalog.selections.favoriteModels)
    }
    val favoriteModelIds by favoriteFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val favoriteModels = catalog.selectableFavorites(favoriteModelIds, modelType)

    var searchKeywords by remember { mutableStateOf("") }

    val typeFilteredModelsByProvider = remember(providers, modelType) {
        providers.associate { provider -> provider.id to provider.models }
    }

    val searchFilteredModelsByProvider = remember(providers, modelType, searchKeywords) {
        providers.associate { provider ->
            provider.id to provider.models.fastFilter {
                it.model.displayName.contains(searchKeywords, true)
            }
        }
    }
    val displayedFavorites = remember(favoriteModels, searchKeywords) {
        favoriteModels.filter { favorite ->
            searchKeywords.isBlank() || favorite.choice?.model?.displayName?.contains(searchKeywords, true) == true
        }
    }
    val displayedProviders = remember(providers, searchFilteredModelsByProvider) {
        providers.filter { searchFilteredModelsByProvider[it.id].orEmpty().isNotEmpty() }
    }
    val unavailableSelection = catalog.unavailableSelection(currentModel)
    val leadingUnresolvedCount = if (unavailableSelection != null) 1 else 0

    // 计算当前选中模型的位置
    val selectedModelPosition = remember(currentModel, favoriteModels, providers, typeFilteredModelsByProvider, leadingUnresolvedCount) {
        if (currentModel == null) return@remember 0

        var position = leadingUnresolvedCount

        // 跳过无providers提示
        if (providers.isEmpty()) {
            position += 1
        }

        // 检查是否在收藏列表中
        val favoriteIndex = favoriteModels.indexOfFirst { it.reference == currentModel }
        if (favoriteIndex >= 0) {
            if (favoriteModels.isNotEmpty()) {
                position += 1 // favorite header
            }
            position += favoriteIndex
            return@remember position
        }

        // 跳过收藏列表
        if (favoriteModels.isNotEmpty()) {
            position += 1 // favorite header
            position += favoriteModels.size
        }

        // 在providers中查找
        for (provider in providers) {
            position += 1 // provider header
            val models = typeFilteredModelsByProvider[provider.id].orEmpty()
            val modelIndex = models.indexOfFirst { it.model.id == currentModel }
            if (modelIndex >= 0) {
                position += modelIndex
                return@remember position
            }
            position += models.size
        }

        0
    }

    val lazyListState = rememberLazyListState(
        initialFirstVisibleItemIndex = selectedModelPosition
    )
    val providerPositions = remember(displayedProviders, displayedFavorites, searchFilteredModelsByProvider, leadingUnresolvedCount) {
        var currentIndex = leadingUnresolvedCount
        if (displayedProviders.isEmpty()) {
            currentIndex = 1 // no providers item
        }
        if (displayedFavorites.isNotEmpty()) {
            currentIndex += 1 // favorite header
            currentIndex += displayedFavorites.size // favorite models
        }

        displayedProviders.map { provider ->
            val position = currentIndex
            currentIndex += 1 // provider header
            currentIndex += searchFilteredModelsByProvider[provider.id].orEmpty().size
            provider.id to position
        }.toMap()
    }

    commandError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Surface(
        shape = RoundedCornerShape(50),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
    ) {
        OutlinedTextField(
            value = searchKeywords,
            onValueChange = { searchKeywords = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text(
                    text = stringResource(R.string.model_list_search_placeholder),
                )
            },
            shape = RoundedCornerShape(50),
            colors = TextFieldDefaults.colors(
                unfocusedIndicatorColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
            ),
            leadingIcon = {
                Icon(HugeIcons.Search01, null)
            },
            maxLines = 1,
        )
    }

    LazyColumn(
        state = lazyListState,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(8.dp),
        modifier = Modifier
            .weight(1f, fill = false)
            .fillMaxWidth(),
    ) {
        if (unavailableSelection != null) {
            item(key = "selected-unavailable:$currentModel") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    RadioButton(selected = true, enabled = false, onClick = null)
                    Column(Modifier.weight(1f)) {
                        Text(unavailableSelection.first, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            configurationUnavailableText(unavailableSelection.second),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        if (displayedProviders.isEmpty() && displayedFavorites.isEmpty()) {
            item {
                Column(modifier = Modifier.padding(8.dp)) {
                    Text(
                        text = stringResource(
                            if (searchKeywords.isBlank()) R.string.model_list_no_providers
                            else R.string.search_page_no_results,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.extendColors.gray6,
                    )
                    if (searchKeywords.isBlank()) {
                        TextButton(onClick = {
                            onDismiss()
                            navController.navigate(if (catalog.selection?.access is RealmAccess.Enterprise) {
                                Screen.Enterprise
                            } else {
                                Screen.SettingProvider
                            })
                        }) {
                            Text(stringResource(if (catalog.selection?.access is RealmAccess.Enterprise) {
                                R.string.enterprise_spaces
                            } else {
                                R.string.setting_provider_page_title
                            }))
                        }
                    } else {
                        TextButton(onClick = { searchKeywords = "" }) {
                            Text(stringResource(R.string.clear_search))
                        }
                    }
                }
            }
        }

        if (displayedFavorites.isNotEmpty()) {
            item(key = "favorite-header") {
                Text(
                    text = stringResource(R.string.model_list_favorite),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(bottom = 4.dp, top = 8.dp)
                )
            }

            items(
                items = displayedFavorites,
                key = { "favorite:" + it.reference.toString() }
            ) { favorite ->
                val model = favorite.choice
                val provider = favorite.group
                if (model != null && provider != null) {
                    ModelItem(
                        model = model,
                        selectionEnabled = selectionEnabled,
                        onSelect = onSelect,
                        modifier = Modifier.animateItem(),
                        select = model.model.id == currentModel,
                        selectedUsesDefault = selectedModelUsesDefault,
                        tail = {
                            IconButton(
                                onClick = {
                                    coroutineScope.launch {
                                        command { configurationCommands.setModelFavorite(catalog.selection, model.model.id, favorite = false) }
                                    }
                                }
                            ) {
                                Icon(
                                    HeartIcon,
                                    contentDescription = stringResource(R.string.configuration_remove_favorite),
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        },
                    )
                }
            }
        }

        displayedProviders.fastForEach { providerSetting ->
            item(key = "header:${providerSetting.id}") {
                Row(
                    modifier = Modifier
                        .padding(bottom = 4.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = providerSetting.name,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            items(
                items = searchFilteredModelsByProvider[providerSetting.id].orEmpty(),
                key = { providerSetting.id + ":" + it.model.id.toString() }
            ) { model ->
                val favorite = model.model.id in favoriteModelIds
                ModelItem(
                    model = model,
                    selectionEnabled = selectionEnabled,
                    onSelect = onSelect,
                    modifier = Modifier.animateItem(),
                    select = currentModel == model.model.id,
                    selectedUsesDefault = selectedModelUsesDefault,
                    tail = {
                        IconButton(
                            enabled = favorite || model.canSelect,
                            onClick = {
                                coroutineScope.launch {
                                    command { configurationCommands.setModelFavorite(catalog.selection, model.model.id, favorite = !favorite) }
                                }
                            }
                        ) {
                            if (favorite) {
                                Icon(
                                    HeartIcon,
                                    contentDescription = stringResource(R.string.configuration_remove_favorite),
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            } else {
                                Icon(
                                    imageVector = HugeIcons.Favourite,
                                    contentDescription = stringResource(R.string.chat_message_add_favorite),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                )
            }
        }
    }

    // 供应商Badge行
    val providerBadgeListState = rememberLazyListState()
    LaunchedEffect(lazyListState) {
        // 当LazyColumn滚动时，LazyRow也跟随滚动
        @OptIn(kotlinx.coroutines.FlowPreview::class)
        snapshotFlow { lazyListState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .debounce(100) // 防抖处理
            .collect { index ->
                if (index > 0) {
                    val currentProvider = providerPositions.entries.findLast {
                        index > it.value
                    }
                    val index = displayedProviders.indexOfFirst { it.id == currentProvider?.key }
                    if (index >= 0) {
                        providerBadgeListState.animateScrollToItem(index)
                    } else {
                        providerBadgeListState.requestScrollToItem(0)
                    }
                } else {
                    providerBadgeListState.requestScrollToItem(0)
                }
            }
    }
    val showProviderNavigation = displayedProviders.size >= 4 &&
        displayedProviders.sumOf { searchFilteredModelsByProvider[it.id].orEmpty().size } >= 24
    if (showProviderNavigation) {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 8.dp),
            state = providerBadgeListState
        ) {
            items(displayedProviders) { provider ->
                AssistChip(
                    onClick = {
                        val position = providerPositions[provider.id] ?: 0
                        coroutineScope.launch {
                            lazyListState.animateScrollToItem(position)
                        }
                    },
                    label = {
                        Text(provider.name)
                    },
                    leadingIcon = {
                        AutoAIIcon(name = provider.name, modifier = Modifier.size(16.dp))
                    },
                )
            }
        }
    }
}

@Composable
private fun ModelItem(
    model: ModelChoiceUiModel,
    selectionEnabled: Boolean,
    select: Boolean,
    selectedUsesDefault: Boolean = false,
    onSelect: (Model) -> Unit,
    modifier: Modifier = Modifier,
    tail: @Composable RowScope.() -> Unit = {},
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = if (select) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
            contentColor = if (select) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp, horizontal = 12.dp)
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .selectable(
                        selected = select,
                        enabled = selectionEnabled && model.canSelect,
                        onClick = { onSelect(model.model) },
                        role = Role.RadioButton,
                    ),
                verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.small,
                ) {
                    AutoAIIcon(
                        name = model.model.modelId,
                        modifier = Modifier
                            .padding(4.dp)
                            .size(28.dp)
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = model.model.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    model.unavailableReason?.let { reason ->
                        Text(configurationUnavailableText(reason), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error)
                    }

                    CompactModelCapabilities(model.model)
                }
                if (select) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (selectedUsesDefault) Text(
                            stringResource(R.string.model_list_default_badge),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Icon(
                            HugeIcons.Tick02,
                            contentDescription = stringResource(R.string.model_list_selected),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                tail()
            }
        }
    }
}

@Composable
private fun CompactModelCapabilities(model: Model) {
    val capabilities = buildList {
        if (Modality.IMAGE in model.inputModalities) add(HugeIcons.Image03 to R.string.setting_provider_page_image)
        if (ModelAbility.TOOL in model.abilities) add(HugeIcons.Tools to R.string.setting_mcp_page_tools)
        if (ModelAbility.REASONING in model.abilities) add(HugeIcons.Brain02 to R.string.setting_provider_page_reasoning)
    }
    if (capabilities.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        capabilities.forEach { (icon, label) ->
            Icon(
                icon,
                contentDescription = stringResource(label),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

@Composable
fun ModelTypeTag(model: Model) {
    Tag(
        type = TagType.INFO
    ) {
        Text(
            text = stringResource(
                when (model.type) {
                    ModelType.CHAT -> R.string.setting_provider_page_chat_model
                    ModelType.EMBEDDING -> R.string.setting_provider_page_embedding_model
                    ModelType.IMAGE -> R.string.setting_provider_page_image_model
                }
            )
        )
    }
}

@Composable
fun ModelModalityTag(model: Model) {
    val inputLabel = stringResource(R.string.setting_provider_page_input_modality)
    val outputLabel = stringResource(R.string.setting_provider_page_output_modality)
    @Composable fun modalityLabel(modality: Modality): String = stringResource(when (modality) {
        Modality.TEXT -> R.string.setting_provider_page_text
        Modality.IMAGE -> R.string.setting_provider_page_image
    })
    Tag(
        type = TagType.SUCCESS
    ) {
        model.inputModalities.fastForEach { modality ->
            Icon(
                imageVector = when (modality) {
                    Modality.TEXT -> HugeIcons.Text
                    Modality.IMAGE -> HugeIcons.Image03
                },
                contentDescription = "$inputLabel: ${modalityLabel(modality)}",
                modifier = Modifier
                    .size(LocalTextStyle.current.lineHeight.toDp())
                    .padding(1.dp)
            )
        }
        Icon(
            imageVector = HugeIcons.ArrowRight01,
            contentDescription = null,
            modifier = Modifier.size(LocalTextStyle.current.lineHeight.toDp())
        )
        model.outputModalities.fastForEach { modality ->
            Icon(
                imageVector = when (modality) {
                    Modality.TEXT -> HugeIcons.Text
                    Modality.IMAGE -> HugeIcons.Image03
                },
                contentDescription = "$outputLabel: ${modalityLabel(modality)}",
                modifier = Modifier
                    .size(LocalTextStyle.current.lineHeight.toDp())
                    .padding(1.dp)
            )
        }
    }
}

@Composable
fun ModelAbilityTag(model: Model) {
    model.abilities.fastForEach { ability ->
        when (ability) {
            ModelAbility.TOOL -> {
                Tag(
                    type = TagType.WARNING
                ) {
                    Icon(
                        imageVector = HugeIcons.Tools,
                        contentDescription = stringResource(R.string.setting_mcp_page_tools),
                        modifier = Modifier.size(LocalTextStyle.current.lineHeight.toDp())
                    )
                }
            }

            ModelAbility.REASONING -> {
                Tag(
                    type = TagType.INFO
                ) {
                    Icon(
                        painter = painterResource(R.drawable.deepthink),
                        contentDescription = stringResource(R.string.setting_provider_page_reasoning),
                        modifier = Modifier.size(LocalTextStyle.current.lineHeight.toDp()),
                    )
                }
            }
        }
    }
}
