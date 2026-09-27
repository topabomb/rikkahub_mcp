package net.weero.measix.pilot.service

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import net.weero.measix.pilot.data.ai.subassistant.SubAssistantAccessPolicy
import net.weero.measix.pilot.data.ai.tools.local.LocalToolOption
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.model.AssistantMemory
import net.weero.measix.pilot.data.model.DisclosureSection
import net.weero.measix.pilot.data.configuration.LegacyEnterprisePrincipalEncoding
import me.rerere.common.configuration.ConfigurationReference

/** canonical envelope 非法：装载或提交必须失败，不得静默把模型基线降级为"没有 context"。 */
class DisclosureContentException(message: String) : IllegalStateException(message)

/**
 * 会话披露快照（Disclosure Snapshot）的唯一 canonical renderer 与 envelope 协议所有者。
 *
 * 只消费调用方已授权读取的事实；不读库、不写库、不读 Settings、不读时钟。
 * 状态按完整分区替换，接纳位置与原因由 Conversation 保存，历史正文永不后台升级。
 *
 * 使用紧凑 canonical JSON 而不是 XML wrapper，因此名称、描述与 Memory 内容只需标准 JSON
 * string escaping 即不可能伪造闭合边界。
 */
object ConversationDisclosureSnapshotService {

    /** envelope 的 `type` 值，也是 System 固定规则向模型解释该数据时使用的名字。 */
    const val CONTENT_TYPE: String = "conversation_disclosure_snapshot"

    /** renderer 当前唯一生成的 format。 */
    const val CURRENT_FORMAT: Int = 3

    /** Mobile request capability for one complete canonical snapshot; content is never truncated. */
    const val MAX_CANONICAL_CONTENT_UTF8_BYTES: Int = 256 * 1024

    /** 本 App 明确支持的 durable format 集合。未知 format 必须 fail-closed：静默忽略等于让
     * 模型基线凭空消失。停止支持一个已落库 format 前必须提供显式数据迁移。
     */
    val SUPPORTED_FORMATS: Set<Int> = setOf(1, 2, CURRENT_FORMAT)

    /**
     * 固定模型规则：在 Turn 捕获时进入 System，不随单个请求是否新增 Snapshot 改变。
     * 只解释 Snapshot 的语义优先级，不引入任何动态内容，保证缓存前缀稳定。
     */
    const val MODEL_RULES: String =
        "A conversation_disclosure_snapshot replaces each included section in its scope; omitted sections stay unchanged.\n" +
        "Apply later confirmed tool changes in order. Empty rows clear that section, not conversation history.\n" +
        "Enterprise background is read-only and separate from editable memory."

    /** memory section 的 scope 取值；关闭时仍输出完整形状，不省略任何 key。 */
    const val MEMORY_SCOPE_DISABLED: String = "disabled"
    const val MEMORY_SCOPE_LOCAL: String = "local"
    const val MEMORY_SCOPE_GLOBAL: String = "global"

    /** sub_assistants section 的 mode 取值，只由 caller 的两个 Assistant 工具开关决定。 */
    const val SUB_ASSISTANTS_MODE_MANAGEMENT_ONLY: String = "management_only"
    const val SUB_ASSISTANTS_MODE_DELEGATION_ONLY: String = "delegation_only"
    const val SUB_ASSISTANTS_MODE_BOTH: String = "both"
    const val SUB_ASSISTANTS_MODE_DISABLED: String = "disabled"

    /** section 的列头是协议的一部分：rows 使用位置数组，列语义只在这里声明一次。 */
    val MEMORY_HEADER: List<String> = listOf("id", "content")
    val SUB_ASSISTANT_HEADER: List<String> = listOf("id", "name", "description")

    /** 固定字段顺序即 canonical 顺序；未来 section 追加在末尾，不改变既有位置。 */
    private val TOP_LEVEL_KEYS = listOf("type", "format", "memory", "sub_assistants")
    private val SEED_KEYS = listOf("header", "rows")
    private val MEMORY_KEYS = listOf("enabled", "scope", "header", "rows")
    private val SUB_ASSISTANT_KEYS = listOf("mode", "header", "rows")
    private val MEMORY_SCOPES = setOf(MEMORY_SCOPE_LOCAL, MEMORY_SCOPE_GLOBAL, MEMORY_SCOPE_DISABLED)
    private val SUB_ASSISTANT_MODES = setOf(
        SUB_ASSISTANTS_MODE_BOTH,
        SUB_ASSISTANTS_MODE_MANAGEMENT_ONLY,
        SUB_ASSISTANTS_MODE_DELEGATION_ONLY,
        SUB_ASSISTANTS_MODE_DISABLED,
    )

