package net.weero.measix.pilot.data.ai.tools

import kotlinx.serialization.json.buildJsonObject
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
            execute = {
                val name = it.jsonObject["name"]?.jsonPrimitive?.content
                    ?: error("name is required")
                val skill = available.firstOrNull { skill -> skill.name == name }
                    ?: error("Skill '$name' is not available. Available skills: ${available.joinToString { it.name }}")
                val path = it.jsonObject["path"]?.jsonPrimitive?.content
                val content = when (val result = skillManager.readSkillContent(name, path)) {
                    is SkillContentReadResult.Success -> result.content
                    SkillContentReadResult.InvalidPath -> error("Path '$path' is outside the skill directory")
                    SkillContentReadResult.InvalidSkill -> error("Skill '$name' is invalid")
                    SkillContentReadResult.InvalidEncoding -> error("File '${path ?: "SKILL.md"}' is not valid UTF-8")
                    SkillContentReadResult.ResourceLimit -> error("File '${path ?: "SKILL.md"}' exceeds the Skill text limit")
                    SkillContentReadResult.NotFound -> error("File '${path ?: "SKILL.md"}' not found in skill '$name'")
                    SkillContentReadResult.ReadFailure -> error("Failed to read '${path ?: "SKILL.md"}' from skill '$name'")
                }
                listOf(UIMessagePart.Text(content))
            }
        )
    )
}
