package net.weero.measix.pilot.utils

import net.weero.measix.pilot.ui.components.ui.ModelIconFallback
import net.weero.measix.pilot.ui.components.ui.resolveModelIconPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AIIconMatcherTest {
    @Test
    fun k3StandaloneIdUsesKimiIcon() {
        assertEquals("kimi-color.svg", computeAIIconByName("k3"))
        assertEquals("kimi-color.svg", computeAIIconByName("vendor/k3-preview"))
    }

    @Test
    fun k3SubstringDoesNotHijackUnrelatedNames() {
        assertNull(computeAIIconByName("sdk3-helper"))
        assertNull(computeAIIconByName("model-k30"))
    }

    @Test
    fun knownBrandWinsForEveryModelFallback() {
        assertEquals("openai.svg", resolveModelIconPath("gpt-5", ModelIconFallback.INITIALS))
        assertEquals("openai.svg", resolveModelIconPath("gpt-5", ModelIconFallback.NOETRAL))
    }

    @Test
    fun unmatchedModelUsesRequestedFallback() {
        assertNull(resolveModelIconPath("factory-model", ModelIconFallback.INITIALS))
        assertEquals("noetral.svg", resolveModelIconPath("factory-model", ModelIconFallback.NOETRAL))
    }

    @Test
    fun hyNamesUseHunyuanWithoutMatchingUnrelatedWords() {
        listOf("hy", "HY3", "vendor/hy-3-preview", "hunyuan", "tencent").forEach {
            assertEquals("hunyuan-color.svg", computeAIIconByName(it))
        }
        listOf("hybrid", "why", "rhythm").forEach { assertNull(computeAIIconByName(it)) }
        assertEquals("openai.svg", computeAIIconByName("gpt-5-hy"))
        assertEquals("hunyuan-color.svg", resolveModelIconPath("hy3", ModelIconFallback.NOETRAL))
    }
}
