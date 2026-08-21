package com.andyl.ignite.domain

import com.andyl.ignite.domain.model.Transfer

/**
 * Persists the history of transfers.
 */
interface TransferRepository {
    fun observeTransfers(): kotlinx.coroutines.flow.Flow<List<Transfer>>
    suspend fun upsert(transfer: Transfer)
    suspend fun clearHistory()
}
