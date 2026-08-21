package com.andyl.ignite.data.db

/**
 * Placeholder database used on non-Android targets. Backed by an in-memory
 * [NoopTransferDao]; swap for a persistent store in a future iteration.
 */
actual class IgniteDatabase {
    private val dao = NoopTransferDao()
    actual fun transferDao(): TransferDao = dao
}

actual fun createDatabase(): IgniteDatabase = IgniteDatabase()
