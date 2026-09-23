package net.weero.measix.pilot.data.ai.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.files.SkillContentReadResult
import net.weero.measix.pilot.data.files.SkillManager
import net.weero.measix.pilot.data.files.SkillMetadata

fun createSkillTools(
    enabledSkills: Set<String>,
    allSkills: List<SkillMetadata>,
    skillManager: SkillManager,
): List<Tool> {
    val available = allSkills.filter { it.name in enabledSkills }
    if (available.isEmpty()) return emptyList()

    return listOf(
        Tool(
            name = "use_skill",
            description = """
                Load a skill's instructions when the user's request matches an available skill.
            """.trimIndent(),
            systemPromptContribution = buildString {
                appendLine("**Skills**")
                appendLine("<available_skills>")
                available.forEach { skill ->
                    appendLine("  <skill>")
                    appendLine("    <name>${skill.name}</name>")
                    appendLine("    <description>${skill.description}</description>")
                    appendLine("  </skill>")
                }
                append("</available_skills>")
                appendLine()
            },
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("name", buildJsonObject {
                            put("type", "string")
                            put("description", "Skill name from the available list")
                        })
                        put("path", buildJsonObject {
                            put("type", "string")
                            put(
                                "description",
                                "Optional relative path to a file inside the skill directory. Omit to read the default SKILL.md instructions. Only use paths extracted from Markdown links in the SKILL.md content. Do NOT guess or infer paths."
                            )
                        })
                    },
                    required = listOf("name")
                )
            },
            validateArguments = { element ->
                val obj = element as? JsonObject
                val name = (obj?.get("name") as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                val path = obj?.get("path")
                when {
                    name.isNullOrBlank() -> invalidToolArguments("name must be a non-empty skill name.")
                    path != null && (path as? JsonPrimitive)?.takeIf { it.isString } == null ->
                        invalidToolArguments("path must be a string when provided.")
                    else -> null
                }
            },
            execute = {
                val name = it.jsonObject["name"]?.jsonPrimitive?.content
                    ?: error("Validated skill name changed")
                val skill = available.firstOrNull { skill -> skill.name == name }
                    ?: failToolResult("skill_unavailable", "Skill '$name' is unavailable. Available: ${available.joinToString { it.name }}")
                val path = it.jsonObject["path"]?.jsonPrimitive?.content
                val content = when (val result = skillManager.readSkillContent(name, path)) {
                    is SkillContentReadResult.Success -> result.content
                    SkillContentReadResult.InvalidPath -> failToolResult("skill_invalid_path", "Path '$path' is outside skill '$name'.")
                    SkillContentReadResult.InvalidSkill -> failToolResult("skill_invalid", "Skill '$name' has invalid instructions.")
                    SkillContentReadResult.InvalidEncoding -> failToolResult("skill_invalid_encoding", "File '${path ?: "SKILL.md"}' is not valid UTF-8.")
                    SkillContentReadResult.ResourceLimit -> failToolResult("skill_too_large", "File '${path ?: "SKILL.md"}' exceeds the Skill text limit.")
                    SkillContentReadResult.NotFound -> failToolResult("skill_file_not_found", "File '${path ?: "SKILL.md"}' was not found in skill '$name'.")
                    is SkillContentReadResult.ReadFailure -> throw result.cause
                }
                listOf(UIMessagePart.Text(content))
            }
        )
    )
}
