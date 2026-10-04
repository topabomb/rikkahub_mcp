package net.weero.measix.pilot.ui.components.files

import me.rerere.highlight.CodeHighlighter
import org.junit.Assert.*
import org.junit.Test

class FileTextFormatTest {
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
            assertEquals(name, FileType.TEXT, fileType(name))
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
        assertEquals(FileType.OTHER, fileType("document.pdf"))
    }
}
