package net.weero.measix.pilot.data.ai.tools

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.files.FileFolders
import net.weero.measix.pilot.data.files.SkillManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SkillsToolsTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `use_skill reads metadata directory when display name differs`() = runBlocking {
        val skillDir = skillDirectory("directory-name")
        skillDir.resolve("SKILL.md").writeText(
            """
                ---
                name: Display Name
                description: Test skill
                ---
                Skill instructions
            """.trimIndent()
        )
        val manager = manager()
        val tool = createSkillTools(
            enabledSkills = setOf("Display Name"),
            allSkills = manager.listSkills(),
            skillManager = manager,
        ).single()

        val result = tool.execute(
            buildJsonObject {
                put("name", "Display Name")
            }
        )

        assertEquals("Skill instructions", (result.single() as UIMessagePart.Text).text)
    }

    @Test
    fun `use_skill fails closed when an indexed skill becomes invalid before execution`() = runBlocking {
        val skillDir = skillDirectory("changed-skill")
        val skillFile = skillDir.resolve("SKILL.md")
        skillFile.writeText("---\nname: Stable\ndescription: valid\n---\nbody")
        val manager = manager()
        val tool = createSkillTools(
            enabledSkills = setOf("Stable"),
            allSkills = manager.listSkills(),
            skillManager = manager,
        ).single()
        skillFile.writeText("---\nname: Stable\ndescription: [invalid, typed, value]\n---\nbody")

        val failure = try {
            tool.execute(buildJsonObject { put("name", "Stable") })
            null
        } catch (error: me.rerere.ai.core.ToolExecutionFailure) {
            error
        }

        assertTrue(failure != null)
        val result = kotlinx.serialization.json.Json.parseToJsonElement(
            (failure!!.output.single() as me.rerere.ai.ui.UIMessagePart.Text).text
        ).jsonObject
        assertEquals("skill_file_not_found", result["reason"]!!.jsonPrimitive.content)
    }

    private fun manager(): SkillManager {
        val context = mockk<Context>()
        every { context.filesDir } returns tempFolder.root
        tempFolder.root.resolve(FileFolders.SKILLS).mkdirs()
        return SkillManager(context, mockk<SettingsStore>(relaxed = true))
    }

    private fun skillDirectory(name: String) =
        tempFolder.root.resolve(FileFolders.SKILLS).resolve(name).apply { mkdirs() }
}
