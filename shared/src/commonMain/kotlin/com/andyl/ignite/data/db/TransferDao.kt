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

    /** Borra una transferencia puntual del historial. */
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM transfers")
    suspend fun clearAll()
}
