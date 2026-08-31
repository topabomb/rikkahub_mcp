package net.weero.measix.pilot.ui.components.message

import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMessageEditedFilesTest {
    @Test
    fun `malformed input with failed result never crashes or advertises requested file`() {
        val parts = listOf(
            write("[]", """{"error":"invalid_arguments"}"""),
            write("""{"path":[]}""", """{"error":"invalid_arguments"}"""),
            write("""{"path":"/workspace/never-written.txt"}""", """{"error":"text is required"}"""),
            write("""{"path":"/workspace/never-written.txt"}""", """{"status":"failed","reason":"invalid_arguments"}"""),
        )
        assertTrue(editedWorkspaceFilePaths(parts).isEmpty())
    }

    @Test
    fun `successful results use returned paths only and deduplicate in completion order`() {
        val parts = listOf(
            write("[]", writeResult("/workspace/first.txt")),
            edit("""{"path":"/workspace/old.txt"}""", editResult("/workspace/second.txt")),
            write("""{"path":"/workspace/requested.txt"}""", writeResult("/workspace/first.txt")),
        )
        assertEquals(listOf("/workspace/first.txt", "/workspace/second.txt"), editedWorkspaceFilePaths(parts))
    }

    @Test
    fun `bad result root path and incomplete success shape do not fall back to input`() {
        val requested = """{"path":"/workspace/requested.txt","text":"contents"}"""
        val outputs = listOf(
            "not json", "[]", "null", "42",
            """{"path":[]}""",
            """{"path":42,"isDirectory":false,"sizeBytes":1,"updatedAt":2}""",
            """{"path":"/workspace/x.txt"}""",
            """{"path":"/workspace/x.txt","isDirectory":"false","sizeBytes":1,"updatedAt":2}""",
            """{"path":"/workspace/x.txt","isDirectory":false,"sizeBytes":"1","updatedAt":2}""",
            """{"path":"/workspace/x.txt","isDirectory":false,"sizeBytes":-1,"updatedAt":2}""",
            """{"path":"relative.txt","isDirectory":false,"sizeBytes":1,"updatedAt":2}""",
            """{"path":"/workspace/","isDirectory":false,"sizeBytes":1,"updatedAt":2}""",
            """{"path":"/workspace/x.txt","isDirectory":false,"sizeBytes":1,"updatedAt":[]}""",
        )
        outputs.forEach { output ->
            assertTrue(output, editedWorkspaceFilePaths(listOf(write(requested, output))).isEmpty())
        }
    }

    @Test
    fun `failure flags refusals and pending calls are never successful file mutations`() {
        val resultWithError = """{"path":"/workspace/x.txt","isDirectory":false,"sizeBytes":1,"updatedAt":2,"error":"failed"}"""
        val resultWithFailure = """{"path":"/workspace/x.txt","isDirectory":false,"sizeBytes":1,"updatedAt":2,"success":false}"""
        val parts = listOf(
            write("{}", resultWithError),
            write("{}", resultWithFailure),
            write("{}", writeResult("/workspace/denied.txt")).copy(approvalState = ToolApprovalState.Denied("No")),
            write("{}", writeResult("/workspace/pending.txt")).copy(approvalState = ToolApprovalState.Pending),
            write("{}", """{"error":"User denied the tool call"}"""),
        )
        assertTrue(editedWorkspaceFilePaths(parts).isEmpty())
    }

    @Test
    fun `read tool media directory and zero replacement results do not advertise edits`() {
        val parts = listOf(
            write("{}", writeResult("/workspace/read.png")).copy(toolName = "workspace_read_file"),
            write("{}", """{"path":"/workspace/folder","isDirectory":true,"sizeBytes":0,"updatedAt":2}"""),
            edit("{}", """{"path":"/workspace/x.txt","replacements":0,"sizeBytes":1,"updatedAt":2}"""),
            edit("{}", """{"path":"/workspace/x.txt","replacements":"1","sizeBytes":1,"updatedAt":2}"""),
            write("{}", writeResult("/workspace/image.png")).copy(output = listOf(UIMessagePart.Image("data:image/png;base64,x"))),
        )
        assertTrue(editedWorkspaceFilePaths(parts).isEmpty())
    }

    @Test
    fun `unfinished tool has no produced file and edit metadata is untouched`() {
        val completed = edit("{}", editResult("/workspace/file.txt"))
        val unfinished = completed.copy(output = emptyList())
        val output = completed.output

        assertTrue(editedWorkspaceFilePaths(listOf(unfinished)).isEmpty())
        assertEquals(listOf("/workspace/file.txt"), editedWorkspaceFilePaths(listOf(completed)))
        assertEquals(output, completed.output)
    }

    private fun write(input: String, output: String) = UIMessagePart.Tool(
        toolCallId = "write", toolName = "workspace_write_file", input = input,
        output = listOf(UIMessagePart.Text(output)),
    )

    private fun edit(input: String, output: String) = write(input, output).copy(toolName = "workspace_edit_file")

    private fun writeResult(path: String) =
        """{"path":"$path","name":"file.txt","isDirectory":false,"sizeBytes":0,"updatedAt":2}"""

    private fun editResult(path: String) =
        """{"path":"$path","replacements":1,"matchStrategy":"line_trimmed","sizeBytes":1,"updatedAt":2}"""
}
