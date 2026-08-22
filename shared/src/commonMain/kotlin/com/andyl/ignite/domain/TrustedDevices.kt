package com.andyl.ignite.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Dispositivo al que ya le pusimos su PIN al menos una vez: la próxima vez
 * no hace falta tipear el código (#recordar PIN).
 */
@Serializable
data class TrustedDevice(
    val deviceId: String,
    val name: String,
    val host: String,
    val pin: String,
    val addedAt: Long,
)

/**
 * Guarda los dispositivos emparejados. La persistencia (archivo JSON en
 * filesDir) la inyecta la plataforma; acá vive sólo la lógica.
 *
 * "Olvidar" saca el dispositivo: vuelve a pedir PIN y deja de auto-conectarse.
 */
class TrustedDevices(
    private val readRaw: () -> String?,
    private val writeRaw: (String) -> Unit,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()

    fun all(): List<TrustedDevice> = synchronized(lock) {
        runCatching {
            readRaw()?.takeIf { it.isNotBlank() }
                ?.let { json.decodeFromString<List<TrustedDevice>>(it) }
        }.getOrNull() ?: emptyList()
    }

    fun pinFor(deviceId: String): TrustedDevice? = all().firstOrNull { it.deviceId == deviceId }

    fun isTrusted(deviceId: String): Boolean = all().any { it.deviceId == deviceId }

    /** Persiste el PIN tras un envío exitoso. Silencioso si falla el disco. */
    fun remember(deviceId: String, name: String, host: String, pin: String) {
        if (pin.length != PIN_LENGTH || deviceId.isBlank()) return
        synchronized(lock) {
            val rest = all().filterNot { it.deviceId == deviceId }
            val updated = listOf(
                TrustedDevice(
                    deviceId = deviceId,
                    name = name,
                    host = host,
                    pin = pin,
                    addedAt = System.currentTimeMillis(),
                ),
            ) + rest
            runCatching { writeRaw(json.encodeToString(updated)) }
        }
    }

    /** Olvidar: devuelve true si había algo que borrar. */
    fun forget(deviceId: String): Boolean {
        synchronized(lock) {
            val before = all()
            val updated = before.filterNot { it.deviceId == deviceId }
            if (updated.size == before.size) return false
            val ok = runCatching { writeRaw(json.encodeToString(updated)); true }.getOrDefault(false)
            return ok
        }
    }

    companion object {
        const val PIN_LENGTH = 6
    }
}
