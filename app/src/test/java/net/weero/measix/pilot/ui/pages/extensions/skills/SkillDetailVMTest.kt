package net.weero.measix.pilot.ui.pages.extensions.skills

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import net.weero.measix.pilot.data.files.*
import org.junit.Assert.*
import org.junit.Test

class SkillDetailVMTest {
    @Test fun `read failure keeps the original diagnostic result`() = runTest {
        val manager = mockk<SkillManager>()
        val error = java.io.IOException("permission detail", IllegalStateException("underlying cause"))
        every { manager.readSkillContent("captured", "notes.md") } returns SkillContentReadResult.ReadFailure(error)
        val result = SkillDetailVM(manager).readFile("captured", SkillFile("notes.md", "notes.md", 1))
        assertSame(error, (result as SkillContentReadResult.ReadFailure).cause)
    }

    @Test fun `save targets the captured skill and cancellation is not a generic failure`() = runTest {
        val manager = mockk<SkillManager>()
        coEvery { manager.saveSkillFile("captured", "notes.md", "draft") } throws CancellationException("cancel save")
        val failure = runCatching { SkillDetailVM(manager).saveFile("captured", "notes.md", "draft") }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        coVerify(exactly = 1) { manager.saveSkillFile("captured", "notes.md", "draft") }
        io.mockk.verify(exactly = 0) { manager.listSkillFiles(any()) }
    }
}
