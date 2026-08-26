package net.weero.measix.pilot.ui.pages.extensions.skills

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import io.mockk.every
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.joinAll
import net.weero.measix.pilot.data.files.SkillManager
import net.weero.measix.pilot.data.files.SkillBundleImportResult
import net.weero.measix.pilot.data.files.SkillMetadata
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SkillsVMImportTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `markdown import uses typed name and the single-skill atomic owner`() = runTest(dispatcher) {
        val content = "---\nname: markdown-skill\ndescription: typed\n---\nbody"
        val manager = mockk<SkillManager>()
        every { manager.listSkills() } returns emptyList()
        coEvery { manager.importSkill("markdown-skill", content) } returns metadata("markdown-skill")
        val (context, uri) = fileInput("SKILL.md", content.toByteArray())
        val result = CompletableDeferred<SkillImportOutcome>()

        SkillsVM(manager, dispatcher).importSkillFromFile(context, uri, result::complete)

        assertEquals(SkillImportOutcome.Success("markdown-skill"), withTimeout(5_000) { result.await() })
        coVerify(exactly = 1) { manager.importSkill("markdown-skill", content) }
    }

    @Test
    fun `zip import publishes one validated bundle transaction`() = runTest(dispatcher) {
        val manager = mockk<SkillManager>()
        every { manager.listSkills() } returns emptyList()
        coEvery { manager.importSkillBundleAtomically(any()) } returns SkillBundleImportResult.SUCCESS
        val bytes = zip(
            "bundle/SKILL.md" to "---\nname: zip-skill\ndescription: typed\n---\nbody".toByteArray(),
            "bundle/notes/info.txt" to "asset".toByteArray(),
        )
        val (context, uri) = fileInput("skills.zip", bytes)
        val result = CompletableDeferred<SkillImportOutcome>()

        SkillsVM(manager, dispatcher).importSkillFromFile(context, uri, result::complete)

        assertEquals(SkillImportOutcome.Success("zip-skill"), withTimeout(5_000) { result.await() })
        coVerify(exactly = 1) {
            manager.importSkillBundleAtomically(
                match { entries ->
                    entries.size == 1 &&
                        entries.single().name == "zip-skill" &&
                        entries.single().files.keys == setOf("SKILL.md", "notes/info.txt") &&
                        entries.single().files.getValue("notes/info.txt").contentEquals("asset".toByteArray())
                },
            )
        }
    }

    @Test
    fun `invalid frontmatter fails closed before any save`() = runTest(dispatcher) {
        val manager = mockk<SkillManager>()
        every { manager.listSkills() } returns emptyList()
        val (context, uri) = fileInput(
            "SKILL.md",
            "---\nname: bad\ndescription: [not, text]\n---\nbody".toByteArray(),
        )
        val result = CompletableDeferred<SkillImportOutcome>()

        SkillsVM(manager, dispatcher).importSkillFromFile(context, uri, result::complete)

        assertEquals(
            SkillImportOutcome.Failure(SkillImportFailure.INVALID_SKILL),
            withTimeout(5_000) { result.await() },
        )
        coVerify(exactly = 0) { manager.importSkill(any(), any()) }
        coVerify(exactly = 0) { manager.importSkillFileBytesAtomically(any(), any()) }
        coVerify(exactly = 0) { manager.importSkillBundleAtomically(any()) }
    }

    @Test
    fun `invalid second skill rejects the zip before bundle publication`() = runTest(dispatcher) {
        val manager = mockk<SkillManager>()
        every { manager.listSkills() } returns emptyList()
        val bytes = zip(
            "first/SKILL.md" to "---\nname: first\ndescription: valid\n---\nbody".toByteArray(),
            "second/SKILL.md" to "---\nname: second\ndescription: [invalid]\n---\nbody".toByteArray(),
        )
        val (context, uri) = fileInput("skills.zip", bytes)
        val result = CompletableDeferred<SkillImportOutcome>()

        SkillsVM(manager, dispatcher).importSkillFromFile(context, uri, result::complete)

        assertEquals(
            SkillImportOutcome.Failure(SkillImportFailure.INVALID_SKILL),
            withTimeout(5_000) { result.await() },
        )
        coVerify(exactly = 0) { manager.importSkillBundleAtomically(any()) }
    }

    @Test
    fun `oversized markdown is rejected before parsing or publication`() = runTest(dispatcher) {
        val manager = mockk<SkillManager>()
        every { manager.listSkills() } returns emptyList()
        val (context, uri) = fileInput("SKILL.md", ByteArray(4 * 1024 * 1024 + 1) { 'a'.code.toByte() })
        val result = CompletableDeferred<SkillImportOutcome>()

        SkillsVM(manager, dispatcher).importSkillFromFile(context, uri, result::complete)

        assertEquals(
            SkillImportOutcome.Failure(SkillImportFailure.RESOURCE_LIMIT),
            withTimeout(5_000) { result.await() },
        )
        coVerify(exactly = 0) { manager.importSkill(any(), any()) }
        coVerify(exactly = 0) { manager.importSkillBundleAtomically(any()) }
    }

    @Test
    fun `oversized zip entry is rejected while streaming decompression`() = runTest(dispatcher) {
        val manager = mockk<SkillManager>()
        every { manager.listSkills() } returns emptyList()
        val bytes = zip(
            "bundle/SKILL.md" to "---\nname: zip-skill\ndescription: valid\n---\nbody".toByteArray(),
            "bundle/large.bin" to ByteArray(4 * 1024 * 1024 + 1),
        )
        val (context, uri) = fileInput("skills.zip", bytes)
        val result = CompletableDeferred<SkillImportOutcome>()

        SkillsVM(manager, dispatcher).importSkillFromFile(context, uri, result::complete)

        assertEquals(
            SkillImportOutcome.Failure(SkillImportFailure.RESOURCE_LIMIT),
            withTimeout(5_000) { result.await() },
        )
        coVerify(exactly = 0) { manager.importSkillBundleAtomically(any()) }
    }

    @Test
    fun `duplicate skill names reject the complete bundle`() = runTest(dispatcher) {
        val manager = mockk<SkillManager>()
        every { manager.listSkills() } returns emptyList()
        val bytes = zip(
            "first/SKILL.md" to "---\nname: duplicate\ndescription: first\n---\nbody".toByteArray(),
            "second/SKILL.md" to "---\nname: duplicate\ndescription: second\n---\nbody".toByteArray(),
        )
        val (context, uri) = fileInput("skills.zip", bytes)
        val result = CompletableDeferred<SkillImportOutcome>()

        SkillsVM(manager, dispatcher).importSkillFromFile(context, uri, result::complete)

        assertEquals(
            SkillImportOutcome.Failure(SkillImportFailure.INVALID_SKILL),
            withTimeout(5_000) { result.await() },
        )
        coVerify(exactly = 0) { manager.importSkillBundleAtomically(any()) }
    }

    @Test
    fun `GitHub import parses the root skill and atomically publishes every listed file`() = runTest(dispatcher) {
        val manager = mockk<SkillManager>()
        val source = mockk<SkillGitHubSource>()
        every { manager.listSkills() } returns emptyList()
        lateinit var published: Map<String, ByteArray>
        coEvery { manager.importSkillFileBytesAtomically("github-skill", any()) } coAnswers {
            published = secondArg()
            true
        }
        coEvery { source.listFiles("owner", "repo", "main", "skills/example") } returns listOf(
            SkillGitHubFile("SKILL.md", "skill-url"),
            SkillGitHubFile("references/info.md", "reference-url"),
        )
        val skillDocument = "---\nname: github-skill\ndescription: typed\n---\nbody".toByteArray()
        val binaryReference = byteArrayOf(0x00, 0xFF.toByte(), 0xC3.toByte(), 0x28)
        coEvery { source.downloadBytes("skill-url") } returns skillDocument
        coEvery { source.downloadBytes("reference-url") } returns binaryReference
        val result = CompletableDeferred<SkillImportOutcome>()

        SkillsVM(manager, dispatcher, source).importSkillFromGitHub(
            "https://github.com/owner/repo/tree/main/skills/example",
            result::complete,
        )

        assertEquals(SkillImportOutcome.Success("github-skill"), withTimeout(5_000) { result.await() })
        coVerify(exactly = 1) { manager.importSkillFileBytesAtomically("github-skill", any()) }
        assertArrayEquals(skillDocument, published.getValue("SKILL.md"))
        assertArrayEquals(binaryReference, published.getValue("references/info.md"))
    }

    @Test
    fun `cancelling GitHub import before commit publishes nothing`() = runTest(dispatcher) {
        val manager = mockk<SkillManager>()
        val source = mockk<SkillGitHubSource>()
        val secondDownloadStarted = CompletableDeferred<Unit>()
        val releaseSecondDownload = CompletableDeferred<Unit>()
        every { manager.listSkills() } returns emptyList()
        coEvery { source.listFiles(any(), any(), any(), any()) } returns listOf(
            SkillGitHubFile("SKILL.md", "skill-url"),
            SkillGitHubFile("references/info.md", "reference-url"),
        )
        coEvery { source.downloadBytes("skill-url") } returns
            "---\nname: cancelled-skill\ndescription: typed\n---\nbody".toByteArray()
        coEvery { source.downloadBytes("reference-url") } coAnswers {
            secondDownloadStarted.complete(Unit)
            releaseSecondDownload.await()
            "reference".toByteArray()
        }

        val job = SkillsVM(manager, dispatcher, source).importSkillFromGitHub(
            "https://github.com/owner/repo/tree/main/skills/example",
        ) { error("cancelled import must not publish a UI result") }
        secondDownloadStarted.await()
        job.cancel()
        releaseSecondDownload.complete(Unit)
        joinAll(job)

        coVerify(exactly = 0) { manager.importSkillFileBytesAtomically(any(), any()) }
        coVerify(exactly = 0) { manager.importSkill(any(), any()) }
    }

    private fun fileInput(fileName: String, bytes: ByteArray): Pair<Context, Uri> {
        val resolver = mockk<ContentResolver>()
        val context = mockk<Context>()
        val uri = mockk<Uri>()
        every { context.applicationContext } returns context
        every { context.contentResolver } returns resolver
        every { uri.scheme } returns "file"
        every { uri.path } returns File("/tmp", fileName).path
        every { resolver.openInputStream(uri) } returns ByteArrayInputStream(bytes)
        return context to uri
    }

    private fun metadata(name: String) = SkillMetadata(name = name, description = "typed")

    private fun zip(vararg files: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().use { output ->
        ZipOutputStream(output).use { zip ->
            files.forEach { (path, content) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(content)
                zip.closeEntry()
            }
        }
        output.toByteArray()
    }
}
