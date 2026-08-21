package com.andyl.ignite.domain.model

import kotlinx.serialization.Serializable

/**
 * Payload advertised over UDP broadcast so that other Ignite devices can
 * discover this one on the local network.
 */
@Serializable
data class Beacon(
    val deviceId: String,
    val deviceName: String,
    val port: Int = TransferDefaults.PORT,
)
