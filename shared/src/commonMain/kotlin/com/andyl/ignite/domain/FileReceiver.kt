package com.andyl.ignite.domain

import com.andyl.ignite.domain.model.Transfer

/**
 * Receives files over HTTP and stores them locally, emitting progress.
 */
interface FileReceiver {
    /**
     * Emits progress (0f..1f) and the received [Transfer] for every incoming
     * file that completes or fails while the server is running.
     */
    val receivedTransfers: kotlinx.coroutines.flow.Flow<Transfer>

    /**
     * Starts the embedded HTTP server. Idempotent.
     */
    suspend fun start()

    /**
     * Stops the server and frees the port.
     */
    suspend fun stop()
}
