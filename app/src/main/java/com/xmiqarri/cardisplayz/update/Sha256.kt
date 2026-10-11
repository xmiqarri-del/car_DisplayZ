package com.xmiqarri.cardisplayz.update

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/** Streams a file through SHA-256 and returns the lowercase hex digest. Never loads the whole file. */
fun File.sha256Hex(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    FileInputStream(this).use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString(separator = "") { "%02x".format(it) }
}

/** Constant-time comparison of two hex digests (both lowercase). */
fun constantTimeHexEquals(a: String, b: String): Boolean =
    MessageDigest.isEqual(a.toByteArray(Charsets.US_ASCII), b.toByteArray(Charsets.US_ASCII))
