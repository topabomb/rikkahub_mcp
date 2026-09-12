package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.OutputStream
import java.util.zip.GZIPOutputStream
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress

class RootfsInstallerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `extract skips OTHER entry data exactly once`() {
        // OTHER 条目 (如 GNU sparse) 带 size>0 数据区, 双重 skip 会让后续 header 错位
        val archive = tmp.newFile("rootfs.tar.gz")
        GZIPOutputStream(archive.outputStream()).use { out ->
            out.writeTarEntry("a.txt", '0', "hello".toByteArray())
            out.writeTarEntry("sparse.bin", 'S', ByteArray(700) { 1 })
            out.writeTarEntry("b.txt", '0', "world".toByteArray())
            out.write(ByteArray(TAR_BLOCK * 2))
        }

        val target = tmp.newFolder("out")
        createInstaller().extractTar(archive, target) {}

        assertEquals("hello", File(target, "a.txt").readText())
        assertEquals("world", File(target, "b.txt").readText())
        assertFalse(File(target, "sparse.bin").exists())
    }

    @Test
    fun `extract handles directories and zero size entries`() {
        val archive = tmp.newFile("rootfs.tar.gz")
        GZIPOutputStream(archive.outputStream()).use { out ->
            out.writeTarEntry("dir/", '5', ByteArray(0))
            out.writeTarEntry("dir/file.txt", '0', "content".toByteArray())
            out.write(ByteArray(TAR_BLOCK * 2))
        }

        val target = tmp.newFolder("out")
        createInstaller().extractTar(archive, target) {}

        assertEquals(true, File(target, "dir").isDirectory)
        assertEquals("content", File(target, "dir/file.txt").readText())
    }

    @Test
    fun `wrong architecture install preserves the existing rootfs before replacement`() {
        val manager = WorkspaceManager(tmp.newFolder())
        val native = tmp.newFolder()
        writeTestElf(File(native, ProotLaunchSpec.PROOT_EXEC), 62)
        val root = "original"
        manager.ensureWorkspace(root)
        val original = File(manager.linuxDir(root), "keep.txt").apply { writeText("original rootfs") }
        writeTestElf(File(manager.linuxDir(root), "usr/bin/env"), 62)
        writeTestElf(File(manager.linuxDir(root), "bin/bash"), 62)
        val wrongElf = tmp.newFile().also { writeTestElf(it, 183) }.readBytes()
        val archive = java.io.ByteArrayOutputStream().also { buffer ->
            GZIPOutputStream(buffer).use { output ->
                output.writeTarEntry("usr/bin/env", '0', wrongElf)
                output.writeTarEntry("bin/bash", '0', wrongElf)
                output.write(ByteArray(TAR_BLOCK * 2))
            }
        }.toByteArray()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/rootfs.tar.gz") { exchange ->
            exchange.sendResponseHeaders(200, archive.size.toLong())
            exchange.responseBody.use { it.write(archive) }
        }
        server.start()
        try {
            val installer = RootfsInstaller(manager, native)
            val compatibility = RootfsCompatibility(native) { 4096L }
            assertTrue(compatibility.isCompatible(manager.linuxDir(root)))
            val stages = mutableListOf<RootfsInstallStage>()
            assertThrows(IllegalArgumentException::class.java) {
                installer.install(root, "http://127.0.0.1:${server.address.port}/rootfs.tar.gz") { stages += it.stage }
            }
            assertEquals("original rootfs", original.readText())
            assertTrue(compatibility.isCompatible(manager.linuxDir(root)))
            assertFalse(RootfsInstallStage.INSTALLED in stages)
            assertFalse(File(manager.tempDir(root), "rootfs-staging").exists())
        } finally {
            server.stop(0)
        }
    }

    private fun createInstaller() = RootfsInstaller(WorkspaceManager(tmp.newFolder()), tmp.newFolder())

    private fun OutputStream.writeTarEntry(name: String, type: Char, data: ByteArray) {
        val header = ByteArray(TAR_BLOCK)
        name.toByteArray(Charsets.UTF_8).copyInto(header, 0)
        "0000755".toByteArray().copyInto(header, 100)
        data.size.toLong().toOctalField().copyInto(header, 124)
        header[156] = type.code.toByte()
        write(header)
        write(data)
        val padding = (TAR_BLOCK - data.size % TAR_BLOCK) % TAR_BLOCK
        write(ByteArray(padding))
    }

    private fun Long.toOctalField(): ByteArray =
        toString(8).padStart(11, '0').toByteArray(Charsets.UTF_8)

    companion object {
        private const val TAR_BLOCK = 512
    }
}
