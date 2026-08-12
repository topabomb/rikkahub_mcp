package net.weero.measix.pilot.ui.components.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ChainOfThoughtSelectionTest {
    @Test
    fun `collapsed timeline keeps every interactive step plus tail in original order`() {
        val steps = listOf("approval-a", "done-a", "approval-b", "done-b", "tail")

        val visible = selectCollapsedSteps(
            steps = steps,
            collapsedVisibleCount = 2,
            keepVisible = { it.startsWith("approval") },
        )

        assertEquals(listOf("approval-a", "approval-b", "done-b", "tail"), visible)
    }

    @Test
    fun `collapsed timeline does not duplicate pinned tail steps`() {
        val steps = listOf("done", "approval")

        val visible = selectCollapsedSteps(
            steps = steps,
            collapsedVisibleCount = 2,
            keepVisible = { it == "approval" },
        )

        assertEquals(steps, visible)
    }
}
