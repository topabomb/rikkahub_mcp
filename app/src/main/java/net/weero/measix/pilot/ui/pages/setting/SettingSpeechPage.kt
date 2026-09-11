package net.weero.measix.pilot.ui.pages.setting

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.hugeicons.stroke.StopCircle
import me.rerere.hugeicons.stroke.DragDropHorizontal
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Mic01
import me.rerere.hugeicons.stroke.Tools
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.VolumeHigh
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import net.weero.measix.pilot.ui.adaptive.AdaptiveModal
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.weero.measix.pilot.R
import me.rerere.asr.ASRProviderSetting
import net.weero.measix.pilot.data.datastore.DEFAULT_SYSTEM_TTS_ID
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.ui.components.nav.BackButton
import net.weero.measix.pilot.ui.components.ui.AutoAIIcon
import net.weero.measix.pilot.ui.components.ui.Tag
import net.weero.measix.pilot.ui.components.ui.TagType
import net.weero.measix.pilot.ui.context.LocalTTSState
import net.weero.measix.pilot.ui.pages.setting.components.ASRProviderConfigure
import net.weero.measix.pilot.ui.pages.setting.components.TTSProviderConfigure
import net.weero.measix.pilot.ui.theme.CustomColors
import net.weero.measix.pilot.utils.plus
import me.rerere.tts.provider.TTSProviderSetting
import org.koin.androidx.compose.koinViewModel
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import org.koin.compose.koinInject
import com.dokar.sonner.ToastType
import net.weero.measix.pilot.ui.context.LocalToaster
import net.weero.measix.pilot.ui.components.ai.configurationUnavailableText
import net.weero.measix.pilot.service.ConfigurationApplicationService
import net.weero.measix.pilot.service.ConfigurationQueryService
import net.weero.measix.pilot.service.SpeechCatalogUiModel
import net.weero.measix.pilot.data.configuration.ConfigurationCategory
import net.weero.measix.pilot.data.configuration.ConfigurationCatalogItem
import net.weero.measix.pilot.data.configuration.ResourceSelectionSlot
import me.rerere.common.configuration.ConfigurationReference
import kotlin.math.roundToInt