    /** 紧凑、不 Pretty Print、键序即构造序；转义交给 kotlinx 的标准 JSON escaping。 */
    private val canonicalJson: Json = Json { prettyPrint = false }

    /**
     * 一个请求边界的已授权事实。调用方采样当前 Memory/目录，Seed 沿 Turn 捕获值；
     * renderer 内部不再解析 live state，相同候选可确定性重放。
     *
     * [assistant] 保持 Turn 捕获的 Memory 地址，目录能力取已装配与当前允许能力的交集，
     * [allAssistants] 是当前域准入的助手目录；序列化按完整 reference 排序。
     */
    data class Candidate(
        val assistant: Assistant,
        val allAssistants: List<Assistant>,
        val memories: List<AssistantMemory>,
        val enterpriseMemorySeeds: List<Pair<ConfigurationReference.Enterprise, String>> = emptyList(),
    )

    /**
     * 渲染 canonical content。相同业务数据 + 相同 format 必须逐字相同：
     *  - 不写入捕获时间、日期、Locale、随机值、revision 或进程内 generation；
     *  - 不按字符数或 token 静默裁掉 rows——完整 baseline 才是一个有效 Snapshot；
     *  - 关闭的 section 仍输出固定形状，避免"键消失"成为第二种状态编码。
     */
    fun render(candidate: Candidate): String {
        val envelope = buildJsonObject {
            put("type", JsonPrimitive(CONTENT_TYPE))
            put("format", JsonPrimitive(CURRENT_FORMAT))
            put("memory", memorySection(candidate))
            put("sub_assistants", subAssistantSection(candidate))
            put("enterprise_memory_seeds", buildJsonObject {
                put("header", JsonArray(MEMORY_HEADER.map(::JsonPrimitive)))
                putJsonArray("rows") {
                    candidate.enterpriseMemorySeeds.forEach { (id, content) ->
                        add(buildJsonArray { add(id.toString()); add(content) })
                    }
                }
            })
        }
        return canonicalJson.encodeToString(JsonObject.serializer(), envelope).also(::requireCanonical)
    }

    /** Validates the whole state before selecting sections, so a small update cannot bypass the cap. */
    fun selectSections(completeContent: String, included: Set<DisclosureSection>): String {
        val sections = readSections(completeContent)
        if (sections.keys != DisclosureSection.entries.toSet()) {
            throw DisclosureContentException("current disclosure state must contain every section")
        }
        return renderSections(sections.filterKeys { it in included })
    }

    fun readSections(content: String): Map<DisclosureSection, JsonObject> {
        requireDurableEnvelope(content)
        val root = parseEnvelope(content)
        return DisclosureSection.entries.mapNotNull { section ->
            root[section.wireName]?.let { section to it.asObjectOrThrow(section.wireName) }
        }.toMap()
    }

    /** Historical sections may come from different envelopes; each selected section remains complete. */
    fun renderSections(sections: Map<DisclosureSection, JsonObject>): String {
        if (sections.isEmpty()) throw DisclosureContentException("disclosure must contain at least one section")
        val envelope = buildJsonObject {
            put("type", CONTENT_TYPE)
            put("format", CURRENT_FORMAT)
            DisclosureSection.entries.forEach { section ->
                sections[section]?.let { put(section.wireName, it) }
            }
        }
        return canonicalJson.encodeToString(JsonObject.serializer(), envelope).also(::requireCanonical)
    }

    /**
     * 装载期校验：type / 整数 format / 固定 JSON 形状合法，未知 format fail-closed。
     *
     * 历史 entry 永不改写，因此打开会话只验证协议形状，不再做 encode round-trip。
     * 新内容由 renderer 的 [requireCanonical] 自检；历史输入仅验证合法形状。
     */
    fun requireDurableEnvelope(content: String): Int {
        requireWithinRequestCapability(content)
        return validateEnvelope(parseEnvelope(content), historical = true)
    }

