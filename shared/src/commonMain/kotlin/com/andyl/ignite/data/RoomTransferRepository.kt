package com.andyl.ignite.data

import com.andyl.ignite.data.db.TransferDao
import com.andyl.ignite.data.db.toDomain
import com.andyl.ignite.data.db.toEntity
import com.andyl.ignite.domain.TransferRepository
import com.andyl.ignite.domain.model.Transfer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomTransferRepository(
    private val dao: TransferDao,
) : TransferRepository {

    override fun observeTransfers(): Flow<List<Transfer>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun upsert(transfer: Transfer) = dao.upsert(transfer.toEntity())

    override suspend fun delete(id: Long) = dao.deleteById(id)

    override suspend fun clearHistory() = dao.clearAll()
}
