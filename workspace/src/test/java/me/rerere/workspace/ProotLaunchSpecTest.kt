package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProotLaunchSpecTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `app bind mounts cover skills tool outputs and upload`() {
        val appFilesDir = tmp.newFolder("app-files")
        val mounts = ProotLaunchSpec.appBindMounts(appFilesDir)

        assertEquals(
            listOf(ProotLaunchSpec.SKILLS_DIR, ProotLaunchSpec.TOOL_OUTPUTS_DIR, ProotLaunchSpec.UPLOAD_DIR),
            mounts.map { it.target },
        )
        mounts.forEach { mount ->
            assertTrue("${mount.target} source must be created", mount.source.isDirectory)
        }
    }

    @Test
    fun `shell and interactive terminal share executable binds cwd and process environment`() {
        val nativeDir = tmp.newFolder("native")
        File(nativeDir, ProotLaunchSpec.PROOT_EXEC).writeText("fake")
        File(nativeDir, ProotLaunchSpec.PROOT_LOADER).writeText("fake")
        val filesDir = tmp.newFolder("files")
        val linuxDir = tmp.newFolder("linux")
        val tempDir = tmp.newFolder("tmp")
        val skills = tmp.newFolder("skills")
        val upload = tmp.newFolder("upload")
        val bindMounts = listOf(
            WorkspaceBindMount(skills, ProotLaunchSpec.SKILLS_DIR),
            WorkspaceBindMount(upload, ProotLaunchSpec.UPLOAD_DIR),
        )

        val shell = ProotLaunchSpec.from(
            nativeLibraryDir = nativeDir,
            filesDir = filesDir,
            linuxDir = linuxDir,
            tempDir = tempDir,
            bindMounts = bindMounts,
            guestCwd = "src",
            command = "ls",
        )
        val terminal = ProotLaunchSpec.from(
            nativeLibraryDir = nativeDir,
            filesDir = filesDir,
            linuxDir = linuxDir,
            tempDir = tempDir,
            bindMounts = bindMounts,
        )

        assertEquals(shell.executable, terminal.executable)
        assertEquals(shell.loader, terminal.loader)
        assertEquals(shell.environment, terminal.environment)
        assertEquals(shell.workingDirectory, terminal.workingDirectory)
        assertEquals(
            listOf(
                "${filesDir.absolutePath}:${ProotLaunchSpec.WORKSPACE_DIR}",
                "${skills.absolutePath}:${ProotLaunchSpec.SKILLS_DIR}",
                "${upload.absolutePath}:${ProotLaunchSpec.UPLOAD_DIR}",
            ),
            bindSpecs(shell).take(3),
        )
        assertEquals(bindSpecs(shell), bindSpecs(terminal))
        assertEquals("${ProotLaunchSpec.WORKSPACE_DIR}/src", cwdOf(shell))
        assertEquals(ProotLaunchSpec.WORKSPACE_DIR, cwdOf(terminal))
        assertTrue(shell.arguments.contains("USER=root"))
        assertTrue(terminal.arguments.contains("USER=root"))
        assertTrue(shell.arguments.contains("SHELL=/bin/bash"))
        assertTrue(terminal.arguments.contains("SHELL=/bin/bash"))
        assertTrue(shell.arguments.contains("ls"))
        assertFalse(terminal.arguments.contains("ls"))
        assertEquals("/bin/bash", terminal.arguments.last())
        assertFalse(shell.environment.containsKey("PROOT_NO_SECCOMP"))
    }

    private fun bindSpecs(spec: ProotLaunchSpec): List<String> =
        spec.arguments.mapIndexedNotNull { index, argument ->
            if (argument == "-b") spec.arguments.getOrNull(index + 1) else null
        }

    private fun cwdOf(spec: ProotLaunchSpec): String =
        spec.arguments[spec.arguments.indexOf("-w") + 1]
}
