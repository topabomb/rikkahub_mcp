package net.weero.measix.pilot.data.datastore

import kotlin.uuid.Uuid
import me.rerere.asr.ASRProviderSetting
import me.rerere.ai.provider.ProviderSetting
import me.rerere.search.SearchServiceOptions
import me.rerere.tts.provider.TTSProviderSetting
import net.weero.measix.pilot.data.ai.mcp.McpServerConfig
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.model.PromptInjection
import net.weero.measix.pilot.data.model.QuickMessage
import net.weero.measix.pilot.data.model.Tag

/** The only configuration read model visible outside [SettingsStore]. */
internal data class EffectiveSettingsSnapshot(
    val settings: Settings,
    val access: SettingsAccessIndex,
    val revision: Long,
    val managedState: ManagedConfigurationState,
    val managedFailureReason: String? = null,
)

internal enum class SettingsValueSource {
    BUILT_IN,
    LOCAL,
    MANAGED,
}

/** Source metadata is read-only UI context; it never authorizes a write. */
internal data class SettingsAccessIndex(
    private val sources: Map<String, SettingsValueSource> = emptyMap(),
    private val lockReasons: Map<String, String> = emptyMap(),
) {
    fun sourceOf(kind: ManagedConfigurationRecordKind, id: Uuid): SettingsValueSource {
        return sources[recordPath(kind, id)] ?: if (id in builtInIds(kind)) {
            SettingsValueSource.BUILT_IN
        } else {
            SettingsValueSource.LOCAL
        }
    }

    fun sourceOfDefault(path: String): SettingsValueSource =
        sources[path] ?: SettingsValueSource.BUILT_IN

    fun withLocalShadow(local: Settings, explicitDefaults: Set<String>): SettingsAccessIndex = copy(
        sources = local.sourcePaths(SettingsValueSource.LOCAL) +
            explicitDefaults.associateWith { SettingsValueSource.LOCAL } + sources,
    )

    fun reasonFor(path: String): String? = firstLocked(setOf(path))?.second

    fun firstLocked(changedPaths: Set<String>): Pair<String, String>? = changedPaths.firstNotNullOfOrNull { changed ->
        lockReasons.entries.firstOrNull { (locked, _) -> changed == locked || changed.startsWith("$locked/") }
            ?.toPair()
    }

    companion object {
        fun managed(
            records: Settings,
            defaults: Set<String>,
            locks: Map<String, String>,
        ) = SettingsAccessIndex(
            sources = records.sourcePaths(SettingsValueSource.MANAGED) +
                defaults.associateWith { SettingsValueSource.MANAGED },
            lockReasons = locks,
        )
    }
}

/** Pure merge of built-in/local state with a previously verified managed aggregate. */
internal object EffectiveSettingsResolver {
    fun resolve(
        local: Settings,
        managed: ManagedConfigurationSnapshot,
        revision: Long,
        explicitLocalDefaults: Set<String> = emptySet(),
    ): EffectiveSettingsSnapshot {
        val localReadModel = local.materializeForRead()
        val overlay = managed.overlay
        if (overlay == null) {
            return managed.toSnapshot(
                localReadModel,
                SettingsAccessIndex().withLocalShadow(local, explicitLocalDefaults),
                revision,
            )
        }

        val settings = localReadModel.copy(
            providers = mergeById(localReadModel.providers, overlay.records.providers, ProviderSetting::id),
            assistants = mergeById(localReadModel.assistants, overlay.records.assistants, Assistant::id),
            assistantTags = mergeById(localReadModel.assistantTags, overlay.records.assistantTags, Tag::id),
            mcpServers = mergeById(localReadModel.mcpServers, overlay.records.mcpServers, McpServerConfig::id),
            ttsProviders = mergeById(localReadModel.ttsProviders, overlay.records.ttsProviders, TTSProviderSetting::id),
            asrProviders = mergeById(localReadModel.asrProviders, overlay.records.asrProviders, ASRProviderSetting::id),
            searchServices = mergeById(localReadModel.searchServices, overlay.records.searchServices, SearchServiceOptions::id),
            modeInjections = mergeById(
                localReadModel.modeInjections,
                overlay.records.modeInjections,
                PromptInjection.ModeInjection::id,
            ),
            quickMessages = mergeById(localReadModel.quickMessages, overlay.records.quickMessages, QuickMessage::id),
            chatModelId = overlay.defaults.chatModelId ?: localReadModel.chatModelId,
            fastModelId = overlay.defaults.fastModelId ?: localReadModel.fastModelId,
            titleModelId = overlay.defaults.titleModelId ?: localReadModel.titleModelId,
            imageGenerationModelId = overlay.defaults.imageGenerationModelId ?: localReadModel.imageGenerationModelId,
            attachmentInspectionModelId = overlay.defaults.attachmentInspectionModelId
                ?: localReadModel.attachmentInspectionModelId,
            compressModelId = overlay.defaults.compressModelId ?: localReadModel.compressModelId,
            assistantId = overlay.defaults.assistantId ?: localReadModel.assistantId,
            selectedSearchServiceId = overlay.defaults.selectedSearchServiceId
                ?: localReadModel.selectedSearchServiceId,
            selectedTTSProviderId = overlay.defaults.selectedTTSProviderId
                ?: localReadModel.selectedTTSProviderId,
            selectedASRProviderId = overlay.defaults.selectedASRProviderId
                ?: localReadModel.selectedASRProviderId,
        ).materializeForRead()
        return managed.toSnapshot(
            settings,
            overlay.access.withLocalShadow(local, explicitLocalDefaults),
            revision,
        )
    }

