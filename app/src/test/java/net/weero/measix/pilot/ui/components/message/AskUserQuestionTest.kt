package net.weero.measix.pilot.ui.components.message

import org.junit.Assert.assertEquals
import org.junit.Test

class AskUserQuestionTest {
    @Test
    fun `every selection type accepts custom answer and whitespace is not an answer`() {
        listOf("text", "single", "multi").forEach { type ->
            val question = AskUserQuestion("q", "Question", listOf("A", "B"), type)
            assertEquals("Custom answer", question.answer("  Custom answer  ", null))
            assertEquals("", question.answer(" \n ", null))
        }
    }

    @Test
    fun `multi answers keep option order and append custom text without duplicates`() {
        val question = AskUserQuestion("q", "Question", listOf("A", "B"), "multi")
        assertEquals("A, B, Custom", question.answer(" Custom ", linkedSetOf("B", "unknown", "A")))
        assertEquals("A", question.answer(" A ", setOf("A")))
    }
}
