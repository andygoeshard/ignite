package com.andyl.ignite.data.network

import com.andyl.ignite.domain.DeviceDiscovery
import com.andyl.ignite.domain.model.Device
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
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
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Fallback a prueba de VPN/firewall: escanea la /24 local haciendo GET http://192.168.100.x:48213/
 * Si responde 200, es un Ignite. No depende de broadcast ni multicast.
 * Se ejecuta cada 5s en paralelo al UDP/mDNS y es el que salva cuando el broadcast lo filtra la VPN.
 */
class SubnetScannerDiscovery(
    private val client: HttpClient,
    private val port: Int = com.andyl.ignite.domain.model.TransferDefaults.PORT,
) : DeviceDiscovery {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val _devices = MutableSharedFlow<Device>(extraBufferCapacity = 32)
    override val devices: SharedFlow<Device> = _devices.asSharedFlow()

    override suspend fun start() {
        if (job != null) return
        job = scope.launch { runScan() }
    }

    override suspend fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun runScan() {
        while (scope.isActive) {
            val base = localSubnetBase() // ej "192.168.100."
            if (base != null) {
                // Escanea .1-.254 en paralelo ligero (lanzamos en batches de 32)
                val ips = (1..254).map { "$base$it" }
                ips.chunked(32).forEach { chunk ->
                    chunk.map { ip ->
                        scope.launch {
                            val ok = withTimeoutOrNull(400) {
                                runCatching {
                                    val resp = client.get("http://$ip:$port/")
                                    resp.status == HttpStatusCode.OK
                                }.getOrDefault(false)
                            } ?: false
                            if (ok) {
                                // Evita auto-detección: si es nuestra propia IP no emite (el server responde pero deviceId distinto)
                                _devices.tryEmit(Device(id = "scan-$ip", name = "Ignite $ip", host = ip, port = port))
                            }
                        }
                    }
                    delay(80)
                }
            }
            delay(5000)
        }
    }

    private fun localSubnetBase(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback && isPhysical(it) }
            .flatMap { it.inetAddresses.asSequence() }
            .filter { !it.isLoopbackAddress && it is java.net.Inet4Address }
            .mapNotNull { it.hostAddress }
            .firstOrNull { it.startsWith("192.168.") || it.startsWith("10.") }
            ?.let { ip -> ip.substringBeforeLast(".") + "." }
    }.getOrNull()

    private fun isPhysical(ni: NetworkInterface): Boolean {
        val n = ni.name.lowercase()
        return !(n.startsWith("utun") || n.startsWith("feth") || n.startsWith("awdl") || n.startsWith("llw") || n.startsWith("bridge") || n == "lo0")
    }
}
