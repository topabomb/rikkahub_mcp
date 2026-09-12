package me.rerere.workspace

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RootfsCompatibilityTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `native ELF selects the matching archive and rejects the other guest architecture`() {
        RootfsCompatibility.Architecture.entries.forEach { architecture ->
            val native = tmp.newFolder()
            val root = tmp.newFolder()
            writeTestElf(File(native, ProotLaunchSpec.PROOT_EXEC), architecture.elfMachine)
            writeTestElf(File(root, "usr/bin/env"), architecture.elfMachine)
            writeTestElf(File(root, "bin/bash"), architecture.elfMachine)
            val compatibility = RootfsCompatibility(native) { 4096L }
            assertEquals(architecture, compatibility.architecture)
            val name = if (architecture == RootfsCompatibility.Architecture.ARM64) "arm64" else "amd64"
            assertTrue(compatibility.defaultRootfsUrl.endsWith("24.04.3-base-$name.tar.gz"))
            assertTrue(compatibility.isCompatible(root))
            writeTestElf(File(root, "bin/bash"), if (architecture.elfMachine == 183) 62 else 183)
            assertFalse(compatibility.isCompatible(root))
            assertTrue(assertThrows(IllegalArgumentException::class.java) {
                compatibility.requireCompatible(root)
            }.message!!.contains("architecture mismatch"))
        }
    }

    @Test
    fun `missing or wrong interpreter and malformed ELF fail closed`() {
        val native = tmp.newFolder()
        val root = tmp.newFolder()
        writeTestElf(File(native, ProotLaunchSpec.PROOT_EXEC), 62)
        val env = File(root, "usr/bin/env")
        val bash = File(root, "bin/bash")
        writeTestElf(env, 62, "/lib/ld.so")
        writeTestElf(bash, 62)
        val compatibility = RootfsCompatibility(native) { 4096L }
        assertFalse(compatibility.isCompatible(root))
        val loader = File(root, "lib/ld.so")
        writeTestElf(loader, 183)
        assertFalse(compatibility.isCompatible(root))
        writeTestElf(loader, 62)
        assertTrue(compatibility.isCompatible(root))

        val valid = env.readBytes()
        listOf(
            valid.copyOf(30),
            valid.copyOf().apply { this[4] = 1 },
            valid.copyOf().apply { this[5] = 2 },
            valid.copyOf().apply { ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).putLong(32, Long.MAX_VALUE) },
            valid.copyOf().apply { this[lastIndex] = 1 },
        ).forEach { bytes ->
            env.writeBytes(bytes)
            assertFalse(compatibility.isCompatible(root))
        }
        env.writeBytes(valid)
        File(native, ProotLaunchSpec.PROOT_EXEC).writeText("not ELF")
        assertFalse(compatibility.isCompatible(root))
        assertThrows(IllegalArgumentException::class.java) { compatibility.defaultRootfsUrl }
    }

    @Test
    fun `guest absolute links resolve inside root while escaping and cyclic links fail`() {
        val native = tmp.newFolder()
        val root = tmp.newFolder()
        writeTestElf(File(native, ProotLaunchSpec.PROOT_EXEC), 62)
        writeTestElf(File(root, "usr/bin/env"), 62, "/lib/ld.so")
        writeTestElf(File(root, "usr/bin/bash"), 62)
        writeTestElf(File(root, "usr/lib/ld.so"), 62)
        try {
            Files.createSymbolicLink(File(root, "bin").toPath(), File("/usr/bin").toPath())
        } catch (error: Exception) {
            assumeNoException("Symbolic links require host support", error)
        }
        Files.createSymbolicLink(File(root, "lib").toPath(), File("usr/lib").toPath())
        val compatibility = RootfsCompatibility(native) { 4096L }
        assertTrue(compatibility.isCompatible(root))
        Files.delete(File(root, "lib").toPath())
        Files.createSymbolicLink(File(root, "lib").toPath(), File("../outside").toPath())
        assertFalse(compatibility.isCompatible(root))
        Files.delete(File(root, "lib").toPath())
        Files.createSymbolicLink(File(root, "lib").toPath(), File("/lib").toPath())
        assertFalse(compatibility.isCompatible(root))
    }

    @Test
    fun `guest page alignment follows the host while native ABI lookup stays independent`() {
        RootfsCompatibility.Architecture.entries.forEach { architecture ->
            val native = tmp.newFolder()
            val root = tmp.newFolder()
            writeTestElf(File(native, ProotLaunchSpec.PROOT_EXEC), architecture.elfMachine)
            val noPageLookup = RootfsCompatibility(native) { error("Page size must not be queried") }
            assertEquals(architecture, noPageLookup.architecture)
            assertEquals(architecture.defaultRootfsUrl, noPageLookup.defaultRootfsUrl)
            assertFalse(noPageLookup.isCompatible(root))
            for (alignment in listOf(4096L, 65536L)) {
                writeTestElf(File(root, "usr/bin/env"), architecture.elfMachine, alignment = alignment)
                writeTestElf(File(root, "bin/bash"), architecture.elfMachine, alignment = alignment)
                assertTrue(RootfsCompatibility(native) { 4096L }.isCompatible(root))
                assertEquals(alignment >= 16384, RootfsCompatibility(native) { 16384L }.isCompatible(root))
            }
        }
    }

    @Test
    fun `page checks reject misleading alignment and incompatible interpreters`() {
        val native = tmp.newFolder()
        val root = tmp.newFolder()
        writeTestElf(File(native, ProotLaunchSpec.PROOT_EXEC), 62)
        val env = File(root, "usr/bin/env")
        writeTestElf(env, 62, "/lib/ld.so", alignment = 65536)
        writeTestElf(File(root, "bin/bash"), 62, alignment = 65536)
        val loader = File(root, "lib/ld.so")
        writeTestElf(loader, 62)
        val compatibility = RootfsCompatibility(native) { 16384L }
        val failure = assertThrows(IllegalArgumentException::class.java) { compatibility.requireCompatible(root) }
        assertTrue(failure.message!!.contains("/lib/ld.so"))
        assertTrue(failure.message!!.contains("alignment 4096"))
        assertTrue(failure.message!!.contains("host page size is 16384"))
        writeTestElf(loader, 62, alignment = 65536)
        assertTrue(compatibility.isCompatible(root))
        writeTestElf(env, 62, alignment = 16384)
        env.writeBytes(env.readBytes().apply {
            ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).putLong(80, 4096)
        })
        assertTrue(RootfsCompatibility(native) { 4096L }.isCompatible(root))
        assertFalse(compatibility.isCompatible(root))
        writeTestElf(env, 62, alignment = 24576)
        assertFalse(compatibility.isCompatible(root))
        writeTestElf(env, 62, alignment = 65536)
        listOf(-1L, 0L, 12288L).forEach { invalid ->
            assertFalse(RootfsCompatibility(native) { invalid }.isCompatible(root))
        }
    }

    @Test
    fun `boolean inspection preserves thread cancellation`() {
        val compatibility = RootfsCompatibility(tmp.newFolder()) { 4096L }
        val root = tmp.newFolder()
        Thread.currentThread().interrupt()
        try {
            assertThrows(InterruptedException::class.java) { compatibility.isCompatible(root) }
        } finally {
            Thread.interrupted()
        }
    }
}

internal fun writeTestElf(file: File, machine: Int, interpreter: String? = null, alignment: Long = 4096) {
    val path = interpreter?.let { "$it\u0000".toByteArray() } ?: byteArrayOf()
    val count = if (interpreter == null) 1 else 2
    val size = 64 + count * 56 + path.size
    val bytes = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
    bytes.putInt(0, 0x464c457f)
    bytes.put(4, 2); bytes.put(5, 1); bytes.put(6, 1)
    bytes.putShort(16, 3); bytes.putShort(18, machine.toShort()); bytes.putInt(20, 1)
    bytes.putLong(32, 64); bytes.putShort(52, 64); bytes.putShort(54, 56); bytes.putShort(56, count.toShort())
    bytes.putInt(64, 1); bytes.putLong(72, 0); bytes.putLong(96, size.toLong()); bytes.putLong(104, size.toLong())
    bytes.putLong(112, alignment)
    if (interpreter != null) {
        bytes.putInt(120, 3); bytes.putLong(128, 176); bytes.putLong(152, path.size.toLong())
        bytes.position(176); bytes.put(path)
    }
    file.parentFile.mkdirs()
    file.writeBytes(bytes.array())
}
