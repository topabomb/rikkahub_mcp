package me.rerere.workspace

import android.system.Os
import android.system.OsConstants
import android.system.ErrnoException
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

/** Native PRoot and its guest executables must use the same instruction set; PRoot does not emulate a CPU. */
class RootfsCompatibility(
    private val nativeLibraryDir: File,
    private val pageSize: () -> Long = { Os.sysconf(OsConstants._SC_PAGESIZE) },
) {
    enum class Architecture(val elfMachine: Int, private val ubuntuName: String) {
        ARM64(183, "arm64"),
        X86_64(62, "amd64");

        val defaultRootfsUrl: String
            get() = "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.3-base-$ubuntuName.tar.gz"
    }

    val architecture: Architecture
        get() = readElf(File(nativeLibraryDir, ProotLaunchSpec.PROOT_EXEC)).architecture

    val defaultRootfsUrl: String get() = architecture.defaultRootfsUrl

    fun requireCompatible(linuxDir: File) {
        checkInterrupted()
        require(linuxDir.isDirectory) { "Rootfs is not installed" }
        val expected = architecture
        val guests = mutableListOf<Pair<String, Elf>>()
        for (path in listOf("/usr/bin/env", "/bin/bash")) {
            val executable = readElf(resolveGuestPath(linuxDir, path))
            require(executable.architecture == expected) {
                "Rootfs architecture mismatch: $path is ${executable.architecture}, PRoot is $expected"
            }
            guests += path to executable
            executable.interpreter?.let { path ->
                val interpreter = readElf(resolveGuestPath(linuxDir, path))
                require(interpreter.architecture == expected) {
                    "Rootfs interpreter architecture mismatch: $path is ${interpreter.architecture}, PRoot is $expected"
                }
                require(interpreter.interpreter == null) { "Rootfs interpreter cannot require another interpreter: $path" }
                guests += path to interpreter
            }
        }
        checkInterrupted()
        val hostPageSize = try {
            pageSize()
        } catch (error: ErrnoException) {
            throw IOException("Unable to determine host page size", error)
        }
        checkInterrupted()
        require(hostPageSize > 0 && (hostPageSize and (hostPageSize - 1)) == 0L) {
            "Invalid host page size: $hostPageSize"
        }
        guests.forEach { (path, elf) ->
            elf.loadSegments.forEach { segment ->
                require(segment.alignment >= hostPageSize &&
                    (segment.alignment and (segment.alignment - 1)) == 0L &&
                    segment.virtualAddress >= 0 &&
                    segment.virtualAddress % hostPageSize == segment.offset % hostPageSize) {
                    "Rootfs ELF alignment incompatible: $path has alignment ${segment.alignment}, " +
                        "offset ${segment.offset}, address ${segment.virtualAddress}; host page size is $hostPageSize"
                }
            }
        }
    }

    fun isCompatible(linuxDir: File): Boolean = try {
        requireCompatible(linuxDir)
        true
    } catch (_: IOException) {
        checkInterrupted()
        false
    } catch (_: IllegalArgumentException) {
        checkInterrupted()
        false
    } catch (_: SecurityException) {
        checkInterrupted()
        false
    }

    /** Absolute links are guest paths, never paths in the Android host filesystem. */
    private fun resolveGuestPath(root: File, path: String): File {
        require(path.startsWith('/') && '\u0000' !in path && '\\' !in path) { "Invalid Rootfs executable path: $path" }
        val base = root.toPath().toAbsolutePath().normalize()
        val pending = ArrayDeque(path.split('/'))
        val resolved = mutableListOf<String>()
        var links = 0
        while (pending.isNotEmpty()) {
            checkInterrupted()
            when (val name = pending.removeFirst()) {
                "", "." -> Unit
                ".." -> {
                    require(resolved.isNotEmpty()) { "Rootfs link escapes guest root: $path" }
                    resolved.removeAt(resolved.lastIndex)
                }
                else -> {
                    val candidate = resolved.fold(base) { parent, part -> parent.resolve(part) }.resolve(name)
                    require(candidate.startsWith(base)) { "Rootfs link escapes guest root: $path" }
                    if (Files.isSymbolicLink(candidate)) {
                        require(++links <= 40) { "Too many Rootfs symbolic links: $path" }
                        val target = Files.readSymbolicLink(candidate).toString().let {
                            if (File.separatorChar == '\\') it.replace('\\', '/') else it
                        }
                        require('\\' !in target && '\u0000' !in target) { "Invalid Rootfs symbolic link: $path" }
                        if (target.startsWith('/')) resolved.clear()
                        target.split('/').asReversed().forEach { pending.addFirst(it) }
                    } else {
                        resolved += name
                    }
                }
            }
        }
        return resolved.fold(base) { parent, part -> parent.resolve(part) }.toFile()
    }

    private data class LoadSegment(val offset: Long, val virtualAddress: Long, val alignment: Long)
    private data class Elf(
        val architecture: Architecture,
        val interpreter: String?,
        val loadSegments: List<LoadSegment>,
    )

    /** Only headers are read, with every file range checked before seeking or allocating. */
    private fun readElf(file: File): Elf {
        checkInterrupted()
        require(file.isFile) { "Rootfs executable is missing: ${file.path}" }
        return RandomAccessFile(file, "r").use { input ->
            val length = input.length()
            fun read(offset: Long, size: Int): ByteBuffer {
                checkInterrupted()
                require(offset >= 0 && size >= 0 && offset <= length - size) { "Truncated ELF: ${file.path}" }
                return ByteArray(size).also { bytes -> input.seek(offset); input.readFully(bytes) }
                    .let { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN) }
            }
            val header = read(0, 64)
            require(header.getInt(0) == 0x464c457f && header[4] == 2.toByte() &&
                header[5] == 1.toByte() && header[6] == 1.toByte() && header.getInt(20) == 1) {
                "Expected a 64-bit little-endian ELF: ${file.path}"
            }
            require(header.getShort(16).toInt() in 2..3 && header.getShort(52).toInt() == 64) {
                "Invalid executable ELF header: ${file.path}"
            }
            val machine = header.getShort(18).toInt() and 0xffff
            val architecture = requireNotNull(Architecture.entries.firstOrNull { it.elfMachine == machine }) {
                "Unsupported ELF architecture $machine: ${file.path}"
            }
            val offset = header.getLong(32)
            val entrySize = header.getShort(54).toInt() and 0xffff
            val count = header.getShort(56).toInt() and 0xffff
            require(entrySize == 56 && count in 1..4096 && offset >= 64 &&
                offset <= length - count.toLong() * entrySize) { "Invalid ELF program headers: ${file.path}" }
            var interpreter: String? = null
            val loadSegments = mutableListOf<LoadSegment>()
            repeat(count) { index ->
                val program = read(offset + index.toLong() * entrySize, entrySize)
                val type = program.getInt(0)
                val fileOffset = program.getLong(8)
                val fileSize = program.getLong(32)
                require(fileOffset >= 0 && fileSize >= 0 && fileOffset <= length - fileSize) {
                    "Invalid ELF segment range: ${file.path}"
                }
                if (type == 1) {
                    require(program.getLong(40) >= fileSize) { "Invalid ELF load segment: ${file.path}" }
                    loadSegments += LoadSegment(fileOffset, program.getLong(16), program.getLong(48))
                }
                if (type == 3) {
                    require(interpreter == null && fileSize in 2..4096) { "Invalid ELF interpreter: ${file.path}" }
                    val bytes = read(fileOffset, fileSize.toInt()).array()
                    require(bytes.last() == 0.toByte() && bytes.dropLast(1).none { it == 0.toByte() }) {
                        "Invalid ELF interpreter path: ${file.path}"
                    }
                    interpreter = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes, 0, bytes.size - 1)).toString()
                    require(interpreter!!.startsWith('/')) { "ELF interpreter must be an absolute guest path: ${file.path}" }
                }
            }
            require(loadSegments.isNotEmpty()) { "ELF has no loadable segment: ${file.path}" }
            Elf(architecture, interpreter, loadSegments)
        }
    }

    private fun checkInterrupted() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Rootfs compatibility check cancelled")
    }
}
