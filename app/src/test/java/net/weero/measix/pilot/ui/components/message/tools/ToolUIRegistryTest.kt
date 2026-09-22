package net.weero.measix.pilot.ui.components.message.tools

import net.weero.measix.pilot.data.ai.tools.ToolOutputToolNames
import org.junit.Assert.assertSame
import org.junit.Test

class ToolUIRegistryTest {
    @Test
    fun `trimmed result lookup tools use dedicated renderers`() {
        assertSame(ReadToolOutputUI, ToolUIRegistry.resolve(ToolOutputToolNames.READ))
        assertSame(GrepToolOutputUI, ToolUIRegistry.resolve(ToolOutputToolNames.GREP))
    }
}
