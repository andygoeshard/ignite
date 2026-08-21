package com.andyl.ignite

import com.andyl.ignite.data.DeviceInfo
import com.andyl.ignite.data.network.UdpDeviceDiscovery
import com.andyl.ignite.domain.model.Beacon
import com.andyl.ignite.domain.model.Device
import com.andyl.ignite.domain.model.TransferDefaults
import io.github.vinceglb.filekit.FileKit
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.test.assertEquals

class UdpDeviceDiscoveryTest {

    @Test
    fun discoveryDetectsPeerBeacon() = runBlocking {
        FileKit.init(appId = "com.andyl.ignite.test")

        val port = ServerSocket(0).use { it.localPort }
        val discovery = UdpDeviceDiscovery(DeviceInfo(), port = port)
        discovery.start()

        val socket = DatagramSocket()
        val beacon = Beacon(deviceId = "peer-1", deviceName = "Peer Uno", port = TransferDefaults.PORT)
        val payload = Json.encodeToString(beacon).encodeToByteArray()

        val found = withTimeout(5_000) {
            val awaitPeer = async { discovery.devices.first { it.id == "peer-1" } }
            repeat(15) {
                socket.send(DatagramPacket(payload, payload.size, InetAddress.getByName("127.0.0.1"), port))
                delay(200)
            }
            awaitPeer.await()
        }

        assertEquals("Peer Uno", found.name)
        assertEquals("127.0.0.1", found.host)

        discovery.stop()
        socket.close()
    }
}