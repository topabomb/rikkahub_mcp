package net.weero.measix.pilot.data.ai.request

import kotlinx.serialization.json.*
import net.weero.measix.pilot.data.enterprise.EnterpriseStarterOpeningSnapshot
import net.weero.measix.pilot.data.model.ConversationContextSource

/** Model text and on-demand raw-input details use the same persisted rendering version. */
internal fun renderContextModelText(source: ConversationContextSource, text: String, version: Int): String {
    require(version in 1..2) { "unsupported_context_model_rendering" }
    return when (source) {
        is ConversationContextSource.Disclosure -> if (version == 1) text else
            JsonObject(Json.parseToJsonElement(text).jsonObject - "format").toString()
        is ConversationContextSource.HistorySummary -> buildJsonObject {
            put("type", "conversation_history_summary")
            if (version == 1) put("format", 1)
            put("content", text)
        }.toString()
        else -> text
    }
}

internal fun renderStarterContext(opening: EnterpriseStarterOpeningSnapshot, version: Int): String {
    require(version in 1..2) { "unsupported_context_model_rendering" }
    return buildJsonObject {
        put("type", "starter_context")
        if (version == 1) put("format", 1)
        putJsonArray("blocks") {
            opening.initialContexts.forEach { block ->
                if (version == 2) add(block.content) else add(buildJsonObject {
                    put("id", block.id)
                    block.legacyTitle?.let { put("title", it) }
                    put("content", block.content)
                })
            }
        }
    }.toString()
}
