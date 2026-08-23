package com.andyl.ignite.data.db

import android.content.Context
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.filesDir
import io.github.vinceglb.filekit.path
import java.io.File

/**
 * Holds the application [Context] so that platform storage can be built lazily
 * without threading Context through the shared DI graph.
 */
object AndroidContextHolder {
    lateinit var context: Context
}

/**
 * Historial persistente: JSON plano vía [JsonTransferDao] (Room/KSP sigue
 * deshabilitado; esto sobrevive reinicios igual).
 */
actual class IgniteDatabase {
    private val file = File(FileKit.filesDir.path, "transfer_history.json")
    private val dao = JsonTransferDao(
        readRaw = { file.takeIf { it.exists() }?.readText() },
        writeRaw = { text ->
            file.parentFile?.mkdirs()
            file.writeText(text)
        },
    )
    actual fun transferDao(): TransferDao = dao
}

actual fun createDatabase(): IgniteDatabase = IgniteDatabase()
