package net.weero.measix.pilot.data.ai.subassistant

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.weero.measix.pilot.data.model.Assistant
import kotlin.uuid.Uuid

enum class CatalogMode {
    /** 只有 delegation */
    DELEGATION_ONLY,

    /** 只有 management */
    MANAGEMENT_ONLY,

    /** 两项同时开启 */
    BOTH,
}

/**
 * 构建可访问的子助手列表（使用有效访问公式）。
 * 始终排除当前 caller，保持 Settings.assistants 中的用户顺序。
 */
fun resolveCatalogEntries(
    caller: Assistant,
    allAssistants: List<Assistant>,
): List<Assistant> {
    return SubAssistantAccessPolicy.accessibleSubAssistants(caller, allAssistants)
}

/**
 * 构建 Catalog JSON 字符串（使用 header + rows 紧凑格式，kotlinx.serialization 生成）。
 * 始终使用同一 header：["id","name","description"]，不增加 callable 或来源列。
 * 空列表仍输出相同 header 与空 rows。
 */
fun buildAssistantCatalog(
    caller: Assistant,
    allAssistants: List<Assistant>,
    @Suppress("UNUSED_PARAMETER") mode: CatalogMode,
    json: Json,
): String {
    val entries = resolveCatalogEntries(caller, allAssistants)

    val rows = entries.map { assistant ->
        buildJsonArray {
            add(JsonPrimitive(assistant.id.toString()))
            add(JsonPrimitive(assistant.name))
            add(JsonPrimitive(assistant.description))
        }
    }

    val catalogJson = buildJsonObject {
        put("header", JsonArray(listOf("id", "name", "description").map { JsonPrimitive(it) }))
        put("rows", JsonArray(rows))
    }
    return catalogJson.toString()
}

/**
 * 构建 Catalog system prompt 前缀文本。
 * 两项同时启用时只注入一次。
 * 使用 <sub_assistant_catalog> 边界标签。
 * 前缀文案不随 [mode] 变化；mode 只决定由哪个工具注入。
 * 对 JSON 中的 <、>、& 做 Unicode escape，避免不可信 name/description 形成伪造闭合标签。
 */
fun buildCatalogPrompt(
    caller: Assistant,
    allAssistants: List<Assistant>,
    @Suppress("UNUSED_PARAMETER") mode: CatalogMode,
    json: Json,
): String {
    val catalogJson = buildAssistantCatalog(caller, allAssistants, mode, json)
    // XML-like 边界转义：把 <、>、& 编码为合法 JSON Unicode escape。
    // 不需要额外转义反斜杠——kotlinx.serialization 已正确处理 JSON 转义序列，
    // 再次转义会破坏已有的 \n、\" 等序列。
    val escapedJson = catalogJson
        .replace("<", "\\u003c")
        .replace(">", "\\u003e")
        .replace("&", "\\u0026")

    return """
<sub_assistant_catalog>
Sub-assistants (sub-agents).
$escapedJson
</sub_assistant_catalog>
    """.trimIndent()
}

/**
 * 确定使用哪种 Catalog 模式。
 */
fun resolveCatalogMode(
    enableManagement: Boolean,
    enableDelegation: Boolean,
): CatalogMode? {
    return when {
        enableManagement && enableDelegation -> CatalogMode.BOTH
        enableManagement -> CatalogMode.MANAGEMENT_ONLY
        enableDelegation -> CatalogMode.DELEGATION_ONLY
        else -> null
    }
}