    /**
     * 校验一份即将提交的 content 是否是本 App 可发送的 canonical envelope，并返回其 format。
     *
     * Renderer 自检走这一条：形状非法或 bytes 非 canonical 都以
     * [DisclosureContentException] fail-closed。
     */
    fun requireCanonical(content: String): Int {
        requireWithinRequestCapability(content)
        val root = parseEnvelope(content)
        val format = validateEnvelope(root)
        val normalized = canonicalJson.encodeToString(JsonObject.serializer(), root)
        if (normalized != content) {
            throw DisclosureContentException("disclosure content is not in canonical byte form")
        }
        return format
    }

    private fun validateEnvelope(root: JsonObject, historical: Boolean = false): Int {
        val type = requireString(root, "type", "envelope")
        if (type != CONTENT_TYPE) {
            throw DisclosureContentException("unexpected disclosure type \"$type\"")
        }
        val format = requireInt(root, "format", "envelope")
        if (format !in SUPPORTED_FORMATS) {
            throw DisclosureContentException("unsupported disclosure format $format")
        }
        val sectionKeys = DisclosureSection.entries.map { it.wireName }
        val keys = when (format) {
            1 -> TOP_LEVEL_KEYS
            2 -> TOP_LEVEL_KEYS + "enterprise_memory_seeds"
            else -> listOf("type", "format") + sectionKeys.filter { it in root }
        }
        requireKeyOrder(root, keys, "envelope")
        if (keys.size == 2) throw DisclosureContentException("disclosure must contain at least one section")
        if ("memory" in root) validateMemory(requireObject(root, "memory", "envelope"))
        val historicalReferences = historical && format in 1..2
        if ("sub_assistants" in root) validateSubAssistants(requireObject(root, "sub_assistants", "envelope"), historicalReferences)
        if ("enterprise_memory_seeds" in root) validateSeeds(requireObject(root, "enterprise_memory_seeds", "envelope"), historicalReferences)
        return format
    }

    private fun validateSeeds(section: JsonObject, historicalReferences: Boolean) {
        requireKeyOrder(section, SEED_KEYS, "enterprise_memory_seeds")
        requireHeader(section, MEMORY_HEADER, "enterprise_memory_seeds")
        val ids = mutableSetOf<String>()
        requireArray(section, "rows", "enterprise_memory_seeds").forEach { row ->
            val cells = row.asArrayOrThrow("enterprise memory seed row")
            if (cells.size != 2) throw DisclosureContentException("enterprise memory seed row must have 2 cells")
            val id = cells[0].asStringOrThrow("enterprise memory seed id")
            val reference = canonicalReference(id, historicalReferences)
            if (reference !is ConfigurationReference.Enterprise || !ids.add(id)) {
                throw DisclosureContentException("enterprise memory seed requires a unique canonical enterprise reference")
            }
            cells[1].asStringOrThrow("enterprise memory seed content")
        }
    }

    private fun requireWithinRequestCapability(content: String) {
        val bytes = content.encodeToByteArray().size
        if (bytes > MAX_CANONICAL_CONTENT_UTF8_BYTES) {
            throw DisclosureContentException(
                "disclosure snapshot exceeds request capability: $bytes > $MAX_CANONICAL_CONTENT_UTF8_BYTES UTF-8 bytes",
            )
        }
    }

    // ---- canonical sections ----

    private fun memorySection(candidate: Candidate): JsonObject {
        val enabled = candidate.assistant.enableMemory
        val scope = when {
            !enabled -> MEMORY_SCOPE_DISABLED
            candidate.assistant.useGlobalMemory -> MEMORY_SCOPE_GLOBAL
            else -> MEMORY_SCOPE_LOCAL
        }
        return buildJsonObject {
            put("enabled", JsonPrimitive(enabled))
            put("scope", JsonPrimitive(scope))
            put("header", JsonArray(MEMORY_HEADER.map(::JsonPrimitive)))
            putJsonArray("rows") {
                if (enabled) {
                    candidate.memories.sortedBy { it.id }.forEach { memory ->
                        add(buildJsonArray {
                            add(JsonPrimitive(memory.id))
                            add(JsonPrimitive(memory.content))
                        })
                    }
                }
            }
        }
    }

