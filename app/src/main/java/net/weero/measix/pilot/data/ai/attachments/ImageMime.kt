package net.weero.measix.pilot.data.ai.attachments

import net.weero.measix.pilot.data.imggen.GeneratedMediaStore
import java.io.File

/**
 * First-stage image MIME gate. JPEG/PNG/GIF/WEBP come from
 * [GeneratedMediaStore.detectImageMimeBySignature]; HEIC is accepted when the
 * ISO-BMFF brand matches the same set [me.rerere.ai.util.encodeBase64] already converts.
 */
object ImageMime {
    fun sniff(bytes: ByteArray): String? {
        GeneratedMediaStore.detectImageMimeBySignature(bytes)?.let { return it }
        return sniffHeic(bytes)
    }

    fun sniffFile(file: File): String? = runCatching {
        file.inputStream().use { input ->
            val header = ByteArray(16)
            val read = input.read(header)
            if (read <= 0) return@use null
            sniff(header.copyOf(read))
        }
    }.getOrNull()

    fun isAcceptedImage(bytes: ByteArray, declaredMime: String? = null): Boolean {
        if (sniff(bytes) != null) return true
        val declared = normalizeDeclared(declaredMime)
        if (declared != null && declared.startsWith("image/") && declared != "image/svg+xml") {
            return GeneratedMediaStore.detectImageMime(bytes) != null
        }
        return GeneratedMediaStore.detectImageMime(bytes) != null
    }

    fun isUnsupportedNonImage(bytes: ByteArray, declaredMime: String? = null): Boolean {
        val declared = normalizeDeclared(declaredMime).orEmpty()
        if (declared.startsWith("application/pdf") ||
            declared.startsWith("audio/") ||
            declared.startsWith("video/") ||
            declared == "application/zip" ||
            declared == "application/octet-stream" && looksLikePdf(bytes)
        ) {
            return !declared.startsWith("image/")
        }
        if (looksLikePdf(bytes)) return true
        return declared.startsWith("audio/") || declared.startsWith("video/")
    }

    private fun normalizeDeclared(raw: String?): String? =
        raw?.substringBefore(';')?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

    private fun looksLikePdf(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        return bytes[0] == '%'.code.toByte() &&
            bytes[1] == 'P'.code.toByte() &&
            bytes[2] == 'D'.code.toByte() &&
            bytes[3] == 'F'.code.toByte()
    }

    private fun sniffHeic(bytes: ByteArray): String? {
        if (bytes.size < 12) return null
        val ftyp = bytes.copyOfRange(4, 8).toString(Charsets.US_ASCII)
        if (ftyp != "ftyp") return null
        return when (bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII)) {
            "heic", "heix", "heim", "heis",
            "hevc", "hevx", "hevm", "hevs",
            "mif1", "msf1", "heif",
            -> "image/heic"
            else -> null
        }
    }
}
