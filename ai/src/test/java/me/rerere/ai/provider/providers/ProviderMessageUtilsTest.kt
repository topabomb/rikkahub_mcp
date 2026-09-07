package me.rerere.ai.provider.providers

import me.rerere.ai.testsupport.executedTool
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

class ProviderMessageUtilsTest {
    @Test
    fun `content and completed tool groups preserve all parts and their order`() {
        val text = UIMessagePart.Text("answer")
        val reasoning = UIMessagePart.Reasoning("thinking")
        val image = UIMessagePart.Image("https://example.com/image.png")
        val tool = executedTool("call", "lookup", "{}", "result")
        val pending = tool.copy(resultStatus = null, output = emptyList())
        val cases = listOf(
            emptyList<UIMessagePart>() to emptyList(),
            listOf(text, reasoning, image, pending) to listOf(PartGroup.Content(listOf(text, reasoning, image, pending))),
            listOf(text, tool, reasoning, image) to listOf(
                PartGroup.Content(listOf(text)), PartGroup.Tools(listOf(tool)), PartGroup.Content(listOf(reasoning, image)),
            ),
            listOf(tool, reasoning, text, tool, image) to listOf(
                PartGroup.Tools(listOf(tool)), PartGroup.Content(listOf(reasoning, text)),
                PartGroup.Tools(listOf(tool)), PartGroup.Content(listOf(image)),
            ),
        )
        cases.forEach { (parts, expected) -> assertEquals(expected, groupPartsByToolBoundary(parts)) }
    }

    @Test
    fun `adjacent calls form batches only within their durable Step`() {
        val first = executedTool("same-wire-id", "lookup", "{}", "one")
        val parallel = first.copy(localCallId = Uuid.random(), output = emptyList())
        val next = first.copy(localCallId = Uuid.random(), stepId = Uuid.random())
        val cases = listOf(
            listOf(first, parallel) to listOf(PartGroup.Tools(listOf(first, parallel))),
            listOf(first, next) to listOf(PartGroup.Tools(listOf(first)), PartGroup.Tools(listOf(next))),
            listOf(first, parallel, next) to listOf(PartGroup.Tools(listOf(first, parallel)), PartGroup.Tools(listOf(next))),
        )
        cases.forEach { (parts, expected) -> assertEquals(expected, groupPartsByToolBoundary(parts)) }
    }
}
