package com.andyl.ignite.data.db

/**
 * Multiplatform database descriptor. Android uses the generated
 * [androidx.room.RoomDatabase] implementation; other targets use a no-op
 * in-memory placeholder (see the per-target actuals).
 */
expect fun createDatabase(): IgniteDatabase
