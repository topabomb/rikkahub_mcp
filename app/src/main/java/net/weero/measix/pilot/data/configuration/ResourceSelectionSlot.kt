package net.weero.measix.pilot.data.configuration

import me.rerere.common.configuration.ConfigurationReference
import net.weero.measix.pilot.data.datastore.ResourceSelections

/** A selector addresses one usage choice, never the definition of the selected resource. */
internal enum class ResourceSelectionSlot(
    val category: ConfigurationCategory,
    val modelRole: ModelSelectionRole? = null,
) {
    CHAT_MODEL(ConfigurationCategory.MODEL, ModelSelectionRole.CHAT),
    FAST_MODEL(ConfigurationCategory.MODEL, ModelSelectionRole.FAST),
    TITLE_MODEL(ConfigurationCategory.MODEL, ModelSelectionRole.TITLE),
    IMAGE_MODEL(ConfigurationCategory.MODEL, ModelSelectionRole.IMAGE),
    ATTACHMENT_INSPECTION_MODEL(ConfigurationCategory.MODEL, ModelSelectionRole.ATTACHMENT_INSPECTION),
    SUGGESTION_MODEL(ConfigurationCategory.MODEL, ModelSelectionRole.SUGGESTION),
    COMPRESS_MODEL(ConfigurationCategory.MODEL, ModelSelectionRole.COMPRESS),
    ASSISTANT(ConfigurationCategory.ASSISTANT),
    SEARCH(ConfigurationCategory.SEARCH),
    TTS(ConfigurationCategory.TTS),
    ASR(ConfigurationCategory.ASR);

    fun reference(selections: ResourceSelections): ConfigurationReference? = when (this) {
        CHAT_MODEL -> selections.chatModelId
        FAST_MODEL -> selections.fastModelId
        TITLE_MODEL -> selections.titleModelId
        IMAGE_MODEL -> selections.imageGenerationModelId
        ATTACHMENT_INSPECTION_MODEL -> selections.attachmentInspectionModelId
        SUGGESTION_MODEL -> selections.suggestionModelId
        COMPRESS_MODEL -> selections.compressModelId
        ASSISTANT -> selections.assistantId
        SEARCH -> selections.selectedSearchServiceId
        TTS -> selections.selectedTTSProviderId
        ASR -> selections.selectedASRProviderId
    }

    /** Null restores the realm default; an invalid non-null reference is never treated as null. */
    fun replace(selections: ResourceSelections, reference: ConfigurationReference?): ResourceSelections = when (this) {
        CHAT_MODEL -> selections.copy(chatModelId = reference)
        FAST_MODEL -> selections.copy(fastModelId = reference)
        TITLE_MODEL -> selections.copy(titleModelId = reference)
        IMAGE_MODEL -> selections.copy(imageGenerationModelId = reference)
        ATTACHMENT_INSPECTION_MODEL -> selections.copy(attachmentInspectionModelId = reference)
        SUGGESTION_MODEL -> selections.copy(suggestionModelId = reference)
        COMPRESS_MODEL -> selections.copy(compressModelId = reference)
        ASSISTANT -> selections.copy(assistantId = reference)
        SEARCH -> selections.copy(selectedSearchServiceId = reference)
        TTS -> selections.copy(selectedTTSProviderId = reference)
        ASR -> selections.copy(selectedASRProviderId = reference)
    }
}
