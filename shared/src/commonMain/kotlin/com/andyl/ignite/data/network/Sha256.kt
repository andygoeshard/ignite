package com.andyl.ignite.data.network

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

fun sha256Hex(bytes: ByteArray): String {
    val md = MessageDigest.getInstance("SHA-256")
    val digest = md.digest(bytes)
    return digest.joinToString("") { "%02x".format(it) }
}

fun sha256File(file: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    FileInputStream(file).use { input ->
        val buffer = ByteArray(64 * 1024)
        var read: Int
        while (input.read(buffer).also { read = it } != -1) {
            if (read > 0) md.update(buffer, 0, read)
        }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

/** Igual que [sha256File] pero acepta content:// URIs (Android MediaStore). */
fun sha256Transfer(path: String): String? {
    val input = com.andyl.ignite.data.openTransferStream(path) ?: return null
    val md = MessageDigest.getInstance("SHA-256")
    input.use {
        val buffer = ByteArray(64 * 1024)
        var read: Int
        while (it.read(buffer).also { r -> read = r } != -1) {
            if (read > 0) md.update(buffer, 0, read)
        }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

fun sha256HexStreaming(digest: MessageDigest): String =
    digest.digest().joinToString("") { "%02x".format(it) }
