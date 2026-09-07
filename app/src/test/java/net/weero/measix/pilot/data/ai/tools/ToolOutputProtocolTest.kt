package net.weero.measix.pilot.data.ai.tools

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import me.rerere.ai.core.ToolArgumentsException
import me.rerere.ai.core.ToolExecutionFailure
import me.rerere.ai.core.ToolOutputPolicy
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.ToolOutputArchive
import me.rerere.ai.ui.ToolOutputArchiveRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ToolOutputProtocolTest {
    @Test
    fun `canonicalization normalizes line endings strips ansi and creates stable virtual lines`() {
        val canonical = canonicalizeToolOutput("a\r\n\u001B[31mred\u001B[0m\rb")
        assertEquals("a\nred\nb", canonical)
        assertEquals(listOf("x".repeat(4096), "x"), virtualLinesOf("x".repeat(4097)))
        val supplementaryPlaneBoundary = "x".repeat(4095) + "😀" + "y"
        assertEquals(
            listOf("x".repeat(4095) + "😀", "y"),
            virtualLinesOf(supplementaryPlaneBoundary),
        )
        assertEquals(supplementaryPlaneBoundary, virtualLinesOf(supplementaryPlaneBoundary).joinToString(""))
        val manySupplementaryPlaneChunks = "😀".repeat(8193)
        assertEquals(
            listOf(4096, 4096, 1),
            virtualLinesOf(manySupplementaryPlaneChunks).map { it.codePointCount(0, it.length) },
        )
        assertEquals(3, virtualLineCount("a\n${"x".repeat(4097)}"))
        assertEquals(0, virtualLineCount(""))
        assertEquals(1, virtualLineCount("a\n"))
        assertEquals(1, virtualLineCount("\n"))
        assertEquals(2, virtualLineCount("a\n\n"))
    }

    @Test
    fun `markers accept only completed or failed and only failure keeps a bounded tail`() {
        val archive = ToolOutputArchive(
            7,
            ToolOutputArchiveRef("tool_outputs/a.txt", "text/plain"),
            40000,
            300,
        )
        val marker = buildToolOutputMarker(archive, "failed", "first\n${"z".repeat(200)}")
        assertFalse(marker.contains('\n'))
        assertTrue(marker.startsWith("[Archived tool result: ref=7; status=failed; lines=300"))
        assertTrue("tail=\"" in marker)
        assertTrue(marker.length < 300)
        assertEquals(
            "[Archived tool result: ref=7; status=completed; lines=300; chars=40000]",
            buildToolOutputMarker(archive, "completed", "ok"),
        )
        assertThrows(IllegalArgumentException::class.java) {
            buildToolOutputMarker(archive, "denied", "private")
        }
        assertThrows(IllegalArgumentException::class.java) {
            buildToolOutputMarker(archive, "answered", "answer")
        }
    }

    @Test
    fun `lookup success text is compact numbered and protocol bounded`() {
        val lines = (1..500).map { ToolOutputLine(it, "值\\\"$it") }
        val read = formatReadResult(
            ToolOutputReadResult.Success(
                startLine = 1,
                endLine = 500,
                totalLines = 600,
                lines = lines,
                nextStartLine = 501,
                byteLimited = false,
            )
        )
        assertTrue(read.startsWith("[lines=1-500/600; next=501]"))
        assertFalse("ref=" in read)
        assertTrue("1: 值\\\"1" in read)
        assertFalse(read.trimStart().startsWith("{"))
        assertTrue(read.toByteArray(Charsets.UTF_8).size <= ToolOutputProtocol.TOOL_OUTPUT_MAX_RESPONSE_BYTES)

        val emptyPage = formatReadResult(
            ToolOutputReadResult.Success(
                startLine = 999,
                endLine = 998,
                totalLines = 600,
                lines = emptyList(),
                nextStartLine = null,
                byteLimited = false,
            )
        )
        assertEquals(
            "[lines=none; total=600]",
            emptyPage,
        )

        val grep = formatGrepResult(
            ToolOutputGrepResult.Success(
                blocks = listOf(ToolOutputGrepBlock(1, 2, lines.take(2))),
                matchCount = 2,
                totalLines = 600,
                truncated = false,
            )
        )
        assertTrue(grep.startsWith("[matches=2; total_lines=600]"))
        assertFalse("ref=" in grep)
        assertFalse("pattern=" in grep)
        assertTrue("2: 值\\\"2" in grep)
    }

    @Test
    fun `lookup tools are stable regenerable tools with bounded validation`() = runTest {
        val tools = createToolOutputLookupTools(mockk(relaxed = true), Uuid.random())
        assertEquals(listOf("read_tool_output", "grep_tool_output"), tools.map { it.name })
        assertTrue(tools.all { it.outputPolicy == ToolOutputPolicy.REGENERABLE_TEXT })
        val read = tools.first()
        assertThrows(ToolArgumentsException::class.java) {
            read.parseArguments("""{"ref":1,"limit":501}""", Json)
        }
        assertThrows(ToolArgumentsException::class.java) {
            read.parseArguments("""{"ref":"1"}""", Json)
        }
        val grep = tools.last()
        assertThrows(ToolArgumentsException::class.java) {
            grep.parseArguments("""{"ref":1,"pattern":"x","context":6}""", Json)
        }
        assertThrows(ToolArgumentsException::class.java) {
            grep.parseArguments("""{"ref":1,"pattern":"x","ignore_case":"true"}""", Json)
        }
    }

    @Test
    fun `lookup failures use the unified short failure envelope`() = runTest {
        val store = mockk<ToolOutputStore>()
        coEvery { store.read(any(), 7, any(), any()) } returns ToolOutputReadResult.Unavailable
        coEvery { store.grep(any(), 7, any(), any(), any(), any()) } returns
            ToolOutputGrepResult.InvalidPattern
        val tools = createToolOutputLookupTools(store, Uuid.random())

        assertEquals(
            "{\"status\":\"failed\",\"reason\":\"archive_unavailable\"}",
            failureText { tools.first().execute(Json.parseToJsonElement("""{"ref":7}""")) },
        )
        assertEquals(
            "{\"status\":\"failed\",\"reason\":\"invalid_pattern\"}",
            failureText {
                tools.last().execute(Json.parseToJsonElement("""{"ref":7,"pattern":"(?=x)"}"""))
            },
        )
    }

    private suspend fun failureText(block: suspend () -> Unit): String {
        val failure = try {
            block()
            throw AssertionError("expected ToolExecutionFailure")
        } catch (error: ToolExecutionFailure) {
            error
        }
        return (failure.output.single() as UIMessagePart.Text).text
    }
}
