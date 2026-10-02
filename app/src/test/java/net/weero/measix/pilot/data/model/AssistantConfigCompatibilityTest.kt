package net.weero.measix.pilot.data.model

import me.rerere.common.configuration.ConfigurationReference


import net.weero.measix.pilot.data.ai.tools.local.LocalToolOption
import kotlinx.serialization.json.Json
import net.weero.measix.pilot.data.datastore.PendingAssistantDeletion
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.normalizeForPersistence
import net.weero.measix.pilot.data.datastore.withInternalStateFrom
import net.weero.measix.pilot.ui.pages.assistant.detail.mergeAssistantDelta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Assistant 配置兼容性回归测试。
 *
 * 覆盖：历史 JSON 缺字段时普通类别、非全局可见、允许列表为空；
 * 关闭类别清理授权；描述规范化与内部删除状态保全。
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
        val targetId = ConfigurationReference.random()
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
            id = ConfigurationReference.random(),
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
        val prefix = "a".repeat(239)
        val result = normalizeDescription(prefix + "🎉" + "tail")

        assertEquals(prefix + "🎉", result)
        assertEquals(240, result.codePointCount(0, result.length))
        assertEquals(241, result.length)
    }

    @Test
    fun `pending deletion is persisted separately and excluded from settings export`() {
        val pending = PendingAssistantDeletion(assistantId = ConfigurationReference.random(), avatarUri = "file:///avatar.png")
        val settings = Settings(pendingAssistantDeletions = listOf(pending))

        val encoded = json.encodeToString(settings)
        assertFalse(encoded.contains("pendingAssistantDeletions"))
        assertTrue(json.decodeFromString<Settings>(encoded).pendingAssistantDeletions.isEmpty())
    }

    @Test
    fun `persistence normalization deduplicates tombstones and normalizes descriptions`() {
        val assistantId = ConfigurationReference.random()
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
        val pending = PendingAssistantDeletion(ConfigurationReference.random())
        val current = Settings(pendingAssistantDeletions = listOf(pending))

        val merged = Settings().withInternalStateFrom(current)

        assertEquals(listOf(pending), merged.pendingAssistantDeletions)
    }

    @Test
    fun `assistant page delta keeps concurrent allowed list change`() {
        val assistantId = ConfigurationReference.random()
        val concurrentTargetId = ConfigurationReference.random()
        val baseline = Assistant(id = assistantId, name = "Before")
        val edited = baseline.copy(name = "After")
        val current = baseline.copy(allowedSubAssistantIds = setOf(concurrentTargetId), builtInSearch = true)

        val merged = mergeAssistantDelta(baseline, edited, current)

        assertEquals("After", merged.name)
        assertEquals(setOf(concurrentTargetId), merged.allowedSubAssistantIds)
        assertEquals(true, merged.builtInSearch)
        assertEquals(false, mergeAssistantDelta(baseline, baseline.copy(builtInSearch = false), current).builtInSearch)
    }

    @Test
    fun `legacy assistant json keeps text to image closed`() {
        val legacyJson = """{"id":"00000000-0000-0000-0000-000000000001","name":"Old","localTools":[{"type":"time_info"}]}"""
        val assistant = json.decodeFromString<Assistant>(legacyJson)
        assertFalse(LocalToolOption.TextToImage in assistant.localTools)
    }

    @Test
    fun `text to image option round trips`() {
        val assistant = Assistant(localTools = listOf(LocalToolOption.TextToImage))
        val encoded = json.encodeToString(assistant)
        assertTrue(encoded.contains("text_to_image"))
        val decoded = json.decodeFromString<Assistant>(encoded)
        assertTrue(LocalToolOption.TextToImage in decoded.localTools)
    }

    @Test
    fun `settings with text to image assistant round trips`() {
        val settings = Settings(
            assistants = listOf(Assistant(localTools = listOf(LocalToolOption.TextToImage))),
        )
        val decoded = json.decodeFromString<Settings>(json.encodeToString(settings))
        assertTrue(LocalToolOption.TextToImage in decoded.assistants.single().localTools)
    }
}
