package net.weero.measix.pilot.data.files

import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.representer.Representer
import org.yaml.snakeyaml.resolver.Resolver

/**
 * Typed frontmatter extracted from a skill file.
 *
 * Unknown YAML keys are allowed at the external file boundary but do not
 * enter the domain model. [allowedTools] is intentionally absent — there
 * is no execution consumer, and retaining it would create a false
 * permission protocol.
 */
data class SkillFrontmatter(
    val name: String,
    val description: String,
    val compatibility: String? = null,
)

data class SkillDocument(
    val frontmatter: SkillFrontmatter,
    val body: String,
)

/**
 * Sealed result of parsing a [SKILL.md] file.
 */
sealed class SkillParseResult {
    data class Success(val document: SkillDocument) : SkillParseResult()
    data class NoFrontmatter(val body: String) : SkillParseResult()
    data class Error(val message: String) : SkillParseResult()
}

object SkillFrontmatterParser {
    private const val MAX_DOCUMENT_CODE_POINTS = 1_000_000
    private const val MAX_CODE_POINTS = 200_000
    private const val MAX_NESTING_DEPTH = 10
    private const val MAX_ALIASES = 50
    private const val MAX_COLLECTION_ENTRIES = 1_024
    private const val MAX_TOTAL_NODES = 4_096

    /**
     * Parse the frontmatter of a skill file.
     *
     * `---` must be on the first line and occupy its own line. The closing
     * `---` must also be on its own line. If the delimiter is present but the
     * YAML is invalid, a typed [SkillParseResult.Error] is returned — it is
     * not silently treated as "no frontmatter".
     */
    fun parseDocument(content: String): SkillParseResult {
        if (content.codePointCount(0, content.length) > MAX_DOCUMENT_CODE_POINTS) {
            return SkillParseResult.Error("Skill document exceeds the size limit")
        }

        val frontmatterStart = when {
            content.startsWith("---\r\n") -> 5
            content.startsWith("---\n") -> 4
            content == "---" -> return SkillParseResult.Error("Missing closing frontmatter delimiter")
            else -> -1
        }
        if (frontmatterStart < 0) {
            return SkillParseResult.NoFrontmatter(body = content)
        }

        val delimiter = findClosingDelimiter(content, frontmatterStart)
            ?: return SkillParseResult.Error("Missing closing frontmatter delimiter")

        val yamlText = content.substring(frontmatterStart, delimiter.yamlEnd)
        val body = content.substring(delimiter.bodyStart)

        return try {
            val loaded = newYaml().load<Any?>(yamlText)
            if (loaded == null) {
                SkillParseResult.Error("Frontmatter is empty")
            } else if (loaded !is Map<*, *>) {
                SkillParseResult.Error("Frontmatter must be a YAML mapping")
            } else {
                validateCollectionLimits(loaded)?.let { return SkillParseResult.Error(it) }
                val name = readRequiredString(loaded, "name")
                    ?: return SkillParseResult.Error("Missing or invalid required string field: name")
                val description = readRequiredString(loaded, "description")
                    ?: return SkillParseResult.Error("Missing or invalid required string field: description")
                val compatibility = when (val value = loaded["compatibility"]) {
                    null -> null
                    is String -> value.trim().takeIf { it.isNotBlank() }
                    else -> return SkillParseResult.Error("Invalid optional string field: compatibility")
                }
                SkillParseResult.Success(
                    SkillDocument(
                        frontmatter = SkillFrontmatter(
                            name = name,
                            description = description,
                            compatibility = compatibility,
                        ),
                        body = body,
                    )
                )
            }
        } catch (e: Exception) {
            SkillParseResult.Error(e.message ?: "Invalid YAML")
        }
    }

    private fun newYaml(): Yaml {
        // SnakeYAML's constructor/composer holds mutable per-load state. A parser instance must
        // never be shared by concurrent UI refresh, import, and tool-execution callers.
        val loaderOptions = LoaderOptions().apply {
            setCodePointLimit(MAX_CODE_POINTS)
            setNestingDepthLimit(MAX_NESTING_DEPTH)
            setMaxAliasesForCollections(MAX_ALIASES)
            setAllowDuplicateKeys(false)
            setAllowRecursiveKeys(false)
        }
        return Yaml(
            SafeConstructor(loaderOptions),
            Representer(DumperOptions()),
            DumperOptions(),
            loaderOptions,
            Resolver(),
        )
    }

    private data class ClosingDelimiter(
        val yamlEnd: Int,
        val bodyStart: Int,
    )

    private fun findClosingDelimiter(content: String, start: Int): ClosingDelimiter? {
        var lineStart = start
        while (lineStart <= content.length) {
            val newline = content.indexOf('\n', lineStart)
            val lineEnd = if (newline < 0) content.length else newline
            val contentEnd = if (lineEnd > lineStart && content[lineEnd - 1] == '\r') lineEnd - 1 else lineEnd
            if (contentEnd - lineStart == 3 && content.regionMatches(lineStart, "---", 0, 3)) {
                var bodyStart = if (newline < 0) content.length else newline + 1
                while (bodyStart < content.length && (content[bodyStart] == '\r' || content[bodyStart] == '\n')) {
                    bodyStart += 1
                }
                return ClosingDelimiter(
                    yamlEnd = lineStart,
                    bodyStart = bodyStart,
                )
            }
            if (newline < 0) return null
            lineStart = newline + 1
        }
        return null
    }

    private fun readRequiredString(map: Map<*, *>, key: String): String? {
        return (map[key] as? String)?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun validateCollectionLimits(root: Any?): String? {
        var totalNodes = 0

        fun visit(value: Any?): String? {
            totalNodes += 1
            if (totalNodes > MAX_TOTAL_NODES) return "Frontmatter contains too many values"
            return when (value) {
                is Map<*, *> -> {
                    if (value.size > MAX_COLLECTION_ENTRIES) return "Frontmatter mapping is too large"
                    value.entries.firstNotNullOfOrNull { entry -> visit(entry.key) ?: visit(entry.value) }
                }
                is Collection<*> -> {
                    if (value.size > MAX_COLLECTION_ENTRIES) return "Frontmatter collection is too large"
                    value.firstNotNullOfOrNull(::visit)
                }
                else -> null
            }
        }

        return visit(root)
    }
}
