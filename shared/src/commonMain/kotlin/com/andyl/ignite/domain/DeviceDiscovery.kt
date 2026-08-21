package com.andyl.ignite.domain

import com.andyl.ignite.domain.model.Device

/**
 * Discovers and announces devices on the local network via UDP broadcast.
 */
interface DeviceDiscovery {
    /**
     * Emits [Device]s currently seen on the network. Typically backed by a
     * [kotlinx.coroutines.flow.SharedFlow] populated as beacons arrive.
     */
    val devices: kotlinx.coroutines.flow.Flow<Device>

    /**
     * Starts announcing this device and listening for beacons. Idempotent.
     */
    suspend fun start()

    /**
     * Stops announcing and listening.
     */
    suspend fun stop()
}