    private fun subAssistantSection(candidate: Candidate): JsonObject {
        val mode = subAssistantMode(candidate.assistant)
        val entries = if (mode == SUB_ASSISTANTS_MODE_DISABLED) {
            emptyList()
        } else {
            // 唯一访问公式仍只在 SubAssistantAccessPolicy 计算一次；Snapshot 不是授权。
            SubAssistantAccessPolicy.accessibleSubAssistants(candidate.assistant, candidate.allAssistants)
                .sortedBy { it.id.toString() }
        }
        return buildJsonObject {
            put("mode", JsonPrimitive(mode))
            put("header", JsonArray(SUB_ASSISTANT_HEADER.map(::JsonPrimitive)))
            putJsonArray("rows") {
                entries.forEach { assistant ->
                    add(buildJsonArray {
                        add(JsonPrimitive(assistant.id.toString()))
                        add(JsonPrimitive(assistant.name))
                        add(JsonPrimitive(assistant.description))
                    })
                }
            }
        }
    }

    /** mode 只由 caller 的两个 LocalToolOption 开关决定，与可见子助手内容无关。 */
    private fun subAssistantMode(assistant: Assistant): String {
        val management = LocalToolOption.AssistantManagement in assistant.localTools
        val delegation = LocalToolOption.AssistantDelegation in assistant.localTools
        return when {
            management && delegation -> SUB_ASSISTANTS_MODE_BOTH
            management -> SUB_ASSISTANTS_MODE_MANAGEMENT_ONLY
            delegation -> SUB_ASSISTANTS_MODE_DELEGATION_ONLY
            else -> SUB_ASSISTANTS_MODE_DISABLED
        }
    }

    // ---- canonical envelope validation ----

    private fun validateMemory(section: JsonObject) {
        requireKeyOrder(section, MEMORY_KEYS, "memory")
        val enabled = requireBoolean(section, "enabled", "memory")
        val scope = requireString(section, "scope", "memory")
        if (scope !in MEMORY_SCOPES) {
            throw DisclosureContentException("unknown memory.scope \"$scope\"")
        }
        // enabled 与 disabled scope 必须一致，避免同一个事实出现第二处编码。
        if (enabled == (scope == MEMORY_SCOPE_DISABLED)) {
            throw DisclosureContentException("memory.scope \"$scope\" disagrees with enabled=$enabled")
        }
        requireHeader(section, MEMORY_HEADER, "memory")
        val rows = requireArray(section, "rows", "memory")
        if (!enabled && rows.isNotEmpty()) {
            throw DisclosureContentException("disabled memory section must not carry rows")
        }
        val ids = mutableSetOf<Int>()
        rows.forEach { row ->
            val cells = row.asArrayOrThrow("memory row")
            if (cells.size != MEMORY_HEADER.size) {
                throw DisclosureContentException("memory row must have ${MEMORY_HEADER.size} cells")
            }
            if (!ids.add(cells[0].asIntOrThrow("memory row id"))) {
                throw DisclosureContentException("duplicate memory row id")
            }
            cells[1].asStringOrThrow("memory row content")
        }
    }

    private fun validateSubAssistants(section: JsonObject, historicalReferences: Boolean) {
        requireKeyOrder(section, SUB_ASSISTANT_KEYS, "sub_assistants")
        val mode = requireString(section, "mode", "sub_assistants")
        if (mode !in SUB_ASSISTANT_MODES) {
            throw DisclosureContentException("unknown sub_assistants.mode \"$mode\"")
        }
        requireHeader(section, SUB_ASSISTANT_HEADER, "sub_assistants")
        val rows = requireArray(section, "rows", "sub_assistants")
        if (mode == SUB_ASSISTANTS_MODE_DISABLED && rows.isNotEmpty()) {
            throw DisclosureContentException("disabled sub_assistants section must not carry rows")
        }
        val ids = mutableSetOf<String>()
        rows.forEach { row ->
            val cells = row.asArrayOrThrow("sub_assistant row")
            if (cells.size != SUB_ASSISTANT_HEADER.size) {
                throw DisclosureContentException("sub_assistant row must have ${SUB_ASSISTANT_HEADER.size} cells")
            }
            cells.forEach { cell -> cell.asStringOrThrow("sub_assistant row cell") }
            // id 必须是规范配置引用文本，否则无法与 durable Assistant identity 对齐。
            val id = cells[0].asStringOrThrow("sub_assistant id")
            if (!ids.add(id)) throw DisclosureContentException("duplicate sub_assistant id")
            canonicalReference(id, historicalReferences)
                ?: throw DisclosureContentException("sub_assistant id is not a canonical configuration reference: $id")
        }
    }

