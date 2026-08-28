package com.andyl.ignite.domain

import kotlinx.coroutines.flow.SharedFlow

/**
 * Lifecycle events for a file being received from a peer.
 * [peerDeviceId]/[peerDeviceName] viajan en headers desde Ignite 1.1; pueden
 * ser null si el emisor es una versión vieja.
 */
sealed interface IncomingEvent {
    val fileName: String
    val peerHost: String
    val peerDeviceId: String?

    data class Started(
        override val fileName: String,
        override val peerHost: String,
        val totalBytes: Long,
        override val peerDeviceId: String? = null,
    ) : IncomingEvent

    data class Progress(
        override val fileName: String,
        override val peerHost: String,
        val receivedBytes: Long,
        val totalBytes: Long,
        val progress: Float,
        override val peerDeviceId: String? = null,
    ) : IncomingEvent

    data class Completed(
        override val fileName: String,
        override val peerHost: String,
        val path: String,
        val sizeBytes: Long,
        override val peerDeviceId: String? = null,
        val sha256: String? = null,
    ) : IncomingEvent

    data class Failed(
        override val fileName: String,
        override val peerHost: String,
        val message: String?,
        override val peerDeviceId: String? = null,
    ) : IncomingEvent

    data class AwaitingApproval(
        override val fileName: String,
        override val peerHost: String,
        val totalBytes: Long,
        val transferId: String,
        override val peerDeviceId: String? = null,
        /** Nombre declarado por el emisor en el header de identidad. */
        val peerDeviceName: String? = null,
    ) : IncomingEvent

    /** Mensaje de texto rápido recibido de un par (Fase 3). */
    data class TextMessageReceived(
        val text: String,
        val senderName: String,
        override val peerHost: String,
        override val peerDeviceId: String? = null,
    ) : IncomingEvent {
        override val fileName: String get() = "[texto]"
    }

    /** Clipboard content received from a paired device (Fase 3 — clipboard sync). */
    data class ClipboardReceived(
        val content: String,
        val senderName: String,
        override val peerHost: String,
        override val peerDeviceId: String? = null,
    ) : IncomingEvent {
        override val fileName: String get() = "[clipboard]"
    }
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

    /**
     * Miniatura enviada por el emisor para la solicitud pendiente, si ya llegó.
     * Null = no hay preview (o no es imagen/video).
     */
    suspend fun pendingPreview(transferId: String): ByteArray?

    /** Whether this receiver requires approval dialog before writing (default true) */
    val requiresApproval: Boolean
}
