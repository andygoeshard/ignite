package com.andyl.ignite.data

import android.net.Uri
import com.andyl.ignite.data.db.AndroidContextHolder
import java.io.File
import java.io.InputStream

actual fun openTransferStream(path: String): InputStream? = runCatching {
    if (path.startsWith("content://")) {
        AndroidContextHolder.context.contentResolver.openInputStream(Uri.parse(path))
    } else {
        File(path).takeIf { it.isFile }?.inputStream()
    }
}.getOrNull()

actual fun transferMeta(path: String): Pair<String, Long>? = runCatching {
    if (path.startsWith("content://")) {
        val resolver = AndroidContextHolder.context.contentResolver
        resolver.query(Uri.parse(path), null, null, null, null)?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            val sizeIdx = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
            if (cursor.moveToFirst() && nameIdx >= 0) {
                val name = cursor.getString(nameIdx)
                val size = if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) cursor.getLong(sizeIdx) else -1L
                name to size
            } else {
                null
            }
        }
    } else {
        File(path).takeIf { it.isFile }?.let { it.name to it.length() }
    }
}.getOrNull()
