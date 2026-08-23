package com.andyl.ignite.data.db

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
private data class TransferRow(
    val id: Long,
    val fileName: String,
    val sizeBytes: Long,
    val direction: String,
    val peerName: String,
    val peerHost: String,
    val status: String,
    val progress: Float,
    val createdAt: Long,
)

/**
 * [TransferDao] sin Room: lista en memoria + persistencia a un archivo JSON
 * plano (mismo enfoque que TrustedDevices). Reemplaza al Noop que descartaba
 * TODO — el historial ahora funciona dentro de la sesión Y sobrevive
 * reinicios, en desktop y Android, sin KSP de por medio.
 */
class JsonTransferDao(
    private val readRaw: () -> String?,
    private val writeRaw: (String) -> Unit,
) : TransferDao {

    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val rows = MutableStateFlow(load())

    override suspend fun upsert(entity: TransferEntity) = mutex.withLock {
        val current = rows.value.toMutableList()
        current.removeAll { it.id == entity.id }
        // id nuevo: max+1 (autoGenerate manual)
        val newId = if (entity.id == 0L) (current.maxOfOrNull { it.id } ?: 0L) + 1 else entity.id
        current.add(entity.copy(id = newId))
        replaceSorted(current)
    }

    override fun observeAll(): Flow<List<TransferEntity>> = rows.asStateFlow()

    override suspend fun deleteById(id: Long) = mutex.withLock {
        replaceSorted(rows.value.filterNot { it.id == id })
    }

    override suspend fun clearAll() = mutex.withLock {
        replaceSorted(emptyList())
    }

    private fun replaceSorted(list: List<TransferEntity>) {
        val trimmed = list.sortedByDescending { it.createdAt }.take(MAX_ROWS)
        rows.value = trimmed
        persist(trimmed)
    }

    private fun persist(list: List<TransferEntity>) {
        runCatching {
            writeRaw(json.encodeToString(list.map { it.toRow() }))
        }
    }

    private fun load(): List<TransferEntity> = runCatching {
        readRaw()?.takeIf { it.isNotBlank() }
            ?.let { raw -> json.decodeFromString<List<TransferRow>>(raw).map { it.toEntity() } }
            .orEmpty()
    }.getOrDefault(emptyList())

    private companion object {
        const val MAX_ROWS = 500
    }
}

private fun TransferEntity.toRow() = TransferRow(
    id, fileName, sizeBytes, direction, peerName, peerHost, status, progress, createdAt,
)

private fun TransferRow.toEntity() = TransferEntity(
    id, fileName, sizeBytes, direction, peerName, peerHost, status, progress, createdAt,
)
