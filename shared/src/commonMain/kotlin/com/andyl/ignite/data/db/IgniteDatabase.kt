package com.andyl.ignite.data.db

/**
 * Multiplatform database descriptor. Android backs this with a real Room
 * database; other targets use an in-memory placeholder until native storage is
 * wired in.
 */
expect class IgniteDatabase {
    fun transferDao(): TransferDao
}
