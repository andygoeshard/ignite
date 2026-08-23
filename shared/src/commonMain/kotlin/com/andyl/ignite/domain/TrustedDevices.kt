package com.andyl.ignite.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Qué hace este dispositivo cuando el par le manda algo.
 * Es LA diferencia con LocalSend: AirDrop no te pregunta dos veces.
 */
@Serializable
enum class TrustPolicy {
    /** Preguntar siempre (comportamiento clásico). */
    ASK,

    /** Aceptar sin preguntar; progreso y notificación normales. */
    AUTO,

    /** Aceptar sin preguntar y sin ruido: solo queda en el historial. */
    SILENT,
}

/**
 * Dispositivo emparejado. Sirve para ambas direcciones:
 * - [pin] recordado ⇒ puedo enviarle sin tipear el código.
 * - [policy] distinto de ASK ⇒ acepta lo que yo le mando sin preguntar.
 */
@Serializable
data class TrustedDevice(
    val deviceId: String,
    val name: String,
    val host: String,

    /** PIN del peer para enviarle archivos. Null si solo confiamos para recibir de él. */
    val pin: String? = null,

    val addedAt: Long,
    val policy: TrustPolicy = TrustPolicy.ASK,
)

/**
 * Guarda los dispositivos emparejados. La persistencia (archivo JSON en
 * filesDir) la inyecta la plataforma; acá vive sólo la lógica.
 *
 * "Olvidar" saca el dispositivo: vuelve a pedir PIN y vuelve a preguntar.
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

    /** Política de recepción para un par; ASK si no lo conocemos. */
    fun policyFor(deviceId: String): TrustPolicy =
        all().firstOrNull { it.deviceId == deviceId }?.policy ?: TrustPolicy.ASK

    /**
     * Persiste confianza tras un envío exitoso (pin real) o al emparejar por
     * QR / "aceptar siempre" (pin null, política explícita).
     */
    fun remember(
        deviceId: String,
        name: String,
        host: String,
        pin: String?,
        policy: TrustPolicy = TrustPolicy.ASK,
    ) {
        if ((pin != null && pin.length != PIN_LENGTH) || deviceId.isBlank()) return
        synchronized(lock) {
            val rest = all().filterNot { it.deviceId == deviceId }
            val updated = listOf(
                TrustedDevice(
                    deviceId = deviceId,
                    name = name,
                    host = host,
                    pin = pin,
                    addedAt = System.currentTimeMillis(),
                    policy = policy,
                ),
            ) + rest
            runCatching { writeRaw(json.encodeToString(updated)) }
        }
    }

    /** Cambia la política de un dispositivo existente. Devuelve la entrada actualizada o null. */
    fun setPolicy(deviceId: String, policy: TrustPolicy): TrustedDevice? {
        synchronized(lock) {
            val current = all()
            val target = current.firstOrNull { it.deviceId == deviceId } ?: return null
            val updated = listOf(target.copy(policy = policy)) + current.filterNot { it.deviceId == deviceId }
            val ok = runCatching { writeRaw(json.encodeToString(updated)); true }.getOrDefault(false)
            return if (ok) target.copy(policy = policy) else null
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
