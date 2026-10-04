package net.weero.measix.pilot.ui.components.files

/** A text candidate is validated by the original file reader; language only selects colouring. */
internal data class FileTextFormat(val language: String? = null)

internal fun fileTextFormat(fileName: String): FileTextFormat? {
    val name = fileName.substringAfterLast('/').lowercase()
    val special = when {
        name == "dockerfile" || name.startsWith("dockerfile.") -> "dockerfile"
        name == "cmakelists.txt" -> "cmake"
        name == ".env" || name.startsWith(".env.") -> "properties"
        else -> null
    }
    if (special != null) return FileTextFormat(special)
    val extension = name.substringAfterLast('.', "")
    if (extension in textLanguages) return FileTextFormat(textLanguages[extension])
    // Extensionless files include README, LICENSE, Makefile and scripts. Reading remains bounded
    // and rejects binary/invalid UTF-8 instead of trusting a filename as proof of text content.
    return if (extension.isEmpty()) FileTextFormat() else null
}

private val textLanguages: Map<String, String?> = buildMap {
    fun language(name: String, vararg extensions: String) = extensions.forEach { put(it, name) }
    language("json", "json", "jsonc", "json5", "ipynb")
    language("xml", "xml", "html", "htm", "xhtml", "xsd", "xsl", "xslt", "svg", "rss", "atom", "plist")
    language("yaml", "yaml", "yml")
    language("ini", "ini", "toml")
    language("properties", "properties", "env")
    language("markdown", "md", "markdown", "mdown", "mkd")
    language("javascript", "js", "mjs", "cjs", "jsx")
    language("typescript", "ts", "tsx", "mts", "cts")
    language("css", "css")
    language("kotlin", "kt", "kts")
    language("java", "java", "jsp")
    language("python", "py", "pyw")
    language("ruby", "rb")
    language("go", "go")
    language("rust", "rs")
    language("c", "c", "h")
    language("cpp", "cpp", "hpp", "cc", "hh", "cxx", "hxx")
    language("csharp", "cs")
    language("swift", "swift")
    language("bash", "sh", "bash", "zsh")
    language("powershell", "ps1", "psm1", "psd1")
    language("sql", "sql")
    language("lua", "lua")
    language("php", "php")
    language("dart", "dart")
    language("diff", "diff", "patch")
    language("cmake", "cmake")
    language("glsl", "glsl", "vert", "frag")
    language("latex", "tex")
    listOf("txt", "text", "conf", "cfg", "csv", "tsv", "log", "scss", "sass", "less", "gradle",
        "gitignore", "gitattributes", "dockerignore", "editorconfig", "dockerfile", "pl", "r", "vue",
        "svelte", "gql", "graphql", "proto", "srt", "vtt", "bat", "cmd").forEach { put(it, null) }
}

/**
 * 工作区文件的粗略分类, 用于决定点击文件时的行为:
 * - TEXT: 应用内文本编辑/预览
 * - IMAGE: 应用内可缩放图片预览
 * - OTHER: 交给系统应用 (视频/音频/文档等) 打开
 */
internal enum class FileType { TEXT, IMAGE, OTHER }

private val IMAGE_EXTENSIONS = setOf(
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif", "avif", "ico",
)

internal fun fileType(name: String): FileType {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when {
        fileTextFormat(name) != null -> FileType.TEXT
        ext in IMAGE_EXTENSIONS -> FileType.IMAGE
        else -> FileType.OTHER
    }
}
