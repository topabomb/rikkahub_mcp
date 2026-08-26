package me.rerere.ai

import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.registry.ModelRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelRegistryTest {
    @Test
    fun testGPT5() {
        assertTrue(ModelRegistry.GPT_5.match("gpt-5"))
        assertFalse(ModelRegistry.GPT_5.match("gpt-5-chat"))
        assertTrue(ModelRegistry.GPT_5.match("gpt-5-mini"))
        assertFalse(ModelRegistry.GPT_5.match("deepseek-v3"))
        assertFalse(ModelRegistry.GPT_5.match("gemini-2.0-flash"))
        assertFalse(ModelRegistry.GPT_5.match("gpt-5.1"))
        assertFalse(ModelRegistry.GPT_5.match("gpt-4o"))
        assertFalse(ModelRegistry.GPT_5.match("gpt-5.0"))
        assertFalse(ModelRegistry.GPT_5.match("gpt-6"))
    }

    @Test
    fun testGemini25() {
        assertTrue(ModelRegistry.GEMINI_LATEST.match("gemini-flash-latest"))
        assertTrue(ModelRegistry.GEMINI_LATEST.match("gemini-pro-latest"))
        assertTrue(ModelRegistry.GEMINI_2_5_FLASH.match("gemini-2.5-flash"))
        assertFalse(ModelRegistry.GEMINI_2_5_FLASH.match("gemini-2.5-pro"))
        assertFalse(ModelRegistry.GEMINI_2_5_FLASH.match("gemini-2.5-flash-image-preview"))
        assertTrue(ModelRegistry.GEMINI_2_5_IMAGE.match("gemini-2.5-flash-image"))
        assertEquals(
            listOf(Modality.TEXT, Modality.IMAGE),
            ModelRegistry.MODEL_OUTPUT_MODALITIES.getData("gemini-2.5-flash-image")
        )
        assertEquals(
            listOf(Modality.TEXT),
            ModelRegistry.MODEL_OUTPUT_MODALITIES.getData("gemini-2.5-flash")
        )
    }

    @Test
    fun testGemini37Flash() {
        // 3.7 Flash 需要显式建模，不能再靠 GEMINI_3_FLASH 的 subsequence 宽匹配继承协议行为
        assertTrue(ModelRegistry.GEMINI_3_7_FLASH.match("gemini-3.7-flash"))
        assertTrue(ModelRegistry.GEMINI_3_7_FLASH.match("gemini-3.7-flash-preview"))
        assertFalse(ModelRegistry.GEMINI_3_7_FLASH.match("gemini-3-flash"))
        assertFalse(ModelRegistry.GEMINI_3_7_FLASH.match("gemini-3.5-flash"))
        assertFalse(ModelRegistry.GEMINI_3_7_FLASH.match("gemini-3.6-flash"))
        assertFalse(ModelRegistry.GEMINI_3_7_FLASH.match("gemini-3.7-pro"))
        assertTrue(ModelRegistry.GEMINI_3_SERIES.match("gemini-3.7-flash"))
        // minimal 不支持集合：只覆盖 3.7 Flash，不吞掉支持 minimal 的旧 3 系列
        assertTrue(ModelRegistry.GEMINI_3_NO_MINIMAL_THINKING.match("gemini-3.7-flash"))
        assertFalse(ModelRegistry.GEMINI_3_NO_MINIMAL_THINKING.match("gemini-3-flash"))
        assertFalse(ModelRegistry.GEMINI_3_NO_MINIMAL_THINKING.match("gemini-3.6-flash"))
        assertFalse(ModelRegistry.GEMINI_3_NO_MINIMAL_THINKING.match("gemini-2.5-flash"))
        assertEquals(
            listOf(Modality.TEXT, Modality.IMAGE),
            ModelRegistry.MODEL_INPUT_MODALITIES.getData("gemini-3.7-flash")
        )
        assertEquals(
            listOf(ModelAbility.TOOL, ModelAbility.REASONING),
            ModelRegistry.MODEL_ABILITIES.getData("gemini-3.7-flash")
        )
    }

    @Test
    fun testGemini31Pro() {
        // 3.1 Pro 全形态（含 preview / customtools）都命中 3.1 定义，不再被 GEMINI_3_PRO 宽匹配吞掉
        assertTrue(ModelRegistry.GEMINI_3_1_PRO.match("gemini-3.1-pro"))
        assertTrue(ModelRegistry.GEMINI_3_1_PRO.match("gemini-3.1-pro-preview"))
        assertTrue(ModelRegistry.GEMINI_3_1_PRO.match("gemini-3.1-pro-preview-customtools"))
        // 版本号优先：3.1 不应落回 3 Pro（GEMINI_3_PRO 已排除含 "1" 的 id）
        assertFalse(ModelRegistry.GEMINI_3_PRO.match("gemini-3.1-pro"))
        assertTrue(ModelRegistry.GEMINI_3_PRO.match("gemini-3-pro"))
        assertTrue(ModelRegistry.GEMINI_3_PRO.match("gemini-3-pro-preview"))
        assertFalse(ModelRegistry.GEMINI_3_PRO.match("gemini-3.5-flash"))
        // 3.1 Pro 不支持 minimal → OFF 降级 low；仍在 3 系列
        assertTrue(ModelRegistry.GEMINI_3_NO_MINIMAL_THINKING.match("gemini-3.1-pro"))
        assertTrue(ModelRegistry.GEMINI_3_NO_MINIMAL_THINKING.match("gemini-3.1-pro-preview"))
        assertTrue(ModelRegistry.GEMINI_3_NO_MINIMAL_THINKING.match("gemini-3.1-pro-preview-customtools"))
        assertTrue(ModelRegistry.GEMINI_3_SERIES.match("gemini-3.1-pro"))
        assertEquals(
            listOf(Modality.TEXT, Modality.IMAGE),
            ModelRegistry.MODEL_INPUT_MODALITIES.getData("gemini-3.1-pro")
        )
        assertEquals(
            listOf(ModelAbility.TOOL, ModelAbility.REASONING),
            ModelRegistry.MODEL_ABILITIES.getData("gemini-3.1-pro")
        )
    }

    @Test
    fun testClaudeSeries() {
        assertTrue(ModelRegistry.CLAUDE_SERIES.match("claude-sonnet-4.5-20250929"))
        assertTrue(ModelRegistry.CLAUDE_SERIES.match("claude-4.5-sonnet"))
        assertTrue(ModelRegistry.CLAUDE_SERIES.match("claude-sonnet-4-20250929"))
        assertTrue(ModelRegistry.CLAUDE_SERIES.match("claude-4-sonnet"))
        assertTrue(ModelRegistry.CLAUDE_SERIES.match("claude-3.5-sonnet"))
        assertTrue(ModelRegistry.CLAUDE_SERIES.match("claude-sonnet-5"))
        assertTrue(ModelRegistry.CLAUDE_SERIES.match("claude-opus-5"))
        assertFalse(ModelRegistry.CLAUDE_ADAPTIVE_THINKING.match("claude-sonnet-4.5-20250929"))
        assertTrue(ModelRegistry.CLAUDE_ADAPTIVE_THINKING.match("claude-sonnet-4.6"))
        assertTrue(ModelRegistry.CLAUDE_ADAPTIVE_THINKING.match("claude-opus-4.8"))
        assertTrue(ModelRegistry.CLAUDE_ADAPTIVE_THINKING.match("claude-sonnet-5"))
        assertTrue(ModelRegistry.CLAUDE_ADAPTIVE_THINKING.match("claude-opus-5"))
        assertFalse(ModelRegistry.CLAUDE_ADAPTIVE_THINKING.match("claude-3.5-sonnet"))
        assertEquals(
            listOf(ModelAbility.TOOL),
            ModelRegistry.MODEL_ABILITIES.getData("claude-3.5-sonnet")
        )
        assertEquals(
            listOf(Modality.TEXT, Modality.IMAGE),
            ModelRegistry.MODEL_INPUT_MODALITIES.getData("claude-sonnet-5")
        )
        assertEquals(
            listOf(ModelAbility.TOOL, ModelAbility.REASONING),
            ModelRegistry.MODEL_ABILITIES.getData("claude-opus-5")
        )
    }

    @Test
    fun testQwen38MaxMimoV3AndMuse() {
        val toolReasoning = listOf(ModelAbility.TOOL, ModelAbility.REASONING)
        val visionInput = listOf(Modality.TEXT, Modality.IMAGE)
        assertEquals(toolReasoning, ModelRegistry.MODEL_ABILITIES.getData("qwen-3.8-max"))
        assertEquals(listOf(Modality.TEXT), ModelRegistry.MODEL_INPUT_MODALITIES.getData("qwen-3.8-max"))
        assertEquals(visionInput, ModelRegistry.MODEL_INPUT_MODALITIES.getData("mimo-v3"))
        assertEquals(visionInput, ModelRegistry.MODEL_INPUT_MODALITIES.getData("mimo-v3-pro"))
        assertEquals(toolReasoning, ModelRegistry.MODEL_ABILITIES.getData("mimo-v3-pro"))
        assertEquals(visionInput, ModelRegistry.MODEL_INPUT_MODALITIES.getData("muse-spark"))
        assertEquals(visionInput, ModelRegistry.MODEL_INPUT_MODALITIES.getData("muse-spark-1.2"))
        assertEquals(visionInput, ModelRegistry.MODEL_INPUT_MODALITIES.getData("muse-glimmer"))
        assertEquals(visionInput, ModelRegistry.MODEL_INPUT_MODALITIES.getData("muse-glimmer-30b"))
        assertEquals(toolReasoning, ModelRegistry.MODEL_ABILITIES.getData("muse-spark"))
        assertEquals(toolReasoning, ModelRegistry.MODEL_ABILITIES.getData("muse-glimmer-30b"))
    }

    @Test
    fun testSpecificityPriority() {
        assertEquals(
            listOf(Modality.TEXT, Modality.IMAGE),
            ModelRegistry.MODEL_INPUT_MODALITIES.getData("kimi-k2.5")
        )
        assertEquals(
            listOf(Modality.TEXT),
            ModelRegistry.MODEL_INPUT_MODALITIES.getData("kimi-k2")
        )
    }

    @Test
    fun testK3Alias() {
        assertEquals(
            listOf(Modality.TEXT, Modality.IMAGE),
            ModelRegistry.MODEL_INPUT_MODALITIES.getData("k3")
        )
        assertEquals(
            listOf(ModelAbility.TOOL, ModelAbility.REASONING),
            ModelRegistry.MODEL_ABILITIES.getData("k3")
        )
        assertEquals(emptyList<ModelAbility>(), ModelRegistry.MODEL_ABILITIES.getData("k30"))
    }

    @Test
    fun testOpenAIOModels() {
        assertTrue(ModelRegistry.OPENAI_O_MODELS.match("o1"))
        assertTrue(ModelRegistry.OPENAI_O_MODELS.match("o3-mini"))
        assertEquals(
            listOf(Modality.TEXT, Modality.IMAGE),
            ModelRegistry.MODEL_INPUT_MODALITIES.getData("o3-mini")
        )
    }

    @Test
    fun testGlm5AndMinimaxM25() {
        assertEquals(
            listOf(Modality.TEXT),
            ModelRegistry.MODEL_INPUT_MODALITIES.getData("glm-5")
        )
        assertEquals(
            listOf(Modality.TEXT),
            ModelRegistry.MODEL_INPUT_MODALITIES.getData("minimax-m2.5")
        )
        assertEquals(
            listOf(ModelAbility.TOOL, ModelAbility.REASONING),
            ModelRegistry.MODEL_ABILITIES.getData("glm-5")
        )
        assertEquals(
            listOf(ModelAbility.TOOL, ModelAbility.REASONING),
            ModelRegistry.MODEL_ABILITIES.getData("minimax-m2.5")
        )
    }

    @Test
    fun testDeepseekV4() {
        val reasonerAbilities = ModelRegistry.MODEL_ABILITIES.getData("deepseek-reasoner")
        assertTrue(ModelRegistry.DEEPSEEK_V4.match("deepseek-v4-flash"))
        assertTrue(ModelRegistry.DEEPSEEK_V4.match("deepseek-v4-flash-free"))
        assertTrue(ModelRegistry.DEEPSEEK_V4.match("opencode/deepseek-v4-flash-free"))
        assertTrue(ModelRegistry.DEEPSEEK_V4.match("opencode-go/deepseek-v4-flash"))
        assertTrue(ModelRegistry.DEEPSEEK_V4.match("Pro/deepseek-ai/DeepSeek-V4-Flash"))
        assertTrue(ModelRegistry.DEEPSEEK_V4.match("deepseek-v4-pro"))
        assertTrue(ModelRegistry.DEEPSEEK_V4.match("deepseek-v4-flash-vision-exp"))
        assertFalse(ModelRegistry.DEEPSEEK_V4.match("deepseek-v3.2"))
        assertEquals(
            reasonerAbilities,
            ModelRegistry.MODEL_ABILITIES.getData("deepseek-v4-flash")
        )
        assertEquals(
            reasonerAbilities,
            ModelRegistry.MODEL_ABILITIES.getData("deepseek-v4-pro")
        )
    }

    @Test
    fun testDeepseekV4FlashVisionExp() {
        val visionInput = listOf(Modality.TEXT, Modality.IMAGE)
        val toolReasoning = listOf(ModelAbility.TOOL, ModelAbility.REASONING)

        // 专用 Vision 规则命中
        assertEquals(visionInput, ModelRegistry.MODEL_INPUT_MODALITIES.getData("deepseek-v4-flash-vision-exp"))
        assertEquals(toolReasoning, ModelRegistry.MODEL_ABILITIES.getData("deepseek-v4-flash-vision-exp"))

        // 大小写不敏感
        assertEquals(visionInput, ModelRegistry.MODEL_INPUT_MODALITIES.getData("DeepSeek-V4-Flash-Vision-Exp"))

        // provider/proxy 前缀
        assertEquals(visionInput, ModelRegistry.MODEL_INPUT_MODALITIES.getData("Pro/deepseek-ai/DeepSeek-V4-Flash-Vision-Exp"))
        assertEquals(visionInput, ModelRegistry.MODEL_INPUT_MODALITIES.getData("deepseek-ai/deepseek-v4-flash-vision-exp"))

        // 通用 V4 Flash 仍为 text-only，不会因为一个型号把整个 family 误标为 vision
        assertEquals(listOf(Modality.TEXT), ModelRegistry.MODEL_INPUT_MODALITIES.getData("deepseek-v4-flash"))
        assertEquals(listOf(Modality.TEXT), ModelRegistry.MODEL_INPUT_MODALITIES.getData("deepseek-v4-flash-free"))
        assertEquals(
            listOf(Modality.TEXT),
            ModelRegistry.MODEL_INPUT_MODALITIES.getData("Pro/deepseek-ai/DeepSeek-V4-Flash")
        )

        // 缺少 Exp 的相邻型号及未发布的 Experimental 别名不能继承尚未核实的 vision capability
        assertEquals(listOf(Modality.TEXT), ModelRegistry.MODEL_INPUT_MODALITIES.getData("deepseek-v4-flash-vision"))
        assertEquals(listOf(Modality.TEXT), ModelRegistry.MODEL_INPUT_MODALITIES.getData("deepseek-v4-flash-vision-experimental"))
    }
}
