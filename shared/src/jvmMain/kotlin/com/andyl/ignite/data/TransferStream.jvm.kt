package com.andyl.ignite.data

import java.io.File
import java.io.InputStream

actual fun openTransferStream(path: String): InputStream? = runCatching {
    val file = File(path)
    if (file.isFile) file.inputStream() else null
}.getOrNull()

actual fun transferMeta(path: String): Pair<String, Long>? = runCatching {
    val file = File(path)
    if (file.isFile) file.name to file.length() else null
}.getOrNull()
