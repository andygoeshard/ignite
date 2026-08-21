package com.andyl.ignite.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TransferDao {

    @Upsert
    suspend fun upsert(entity: TransferEntity)

    @Query("SELECT * FROM transfers ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<TransferEntity>>

    @Query("DELETE FROM transfers")
    suspend fun clearAll()

    @Query("DELETE FROM transfers WHERE createdAt < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)

    @Query("DELETE FROM transfers WHERE status = 'FAILED'")
    suspend fun deleteFailed()

    @Query("SELECT COUNT(*) FROM transfers")
    suspend fun count(): Int
}
