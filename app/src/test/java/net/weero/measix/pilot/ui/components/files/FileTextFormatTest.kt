package net.weero.measix.pilot.ui.components.files

import me.rerere.highlight.CodeHighlighter
import org.junit.Assert.*
import org.junit.Test

class FileTextFormatTest {
    @Test fun `long line layout guard covers paste and joined lines without affecting ordinary multiline edits`() {
        val multiline = ("a".repeat(80) + "\n").repeat(10000)
        assertFalse(hasLongFileLine(multiline))
        assertFalse(replacementHasLongFileLine(multiline, 5, 6, "b", 0, 1))
        assertTrue(replacementHasLongFileLine("short", 0, 5, "x".repeat(1900000), 0, 1900000))
        val joined = "a".repeat(3000) + "\n" + "b".repeat(3000)
        assertTrue(replacementHasLongFileLine(joined, 3000, 3001, "", 0, 0))
        assertFalse(replacementHasLongFileLine("short", 0, 5, multiline, 0, multiline.length))
        assertFalse(hasLongFileLine("a".repeat(4096)))
        assertTrue(hasLongFileLine("a".repeat(4097)))
    }

    @Test fun `line numbers count logical lines including empty final line`() {
        assertArrayEquals(intArrayOf(0), fileLineStarts(""))
        assertArrayEquals(intArrayOf(0, 3, 4, 7), fileLineStarts("ab\n\n中x\n"))
        assertArrayEquals(intArrayOf(0, 3), fileLineStarts("a\r\nb"))
        assertArrayEquals(intArrayOf(0), fileLineStarts("a".repeat(150_000)))
    }

    @Test fun `common source and configuration files have one classification with supported grammars`() {
        val cases = mapOf("page.HTML" to "xml", "page.htm" to "xml", "data.json" to "json",
            "config.jsonc" to "json", "view.xml" to "xml", "server.py" to "python",
            "src/app.tsx" to "typescript", "config.yml" to "yaml", "build.kts" to "kotlin",
            "Dockerfile" to "dockerfile", "Dockerfile.dev" to "dockerfile", "CMakeLists.txt" to "cmake",
            ".env" to "properties", ".env.production" to "properties", "script.ps1" to "powershell",
            "image.svg" to "xml", "notes.toml" to "ini", "changes.patch" to "diff")
        val highlighter = CodeHighlighter()
        cases.forEach { (name, language) ->
            assertEquals(name, language, fileTextFormat(name)?.language)
            assertEquals(name, if (name.endsWith(".svg")) FileType.SVG else FileType.TEXT, fileType(name))
            assertTrue(language, highlighter.supports(language))
        }
    }

    @Test fun `plain or unsupported syntax remains text while binary formats keep their original opening path`() {
        listOf("notes.txt", "data.csv", "settings.conf", "page.vue", ".gitignore", "README", "LICENSE", "Makefile")
            .forEach { name ->
                assertNotNull(name, fileTextFormat(name))
                assertNull(name, fileTextFormat(name)?.language)
                assertEquals(FileType.TEXT, fileType(name))
            }
        listOf("photo.png", "document.pdf", "archive.zip", "program.exe").forEach { assertNull(fileTextFormat(it)) }
        assertEquals(FileType.IMAGE, fileType("photo.png"))
        assertEquals(FileType.PDF, fileType("document.pdf"))
        listOf("readme.md", "readme.markdown", "readme.mdown", "readme.mkd").forEach { assertEquals(FileType.MARKDOWN, fileType(it)) }
        assertEquals(FileType.VIDEO, fileType("clip.MP4"))
        assertEquals(FileType.AUDIO, fileType("audio.flac"))
        assertEquals(FileType.TEXT, fileType("module.ts"))
    }
}
