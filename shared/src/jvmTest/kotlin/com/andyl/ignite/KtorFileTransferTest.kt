package com.andyl.ignite

import com.andyl.ignite.data.AppStorage
import com.andyl.ignite.data.DeviceInfo
import com.andyl.ignite.data.RoomTransferRepository
import com.andyl.ignite.data.db.NoopTransferDao
import com.andyl.ignite.data.network.KtorFileReceiver
import com.andyl.ignite.data.network.KtorFileSender
import com.andyl.ignite.data.network.createHttpClient
import com.andyl.ignite.data.network.createServerEngine
import com.andyl.ignite.domain.model.Device
import io.github.vinceglb.filekit.FileKit
import kotlinx.coroutines.flow.toList
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
        val receiver = KtorFileReceiver(storage, repository, port, ::createServerEngine)
        receiver.start()

        val sender = KtorFileSender(createHttpClient())

        val source = File.createTempFile("ignite-src", ".txt").apply { writeText("hola ignite test") }
        val target = Device(id = "test-peer", name = "test", host = "127.0.0.1", port = port)

        val progress = sender.send(target, source.absolutePath, "ignite-src.txt", source.length()).toList()

        assertTrue(progress.isNotEmpty(), "Progress flow should emit")
        assertEquals(1f, progress.last(), "Final progress should be 100%")

        val received = File(storage.receiveDir(), "ignite-src.txt")
        assertTrue(received.exists(), "Received file should exist")
        assertEquals("hola ignite test", received.readText())

        receiver.stop()
    }
}