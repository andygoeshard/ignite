package com.andyl.ignite.data.network

import com.andyl.ignite.data.DeviceInfo
import com.andyl.ignite.domain.DeviceDiscovery
import com.andyl.ignite.domain.model.Beacon
import com.andyl.ignite.domain.model.Device
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
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
import kotlinx.serialization.json.Json
import java.net.NetworkInterface

/**
 * Fallback a prueba de VPN/firewall: escanea la /24 local haciendo GET http://192.168.100.x:48213/
 * Si responde 200, es un Ignite. No depende de broadcast ni multicast.
 * Se ejecuta cada 5s en paralelo al UDP/mDNS y es el que salva cuando el broadcast lo filtra la VPN.
 */
class SubnetScannerDiscovery(
    private val client: HttpClient,
    private val deviceInfo: DeviceInfo,
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
        val ownId = deviceInfo.deviceId
        val ownIps = localIpsSet()
        while (scope.isActive) {
            val base = localSubnetBase() // ej "192.168.100."
            if (base != null) {
                val ips = (1..254).map { "$base$it" }.filter { it !in ownIps }
                ips.chunked(32).forEach { chunk ->
                    chunk.map { ip ->
                        scope.launch {
                            val device = withTimeoutOrNull(500) {
                                runCatching {
                                    // Primero verifica que haya un Ignite escuchando
                                    val ok = client.get("http://$ip:$port/").status == HttpStatusCode.OK
                                    if (!ok) return@runCatching null
                                    // Intenta traer beacon real para nombre/id correctos (evita "Ignite 192.168.x")
                                    val json = Json { ignoreUnknownKeys = true }
                                    val body = client.get("http://$ip:$port/beacon").bodyAsText()
                                    val beacon = json.decodeFromString<Beacon>(body)
                                    if (beacon.deviceId == ownId) return@runCatching null // soy yo
                                    Device(id = beacon.deviceId, name = beacon.deviceName, host = ip, port = beacon.port)
                                }.getOrNull()
                            }
                            if (device != null) _devices.tryEmit(device)
                        }
                    }
                    delay(80)
                }
            }
            delay(5000)
        }
    }

    private fun localIpsSet(): Set<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .filter { !it.isLoopbackAddress && it is java.net.Inet4Address }
            .mapNotNull { it.hostAddress }
            .toSet()
    }.getOrDefault(emptySet())

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
