package net.weero.measix.pilot.architecture

import org.junit.Test

/** Static dependency boundaries only. Execution order and state changes are covered by behavior tests. */
class TurnStepProtocolContractTest {
    @Test
    fun `turn orchestration cannot restore workspace output truncation or cleanup`() {
        val owners = architectureSources.filter {
            it.relativeTo(architectureSourceRoot).invariantSeparatorsPath.startsWith("service/turn/")
        }
        listOf(
            "MAX_TOOL_OUTPUT_CHARS", "TOOL_OUTPUT_PREVIEW_CHARS", "maybeTruncateToolOutput",
            "legacyTruncateOutput", "/tool_outputs/", "cleanupToolOutputs",
        ).forEach { assertNoHits(it, owners) }
    }

    @Test
    fun `recovery and lifecycle owners cannot consume render overlays`() {
        val paths = setOf(
            "service/turn/TurnRecovery.kt", "service/turn/TurnFinalizer.kt",
            "service/subassistant/SubAssistantLifecycle.kt",
        )
        val owners = architectureSources.filter {
            it.relativeTo(architectureSourceRoot).invariantSeparatorsPath in paths
        }
        assertNoHits("renderNodes", owners)
    }
}
