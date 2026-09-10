package net.weero.measix.pilot.ui.components.ai

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
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
import me.rerere.hugeicons.stroke.DragDropHorizontal
import me.rerere.hugeicons.stroke.Favourite
import me.rerere.hugeicons.stroke.Image03
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Text
import me.rerere.hugeicons.stroke.Tools
import net.weero.measix.pilot.R
import net.weero.measix.pilot.Screen
import net.weero.measix.pilot.service.ConfigurationApplicationService
import net.weero.measix.pilot.service.ConfigurationQueryService
import net.weero.measix.pilot.service.ModelCatalogUiModel
import net.weero.measix.pilot.service.ModelChoiceUiModel
import net.weero.measix.pilot.service.ModelGroupUiModel
import net.weero.measix.pilot.ui.adaptive.AdaptiveModal
import net.weero.measix.pilot.ui.components.ui.AutoAIIcon
import net.weero.measix.pilot.ui.components.ui.Tag
import net.weero.measix.pilot.ui.components.ui.TagType
import net.weero.measix.pilot.ui.components.ui.icons.HeartIcon
import net.weero.measix.pilot.ui.context.LocalNavController
import net.weero.measix.pilot.ui.theme.extendColors
import net.weero.measix.pilot.utils.toDp
import org.koin.compose.koinInject
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

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
    internal val filteredGroups: List<ModelGroupUiModel> get() = catalog.filterModels { it.type == type }.groups

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
) {
    val model = state.currentModel

    if (!onlyIcon) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = {
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

internal data class ModelSelectionAction(val label: String, val commit: suspend () -> Unit)

@Composable
internal fun ModelListSheet(
    state: ModelListState,
    onSelect: suspend (Model) -> Unit,
    additionalActions: List<ModelSelectionAction> = emptyList(),
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
                selectionError = error.message ?: "configuration_change_failed"
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
                .fillMaxHeight(0.8f)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            selectionError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            additionalActions.forEach { action ->
                TextButton(enabled = !submitting, onClick = { submit(action.commit) }) { Text(action.label) }
            }
            ModelList(
                currentModel = state.modelId,
                providers = state.filteredGroups,
                catalog = state.catalog,
                configurationCommands = configurationCommands,
                configurationQueries = configurationQueries,
                modelType = state.type,
                selectionEnabled = !submitting,
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
    onSelect: (Model) -> Unit,
    onDismiss: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var commandError by remember(catalog.selection) { mutableStateOf<String?>(null) }
    suspend fun command(action: suspend () -> Unit) {
        try { action(); commandError = null }
        catch (error: kotlinx.coroutines.CancellationException) { throw error }
        catch (error: Exception) { commandError = error.message ?: "configuration_change_failed" }
    }
    val favoriteFlow = remember(catalog) {
        if (catalog.selection == null) configurationQueries.observePersonalModelFavorites()
        else kotlinx.coroutines.flow.flowOf(catalog.selections.favoriteModels)
    }
    val favoriteModelIds by favoriteFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val favoriteModels = catalog.favorites(favoriteModelIds, modelType)

    var searchKeywords by remember { mutableStateOf("") }

    val typeFilteredModelsByProvider = remember(providers, modelType) {
        providers.associate { provider ->
            provider.id to provider.models.fastFilter { it.model.type == modelType }
        }
    }

    val searchFilteredModelsByProvider = remember(providers, modelType, searchKeywords) {
        providers.associate { provider ->
            provider.id to provider.models.fastFilter {
                it.model.type == modelType && it.model.displayName.contains(searchKeywords, true)
            }
        }
    }

    // 计算当前选中模型的位置
    val selectedModelPosition = remember(currentModel, favoriteModels, providers, typeFilteredModelsByProvider) {
        if (currentModel == null) return@remember 0

        var position = 0

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
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        // 计算favorite models在列表中的位置偏移
        var favoriteStartIndex = 0
        if (providers.isEmpty()) {
            favoriteStartIndex = 1 // no providers item
        }
        if (favoriteModels.isNotEmpty()) {
            favoriteStartIndex += 1 // favorite header
        }

        val fromIndex = from.index - favoriteStartIndex
        val toIndex = to.index - favoriteStartIndex

        // 只处理favorite models范围内的拖拽
        if (fromIndex >= 0 && toIndex >= 0 &&
            fromIndex < favoriteModels.size && toIndex < favoriteModels.size
        ) {
            val fromModelId = favoriteModels[fromIndex].reference
            val toModelId = favoriteModels[toIndex].reference
            coroutineScope.launch {
                command { configurationCommands.moveModelFavorite(catalog.selection, fromModelId, toModelId) }
            }
        }
    }
    val haptic = LocalHapticFeedback.current

    val providerPositions = remember(providers, favoriteModels, searchFilteredModelsByProvider) {
        var currentIndex = 0
        if (providers.isEmpty()) {
            currentIndex = 1 // no providers item
        }
        if (favoriteModels.isNotEmpty()) {
            currentIndex += 1 // favorite header
            currentIndex += favoriteModels.size // favorite models
        }

        providers.map { provider ->
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
            .weight(1f)
            .fillMaxWidth(),
    ) {
        if (providers.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.model_list_no_providers),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.extendColors.gray6,
                    modifier = Modifier.padding(8.dp)
                )
            }
        }

        if (favoriteModels.isNotEmpty()) {
            stickyHeader {
                Text(
                    text = stringResource(R.string.model_list_favorite),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(bottom = 4.dp, top = 8.dp)
                )
            }

            items(
                items = favoriteModels,
                key = { "favorite:" + it.reference.toString() }
            ) { favorite ->
                val model = favorite.choice
                val provider = favorite.group
                if (model == null || provider == null) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(favorite.reference.toString(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(configurationUnavailableText(requireNotNull(favorite.unavailableReason)),
                                color = MaterialTheme.colorScheme.error)
                        }
                        IconButton(onClick = { coroutineScope.launch {
                            command { configurationCommands.setModelFavorite(catalog.selection, favorite.reference, false) }
                        } }) { Icon(HeartIcon, contentDescription = stringResource(R.string.configuration_remove_favorite)) }
                    }
                } else ReorderableItem(
                    state = reorderableState,
                    key = "favorite:" + model.model.id.toString()
                ) { isDragging ->
                    ModelItem(
                        model = model,
                        selectionEnabled = selectionEnabled,
                        onSelect = onSelect,
                        modifier = Modifier
                            .scale(if (isDragging) 0.95f else 1f)
                            .animateItem(),
                        providerSetting = provider,
                        select = model.model.id == currentModel,
                        onDismiss = {
                            onDismiss()
                        },
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
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        },
                        dragHandle = {
                            Icon(
                                imageVector = HugeIcons.DragDropHorizontal,
                                contentDescription = null,
                                modifier = Modifier.longPressDraggableHandle(
                                    onDragStarted = {
                                        haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                    },
                                    onDragStopped = {
                                        haptic.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                    }
                                )
                            )
                        }
                    )
                }
            }
        }

        providers.fastForEach { providerSetting ->
            stickyHeader(key = "header:${providerSetting.id}") {
                Row(
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .padding(bottom = 4.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = providerSetting.name,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(stringResource(if (providerSetting.userProviderId == null) R.string.configuration_source_enterprise
                        else R.string.configuration_source_user), style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(start = 8.dp))

                    Spacer(modifier = Modifier.weight(1f))

                    providerSetting.userProviderId?.takeIf { providerSetting.models.any { it.canSelect } }?.let { providerId ->
                        ProviderBalanceText(providerId = providerId, style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary)
                    }
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
                    providerSetting = providerSetting,
                    select = currentModel == model.model.id,
                    onDismiss = {
                        onDismiss()
                    },
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
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            } else {
                                Icon(
                                    imageVector = HugeIcons.Favourite,
                                    contentDescription = null,
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
                    val index = providers.indexOfFirst { it.id == currentProvider?.key }
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
    if (providers.isNotEmpty()) {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 8.dp),
            state = providerBadgeListState
        ) {
            items(providers) { provider ->
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
    providerSetting: ModelGroupUiModel,
    select: Boolean,
    onSelect: (Model) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    tail: @Composable RowScope.() -> Unit = {},
    dragHandle: @Composable (RowScope.() -> Unit)? = null
) {
    val navController = LocalNavController.current
    val interactionSource = remember { MutableInteractionSource() }
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = if (select) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
            contentColor = if (select) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp, horizontal = 16.dp)
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .combinedClickable(
                        enabled = selectionEnabled && model.canSelect,
                        onClick = { onSelect(model.model) },
                        interactionSource = interactionSource,
                        indication = LocalIndication.current
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.small,
                ) {
                    AutoAIIcon(
                        name = model.model.modelId,
                        modifier = Modifier
                            .padding(4.dp)
                            .size(32.dp)
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

                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        ModelTypeTag(model = model.model)

                        ModelModalityTag(model = model.model)

                        ModelAbilityTag(model = model.model)
                    }
                }
                tail()
            }
            providerSetting.userProviderId?.let { providerId ->
                IconButton(enabled = selectionEnabled, onClick = {
                    onDismiss()
                    navController.navigate(Screen.SettingProviderDetail(providerId.toString()))
                }) { Icon(HugeIcons.ArrowRight01, contentDescription = stringResource(R.string.edit)) }
            }
            dragHandle?.let { it() }
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
    Tag(
        type = TagType.SUCCESS
    ) {
        model.inputModalities.fastForEach { modality ->
            Icon(
                imageVector = when (modality) {
                    Modality.TEXT -> HugeIcons.Text
                    Modality.IMAGE -> HugeIcons.Image03
                },
                contentDescription = null,
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
                contentDescription = null,
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
                        contentDescription = null,
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
                        contentDescription = null,
                        modifier = Modifier.size(LocalTextStyle.current.lineHeight.toDp()),
                    )
                }
            }
        }
    }
}
