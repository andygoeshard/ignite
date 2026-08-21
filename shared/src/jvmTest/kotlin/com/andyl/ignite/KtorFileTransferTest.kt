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
import kotlinx.coroutines.cancel
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
        val pin = pairing.getPin()
        val receiver = KtorFileReceiver(storage, repository, pairing, port, ::createServerEngine, requiresApproval = false)
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
}