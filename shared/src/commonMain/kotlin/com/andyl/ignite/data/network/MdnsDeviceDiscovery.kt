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
import java.net.InetAddress
import java.net.MulticastSocket

/**
 * mDNS/DNS-SD fallback using multicast group 224.0.0.251 (mDNS) as transport.
 * When UDP broadcast is blocked by VLAN/client-isolation, multicast still often works.
 * For a full DNS-SD implementation, swap this with a JmDNS/NSD backend – the interface stays the same.
 *
 * Service type: _ignite._tcp.local
 */
class MdnsDeviceDiscovery(
    private val deviceInfo: DeviceInfo,
    private val port: Int = MDNS_PORT,
) : DeviceDiscovery {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val _devices = MutableSharedFlow<Device>(extraBufferCapacity = 32)
    override val devices: SharedFlow<Device> = _devices.asSharedFlow()
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun start() {
        if (job != null) return
        job = scope.launch { runMdns() }
    }

    override suspend fun stop() {
        job?.cancel()
        job = null
        scope.cancel()
    }

    private suspend fun runMdns() {
        // Two sockets: one for sending to multicast group, one for listening
        val group = InetAddress.getByName(MDNS_GROUP)
        val socket = MulticastSocket(port).apply {
            reuseAddress = true
            timeToLive = 2
            joinGroup(group)
            soTimeout = 200
        }
        val buffer = ByteArray(1024)
        while (scope.isActive) {
            // Announce via multicast
            runCatching {
                val payload = beaconBytes()
                val pkt = DatagramPacket(payload, payload.size, group, port)
                socket.send(pkt)
            }
            // Listen
            while (true) {
                val pkt = DatagramPacket(buffer, buffer.size)
                val got = runCatching { socket.receive(pkt); true }.getOrDefault(false)
                if (!got) break
                val raw = String(pkt.data, 0, pkt.length, Charsets.UTF_8)
                val beacon = runCatching { json.decodeFromString<Beacon>(raw) }.getOrNull() ?: continue
                if (beacon.deviceId == deviceInfo.deviceId) continue
                _devices.tryEmit(Device(id = beacon.deviceId, name = beacon.deviceName, host = pkt.address.hostAddress ?: continue, port = beacon.port))
            }
            delay(5000)
        }
        runCatching { socket.leaveGroup(group) }
        socket.close()
    }

    private fun beaconBytes(): ByteArray {
        val beacon = Beacon(deviceId = deviceInfo.deviceId, deviceName = deviceInfo.deviceName, port = TransferDefaults.PORT)
        return Json.encodeToString(beacon).toByteArray(Charsets.UTF_8)
    }

    private companion object {
        const val MDNS_GROUP = "224.0.0.251"
        const val MDNS_PORT = 5354 // non-standard to avoid clashing with system mDNS (5353); system mDNS would need JmDNS/NSD for true _ignite._tcp.local
        // To implement true DNS-SD, replace this with JmDNS (JVM) / NsdManager (Android):
        //   JmDNS.create().registerService(ServiceInfo.create("_ignite._tcp.local", deviceName, TransferDefaults.PORT, ...))
        //   and discovery via ServiceListener.
    }
}
