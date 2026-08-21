package com.andyl.ignite.data.network

import com.andyl.ignite.domain.DeviceDiscovery
import com.andyl.ignite.domain.model.Device
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * Combines multiple [DeviceDiscovery] strategies (UDP broadcast + mDNS/DNS-SD) and merges
 * their [devices] flows. Swappable without touching UI because HomeViewModel only depends on the interface.
 */
class CompositeDeviceDiscovery(
    private val delegates: List<DeviceDiscovery>,
) : DeviceDiscovery {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _devices = MutableSharedFlow<Device>(extraBufferCapacity = 64)
    override val devices: SharedFlow<Device> = _devices.asSharedFlow()

    init {
        delegates.forEach { delegate ->
            scope.launch {
                delegate.devices.collect { _devices.tryEmit(it) }
            }
        }
    }

    override suspend fun start() {
        delegates.forEach { runCatching { it.start() } }
    }

    override suspend fun stop() {
        delegates.forEach { runCatching { it.stop() } }
    }
}
