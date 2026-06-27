package net.weero.measix.pilot.data.datastore

import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultProvidersTest {
    @Test
    fun `default providers should contain exactly 4 presets`() {
        assertEquals(4, DEFAULT_PROVIDERS.size)
    }

    @Test
    fun `default providers should include OpenAI`() {
        val openai = DEFAULT_PROVIDERS.filterIsInstance<ProviderSetting.OpenAI>()
            .first { it.name == "OpenAI" }
        assertEquals("https://api.openai.com/v1", openai.baseUrl)
        assertTrue(openai.builtIn)
        assertFalse(openai.enabled) // 默认禁用，用户需手动启用
    }

    @Test
    fun `default providers should include Gemini`() {
        val gemini = DEFAULT_PROVIDERS.filterIsInstance<ProviderSetting.Google>()
            .first { it.name == "Gemini" }
        assertTrue(gemini.builtIn)
        assertFalse(gemini.enabled) // 默认禁用，用户需手动启用
    }

    @Test
    fun `default providers should include Claude`() {
        val claude = DEFAULT_PROVIDERS.filterIsInstance<ProviderSetting.Claude>()
            .first { it.name == "Claude" }
        assertEquals("https://api.anthropic.com/v1", claude.baseUrl)
        assertTrue(claude.builtIn)
        assertFalse(claude.enabled) // 默认禁用，用户需手动启用
    }

    @Test
    fun `default providers should include DeepSeek with balance config`() {
        val deepseek = DEFAULT_PROVIDERS.filterIsInstance<ProviderSetting.OpenAI>()
            .first { it.name == "DeepSeek" }
        assertEquals("https://api.deepseek.com/v1", deepseek.baseUrl)
        assertTrue(deepseek.builtIn)
        assertFalse(deepseek.enabled) // 默认禁用，用户需手动启用
        assertTrue(deepseek.balanceOption.enabled)
        assertEquals("/user/balance", deepseek.balanceOption.apiPath)
        assertEquals("balance_infos[0].total_balance", deepseek.balanceOption.resultPath)
    }

    @Test
    fun `all default providers should be builtIn`() {
        DEFAULT_PROVIDERS.forEach { provider ->
            assertTrue("${provider.name} should be builtIn", provider.builtIn)
        }
    }
}
