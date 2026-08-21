package com.andyl.ignite.domain.model

/**
 * A peer device discovered on the local network.
 */
data class Device(
    val id: String,
    val name: String,
    val host: String,
    val port: Int = TransferDefaults.PORT,
)

object TransferDefaults {
    const val PORT = 48213
}
