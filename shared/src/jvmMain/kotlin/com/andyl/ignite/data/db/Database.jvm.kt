package com.andyl.ignite.data.db

import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.filesDir
import io.github.vinceglb.filekit.path
import java.io.File

/**
 * "Base de datos" de historial: JSON plano persistente vía [JsonTransferDao].
 * Reemplaza al Noop que descartaba todo (el historial no funcionaba).
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
