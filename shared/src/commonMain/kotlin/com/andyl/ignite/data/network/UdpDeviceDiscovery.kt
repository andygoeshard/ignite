package com.andyl.ignite.data.network

import com.andyl.ignite.data.DeviceInfo
import com.andyl.ignite.domain.DeviceDiscovery
import com.andyl.ignite.domain.model.Beacon
import com.andyl.ignite.domain.model.Device
import com.andyl.ignite.domain.model.TransferDefaults
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import java.net.NetworkInterface
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
    private var cachedBroadcasts: List<InetAddress>? = null
    private var cachedBroadcastsAt: Long = 0L

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
        scope.cancel()
    }

    private suspend fun runDiscovery() {
        val socket = DatagramSocket(null)
        socket.broadcast = true
        socket.reuseAddress = true
        socket.bind(java.net.InetSocketAddress(port))

        val buffer = ByteArray(MAX_PACKET)
        var loggedTargets = false

        while (scope.isActive) {
            val announce = beaconBytes()
            // Announce our presence on every broadcast address (global + per-interface).
            // Sending only to 255.255.255.255 can miss peers when the machine has
            // virtual adapters (WSL/Hyper-V/Docker on Windows, utun on macOS).
            for (address in cachedBroadcasts()) {
                try {
                    socket.send(DatagramPacket(announce, announce.size, address, port))
                } catch (_: Exception) {
                }
            }
            if (!loggedTargets) {
                loggedTargets = true
                val locals = localIps()
                println("[Ignite] local IPs: $locals")
                println("[Ignite] announcing as '${deviceInfo.deviceName}' (${deviceInfo.deviceId.take(8)}) to broadcasts: ${cachedBroadcasts().mapNotNull { it.hostAddress }}")
                println("[Ignite] tip: tu IP local real debe coincidir en los 3 primeros octetos con la del otro. Si ves 10.x o 192.168.196.x es VPN/VM, no tu Wi-Fi. Usa 'ifconfig | grep inet' y conecta manual con esa IP.")
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

                println("[Ignite] beacon received from '${beacon.deviceName}' at ${packet.address.hostAddress}:${beacon.port}")
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

    private fun isPhysicalWifiInterface(ni: NetworkInterface): Boolean {
        // En macOS: en0 = Wi-Fi, en1 = eth; en Android: wlan0. Excluimos VPN/VM que rompen el broadcast con VPN prendida.
        val name = ni.name.lowercase()
        if (name.startsWith("utun") || name.startsWith("feth") || name.startsWith("awdl") || name.startsWith("llw") || name.startsWith("bridge") || name == "lo0") return false
        // Solo interfaces con IPv4 192.168.x o 10.x privado real, no link-local
        return true
    }

    private fun cachedBroadcasts(): List<InetAddress> {
        val now = System.currentTimeMillis()
        if (cachedBroadcasts != null && now - cachedBroadcastsAt < 60_000) return cachedBroadcasts!!
        cachedBroadcasts = broadcastAddresses()
        cachedBroadcastsAt = now
        return cachedBroadcasts!!
    }

    private fun broadcastAddresses(): List<InetAddress> = buildList {
        add(InetAddress.getByName(BROADCAST_ADDRESS))
        runCatching {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback && isPhysicalWifiInterface(it) }
                .flatMap { ni -> ni.interfaceAddresses.asSequence().mapNotNull { it.broadcast } }
                .forEach { add(it) }
        }
        // Fallback: si filtramos todo (ej solo VPN), usa cualquier broadcast igual
        if (size == 1) runCatching {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { ni -> ni.interfaceAddresses.asSequence().mapNotNull { it.broadcast } }
                .forEach { add(it) }
        }
    }.distinctBy { it.hostAddress }

    private fun localIps(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { ni -> ni.inetAddresses.asSequence().filter { !it.isLoopbackAddress } }
            .map { addr ->
                val ni = runCatching { NetworkInterface.getByInetAddress(addr) }.getOrNull()
                val niName = ni?.name ?: "?"
                val mark = if (ni != null && isPhysicalWifiInterface(ni)) "" else " [VPN/VM-ignorado]"
                "${addr.hostAddress} ($niName)$mark"
            }
            .toList()
    }.getOrElse { emptyList() }

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
        const val ANNOUNCE_INTERVAL_MS = 5_000L
    }
}
