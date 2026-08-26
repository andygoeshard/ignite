package com.andyl.ignite.data.network

import com.andyl.ignite.data.DeviceInfo
import com.andyl.ignite.domain.TextSender
import com.andyl.ignite.domain.model.Device
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.Json

/**
 * Ktor-based implementation of [TextSender]. Posts a JSON body with the text
 * to the receiver's POST /message endpoint.
 */
class KtorTextSender(
    private val client: HttpClient,
    private val deviceInfo: DeviceInfo? = null,
    private val selfAddresses: Set<String> = emptySet(),
) : TextSender {

    private fun dialTarget(target: Device): String {
        val defaults = com.andyl.ignite.domain.model.TransferDefaults
        val isNotSelf = target.id != deviceInfo?.deviceId
        val ghostLoopback =
            target.port == defaults.PORT &&
                (target.host == "127.0.0.1" || target.host == "localhost")
        val claimsMyIp = target.host in selfAddresses
        if (isNotSelf && (ghostLoopback || claimsMyIp)) {
            return "127.0.0.1:${defaults.PORT + 1}"
        }
        return "${target.host}:${target.port}"
    }

    override suspend fun send(target: Device, text: String, pin: String?) {
        println("[Ignite][TXT] enviando mensaje a ${target.name} (${target.host}): «${text.take(80)}»")
        val response = client.post("http://${dialTarget(target)}/message") {
            contentType(ContentType.Application.Json)
            if (pin != null) header(HEADER_PIN, pin)
            if (deviceInfo != null) {
                header(HEADER_DEVICE_ID, deviceInfo.deviceId)
                header(HEADER_DEVICE_NAME, deviceInfo.deviceName)
            }
            setBody(Json.encodeToString(mapOf("text" to text)))
        }
        when (response.status) {
            HttpStatusCode.OK -> println("[Ignite][TXT] mensaje enviado a ${target.name}")
            HttpStatusCode.Unauthorized -> throw IllegalStateException("PIN incorrecto")
            else -> throw IllegalStateException("Error enviando mensaje: ${response.status}")
        }
    }

    private companion object {
        const val HEADER_PIN = "X-Ignite-Pin"
        const val HEADER_DEVICE_ID = "X-Ignite-Device-Id"
        const val HEADER_DEVICE_NAME = "X-Ignite-Device-Name"
    }
}
