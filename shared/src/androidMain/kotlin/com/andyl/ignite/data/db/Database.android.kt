package com.andyl.ignite.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Holds the application [Context] so that the Room database can be built lazily
 * without threading Context through the shared DI graph.
 */
object AndroidContextHolder {
    lateinit var context: Context
}

@androidx.room.Database(
    entities = [TransferEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class IgniteRoomDatabase : RoomDatabase() {
    abstract fun dao(): TransferDao
}

actual class IgniteDatabase {
    private val room: IgniteRoomDatabase = Room.databaseBuilder(
        AndroidContextHolder.context,
        IgniteRoomDatabase::class.java,
        "ignite.db",
    ).build()

    actual fun transferDao(): TransferDao = room.dao()
}

actual fun createDatabase(): IgniteDatabase = IgniteDatabase()
