package com.andyl.ignite.domain

import kotlinx.coroutines.flow.SharedFlow

/**
 * Lifecycle events for a file being received from a peer.
 */
sealed interface IncomingEvent {
    val fileName: String
    val peerHost: String

    data class Started(
        override val fileName: String,
        override val peerHost: String,
        val totalBytes: Long,
    ) : IncomingEvent

    data class Progress(
        override val fileName: String,
        override val peerHost: String,
        val receivedBytes: Long,
        val totalBytes: Long,
        val progress: Float,
    ) : IncomingEvent

    data class Completed(
        override val fileName: String,
        override val peerHost: String,
        val path: String,
        val sizeBytes: Long,
    ) : IncomingEvent

    data class Failed(
        override val fileName: String,
        override val peerHost: String,
        val message: String?,
    ) : IncomingEvent

    data class AwaitingApproval(
        override val fileName: String,
        override val peerHost: String,
        val totalBytes: Long,
        val transferId: String,
    ) : IncomingEvent
}

/**
 * Receives files over HTTP and stores them locally, emitting [IncomingEvent]s.
 */
interface FileReceiver {
    val incomingEvents: SharedFlow<IncomingEvent>

    /**
     * Starts the embedded HTTP server. Idempotent.
     */
    suspend fun start()

    /**
     * Stops the server and frees the port.
     */
    suspend fun stop()

    /**
     * Called by UI to approve/reject a pending transfer that emitted [IncomingEvent.AwaitingApproval].
     * @param transferId the id from the event
     * @param approved true to write to disk, false to reject with 403
     */
    suspend fun decideApproval(transferId: String, approved: Boolean)

    /** Whether this receiver requires approval dialog before writing (default true) */
    val requiresApproval: Boolean
}
