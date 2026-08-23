package com.andyl.ignite.data.db

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * In-memory [TransferDao] used by non-Android targets where a persistent Room
 * database is not yet wired. Keeps the app compiling and functional until a
 * native storage backend is added.
 */
class NoopTransferDao : TransferDao {
    override suspend fun upsert(entity: TransferEntity) = Unit
    override fun observeAll(): Flow<List<TransferEntity>> = flowOf(emptyList())
    override suspend fun deleteById(id: Long) = Unit
    override suspend fun clearAll() = Unit
}