@Composable
fun SettingSpeechPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val queries = koinInject<ConfigurationQueryService>()
    val commands = koinInject<ConfigurationApplicationService>()
    val catalog by remember(queries) { queries.observeSpeechCatalog() }.collectAsStateWithLifecycle(null)
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val failureText = stringResource(R.string.configuration_reason_not_ready)
    fun select(slot: ResourceSelectionSlot, reference: ConfigurationReference) {
        val original = catalog?.selection ?: return
        scope.launch {
            try { commands.selectResource(original, slot, reference) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { toaster.show(failureText, type = ToastType.Error) }
        }
    }
    var editingTTSProvider by remember { mutableStateOf<TTSProviderSetting?>(null) }
    var editingASRProvider by remember { mutableStateOf<ASRProviderSetting?>(null) }
    var selectedPage by remember { mutableIntStateOf(0) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(text = stringResource(R.string.speech_page_title))
                },
                navigationIcon = {
                    BackButton()
                },
                actions = {
                    if (selectedPage == 0) {
                        AddTTSProviderButton { provider ->
                            vm.updateSettings { current ->
                                current.copy(ttsProviders = listOf(provider) + current.ttsProviders)
                            }
                        }
                    } else {
                        AddASRProviderButton { provider ->
                            vm.updateSettings { current ->
                                current.copy(
                                    asrProviders = listOf(provider) + current.asrProviders,
                                )
                            }
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = CustomColors.cardColorsOnSurfaceContainer.containerColor
            ) {
                NavigationBarItem(
                    selected = selectedPage == 0,
                    onClick = { selectedPage = 0 },
                    icon = { Icon(HugeIcons.VolumeHigh, contentDescription = null) },
                    label = { Text(stringResource(R.string.speech_tab_tts)) }
                )
                NavigationBarItem(
                    selected = selectedPage == 1,
                    onClick = { selectedPage = 1 },
                    icon = { Icon(HugeIcons.Mic01, contentDescription = null) },
                    label = { Text(stringResource(R.string.speech_tab_asr)) }
                )
            }
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        when (selectedPage) {
            0 -> Column(modifier = Modifier.padding(innerPadding)) {
                TTSPlaybackSpeedSetting(
                    speed = settings.defaultTTSPlaybackSpeed,
                    onSpeedChange = { speed ->
                        vm.updateSettings { it.copy(defaultTTSPlaybackSpeed = speed) }
                    },
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 8.dp),
                )
                SpeechProviderList(
                    settings = settings, catalog = catalog, category = ConfigurationCategory.TTS,
                    onUpdateSettings = vm::updateSettings,
                    onSelect = { select(ResourceSelectionSlot.TTS, it) },
                    onEditTts = { editingTTSProvider = it }, onEditAsr = { editingASRProvider = it },
                    modifier = Modifier.weight(1f),
                )
            }

            1 -> SpeechProviderList(
                settings = settings, catalog = catalog, category = ConfigurationCategory.ASR,
                onUpdateSettings = vm::updateSettings,
                onSelect = { select(ResourceSelectionSlot.ASR, it) },
                onEditTts = { editingTTSProvider = it }, onEditAsr = { editingASRProvider = it },
                modifier = Modifier.padding(innerPadding)
            )
        }
    }

    // Edit TTS Provider Bottom Sheet
    editingTTSProvider?.let { provider ->
        val bottomSheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))
        var currentProvider by remember(provider) { mutableStateOf(provider) }

        AdaptiveModal(
            onDismissRequest = {
                editingTTSProvider = null
            },
            sheetState = bottomSheetState,
            dragHandle = {
                BottomSheetDefaults.DragHandle()
            }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .fillMaxHeight(0.8f),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.setting_tts_page_edit_provider),
                    style = MaterialTheme.typography.headlineSmall
                )

                TTSProviderConfigure(
                    setting = currentProvider,
                    onValueChange = { newState ->
                        currentProvider = newState
                    },
                    modifier = Modifier.weight(1f)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(
                        onClick = {
                            editingTTSProvider = null
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.cancel))
                    }

                    TextButton(
                        onClick = {
                            vm.updateSettings { current ->
                                current.copy(
                                    ttsProviders = current.ttsProviders.map {
                                        if (it.id == provider.id) currentProvider else it
                                    }
                                )
                            }
                            editingTTSProvider = null
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.chat_page_save))
                    }
                }
            }
        }
    }

    editingASRProvider?.let { provider ->
        val bottomSheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))
        var currentProvider by remember(provider) { mutableStateOf(provider) }

        AdaptiveModal(
            onDismissRequest = {
                editingASRProvider = null
            },
            sheetState = bottomSheetState,
            dragHandle = {
                BottomSheetDefaults.DragHandle()
            }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .fillMaxHeight(0.8f),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.setting_asr_page_edit_provider),
                    style = MaterialTheme.typography.headlineSmall
                )

                ASRProviderConfigure(
                    setting = currentProvider,
                    onValueChange = { newState ->
                        currentProvider = newState
                    },
                    modifier = Modifier.weight(1f)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(
                        onClick = {
                            editingASRProvider = null
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.cancel))
                    }

                    TextButton(
                        onClick = {
                            vm.updateSettings { current ->
                                current.copy(
                                    asrProviders = current.asrProviders.map {
                                        if (it.id == provider.id) currentProvider else it
                                    }
                                )
                            }
                            editingASRProvider = null
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.chat_page_save))
                    }
                }
            }
        }
    }
}

@Composable
private fun TTSPlaybackSpeedSetting(
    speed: Float,
    onSpeedChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var sliderValue by remember(speed) { mutableFloatStateOf(speed) }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.setting_tts_page_default_playback_speed),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "x${"%.1f".format(sliderValue)}",
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Slider(
                value = sliderValue,
                onValueChange = { sliderValue = (it * 10).roundToInt() / 10f },
                onValueChangeFinished = { onSpeedChange(sliderValue) },
                valueRange = 0.5f..2.0f,
                steps = 14,
            )
            Text(
                text = stringResource(R.string.setting_tts_page_default_playback_speed_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AddTTSProviderButton(onAdd: (TTSProviderSetting) -> Unit) {
    var showBottomSheet by remember { mutableStateOf(false) }
    var currentProvider: TTSProviderSetting by remember { mutableStateOf(TTSProviderSetting.SystemTTS()) }

    IconButton(
        onClick = {
            currentProvider = TTSProviderSetting.SystemTTS()
            showBottomSheet = true
        }
    ) {
        Icon(HugeIcons.Add01, stringResource(R.string.setting_tts_page_add_provider_content_description))
    }

    if (showBottomSheet) {
        val bottomSheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))
        AdaptiveModal(
            onDismissRequest = {
                showBottomSheet = false
            },
            sheetState = bottomSheetState,
            dragHandle = {
                BottomSheetDefaults.DragHandle()
            }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .fillMaxHeight(0.8f),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.setting_tts_page_add_provider),
                    style = MaterialTheme.typography.headlineSmall
                )

                TTSProviderConfigure(
                    setting = currentProvider,
                    onValueChange = { newState ->
                        currentProvider = newState
                    },
                    modifier = Modifier.weight(1f)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(
                        onClick = {
                            showBottomSheet = false
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.cancel))
                    }

                    TextButton(
                        onClick = {
                            onAdd(currentProvider)
                            showBottomSheet = false
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.setting_tts_page_add))
                    }
                }
            }
        }
    }
}

