package com.andyl.ignite.data.db

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
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
        // Los ticks de progreso se coalescan; todo estado terminal persiste ya.
        replaceSorted(current, forcePersist = entity.status != "IN_PROGRESS")
    }

    override fun observeAll(): Flow<List<TransferEntity>> = rows.asStateFlow()

    override suspend fun deleteById(id: Long) = mutex.withLock {
        replaceSorted(rows.value.filterNot { it.id == id }, forcePersist = true)
    }

    override suspend fun clearAll() = mutex.withLock {
        replaceSorted(emptyList(), forcePersist = true)
    }

    /**
     * Escribe a disco como máximo una vez por [PERSIST_COALESCE_MS]: los ticks
     * de progreso generan decenas de upserts por transferencia y serializar +
     * reescribir todo el JSON en cada uno era puro churn de RAM/dispatcher.
     * Cambios terminales o borrados persisten al instante.
     */
    private fun replaceSorted(list: List<TransferEntity>, forcePersist: Boolean) {
        val trimmed = list.sortedByDescending { it.createdAt }.take(MAX_ROWS)
        rows.value = trimmed
        persist(trimmed, forcePersist)
    }

    private var lastPersistAt = 0L
    private var pendingPersist: kotlinx.coroutines.Job? = null

    private fun persist(list: List<TransferEntity>, forcePersist: Boolean) {
        val snapshot = json.encodeToString(list.map { it.toRow() })
        val now = System.currentTimeMillis()
        val sinceLast = now - lastPersistAt
        if (forcePersist || sinceLast >= PERSIST_COALESCE_MS) {
            pendingPersist?.cancel()
            runCatching {
                writeRaw(snapshot)
                lastPersistAt = System.currentTimeMillis()
            }
        } else {
            pendingPersist?.cancel()
            pendingPersist = persistScope.launch {
                delay(PERSIST_COALESCE_MS - sinceLast)
                mutex.withLock {
                    runCatching {
                        writeRaw(json.encodeToString(rows.value.map { it.toRow() }))
                        lastPersistAt = System.currentTimeMillis()
                    }
                }
            }
        }
    }

    private fun load(): List<TransferEntity> = runCatching {
        readRaw()?.takeIf { it.isNotBlank() }
            ?.let { raw -> json.decodeFromString<List<TransferRow>>(raw).map { it.toEntity() } }
            .orEmpty()
    }.getOrDefault(emptyList())

    private companion object {
        const val MAX_ROWS = 500

        /** Coalescencia de escrituras del JSON (ticks de progreso). */
        const val PERSIST_COALESCE_MS = 750L
        private val persistScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

private fun TransferEntity.toRow() = TransferRow(
    id, fileName, sizeBytes, direction, peerName, peerHost, status, progress, createdAt,
)

private fun TransferRow.toEntity() = TransferEntity(
    id, fileName, sizeBytes, direction, peerName, peerHost, status, progress, createdAt,
)
