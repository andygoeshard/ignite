package com.andyl.ignite.data.db

import android.content.Context

/**
 * Holds the application [Context] so that the Room database can be built lazily
 * without threading Context through the shared DI graph.
 */
object AndroidContextHolder {
    lateinit var context: Context
}

// Room KSP disabled for now to unblock Android — usa Noop dao en memoria.
// Para persistencia real, reactivar @Database y Room.databaseBuilder con KSP 2.4.10-2.0.2
actual class IgniteDatabase {
    private val noop = NoopTransferDao()
    actual fun transferDao(): TransferDao = noop
}

actual fun createDatabase(): IgniteDatabase = IgniteDatabase()
