package net.weero.measix.pilot.utils

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
}
