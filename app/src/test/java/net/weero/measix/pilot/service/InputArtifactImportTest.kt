package net.weero.measix.pilot.service

import android.net.Uri
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InputArtifactImportTest {
    @Test
    fun `a changed input owner releases only the newly imported batch and preserves the original failure`() = runTest {
        for (failure in listOf(IllegalStateException("assistant changed"), CancellationException("page closed"))) {
            val imports = mockk<ArtifactDraftScope>()
            val source = Uri.parse("content://fixture/input")
            val created = Uri.parse("file:///fixture/new-image.png")
            coEvery { imports.importUrisOrThrow(listOf(source)) } returns listOf(ArtifactDraftItem(created, "new-image.png", "image/png"))
            coEvery { imports.discard(created) } returns Unit
            var validations = 0
            try {
                imports.importInputUris(listOf(source)) { if (++validations == 2) throw failure }
                fail("A revoked target must not receive imported files")
            } catch (error: Exception) { assertSame(failure, error) }
            coVerify(exactly = 1) { imports.discard(created) }
            coVerify(exactly = 1) { imports.discard(any()) }
        }
    }

    @Test
    fun `cleanup failure is suppressed on the original target rejection`() = runTest {
        val imports = mockk<ArtifactDraftScope>()
        val source = Uri.parse("content://fixture/input")
        val created = Uri.parse("file:///fixture/new-image.png")
        val rejection = IllegalStateException("assistant changed")
        val cleanup = IllegalStateException("scope already closed")
        coEvery { imports.importUrisOrThrow(listOf(source)) } returns listOf(ArtifactDraftItem(created, "new-image.png", "image/png"))
        coEvery { imports.discard(created) } throws cleanup
        var validations = 0
        try {
            imports.importInputUris(listOf(source)) { if (++validations == 2) throw rejection }
            fail("Expected rejection")
        } catch (error: Exception) { assertSame(rejection, error); assertSame(cleanup, error.suppressed.single()) }
    }

    @Test
    fun `closed owner is rejected before reading a source`() = runTest {
        val imports = mockk<ArtifactDraftScope>()
        val source = Uri.parse("content://fixture/input")
        val failure = IllegalStateException("closed")
        try { imports.importInputUris(listOf(source)) { throw failure }; fail("Expected rejection") }
        catch (error: Exception) { assertSame(failure, error) }
        coVerify(exactly = 0) { imports.importUrisOrThrow(any()) }
    }
}