    private fun ManagedConfigurationSnapshot.toSnapshot(
        settings: Settings,
        access: SettingsAccessIndex,
        revision: Long,
    ) = EffectiveSettingsSnapshot(settings, access, revision, state, failureReason)

    private fun <T> mergeById(
        local: List<T>,
        managed: List<T>,
        idOf: (T) -> Uuid,
    ): List<T> {
        val managedById = managed.associateBy(idOf)
        return local.map { managedById[idOf(it)] ?: it } + managed.filter { managedValue ->
            local.none { idOf(it) == idOf(managedValue) }
        }
    }
}

internal fun Settings.recordValues(): Map<ManagedConfigurationRecordKind, Map<Uuid, Any>> = mapOf(
    ManagedConfigurationRecordKind.PROVIDER to providers.associateBy(ProviderSetting::id),
    ManagedConfigurationRecordKind.ASSISTANT to assistants.associateBy(Assistant::id),
    ManagedConfigurationRecordKind.ASSISTANT_TAG to assistantTags.associateBy(Tag::id),
    ManagedConfigurationRecordKind.MCP_SERVER to mcpServers.associateBy(McpServerConfig::id),
    ManagedConfigurationRecordKind.TTS_PROVIDER to ttsProviders.associateBy(TTSProviderSetting::id),
    ManagedConfigurationRecordKind.ASR_PROVIDER to asrProviders.associateBy(ASRProviderSetting::id),
    ManagedConfigurationRecordKind.SEARCH_SERVICE to searchServices.associateBy(SearchServiceOptions::id),
    ManagedConfigurationRecordKind.MODE_INJECTION to modeInjections.associateBy(PromptInjection.ModeInjection::id),
    ManagedConfigurationRecordKind.QUICK_MESSAGE to quickMessages.associateBy(QuickMessage::id),
)

private fun Settings.sourcePaths(source: SettingsValueSource): Map<String, SettingsValueSource> =
    recordValues().flatMap { (kind, values) ->
        values.keys.map { id -> recordPath(kind, id) to source }
    }.toMap()

private fun recordPath(kind: ManagedConfigurationRecordKind, id: Uuid): String =
    "records/${kind.settingsPath}/$id"

private fun builtInIds(kind: ManagedConfigurationRecordKind): Set<Uuid> = when (kind) {
    ManagedConfigurationRecordKind.PROVIDER -> DEFAULT_PROVIDERS.mapTo(linkedSetOf(), ProviderSetting::id)
    ManagedConfigurationRecordKind.ASSISTANT -> DEFAULT_ASSISTANTS.mapTo(linkedSetOf(), Assistant::id)
    ManagedConfigurationRecordKind.TTS_PROVIDER -> DEFAULT_TTS_PROVIDERS.mapTo(linkedSetOf(), TTSProviderSetting::id)
    ManagedConfigurationRecordKind.SEARCH_SERVICE -> setOf(SearchServiceOptions.DEFAULT.id)
    else -> emptySet()
}
