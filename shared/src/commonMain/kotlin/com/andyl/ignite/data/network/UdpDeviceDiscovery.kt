package com.andyl.ignite.data.network

import com.andyl.ignite.data.DeviceInfo
import com.andyl.ignite.domain.DeviceDiscovery
import com.andyl.ignite.domain.model.Beacon
import com.andyl.ignite.domain.model.Device
import com.andyl.ignite.domain.model.TransferDefaults
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.charset.Charset

/**
 * Discovers peers using UDP broadcast. This device periodically announces its
 * presence with a [Beacon] and responds to discovery probes. Peers are exposed
 * via [devices].
 *
 * Note: this implementation lives in `jvmAndAndroidMain` because Android's
 * Wi-Fi multicast behaviour requires a `MulticastLock` for reliable broadcast
 * delivery (wired in the Android actuals of [DeviceInfo]).
 */
class UdpDeviceDiscovery(
    private val deviceInfo: DeviceInfo,
    private val port: Int = DISCOVERY_PORT,
) : DeviceDiscovery {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val _devices = MutableSharedFlow<Device>(extraBufferCapacity = 32)
    override val devices: SharedFlow<Device> = _devices.asSharedFlow()

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun start() {
        if (job != null) return
        job = scope.launch { runDiscovery() }
    }

    override suspend fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun runDiscovery() {
        val socket = DatagramSocket(null)
        socket.broadcast = true
        socket.reuseAddress = true
        socket.bind(java.net.InetSocketAddress(port))

        val buffer = ByteArray(MAX_PACKET)
        val announce = beaconBytes()

        while (scope.isActive) {
            // Announce our presence to the broadcast address.
            try {
                val broadcast = InetAddress.getByName(BROADCAST_ADDRESS)
                socket.send(DatagramPacket(announce, announce.size, broadcast, port))
            } catch (_: Exception) {
            }

            // Listen for beacons (non-blocking poll).
            while (true) {
                val packet = DatagramPacket(buffer, buffer.size)
                val received = try {
                    socket.soTimeout = 100
                    socket.receive(packet)
                    true
                } catch (_: Exception) {
                    false
                }
                if (!received) break

                val raw = String(packet.data, 0, packet.length, Charset.forName("UTF-8"))
                val beacon = try {
                    json.decodeFromString<Beacon>(raw)
                } catch (_: Exception) {
                    continue
                }
                if (beacon.deviceId == deviceInfo.deviceId) continue

                _devices.tryEmit(
                    Device(
                        id = beacon.deviceId,
                        name = beacon.deviceName,
                        host = packet.address.hostAddress ?: continue,
                        port = beacon.port,
                    ),
                )
            }

            delay(ANNOUNCE_INTERVAL_MS)
        }

        socket.close()
    }

    private fun beaconBytes(): ByteArray {
        val beacon = Beacon(
            deviceId = deviceInfo.deviceId,
            deviceName = deviceInfo.deviceName,
            port = TransferDefaults.PORT,
        )
        return json.encodeToString(beacon).toByteArray(Charset.forName("UTF-8"))
    }

    private companion object {
        const val DISCOVERY_PORT = 48432
        const val BROADCAST_ADDRESS = "255.255.255.255"
        const val MAX_PACKET = 1024
        const val ANNOUNCE_INTERVAL_MS = 2_000L
    }
}