@Composable
private fun AddASRProviderButton(onAdd: (ASRProviderSetting) -> Unit) {
    var showBottomSheet by remember { mutableStateOf(false) }
    var showTypeMenu by remember { mutableStateOf(false) }
    var currentProvider: ASRProviderSetting by remember { mutableStateOf(ASRProviderSetting.OpenAIRealtime()) }

    Box {
        IconButton(
            onClick = { showTypeMenu = true }
        ) {
            Icon(HugeIcons.Add01, stringResource(R.string.setting_asr_page_add_provider))
        }
        DropdownMenu(
            expanded = showTypeMenu,
            onDismissRequest = { showTypeMenu = false }
        ) {
            DropdownMenuItem(
                text = { Text("OpenAI Realtime") },
                onClick = {
                    currentProvider = ASRProviderSetting.OpenAIRealtime()
                    showTypeMenu = false
                    showBottomSheet = true
                }
            )
            DropdownMenuItem(
                text = { Text("DashScope") },
                onClick = {
                    currentProvider = ASRProviderSetting.DashScope()
                    showTypeMenu = false
                    showBottomSheet = true
                }
            )

        }
    }

    if (showBottomSheet) {
        val bottomSheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))
        AdaptiveModal(
            onDismissRequest = {
                showBottomSheet = false
            },
            sheetState = bottomSheetState,
            dragHandle = {
                BottomSheetDefaults.DragHandle()
            }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .fillMaxHeight(0.8f),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.setting_asr_page_add_provider),
                    style = MaterialTheme.typography.headlineSmall
                )

                ASRProviderConfigure(
                    setting = currentProvider,
                    onValueChange = { newState ->
                        currentProvider = newState
                    },
                    modifier = Modifier.weight(1f)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(
                        onClick = {
                            showBottomSheet = false
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.cancel))
                    }

                    TextButton(
                        onClick = {
                            onAdd(currentProvider)
                            showBottomSheet = false
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.setting_tts_page_add))
                    }
                }
            }
        }
    }
}

