package net.weero.measix.pilot.data.ai.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.configuration.ResolvedConfiguration
import net.weero.measix.pilot.data.configuration.ResourceSelectionSlot
import net.weero.measix.pilot.utils.JsonInstantPretty
import me.rerere.search.SearchService
import me.rerere.search.SearchServiceOptions
import me.rerere.search.SearchHttpException
import me.rerere.search.SearchNoResultsException
import kotlin.uuid.Uuid

internal fun createSearchTools(settings: Settings, configuration: ResolvedConfiguration): Set<Tool> {
    val selected = configuration.selection(ResourceSelectionSlot.SEARCH)
    check(selected.isAvailable) { "search_selection_unavailable" }
    val options = requireNotNull(settings.searchServices.singleOrNull { it.id == selected.reference }) {
        "search_definition_unavailable"
    }
    val service = SearchService.getService(options)

    return buildSet {
        add(
            Tool(
                name = "search_web",
                description = """
                    Search the web for current or specific facts. Use focused keywords; run multiple searches if needed.
                    For time-sensitive facts, verify publication dates and when the events occurred; result order and retrieval time do not prove freshness. If dates or primary evidence are missing, refine the search or inspect the source before claiming a result is current.
                    Cite with `[citation,domain](id)` after the sentence.
                    If images help, embed 2–4 from `images[]` at the start of the reply; never invent urls.
                    """.trimIndent(),
                parameters = {
                    service.parameters(options)
                },
                validateArguments = { args ->
                    validateSearchArguments(args, options is SearchServiceOptions.TavilyOptions)
                },
                execute = {
                    val result = service.search(
                        params = it.jsonObject,
                        commonOptions = settings.searchCommonOptions,
                        serviceOptions = options,
                    )
                    val results =
                        JsonInstantPretty.encodeToJsonElement(result.searchToolValue()).jsonObject.let { json ->
                            val map = json.toMutableMap()
                            map["items"] =
                                JsonArray(map["items"]!!.jsonArray.mapIndexed { index, item ->
                                    JsonObject(item.jsonObject.toMutableMap().apply {
                                        put("id", JsonPrimitive(Uuid.random().toString().take(6)))
                                        put("index", JsonPrimitive(index + 1))
                                    })
                                })
                            JsonObject(map)
                        }
                    listOf(UIMessagePart.Text(results.toString()))
                }
            )
        )

        if (service.scrapingParameters(options) != null) {
            add(
                Tool(
                    name = "scrape_web",
                    description = """
                        Scrape a URL when the user wants that page, or when search snippets are not enough.
                        Do not use it for common questions unless asked.
                        """.trimIndent(),
                    parameters = {
                        service.scrapingParameters(options)
                    },
                    validateArguments = { args ->
                        requireWebString(args, "url")
                    },
                    execute = {
                        val result = service.scrape(
                            params = it.jsonObject,
                            commonOptions = settings.searchCommonOptions,
                            serviceOptions = options,
                        )
                        val payload = JsonInstantPretty.encodeToJsonElement(result.searchToolValue()).jsonObject
                        listOf(UIMessagePart.Text(payload.toString()))
                    }
                ))
        }
    }
}

private fun searchHttpReason(statusCode: Int): String = when (statusCode) {
    400, 422 -> "invalid_request"
    401, 403 -> "auth_failed"
    429 -> "rate_limited"
    else -> "search_provider_error"
}

internal fun <T> Result<T>.searchToolValue(): T = try {
    getOrThrow()
} catch (error: SearchHttpException) {
    failToolResult(searchHttpReason(error.statusCode), error.message)
} catch (error: SearchNoResultsException) {
    failToolResult("no_results")
}

internal fun requireWebString(args: JsonElement, field: String): JsonObject? {
    val value = (args as? JsonObject)?.get(field) as? JsonPrimitive
    if (value?.isString == true && value.content.isNotBlank()) return null
    return JsonObject(mapOf(
        "reason" to JsonPrimitive("invalid_arguments"),
        "detail" to JsonPrimitive("$field must be a non-empty string."),
    ))
}

internal fun validateSearchArguments(args: JsonElement, tavily: Boolean): JsonObject? {
    requireWebString(args, "query")?.let { return it }
    if (!tavily) return null
    val topic = (args as? JsonObject)?.get("topic") ?: return null
    if (topic is JsonPrimitive && topic.isString && topic.content in setOf("general", "news", "finance")) {
        return null
    }
    return JsonObject(mapOf(
        "reason" to JsonPrimitive("invalid_arguments"),
        "detail" to JsonPrimitive("topic must be general, news, or finance."),
    ))
}
