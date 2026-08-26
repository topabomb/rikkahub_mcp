package net.weero.measix.pilot.data.files

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillFrontmatterParserTest {

    @Test
    fun `parse simple frontmatter`() {
        val content = "---\nname: test-skill\ndescription: A test skill\n---\n\nbody"
        val result = SkillFrontmatterParser.parseDocument(content)

        assertTrue(result is SkillParseResult.Success)
        val doc = (result as SkillParseResult.Success).document
        assertEquals("test-skill", doc.frontmatter.name)
        assertEquals("A test skill", doc.frontmatter.description)
        assertEquals("body", doc.body)
    }

    @Test
    fun `parse CRLF frontmatter`() {
        val content = "---\r\nname: test-skill\r\ndescription: test\r\n---\r\n\r\nbody"
        val document = content.parseSuccess()
        val frontmatter = document.frontmatter

        assertEquals("test-skill", frontmatter.name)
        assertEquals("test", frontmatter.description)
        assertEquals("body", document.body)
    }

    @Test
    fun `parse block scalar description`() {
        val content = "---\nname: test\ndescription: |\n  Line one\n  Line two\n---\nbody"
        val frontmatter = content.parseSuccess().frontmatter

        assertEquals("test", frontmatter.name)
        assertTrue(frontmatter.description.contains("Line one"))
        assertTrue(frontmatter.description.contains("Line two"))
    }

    @Test
    fun `parse description with colon`() {
        val content = "---\nname: test\ndescription: \"value: with: colons\"\n---\nbody"
        val frontmatter = content.parseSuccess().frontmatter

        assertEquals("test", frontmatter.name)
        assertEquals("value: with: colons", frontmatter.description)
    }

    @Test
    fun `parse single-quoted value`() {
        val content = "---\nname: 'quoted-skill'\ndescription: test\n---\nbody"
        val frontmatter = content.parseSuccess().frontmatter

        assertEquals("quoted-skill", frontmatter.name)
    }

    @Test
    fun `parse double-quoted value`() {
        val content = "---\nname: \"quoted-skill\"\ndescription: test\n---\nbody"
        val frontmatter = content.parseSuccess().frontmatter

        assertEquals("quoted-skill", frontmatter.name)
    }

    @Test
    fun `parse with compatibility field`() {
        val content = "---\nname: test\ndescription: test\ncompatibility: \"1.0\"\n---\nbody"
        val frontmatter = content.parseSuccess().frontmatter

        assertEquals("test", frontmatter.name)
        assertEquals("1.0", frontmatter.compatibility)
    }

    @Test
    fun `no frontmatter returns original content as body`() {
        val content = "Just some markdown without frontmatter."
        val result = SkillFrontmatterParser.parseDocument(content)

        assertTrue(result is SkillParseResult.NoFrontmatter)
        assertEquals(content, (result as SkillParseResult.NoFrontmatter).body)
    }

    @Test
    fun `empty body after frontmatter`() {
        val content = "---\nname: test\ndescription: test\n---\n"
        val result = SkillFrontmatterParser.parseDocument(content)

        assertTrue(result is SkillParseResult.Success)
        assertEquals("", (result as SkillParseResult.Success).document.body)
    }

    @Test
    fun `missing name returns error`() {
        val content = "---\ndescription: test\n---\nbody"
        val result = SkillFrontmatterParser.parseDocument(content)

        assertTrue(result is SkillParseResult.Error)
    }

    @Test
    fun `missing description returns error`() {
        val content = "---\nname: test\n---\nbody"
        val result = SkillFrontmatterParser.parseDocument(content)

        assertTrue(result is SkillParseResult.Error)
    }

    @Test
    fun `invalid YAML returns typed error not silent`() {
        val content = "---\nname: test\n: invalid\n---\nbody"
        val result = SkillFrontmatterParser.parseDocument(content)

        assertTrue(result is SkillParseResult.Error)
    }

    @Test
    fun `duplicate keys return error`() {
        val content = "---\nname: first\nname: second\ndescription: test\n---\nbody"
        val result = SkillFrontmatterParser.parseDocument(content)

        assertTrue(result is SkillParseResult.Error)
    }

    @Test
    fun `unknown YAML keys are ignored but do not fail parsing`() {
        val content = "---\nname: test\ndescription: test\nunknown_key: value\n---\nbody"
        val frontmatter = content.parseSuccess().frontmatter

        assertEquals("test", frontmatter.name)
    }

    @Test
    fun `mapping and sequence values are rejected for typed string fields`() {
        val mappingName = "---\nname: { nested: value }\ndescription: test\n---\nbody"
        val sequenceDescription = "---\nname: test\ndescription: [one, two]\n---\nbody"

        assertTrue(SkillFrontmatterParser.parseDocument(mappingName) is SkillParseResult.Error)
        assertTrue(SkillFrontmatterParser.parseDocument(sequenceDescription) is SkillParseResult.Error)
    }

    @Test
    fun `oversized collection is rejected`() {
        val items = (0..1_024).joinToString("\n") { "  - item-$it" }
        val content = "---\nname: test\ndescription: test\nitems:\n$items\n---\nbody"

        assertTrue(SkillFrontmatterParser.parseDocument(content) is SkillParseResult.Error)
    }

    @Test
    fun `alias expansion beyond configured limit is rejected`() {
        val aliases = (0..50).joinToString(", ") { "*item" }
        val content = "---\nname: test\ndescription: test\nbase: &item [one]\naliases: [$aliases]\n---\nbody"

        assertTrue(SkillFrontmatterParser.parseDocument(content) is SkillParseResult.Error)
    }

    @Test
    fun `nesting beyond configured depth is rejected`() {
        val nested = buildString {
            repeat(12) { depth ->
                append("  ".repeat(depth + 1))
                appendLine("level$depth:")
            }
            append("  ".repeat(13))
            append("value: leaf")
        }
        val content = "---\nname: test\ndescription: test\nnested:\n$nested\n---\nbody"

        assertTrue(SkillFrontmatterParser.parseDocument(content) is SkillParseResult.Error)
    }

    @Test
    fun `oversized document is rejected before delimiter scan`() {
        val content = "---\nname: test\ndescription: test\n---\n" + "x".repeat(1_000_001)

        assertTrue(SkillFrontmatterParser.parseDocument(content) is SkillParseResult.Error)
    }

    @Test
    fun `missing closing delimiter returns error`() {
        val content = "---\nname: test\ndescription: test\nbody without closing"
        val result = SkillFrontmatterParser.parseDocument(content)

        assertTrue(result is SkillParseResult.Error)
    }

    @Test
    fun `parse error never exposes the original document as executable body`() {
        val content = "---\nbroken: [unclosed\n---\nbody"
        assertTrue(SkillFrontmatterParser.parseDocument(content) is SkillParseResult.Error)
    }

    @Test
    fun `concurrent parses never share SnakeYAML constructor state`() = runTest {
        val documents = (0 until 200).map { index ->
            async(Dispatchers.Default) {
                val content = "---\nname: skill-$index\ndescription: item $index\n---\nbody-$index"
                content.parseSuccess()
            }
        }.awaitAll()

        documents.forEachIndexed { index, document ->
            assertEquals("skill-$index", document.frontmatter.name)
            assertEquals("body-$index", document.body)
        }
    }

    private fun String.parseSuccess(): SkillDocument =
        (SkillFrontmatterParser.parseDocument(this) as SkillParseResult.Success).document
}
