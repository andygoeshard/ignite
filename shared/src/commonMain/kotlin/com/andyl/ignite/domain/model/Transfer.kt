package com.andyl.ignite.domain.model

/**
 * A single file transfer between this device and a peer. Both incoming
 * (received) and outgoing (sent) transfers are modelled with the same type,
 * distinguished by [direction].
 */
data class Transfer(
    val id: Long,
    val fileName: String,
    val sizeBytes: Long,
    val direction: Direction,
    val peerName: String,
    val peerHost: String,
    val status: Status,
    val progress: Float,
    val createdAt: Long,
) {
    enum class Direction { SENT, RECEIVED }

    enum class Status { QUEUED, IN_PROGRESS, COMPLETED, FAILED, CANCELLED, INTERRUPTED }

    val isActive: Boolean
        get() = status == Status.IN_PROGRESS
}