    /** Formats 1/2 retain their original identity syntax. Validation never rewrites replay bytes. */
    private fun canonicalReference(id: String, historicalReferences: Boolean): ConfigurationReference? {
        val encoded = if (historicalReferences && id.count { it == '~' } == 4) {
            LegacyEnterprisePrincipalEncoding.migrateReference(id) ?: return null
        } else id
        return runCatching { ConfigurationReference.parse(encoded) }.getOrNull()?.takeIf { it.toString() == encoded }
    }

    private fun parseEnvelope(content: String): JsonObject {
        val element = runCatching { canonicalJson.parseToJsonElement(content) }.getOrNull()
            ?: throw DisclosureContentException("disclosure content is not valid JSON")
        return element as? JsonObject
            ?: throw DisclosureContentException("disclosure envelope is not a JSON object")
    }

    /** key 顺序也是 canonical 事实：乱序不是同一份内容。 */
    private fun requireKeyOrder(section: JsonObject, expected: List<String>, what: String) {
        if (section.keys.toList() != expected) {
            throw DisclosureContentException("$what keys must be $expected in this order")
        }
    }

    private fun requireHeader(section: JsonObject, expected: List<String>, what: String) {
        val header = requireArray(section, "header", what).map { cell ->
            cell.asStringOrThrow("$what header")
        }
        if (header != expected) {
            throw DisclosureContentException("$what header must be $expected")
        }
    }

    private fun element(section: JsonObject, key: String, what: String): JsonElement =
        section[key] ?: throw DisclosureContentException("$what is missing \"$key\"")

    private fun requireObject(section: JsonObject, key: String, what: String): JsonObject =
        element(section, key, what).asObjectOrThrow("$what.$key")

    private fun requireArray(section: JsonObject, key: String, what: String): JsonArray =
        element(section, key, what).asArrayOrThrow("$what.$key")

    private fun requireString(section: JsonObject, key: String, what: String): String =
        element(section, key, what).asStringOrThrow("$what.$key")

    private fun requireInt(section: JsonObject, key: String, what: String): Int =
        element(section, key, what).asIntOrThrow("$what.$key")

    private fun requireBoolean(section: JsonObject, key: String, what: String): Boolean =
        element(section, key, what).asBooleanOrThrow("$what.$key")

    private fun JsonElement.asObjectOrThrow(what: String): JsonObject =
        this as? JsonObject ?: throw DisclosureContentException("$what is not a JSON object")

    private fun JsonElement.asArrayOrThrow(what: String): JsonArray =
        this as? JsonArray ?: throw DisclosureContentException("$what is not a JSON array")

    private fun JsonElement.asStringOrThrow(what: String): String =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            ?: throw DisclosureContentException("$what must be a JSON string")

    private fun JsonElement.asIntOrThrow(what: String): Int = asLiteral(what).intOrNull
        ?: throw DisclosureContentException("$what must be a JSON integer")

    private fun JsonElement.asBooleanOrThrow(what: String): Boolean = asLiteral(what).booleanOrNull
        ?: throw DisclosureContentException("$what must be a JSON boolean")

    /**
     * 数字与布尔只接受 JSON literal，不接受 `"1"` / `"true"` 这类字符串伪装：
     * canonical renderer 从不产生它们，接受它们等于允许第二套编码进入同一份 content。
     */
    private fun JsonElement.asLiteral(what: String): JsonPrimitive =
        (this as? JsonPrimitive)?.takeIf { !it.isString }
            ?: throw DisclosureContentException("$what must be a JSON literal, not a string")
}
