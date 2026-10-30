package com.saraai.offline.model

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

object GgufValidator {
    data class Result(
        val ok: Boolean,
        val reason: String,
        val sizeBytes: Long = 0,
        val magic: String = "",
    )

    fun validate(file: File, expectedMinBytes: Long = 1024, expectedSha256: String? = null): Result {
        if (!file.exists()) return Result(false, "File missing")
        val len = file.length()
        if (len < expectedMinBytes) return Result(false, "File too small ($len bytes)", len)
        val magic = try {
            RandomAccessFile(file, "r").use { raf ->
                val buf = ByteArray(4)
                if (raf.read(buf) != 4) return Result(false, "Cannot read magic", len)
                String(buf, Charsets.US_ASCII)
            }
        } catch (t: Throwable) {
            return Result(false, "Read error: ${t.message}", len)
        }
        if (magic != "GGUF") {
            return Result(false, "Not a GGUF file (magic=$magic)", len, magic)
        }
        if (!expectedSha256.isNullOrBlank()) {
            val actual = sha256(file) ?: return Result(false, "Checksum failed to compute", len, magic)
            if (!actual.equals(expectedSha256, ignoreCase = true)) {
                return Result(false, "SHA256 mismatch", len, magic)
            }
        }
        return Result(true, "Valid GGUF", len, magic)
    }

    fun sha256(file: File): String? = try {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { ins ->
            val buf = ByteArray(8192)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (_: Throwable) {
        null
    }
}
