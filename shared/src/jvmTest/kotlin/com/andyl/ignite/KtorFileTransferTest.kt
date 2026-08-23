package com.andyl.ignite

import com.andyl.ignite.data.AppStorage
import com.andyl.ignite.data.RoomTransferRepository
import com.andyl.ignite.data.db.NoopTransferDao
import com.andyl.ignite.data.network.KtorFileReceiver
import com.andyl.ignite.data.network.KtorFileSender
import com.andyl.ignite.data.network.createHttpClient
import com.andyl.ignite.data.network.createServerEngine
import com.andyl.ignite.domain.IncomingEvent
import com.andyl.ignite.domain.PairingManager
import com.andyl.ignite.domain.model.Device
import io.github.vinceglb.filekit.FileKit
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.contentType
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KtorFileTransferTest {

    @Test
    fun sendAndReceiveRoundTrip() = runBlocking {
        FileKit.init(appId = "com.andyl.ignite.test")

        val port = 48230
        val storage = AppStorage()
        val repository = RoomTransferRepository(NoopTransferDao())
        val pairing = PairingManager()
        val deviceInfo = com.andyl.ignite.data.DeviceInfo()
        val pin = pairing.getPin()
        val receiver = KtorFileReceiver(storage, repository, pairing, deviceInfo, port, ::createServerEngine, requiresApproval = false)
        receiver.start()

        val sender = KtorFileSender(createHttpClient())

        val source = File.createTempFile("ignite-src", ".txt").apply { writeText("hola ignite test") }
        val target = Device(id = "test-peer", name = "test", host = "127.0.0.1", port = port)

        File(storage.receiveDir(), "ignite-src.txt").delete()
        File(storage.receiveDir(), "ignite-src (1).txt").delete()

        val incoming = mutableListOf<IncomingEvent>()
        val collector = launch {
            receiver.incomingEvents.collect { event ->
                incoming.add(event)
                if (event is IncomingEvent.Completed) cancel()
            }
        }
        kotlinx.coroutines.delay(200)
        val progress = sender.send(target, source.absolutePath, "ignite-src.txt", source.length(), pin).toList()
        kotlinx.coroutines.delay(500)
        collector.cancel()

        assertTrue(progress.isNotEmpty(), "Progress flow should emit")
        assertEquals(1f, progress.last(), "Final progress should be 100%")

        assertTrue(incoming.any { it is IncomingEvent.Started && it.fileName == "ignite-src.txt" }, "Should emit Started event (got $incoming)")
        val completed = incoming.filterIsInstance<IncomingEvent.Completed>().firstOrNull()
        assertTrue(completed != null, "Should emit Completed (got $incoming)")
        assertTrue(completed.path.isNotBlank(), "Completed path should not be blank")
        assertTrue(File(completed.path).exists(), "Completed path file should exist: ${completed.path}")

        val received = File(storage.receiveDir(), "ignite-src.txt")
        assertTrue(received.exists(), "Received file should exist")
        assertEquals("hola ignite test", received.readText())

        receiver.stop()
    }

    /**
     * Fase 1b: un par con política AUTO entra sin pasar por aprobación, y el
     * emisor transmite su identidad por headers (peerDeviceId en eventos).
     */
    @Test
    fun autoPolicyReceivesWithoutAsking() = runBlocking {
        FileKit.init(appId = "com.andyl.ignite.test")

        val port = 48231
        val storage = AppStorage()
        val repository = RoomTransferRepository(NoopTransferDao())
        val pairing = PairingManager()
        val deviceInfo = com.andyl.ignite.data.DeviceInfo()
        val pin = pairing.getPin()

        var raw: String? = null
        val trustedDevices = com.andyl.ignite.domain.TrustedDevices({ raw }, { raw = it })
        // El emisor (deviceInfo) queda confiado con política AUTO
        trustedDevices.remember(
            deviceId = deviceInfo.deviceId,
            name = deviceInfo.deviceName,
            host = "127.0.0.1",
            pin = null,
            policy = com.andyl.ignite.domain.TrustPolicy.AUTO,
        )

        val receiver = KtorFileReceiver(
            storage, repository, pairing, deviceInfo, port, ::createServerEngine,
            trustedDevices = trustedDevices,
            requiresApproval = true, // el gate existe, pero la política lo salta
        )
        receiver.start()

        val sender = KtorFileSender(createHttpClient(), deviceInfo)

        val source = File.createTempFile("ignite-auto", ".txt").apply { writeText("sin preguntar") }
        File(storage.receiveDir(), "ignite-auto.txt").delete()
        val target = Device(id = deviceInfo.deviceId, name = "test", host = "127.0.0.1", port = port)

        val incoming = mutableListOf<IncomingEvent>()
        val collector = launch {
            receiver.incomingEvents.collect { event ->
                incoming.add(event)
                if (event is IncomingEvent.Completed) cancel()
            }
        }
        kotlinx.coroutines.delay(200)
        val progress = sender.send(target, source.absolutePath, "ignite-auto.txt", source.length(), pin).toList()
        kotlinx.coroutines.delay(500)
        collector.cancel()

        assertEquals(1f, progress.lastOrNull(), "Debe completar sin intervención")
        assertTrue(incoming.none { it is IncomingEvent.AwaitingApproval }, "Política AUTO no debe pedir aprobación (got $incoming)")
        val completed = incoming.filterIsInstance<IncomingEvent.Completed>().first()
        assertEquals(deviceInfo.deviceId, completed.peerDeviceId, "La identidad del emisor debe llegar al receptor")
        assertTrue(File(completed.path).readText() == "sin preguntar")

        receiver.stop()
    }

    /** Fase 1a: par desconocido (ASK) muestra identidad declarada en el diálogo. */
    @Test
    fun askPolicyCarriesSenderIdentity() = runBlocking {
        FileKit.init(appId = "com.andyl.ignite.test")

        val port = 48232
        val storage = AppStorage()
        val repository = RoomTransferRepository(NoopTransferDao())
        val pairing = PairingManager()
        val deviceInfo = com.andyl.ignite.data.DeviceInfo()
        val pin = pairing.getPin()
        val receiver = KtorFileReceiver(storage, repository, pairing, deviceInfo, port, ::createServerEngine)
        receiver.start()

        val sender = KtorFileSender(createHttpClient(), deviceInfo)

        val source = File.createTempFile("ignite-ask", ".txt").apply { writeText("quien sos") }
        File(storage.receiveDir(), "ignite-ask.txt").delete()
        val target = Device(id = deviceInfo.deviceId, name = "test", host = "127.0.0.1", port = port)

        var awaiting: IncomingEvent.AwaitingApproval? = null
        val collector = launch {
            receiver.incomingEvents.collect { event ->
                if (event is IncomingEvent.AwaitingApproval && awaiting == null) awaiting = event
            }
        }
        kotlinx.coroutines.delay(200)

        val sendJob = launch {
            runCatching { sender.send(target, source.absolutePath, "ignite-ask.txt", source.length(), pin).toList() }
        }

        // Esperar a que aparezca la solicitud de aprobación (hasta ~5s)
        val deadline = System.currentTimeMillis() + 5_000
        while (awaiting == null && System.currentTimeMillis() < deadline) delay(50)

        val request = awaiting
        assertTrue(request != null, "Debe emitir AwaitingApproval")
        assertEquals(deviceInfo.deviceId, request.peerDeviceId, "El header de identidad debe llegar al evento")
        assertEquals(deviceInfo.deviceName, request.peerDeviceName)

        // Aprobar y dejar terminar limpio
        receiver.decideApproval(request.transferId, true)
        sendJob.cancel()
        collector.cancel()

        receiver.stop()
    }

    /** Fase 1c: el emisor manda una miniatura y llega al receptor antes de aprobar. */
    @Test
    fun previewArrivesBeforeApproval() = runBlocking {
        FileKit.init(appId = "com.andyl.ignite.test")

        val port = 48233
        val storage = AppStorage()
        val repository = RoomTransferRepository(NoopTransferDao())
        val pairing = PairingManager()
        val deviceInfo = com.andyl.ignite.data.DeviceInfo()
        val pin = pairing.getPin()
        val receiver = KtorFileReceiver(storage, repository, pairing, deviceInfo, port, ::createServerEngine)
        receiver.start()

        // Imagen PNG de prueba (64x32 verde) para que createThumbnail produzca algo
        val source = File.createTempFile("ignite-preview", ".png")
        val img = java.awt.image.BufferedImage(64, 32, java.awt.image.BufferedImage.TYPE_INT_RGB)
        img.graphics.apply { fillRect(0, 0, 64, 32); dispose() }
        javax.imageio.ImageIO.write(img, "png", source)

        val sender = KtorFileSender(createHttpClient(), deviceInfo)
        val target = Device(id = deviceInfo.deviceId, name = "test", host = "127.0.0.1", port = port)

        var awaiting: IncomingEvent.AwaitingApproval? = null
        val collector = launch {
            receiver.incomingEvents.collect { event ->
                if (event is IncomingEvent.AwaitingApproval && awaiting == null) awaiting = event
            }
        }
        kotlinx.coroutines.delay(200)

        val sendJob = launch {
            runCatching { sender.send(target, source.absolutePath, source.name, source.length(), pin).toList() }
        }

        val deadline = System.currentTimeMillis() + 5_000
        while (awaiting == null && System.currentTimeMillis() < deadline) delay(50)

        val request = awaiting
        assertTrue(request != null, "Debe emitir AwaitingApproval")

        // La preview puede llegar en paralelo con la solicitud: poll corto
        var preview: ByteArray? = null
        val previewDeadline = System.currentTimeMillis() + 3_000
        while (preview == null && System.currentTimeMillis() < previewDeadline) {
            preview = receiver.pendingPreview(request.transferId)
            if (preview == null) delay(50)
        }
        assertTrue(preview != null, "La miniatura debe haber llegado al receptor")
        // JPEG empieza con FF D8
        assertEquals(0xFF, preview[0].toInt() and 0xFF, "La miniatura debe ser JPEG")
        assertEquals(0xD8, preview[1].toInt() and 0xFF)

        receiver.decideApproval(request.transferId, true)
        sendJob.cancel()
        collector.cancel()

        receiver.stop()
    }

    /** Fase 1d: /pair valida PIN y deja al escaneador como confiable AUTO en el receptor. */
    @Test
    fun pairHandshakeGrantsMutualTrust() = runBlocking {
        FileKit.init(appId = "com.andyl.ignite.test")

        val port = 48234
        val storage = AppStorage()
        val repository = RoomTransferRepository(NoopTransferDao())
        val pairing = PairingManager()
        val deviceInfo = com.andyl.ignite.data.DeviceInfo()
        val pin = pairing.getPin()

        var raw: String? = null
        val trustedDevices = com.andyl.ignite.domain.TrustedDevices({ raw }, { raw = it })
        val receiver = KtorFileReceiver(
            storage, repository, pairing, deviceInfo, port, ::createServerEngine,
            trustedDevices = trustedDevices,
        )
        receiver.start()
        kotlinx.coroutines.delay(200)

        // El "escaneador" (device B) le habla a A vía /pair con el PIN del QR de A
        val client = createHttpClient()
        val response = client.post("http://127.0.0.1:$port/pair?pin=$pin") {
            contentType(io.ktor.http.ContentType.Application.Json)
            setBody("""{"deviceId":"dev-B","name":"Celu de B"}""")
        }
        assertEquals(io.ktor.http.HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains(deviceInfo.deviceId), "La respuesta debe traer la identidad de A")
        assertTrue(body.contains(deviceInfo.deviceName))

        // El receptor ahora confía en B con política AUTO
        assertEquals(com.andyl.ignite.domain.TrustPolicy.AUTO, trustedDevices.policyFor("dev-B"))
        assertEquals("Celu de B", trustedDevices.pinFor("dev-B")?.name)

        // PIN incorrecto NO otorga confianza
        val bad = client.post("http://127.0.0.1:$port/pair?pin=000000") {
            contentType(io.ktor.http.ContentType.Application.Json)
            setBody("""{"deviceId":"dev-evil","name":"Evil"}""")
        }
        assertEquals(io.ktor.http.HttpStatusCode.Unauthorized, bad.status)
        assertEquals(com.andyl.ignite.domain.TrustPolicy.ASK, trustedDevices.policyFor("dev-evil"))

        client.get("http://127.0.0.1:$port/beacon") // sanity: server vivo
        receiver.stop()
    }
}