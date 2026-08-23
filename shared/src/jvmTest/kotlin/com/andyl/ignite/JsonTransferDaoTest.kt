package com.andyl.ignite

import com.andyl.ignite.data.db.JsonTransferDao
import com.andyl.ignite.data.db.TransferEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** El historial debe funcionar: antes NoopTransferDao descartaba todo. */
class JsonTransferDaoTest {

    private fun entity(name: String, createdAt: Long, id: Long = 0) = TransferEntity(
        id = id,
        fileName = name,
        sizeBytes = 123L,
        direction = "SEND",
        peerName = "andy mac",
        peerHost = "192.168.1.5",
        status = "COMPLETED",
        progress = 1f,
        createdAt = createdAt,
    )

    @Test
    fun upsert_assignsIdsAndSortsByDate() = runTest {
        val dao = JsonTransferDao(readRaw = { null }, writeRaw = { })
        dao.upsert(entity("a.jpg", createdAt = 100))
        dao.upsert(entity("b.pdf", createdAt = 200))
        dao.upsert(entity("c.png", createdAt = 300))

        val all = dao.observeAll().first()
        assertEquals(listOf("c.png", "b.pdf", "a.jpg"), all.map { it.fileName }, "Debe ordenar DESC por fecha")
        assertEquals(setOf(1L, 2L, 3L), all.map { it.id }.toSet(), "IDs autogenerados")
    }

    @Test
    fun upsert_sameIdReplaces() = runTest {
        val dao = JsonTransferDao(readRaw = { null }, writeRaw = { })
        dao.upsert(entity("a.jpg", createdAt = 100, id = 7))
        dao.upsert(entity("a.jpg", createdAt = 100, id = 7).copy(status = "FAILED"))
        val all = dao.observeAll().first()
        assertEquals(1, all.size)
        assertEquals("FAILED", all.single().status)
    }

    @Test
    fun persistsAcrossInstances() = runTest {
        val file = File.createTempFile("ignite-history", ".json")
        val writer = JsonTransferDao(
            readRaw = { file.takeIf { it.exists() }?.readText() },
            writeRaw = { file.writeText(it) },
        )
        writer.upsert(entity("screenshot.jpg", createdAt = 42))

        // Nueva instancia = reinicio de la app
        val reader = JsonTransferDao(
            readRaw = { file.takeIf { it.exists() }?.readText() },
            writeRaw = { file.writeText(it) },
        )
        val restored = reader.observeAll().first()
        assertEquals(1, restored.size)
        assertEquals("screenshot.jpg", restored.single().fileName)
        assertTrue(restored.single().id > 0)

        reader.clearAll()
        assertEquals(0, JsonTransferDao(
            readRaw = { file.takeIf { it.exists() }?.readText() },
            writeRaw = { },
        ).observeAll().first().size)
        file.delete()
    }

    @Test
    fun corruptFile_fallsBackToEmpty() = runTest {
        val dao = JsonTransferDao(readRaw = { "{no soy json" }, writeRaw = { })
        assertEquals(0, dao.observeAll().first().size)
    }
}
