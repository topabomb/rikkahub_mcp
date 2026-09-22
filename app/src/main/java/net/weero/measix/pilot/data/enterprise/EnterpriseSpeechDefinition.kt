package net.weero.measix.pilot.data.enterprise

import me.rerere.tts.provider.SystemTtsParameterPolicy
import me.rerere.tts.provider.TTSProviderSetting

internal fun EnterpriseAsrResource.realtimeSetting(reference: me.rerere.common.configuration.ConfigurationReference): me.rerere.asr.ASRProviderSetting =
    when (protocol) {
        EnterpriseAsrProtocol.OPENAI_REALTIME -> me.rerere.asr.ASRProviderSetting.OpenAIRealtime(
            id = reference, name = name, model = modelId, language = language.orEmpty(), prompt = prompt.orEmpty(),
            sampleRate = requireNotNull(sampleRate), vadThreshold = requireNotNull(vadThreshold).toFloat(),
            silenceDurationMs = requireNotNull(silenceDurationMs), prefixPaddingMs = requireNotNull(prefixPaddingMs))
        EnterpriseAsrProtocol.DASHSCOPE -> me.rerere.asr.ASRProviderSetting.DashScope(
            id = reference, name = name, model = modelId, language = language.orEmpty(),
            sampleRate = requireNotNull(sampleRate), vadThreshold = requireNotNull(vadThreshold).toFloat(),
            silenceDurationMs = requireNotNull(silenceDurationMs))
        else -> error("realtime_asr_protocol_required")
    }

internal fun EnterpriseAsrResource.fileProtocol(): me.rerere.asr.providers.FileTranscriptionProtocol = when (protocol) {
    EnterpriseAsrProtocol.OPENAI_HTTP -> me.rerere.asr.providers.FileTranscriptionProtocol.OPENAI
    EnterpriseAsrProtocol.DASHSCOPE_HTTP -> me.rerere.asr.providers.FileTranscriptionProtocol.DASHSCOPE
    else -> error("file_transcription_protocol_required")
}

/** Runtime parameters only; platform authentication never enters a user provider credential field. */
internal fun EnterpriseTtsResource.providerSetting(reference: me.rerere.common.configuration.ConfigurationReference): TTSProviderSetting =
    when (protocol) {
        EnterpriseTtsProtocol.OPENAI -> TTSProviderSetting.OpenAI(id = reference, name = name,
            model = requireNotNull(modelId), voice = requireNotNull(voice))
        EnterpriseTtsProtocol.GEMINI -> TTSProviderSetting.Gemini(id = reference, name = name,
            model = requireNotNull(modelId), voiceName = requireNotNull(voice))
        EnterpriseTtsProtocol.MIMO -> TTSProviderSetting.MiMo(id = reference, name = name,
            model = requireNotNull(modelId), voice = voice.orEmpty(), voiceDesignPrompt = voiceDesignPrompt.orEmpty())
        EnterpriseTtsProtocol.SYSTEM -> TTSProviderSetting.SystemTTS(id = reference, name = name,
            speechRate = requireNotNull(speechRate).toFloat(), pitch = requireNotNull(pitch).toFloat())
    }

internal fun EnterpriseTtsResource.validate() {
    require(id.startsWith("tts_") && name.isNotBlank()) { "invalid_enterprise_tts_resource" }
    if (protocol == EnterpriseTtsProtocol.SYSTEM) {
        SystemTtsParameterPolicy.requireValid(
            requireNotNull(speechRate) { "invalid_system_tts_speech_rate" },
            requireNotNull(pitch) { "invalid_system_tts_pitch" },
        )
        require(modelId == null && voice == null && voiceDesignPrompt == null) { "system_tts_cloud_fields" }
        return
    }
    require(!modelId.isNullOrBlank()) { "missing_tts_model_key" }
    require(speechRate == null && pitch == null) { "cloud_tts_system_fields" }
    require(protocol == EnterpriseTtsProtocol.MIMO || voiceDesignPrompt == null) { "unexpected_voice_design_prompt" }
    val design = protocol == EnterpriseTtsProtocol.MIMO && modelId.contains("voicedesign", ignoreCase = true)
    if (design) require(!voiceDesignPrompt.isNullOrBlank() && voice == null) { "invalid_voice_design" }
    else require(!enabled || !voice.isNullOrBlank()) { "missing_tts_voice" }
    require(voiceDesignPrompt == null || voiceDesignPrompt.isNotBlank()) { "invalid_voice_design_prompt" }
}

internal fun EnterpriseAsrResource.validate() {
    require(id.startsWith("asr_") && name.isNotBlank() && modelId.isNotBlank() && language?.isBlank() != true) {
        "invalid_enterprise_asr_resource"
    }
    if (protocol in setOf(EnterpriseAsrProtocol.OPENAI_HTTP, EnterpriseAsrProtocol.DASHSCOPE_HTTP)) {
        require(sampleRate == null && vadThreshold == null && silenceDurationMs == null && prefixPaddingMs == null && prompt == null) {
            "http_asr_realtime_fields"
        }
        return
    }
    require(vadThreshold?.let { it.isFinite() && it in 0.0..1.0 } == true && silenceDurationMs?.let { it > 0 } == true) {
        "invalid_realtime_asr_vad"
    }
    when (protocol) {
        EnterpriseAsrProtocol.OPENAI_REALTIME -> {
            require(sampleRate == 24000 && prefixPaddingMs?.let { it >= 0 } == true && prompt?.isBlank() != true) {
                "invalid_openai_realtime_asr_settings"
            }
        }
        EnterpriseAsrProtocol.DASHSCOPE -> require(sampleRate in setOf(8000, 16000) && prefixPaddingMs == null && prompt == null) {
            "invalid_dashscope_asr_settings"
        }
        EnterpriseAsrProtocol.OPENAI_HTTP, EnterpriseAsrProtocol.DASHSCOPE_HTTP -> error("http_asr_already_validated")
    }
}
