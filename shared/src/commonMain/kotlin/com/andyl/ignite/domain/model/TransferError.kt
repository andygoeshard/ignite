package com.andyl.ignite.domain.model

/**
 * Taxonomía de errores de transferencia (#26): mapea los mensajes crudos del
 * stack (Ktor/IO) a casos de dominio accionables para la UI.
 */
sealed class TransferError(open val detail: String?) {

    /** PIN incorrecto o solicitud rechazada por el receptor (403). */
    data class PinRejected(override val detail: String? = null) : TransferError(detail)

    /** El receptor tiene otra solicitud pendiente o recepción en curso (409). No reintenta. */
    data class Busy(override val detail: String? = null) : TransferError(detail)

    /** El destino no está accesible: IP mal, app cerrada, firewall u otra red. */
    data class Unreachable(val host: String?, override val detail: String? = null) : TransferError(detail)

    /** El destino dejó de responder dentro del plazo esperado. */
    data class Timeout(override val detail: String? = null) : TransferError(detail)

    /** La conexión se cortó a mitad de la transferencia. */
    data class ConnectionLost(override val detail: String? = null) : TransferError(detail)

    /** Error no clasificado. */
    data class Unexpected(override val detail: String? = null) : TransferError(detail)

    companion object {
        fun from(t: Throwable): TransferError {
            val m = t.message ?: ""
            return when {
                m.contains("PIN incorrecto", ignoreCase = true) ||
                    m.contains("rechazada", ignoreCase = true) ||
                    m.contains("rechazado", ignoreCase = true) -> PinRejected(m)

                m.contains("otra solicitud", ignoreCase = true) ||
                    m.contains("atendiendo otra", ignoreCase = true) ||
                    m.contains("ocupado", ignoreCase = true) -> Busy(m)

                t is java.net.UnknownHostException ||
                    t is java.net.ConnectException ||
                    m.contains("Failed to connect", ignoreCase = true) ||
                    m.contains("Connection refused", ignoreCase = true) ||
                    m.contains("no responde", ignoreCase = true) -> Unreachable(null, m)

                t is kotlinx.coroutines.TimeoutCancellationException ||
                    t is java.net.SocketTimeoutException ||
                    m.contains("timed out", ignoreCase = true) ||
                    m.contains("timeout", ignoreCase = true) -> Timeout(m)

                m.contains("STREAM CORTADO", ignoreCase = true) ||
                    m.contains("prematuro", ignoreCase = true) ||
                    m.contains("premature", ignoreCase = true) ||
                    m.contains("Connection reset", ignoreCase = true) ||
                    m.contains("Broken pipe", ignoreCase = true) ||
                    m.contains("unexpected eof", ignoreCase = true) -> ConnectionLost(m)

                else -> Unexpected(m.ifBlank { t::class.simpleName })
            }
        }
    }

    /** Mensaje accionable para mostrar al usuario, nombrando al peer. */
    fun userMessage(peerName: String): String = when (this) {
        is PinRejected ->
            "PIN incorrecto o solicitud rechazada por $peerName. Pedile el PIN actual y volvé a enviar."
        is Busy ->
            "$peerName está atendiendo otra solicitud. Esperá que termine y volvé a enviar."
        is Unreachable ->
            "No se pudo conectar con $peerName. Revisá que Ignite esté abierto allá, en la misma red Wi-Fi y sin bloqueo de firewall."
        is Timeout ->
            "$peerName no respondió a tiempo. Reintentá el envío."
        is ConnectionLost ->
            "Se cortó la conexión con $peerName a mitad del archivo. Reintentá cuando vuelva la red."
        is Unexpected ->
            "No se pudo enviar a $peerName${detail?.let { ": $it" }.orEmpty()}"
    }
}
