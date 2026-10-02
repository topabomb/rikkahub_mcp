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
    fun `shared app mounts exclude private uploads`() {
        val appFilesDir = tmp.newFolder("app-files")
        val mounts = ProotLaunchSpec.appBindMounts(appFilesDir)

        assertEquals(
            listOf(ProotLaunchSpec.SKILLS_DIR),
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
        assertEquals(
            mapOf(
                "PROOT_LOADER" to shell.loader.absolutePath,
                "PROOT_TMP_DIR" to tempDir.absolutePath,
                "TMPDIR" to tempDir.absolutePath,
            ),
            shell.environment,
        )
        for (spec in listOf(shell, terminal)) {
            val arguments = spec.arguments
            assertTrue(arguments.containsAll(listOf("--root-id", "--link2symlink", "--kill-on-exit")))
            assertEquals("4.14.0", arguments[arguments.indexOf("-k") + 1])
            assertEquals(linuxDir.absolutePath, arguments[arguments.indexOf("-r") + 1])
            assertEquals("PWD=${cwdOf(spec)}", arguments.single { it.startsWith("PWD=") })
            assertEquals("-i", arguments[arguments.indexOf("/usr/bin/env") + 1])
            assertTrue(arguments.containsAll(listOf("LANG=C.UTF-8", "LC_ALL=C.UTF-8")))
            assertEquals(
                "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                arguments.single { it.startsWith("PATH=") },
            )
        }
        assertEquals(listOf("/bin/bash", "-c", "eval \"\$1\"", "MeasixPilot", "ls"), shell.arguments.takeLast(5))
    }

    @Test
    fun `non interactive commands declare a non interactive guest environment`() {
        val nativeDir = tmp.newFolder("native")
        File(nativeDir, ProotLaunchSpec.PROOT_EXEC).writeText("fake")
        File(nativeDir, ProotLaunchSpec.PROOT_LOADER).writeText("fake")

        val shell = ProotLaunchSpec.from(
            nativeLibraryDir = nativeDir,
            filesDir = tmp.newFolder("shell-files"),
            linuxDir = tmp.newFolder("shell-linux"),
            tempDir = tmp.newFolder("shell-tmp"),
            bindMounts = emptyList(),
            command = "git status",
        )
        val terminal = ProotLaunchSpec.from(
            nativeLibraryDir = nativeDir,
            filesDir = tmp.newFolder("terminal-files"),
            linuxDir = tmp.newFolder("terminal-linux"),
            tempDir = tmp.newFolder("terminal-tmp"),
            bindMounts = emptyList(),
        )

        // 非交互执行没有可回退的终端，必须显式告知子进程
        assertTrue(shell.arguments.contains("CI=1"))
        assertTrue(shell.arguments.contains("NO_COLOR=1"))
        assertTrue(shell.arguments.contains("PAGER=cat"))
        // 交互式 terminal 保持原环境，用户仍可自行设置分页与颜色
        assertFalse(terminal.arguments.contains("CI=1"))
        assertFalse(terminal.arguments.contains("NO_COLOR=1"))
        assertFalse(terminal.arguments.contains("PAGER=cat"))
        // 非交互开关只出现在 env -i 白名单之后、命令之前
        val envIndex = shell.arguments.indexOf("-i")
        val commandIndex = shell.arguments.indexOf("git status")
        listOf("CI=1", "NO_COLOR=1", "PAGER=cat").forEach { flag ->
            val index = shell.arguments.indexOf(flag)
            assertTrue("$flag must follow env -i", index > envIndex)
            assertTrue("$flag must precede the command", index < commandIndex)
        }
    }

    private fun bindSpecs(spec: ProotLaunchSpec): List<String> =
        spec.arguments.mapIndexedNotNull { index, argument ->
            if (argument == "-b") spec.arguments.getOrNull(index + 1) else null
        }

    private fun cwdOf(spec: ProotLaunchSpec): String =
        spec.arguments[spec.arguments.indexOf("-w") + 1]
}
