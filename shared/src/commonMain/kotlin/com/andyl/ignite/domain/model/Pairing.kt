package com.andyl.ignite.domain.model

import kotlinx.serialization.Serializable

/**
 * Contenido del QR de emparejamiento: quien lo escanea puede establecer
 * confianza MUTUA con un solo POST /pair (los dos lados quedan AUTO).
 */
@Serializable
data class PairingPayload(
    /** Versión del formato, por si cambia el protocolo. */
    val v: Int = 1,
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val pin: String,
)

/** Respuesta del handshake /pair con la identidad del receptor. */
@Serializable
data class PairResponse(
    val deviceId: String,
    val deviceName: String,
)
