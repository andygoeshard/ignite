package com.andyl.ignite.domain.model

/**
 * A short text message received from a peer device (Fase 3 — push de texto).
 */
data class TextMessage(
    val text: String,
    val senderName: String,
    val senderHost: String,
    val timestamp: Long = System.currentTimeMillis(),
)
