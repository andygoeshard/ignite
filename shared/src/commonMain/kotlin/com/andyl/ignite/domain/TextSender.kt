package com.andyl.ignite.domain

import com.andyl.ignite.domain.model.Device

/**
 * Sends short text messages to a peer device (Fase 3 — push de texto).
 */
interface TextSender {
    /**
     * Sends [text] to [target]. Throws on failure.
     * @param pin PIN shown on receiver (X-Ignite-Pin)
     */
    suspend fun send(target: Device, text: String, pin: String?)
}
