package me.rerere.workspace

import java.io.File
import java.security.MessageDigest
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProotArtifactContractTest {
    @Test
    fun `manifest pins and verifies every shipped exec and loader artifact`() {
        val root = locateRepositoryRoot()
        val manifest = Json.parseToJsonElement(File(root, "workspace/proot-lock.json").readText()).jsonObject
        assertEquals("v5.1.107.92", manifest.getValue("proot").jsonObject.getValue("tag").jsonPrimitive.content)
        assertEquals("r29", manifest.getValue("build").jsonObject.getValue("ndk").jsonPrimitive.content)
        assertEquals("24", manifest.getValue("build").jsonObject.getValue("api").jsonPrimitive.content)
        assertEquals("7266fb3e8516535682f5a9c8f3a7e70f6506eddb", manifest.getValue("proot").jsonObject.getValue("commit").jsonPrimitive.content)
        assertEquals("29385d1ddb619a9c4449ab512bfd55032034b22f724ddf98fc95ff300ea32135", manifest.getValue("proot").jsonObject.getValue("sourceZipSha256").jsonPrimitive.content)
        assertEquals("f4508dfac2255cf83e75859a8fe37dd7da6778a3", manifest.getValue("proot").jsonObject.getValue("upstreamBinaryCommit").jsonPrimitive.content)
        assertEquals("byte-identical", manifest.getValue("proot").jsonObject.getValue("upstreamBinaryMatch").jsonPrimitive.content)
        assertEquals("08b49b3ce00b1e14a3a0365200f30e50f8dfafe1", manifest.getValue("termuxPackage").jsonObject.getValue("recipeCommit").jsonPrimitive.content)
        assertEquals("not-run", manifest.getValue("build").jsonObject.getValue("localRebuildStatus").jsonPrimitive.content)
        assertEquals("fail-if-output-differs-from-recorded-artifacts", manifest.getValue("build").jsonObject.getValue("hashPolicy").jsonPrimitive.content)
        assertEquals("1e5ff8459bc0a8c229dd8a94b27d119987e09ef3414331c2b5ebfff20b98e867", manifest.getValue("dependencies").jsonObject.getValue("libandroidShmem").jsonObject.getValue("sha256").jsonPrimitive.content)
        assertEquals("dc46c40b9f46bb34dd97fe41f548b0e8b247b77a918576733c528e83abd854dd", manifest.getValue("dependencies").jsonObject.getValue("libtalloc").jsonObject.getValue("sha256").jsonPrimitive.content)

        val artifacts = manifest.getValue("artifacts").jsonArray
        assertEquals(4, artifacts.size)
        artifacts.forEach { item ->
            val artifact = item.jsonObject
            val file = File(root, artifact.getValue("repoPath").jsonPrimitive.content)
            assertTrue("missing ${file.path}", file.isFile)
            assertEquals(artifact.getValue("sha256").jsonPrimitive.content, file.sha256())
            val bytes = file.readBytes()
            assertArrayEquals(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()), bytes.copyOf(4))
            assertEquals(2, bytes[4].toInt()) // ELFCLASS64
            val expectedMachine = when (artifact.getValue("elfMachine").jsonPrimitive.content) {
                "AArch64" -> 183
                "x86-64" -> 62
                else -> error("unexpected machine")
            }
            val machine = (bytes[18].toInt() and 0xff) or ((bytes[19].toInt() and 0xff) shl 8)
            assertEquals(expectedMachine, machine)
            val elf = Elf64(bytes)
            artifact["androidApi"]?.jsonPrimitive?.content?.toInt()?.let { androidApi ->
                assertEquals(androidApi, elf.androidApi())
            }
            val expectedNeeded = artifact.getValue("dtNeeded").jsonArray.map { it.jsonPrimitive.content }
            assertEquals("unexpected DT_NEEDED for ${file.path}", expectedNeeded, elf.neededLibraries())
            artifact["interpreter"]?.takeUnless { it.toString() == "null" }?.jsonPrimitive?.content?.let { interpreter ->
                assertEquals(interpreter, elf.interpreter())
            }
            if (file.name == "libproot_exec.so") {
                val ascii = bytes.toString(Charsets.ISO_8859_1)
                assertTrue("missing embedded PRoot version", ascii.contains("5.1.107.92"))
                assertTrue("missing SysV shared-memory implementation", ascii.contains("libandroid_shmget"))
            } else {
                assertEquals("loader must remain platform-independent", emptyList<String>(), elf.neededLibraries())
            }
        }
        artifacts.groupBy { it.jsonObject.getValue("abi").jsonPrimitive.content }.forEach { (abi, pair) ->
            assertEquals("exec/loader pair is incomplete for $abi", 2, pair.size)
            assertEquals(
                setOf("libproot_exec.so", "libproot_loader.so"),
                pair.map { File(it.jsonObject.getValue("repoPath").jsonPrimitive.content).name }.toSet(),
            )
        }
    }

    @Test
    fun `rebuild script is pinned and rejects artifact drift`() {
        val scriptFile = File(locateRepositoryRoot(), "workspace/tools/build-proot.sh")
        val scriptBytes = scriptFile.readBytes()
        assertTrue("POSIX rebuild script must use LF without carriage returns", scriptBytes.none { it == '\r'.code.toByte() })
        val script = scriptBytes.toString(Charsets.UTF_8)
        assertTrue(script.startsWith("#!/usr/bin/env bash\n"))
        assertTrue(script.contains("08b49b3ce00b1e14a3a0365200f30e50f8dfafe1"))
        assertTrue(script.contains("TERMUX_NDK_VERSION_NUM=29"))
        assertTrue(script.contains("TERMUX_PKG_API_LEVEL=24"))
        assertTrue(script.contains("TERMUX_PKG_VERSION=\"5.1.107.92\""))
        assertTrue(script.contains("sha256sum --check --status"))
        assertTrue(script.contains("build-package.sh"))
        assertTrue(script.contains("mktemp -d \"\$work_parent/proot-build.XXXXXXXX\""))
        assertTrue(script.contains("work_dir_owned=true"))
        assertTrue(script.contains("\"\$work_dir_owned\" == \"true\""))
        assertTrue(!script.contains("work_dir=\"\${PROOT_BUILD_WORK_DIR:-"))
    }

    @Test
    fun `distribution notice records binary provenance and complete shmem notice`() {
        val notice = File(locateRepositoryRoot(), "THIRD_PARTY_NOTICES.md").readText()
        assertTrue(notice.contains("f4508dfac2255cf83e75859a8fe37dd7da6778a3"))
        assertTrue(notice.contains("Copyright (c) 2013, Sergii Pylypenko"))
        assertTrue(notice.contains("Copyright (c) 2017, Fredrik Fornwall"))
        assertTrue(notice.contains("Redistributions in binary form must reproduce"))
        assertTrue(notice.contains("Neither the name of the {organization}"))
    }

    private fun locateRepositoryRoot(): File = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile && File(it, "workspace/proot-lock.json").isFile }

    private fun File.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(readBytes())
        .joinToString("") { "%02x".format(it) }

    private class Elf64(private val bytes: ByteArray) {
        private val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        private val segments: List<Segment> by lazy {
            val offset = buffer.getLong(32).toInt()
            val size = buffer.getShort(54).toInt() and 0xffff
            val count = buffer.getShort(56).toInt() and 0xffff
            (0 until count).map { index ->
                val base = offset + index * size
                Segment(
                    type = buffer.getInt(base),
                    offset = buffer.getLong(base + 8),
                    virtualAddress = buffer.getLong(base + 16),
                    fileSize = buffer.getLong(base + 32),
                    memorySize = buffer.getLong(base + 40),
                )
            }
        }

        fun interpreter(): String? = segments.firstOrNull { it.type == PT_INTERP }
            ?.let { readCString(it.offset.toInt()) }

        fun androidApi(): Int {
            val sectionOffset = buffer.getLong(40).toInt()
            val sectionSize = buffer.getShort(58).toInt() and 0xffff
            val sectionCount = buffer.getShort(60).toInt() and 0xffff
            val stringSectionIndex = buffer.getShort(62).toInt() and 0xffff
            val stringSection = section(sectionOffset, sectionSize, stringSectionIndex)
            val note = (0 until sectionCount).map { section(sectionOffset, sectionSize, it) }
                .first { readCString((stringSection.offset + it.nameOffset).toInt()) == ".note.android.ident" }
            val noteOffset = note.offset.toInt()
            val nameSize = buffer.getInt(noteOffset)
            val descriptorOffset = noteOffset + 12 + align4(nameSize)
            return buffer.getInt(descriptorOffset)
        }

        fun neededLibraries(): List<String> {
            val dynamic = segments.firstOrNull { it.type == PT_DYNAMIC } ?: return emptyList()
            val needed = mutableListOf<Long>()
            var stringTableAddress: Long? = null
            var cursor = dynamic.offset.toInt()
            val end = (dynamic.offset + dynamic.fileSize).toInt()
            while (cursor + 16 <= end) {
                val tag = buffer.getLong(cursor)
                val value = buffer.getLong(cursor + 8)
                if (tag == DT_NULL) break
                if (tag == DT_NEEDED) needed += value
                if (tag == DT_STRTAB) stringTableAddress = value
                cursor += 16
            }
            val stringTableOffset = virtualToFileOffset(requireNotNull(stringTableAddress))
            return needed.map { readCString((stringTableOffset + it).toInt()) }
        }

        private fun virtualToFileOffset(address: Long): Long {
            val load = segments.firstOrNull {
                it.type == PT_LOAD && address >= it.virtualAddress && address < it.virtualAddress + it.memorySize
            } ?: error("ELF virtual address is outside PT_LOAD: $address")
            return load.offset + address - load.virtualAddress
        }

        private fun readCString(offset: Int): String {
            var end = offset
            while (end < bytes.size && bytes[end] != 0.toByte()) end++
            return bytes.copyOfRange(offset, end).toString(Charsets.UTF_8)
        }

        private fun section(tableOffset: Int, entrySize: Int, index: Int): Section {
            val base = tableOffset + entrySize * index
            return Section(
                nameOffset = buffer.getInt(base).toLong() and 0xffff_ffffL,
                offset = buffer.getLong(base + 24),
            )
        }

        private fun align4(value: Int): Int = (value + 3) and -4

        private data class Segment(
            val type: Int,
            val offset: Long,
            val virtualAddress: Long,
            val fileSize: Long,
            val memorySize: Long,
        )

        private data class Section(val nameOffset: Long, val offset: Long)

        private companion object {
            const val PT_LOAD = 1
            const val PT_DYNAMIC = 2
            const val PT_INTERP = 3
            const val DT_NULL = 0L
            const val DT_NEEDED = 1L
            const val DT_STRTAB = 5L
        }
    }
}