@Composable
private fun SpeechProviderList(
    settings: Settings,
    catalog: SpeechCatalogUiModel?,
    category: ConfigurationCategory,
    onUpdateSettings: ((Settings) -> Settings) -> Unit,
    onSelect: (ConfigurationReference) -> Unit,
    onEditTts: (TTSProviderSetting) -> Unit,
    onEditAsr: (ASRProviderSetting) -> Unit,
    modifier: Modifier = Modifier,
) {
    val resources = catalog?.resources.orEmpty().filter { it.key.category == category }
    val selected = if (category == ConfigurationCategory.TTS) catalog?.selectedTts else catalog?.selectedAsr
    val lazyState = rememberLazyListState()
    val reorder = rememberReorderableLazyListState(lazyState) { from, to ->
        val fromId = resources.find { it.key.reference.toString() == from.key }?.key?.reference
        val toId = resources.find { it.key.reference.toString() == to.key }?.key?.reference
        if (fromId == null || toId == null || fromId is ConfigurationReference.Enterprise || toId is ConfigurationReference.Enterprise) return@rememberReorderableLazyListState
        onUpdateSettings { current ->
            if (category == ConfigurationCategory.TTS) {
                val source = current.ttsProviders.indexOfFirst { it.id == fromId }
                val target = current.ttsProviders.indexOfFirst { it.id == toId }
                if (source < 0 || target < 0) current else current.copy(ttsProviders = current.ttsProviders.toMutableList().apply { add(target, removeAt(source)) })
            } else {
                val source = current.asrProviders.indexOfFirst { it.id == fromId }
                val target = current.asrProviders.indexOfFirst { it.id == toId }
                if (source < 0 || target < 0) current else current.copy(asrProviders = current.asrProviders.toMutableList().apply { add(target, removeAt(source)) })
            }
        }
    }
    val playback = LocalTTSState.current
    val speaking by playback.isSpeaking.collectAsStateWithLifecycle()
    val testText = stringResource(R.string.setting_tts_page_test_text)
    LazyColumn(modifier.fillMaxSize().imePadding(), state = lazyState,
        contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        catalog?.selection?.let { selection -> item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(if (selection.access == net.weero.measix.pilot.data.enterprise.RealmAccess.Personal)
                    R.string.configuration_scope_personal else R.string.configuration_scope_enterprise), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.configuration_speech_scope_notice), style = MaterialTheme.typography.bodySmall)
            }
        } }
        if (catalog == null) item { Text(stringResource(R.string.configuration_reason_not_ready)) }
        selected?.unavailableReason?.let { reason -> item { Text(configurationUnavailableText(reason), color = MaterialTheme.colorScheme.error) } }
        items(resources, key = { it.key.reference.toString() }) { resource ->
            val reference = resource.key.reference
            val tts = settings.ttsProviders.find { it.id == reference }
            val asr = settings.asrProviders.find { it.id == reference }
            val managedId = (reference as? ConfigurationReference.Enterprise)?.id
            val managedTts = catalog?.managedTts?.find { it.id == managedId }
            val managedAsr = catalog?.managedAsr?.find { it.id == managedId }
            val details = when {
                managedTts != null -> "${managedTts.modelId} · ${managedTts.voice}"
                managedAsr != null -> listOfNotNull(managedAsr.modelId, managedAsr.language).joinToString(" · ")
                tts is TTSProviderSetting.OpenAI -> stringResource(R.string.setting_tts_page_provider_openai)
                tts is TTSProviderSetting.Gemini -> stringResource(R.string.setting_tts_page_provider_gemini)
                tts is TTSProviderSetting.MiMo -> stringResource(R.string.setting_tts_page_provider_mimo)
                tts is TTSProviderSetting.SystemTTS -> stringResource(R.string.setting_tts_page_provider_system)
                asr is ASRProviderSetting.OpenAIRealtime -> "OpenAI Realtime"
                asr is ASRProviderSetting.DashScope -> "DashScope"
                else -> ""
            }
            ReorderableItem(reorder, key = reference.toString()) { dragging ->
                SpeechProviderItem(resource, details, selected?.reference == reference,
                    modifier = Modifier.fillMaxWidth().scale(if (dragging) .95f else 1f),
                    onSelect = { onSelect(reference) },
                    onEdit = if (tts != null) ({ onEditTts(tts) }) else if (asr != null) ({ onEditAsr(asr) }) else null,
                    onDelete = if (resource.access.canEditDefinition && reference != DEFAULT_SYSTEM_TTS_ID) ({
                        onUpdateSettings { current ->
                            if (category == ConfigurationCategory.TTS) current.copy(ttsProviders = current.ttsProviders.filterNot { it.id == reference })
                            else current.copy(asrProviders = current.asrProviders.filterNot { it.id == reference })
                        }
                    }) else null,
                    test = if (category == ConfigurationCategory.TTS && selected?.reference == reference && resource.access.canExecute && catalog != null) ({
                        IconButton(onClick = { if (speaking) playback.stop() else playback.speak(catalog.selection, testText) }) {
                            Icon(if (speaking) HugeIcons.StopCircle else HugeIcons.VolumeHigh,
                                contentDescription = stringResource(if (speaking) R.string.stop else R.string.test_tts))
                        }
                    }) else null,
                    dragHandle = {
                        if (reference !is ConfigurationReference.Enterprise) {
                            val haptic = LocalHapticFeedback.current
                            IconButton(onClick = {}, modifier = Modifier.longPressDraggableHandle(
                                onDragStarted = { haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate) },
                                onDragStopped = { haptic.performHapticFeedback(HapticFeedbackType.GestureEnd) })) {
                                Icon(HugeIcons.DragDropHorizontal, contentDescription = null)
                            }
                        }
                    })
            }
        }
    }
}

@Composable
private fun SpeechProviderItem(
    resource: ConfigurationCatalogItem,
    details: String,
    selected: Boolean,
    modifier: Modifier,
    onSelect: () -> Unit,
    onEdit: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    test: (@Composable () -> Unit)?,
    dragHandle: @Composable () -> Unit,
) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else CustomColors.listItemColors.containerColor)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AutoAIIcon(resource.name, modifier = Modifier.size(32.dp))
                Column(Modifier.weight(1f)) {
                    Text(resource.name, style = MaterialTheme.typography.titleMedium)
                    Text(details, style = MaterialTheme.typography.bodySmall)
                }
                RadioButton(selected, onSelect, enabled = resource.access.canSelect)
                dragHandle()
            }
            Text(stringResource(when (resource.key.reference) {
                is ConfigurationReference.Enterprise -> R.string.configuration_source_enterprise
                is ConfigurationReference.User -> if (resource.key.reference == DEFAULT_SYSTEM_TTS_ID) R.string.managed_configuration_source_builtin else R.string.configuration_source_user
            }), style = MaterialTheme.typography.labelMedium)
            resource.access.unavailableReason?.let { Text(configurationUnavailableText(it), color = MaterialTheme.colorScheme.error) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                test?.invoke()
                onEdit?.let { IconButton(it) { Icon(HugeIcons.PencilEdit01, stringResource(R.string.edit)) } }
                onDelete?.let { IconButton(it) { Icon(HugeIcons.Delete01, stringResource(R.string.delete)) } }
            }
        }
    }
}
