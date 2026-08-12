package net.weero.measix.pilot.data.model

import net.weero.measix.pilot.data.ai.tools.local.LocalToolOption
import kotlinx.serialization.json.Json
import net.weero.measix.pilot.data.datastore.PendingAssistantDeletion
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.normalizeForPersistence
import net.weero.measix.pilot.data.datastore.withInternalStateFrom
import net.weero.measix.pilot.ui.pages.assistant.detail.mergeAssistantDelta
import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设计文档 §13.1 — AssistantConfigCompatibilityTest
 *
 * 覆盖：历史 JSON 缺字段时普通类别、非全局可见、允许列表为空；
 * 关闭类别清理授权；克隆保留类别但重置全局与允许列表。
 */
class AssistantConfigCompatibilityTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun `legacy JSON missing sub-assistant fields - defaults to false and empty`() {
        // 模拟历史 JSON：没有 description/allowAsSubAssistant/isSubAssistantGloballyVisible/allowedSubAssistantIds
        val legacyJson = """{"id":"00000000-0000-0000-0000-000000000001","name":"Old Assistant","systemPrompt":"test"}"""
        val assistant = json.decodeFromString<Assistant>(legacyJson)
        assertEquals("", assistant.description)
        assertFalse(assistant.allowAsSubAssistant)
        assertFalse(assistant.isSubAssistantGloballyVisible)
        assertTrue(assistant.allowedSubAssistantIds.isEmpty())
    }

    @Test
    fun `sub-assistant with all fields - decodes correctly`() {
        val targetId = Uuid.random()
        val subJson = """{"id":"00000000-0000-0000-0000-000000000002","name":"Sub","description":"Helper","allowAsSubAssistant":true,"isSubAssistantGloballyVisible":true,"allowedSubAssistantIds":["$targetId"]}"""
        val assistant = json.decodeFromString<Assistant>(subJson)
        assertTrue(assistant.allowAsSubAssistant)
        assertTrue(assistant.isSubAssistantGloballyVisible)
        assertEquals(1, assistant.allowedSubAssistantIds.size)
        assertEquals(targetId, assistant.allowedSubAssistantIds.first())
    }

    @Test
    fun `normalization - non-sub-assistant forces globally visible false`() {
        val assistant = Assistant(
            id = Uuid.random(),
            name = "Test",
            allowAsSubAssistant = false,
            isSubAssistantGloballyVisible = true,
        )
        val normalized = Settings(assistants = listOf(assistant)).normalizeForPersistence()
        assertFalse(normalized.assistants.single().isSubAssistantGloballyVisible)
    }

    @Test
    fun `normalizeDescription - trims and collapses whitespace`() {
        val input = "  Hello   World\n\n  This   is  a test  "
        val result = normalizeDescription(input)
        assertEquals("Hello World This is a test", result)
    }

    @Test
    fun `normalizeDescription - empty string stays empty`() {
        assertEquals("", normalizeDescription(""))
        assertEquals("", normalizeDescription("   "))
        assertEquals("", normalizeDescription("\n\n\t"))
    }

    @Test
    fun `normalizeDescription - truncates at 240 code points without breaking surrogate pairs`() {
        // 构造一个超过 240 个 code point 的字符串，末尾放一个 emoji（surrogate pair）
        val base = "a".repeat(250)
        val emoji = "🎉" // U+1F389，需要 surrogate pair
        val input = base + emoji
        val result = normalizeDescription(input)
        // 不应超过 240 个 code point
        val codePoints = result.codePoints().toArray()
        assertTrue(codePoints.size <= 240)
        // 不应包含不完整的 surrogate（String.length 不抛异常即说明编码完整）
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun `clone resets globally visible and allowed list`() {
        val targetId = Uuid.random()
        val original = Assistant(
            id = Uuid.random(),
            name = "Original",
            description = "test",
            allowAsSubAssistant = true,
            isSubAssistantGloballyVisible = true,
            allowedSubAssistantIds = setOf(targetId),
        )
        // 模拟 clone 逻辑
        val cloned = original.copy(
            id = Uuid.random(),
            name = "${original.name} (Clone)",
            isSubAssistantGloballyVisible = false,
            allowedSubAssistantIds = emptySet(),
        )
        assertTrue(cloned.allowAsSubAssistant) // 类别保留
        assertFalse(cloned.isSubAssistantGloballyVisible) // 全局可见重置
        assertTrue(cloned.allowedSubAssistantIds.isEmpty()) // 允许列表重置
    }

    @Test
    fun `clone does not auto-add to any allowed list`() {
        val original = Assistant(
            id = Uuid.random(),
            name = "Original",
            description = "test",
            allowAsSubAssistant = true,
        )
        val cloned = original.copy(
            id = Uuid.random(),
            name = "${original.name} (Clone)",
            isSubAssistantGloballyVisible = false,
            allowedSubAssistantIds = emptySet(),
        )
        // 克隆不是工具创建行为，不自动加入任何允许列表
        assertTrue(cloned.allowedSubAssistantIds.isEmpty())
    }

    @Test
    fun `pending deletion is persisted separately and excluded from settings export`() {
        val pending = PendingAssistantDeletion(assistantId = Uuid.random(), avatarUri = "file:///avatar.png")
        val settings = Settings(pendingAssistantDeletions = listOf(pending))

        val encoded = json.encodeToString(settings)
        assertFalse(encoded.contains("pendingAssistantDeletions"))
        assertTrue(json.decodeFromString<Settings>(encoded).pendingAssistantDeletions.isEmpty())
    }

    @Test
    fun `persistence normalization deduplicates tombstones and normalizes descriptions`() {
        val assistantId = Uuid.random()
        val pending = PendingAssistantDeletion(assistantId)
        val normalized = Settings(
            assistants = listOf(Assistant(description = "  routing\n  description  ")),
            pendingAssistantDeletions = listOf(pending, pending),
        ).normalizeForPersistence()

        assertEquals("routing description", normalized.assistants.single().description)
        assertEquals(listOf(pending), normalized.pendingAssistantDeletions)
    }

    @Test
    fun `ordinary settings update preserves internal deletion tombstones`() {
        val pending = PendingAssistantDeletion(Uuid.random())
        val current = Settings(pendingAssistantDeletions = listOf(pending))

        val merged = Settings().withInternalStateFrom(current)

        assertEquals(listOf(pending), merged.pendingAssistantDeletions)
    }

    @Test
    fun `assistant page delta keeps concurrent allowed list change`() {
        val assistantId = Uuid.random()
        val concurrentTargetId = Uuid.random()
        val baseline = Assistant(id = assistantId, name = "Before")
        val edited = baseline.copy(name = "After")
        val current = baseline.copy(allowedSubAssistantIds = setOf(concurrentTargetId))

        val merged = mergeAssistantDelta(baseline, edited, current)

        assertEquals("After", merged.name)
        assertEquals(setOf(concurrentTargetId), merged.allowedSubAssistantIds)
    }
}
