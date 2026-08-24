package com.andyl.ignite.data.network

import com.andyl.ignite.data.DeviceInfo
import com.andyl.ignite.data.createThumbnail
import com.andyl.ignite.domain.FileSender
import com.andyl.ignite.domain.model.Device
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.BufferedInputStream
import java.io.FileNotFoundException

class KtorFileSender(
    private val client: HttpClient,
    private val deviceInfo: DeviceInfo? = null,
    private val chunkSize: Int = DEFAULT_CHUNK_SIZE,
    private val selfAddresses: Set<String> = emptySet(),
) : FileSender {

    /**
     * El emulador Android vive detrás del NAT del host: sus paquetes llegan
     * con IP 127.0.0.1 o con la PROPIA IP de la máquina. En ambos casos NO se
     * puede dialear directo: hay que pasar por el túnel adb reverse, que vive
     * en loopback pero en OTRO puerto (el receptor local ya ocupa PORT).
     * Setup: `adb reverse tcp:48214 tcp:48213`
     */
    private fun dialTarget(target: Device): String {
        val defaults = com.andyl.ignite.domain.model.TransferDefaults
        val isNotSelf = target.id != deviceInfo?.deviceId
        // Solo el emulador real: beacon en loopback CON el puerto estándar.
        // Los tests usan loopback con puerto efímero → no se tocan.
        val ghostLoopback =
            target.port == defaults.PORT &&
                (target.host == "127.0.0.1" || target.host == "localhost")
        val claimsMyIp = target.host in selfAddresses
        if (isNotSelf && (ghostLoopback || claimsMyIp)) {
            return "127.0.0.1:${defaults.PORT + 1}"
        }
        return "${target.host}:${target.port}"
    }

    override suspend fun send(
        target: Device,
        localPath: String,
        fileName: String,
        sizeBytes: Long,
        pin: String?,
    ): Flow<Float> = callbackFlow {
        // 1) Compute SHA-256 upfront (for integrity header)
        println("[Ignite][SND] inicio: '$fileName' (${sizeBytes / 1024 / 1024}MB) → ${target.host}:${target.port} pin=${pin != null} (dial ${dialTarget(target)})")
        val t0Sha = System.currentTimeMillis()
        val sha256 = runCatching { sha256Transfer(localPath) }
            .onFailure {
                println("[Ignite][SND] sha256File falló para $localPath: ${it.message}")
                it.printStackTrace()
            }
            .getOrNull()
        println("[Ignite][SND] sha256 calculado en ${System.currentTimeMillis() - t0Sha}ms: ${sha256?.take(12)}…")

        // ID estable por archivo: los reintentos comparten la misma aprobación
        // en el receptor en vez de apilar solicitudes nuevas (bug de duplicados).
        val uploadId = sha256Hex("$fileName:$sizeBytes".encodeToByteArray()).take(16)
        println("[Ignite][SND] uploadId=$uploadId")

        // 1.b) Miniatura best-effort para el diálogo de aprobación del receptor.
        if (deviceInfo != null && isPreviewable(fileName)) {
            runCatching { sendPreview(target, localPath, uploadId, pin) }
                .onFailure { println("[Ignite][SND] preview falló (ignorado): ${it.message}") }
        }

        // 2) Query resumption offset (best-effort, falls back to 0)
        var offset = 0L
        if (pin != null) {
            offset = runCatching { queryOffset(target, fileName, pin) }
                .onFailure { println("[Ignite][SND] queryOffset falló: ${it.message}") }
                .getOrDefault(0L)
            println("[Ignite][SND] offset remoto: $offset")
            if (offset >= sizeBytes && sizeBytes > 0) {
                // El receptor YA tiene el archivo completo (ej.: la respuesta OK
                // se perdió tras una transferencia exitosa). Reenviarlo crearía
                // un duplicado "(1)" — éxito idempotente y listo.
                println("[Ignite][SND] '$fileName' ya está completo en el receptor — no se reenvía")
                trySend(1f)
                close()
                awaitClose { }
                return@callbackFlow
            }
        }

        var attempt = 0
        var currentOffset = offset
        // Byte más lejano alcanzado en cualquier intento: mientras la red
        // inestable siga sumando aunque sea 0.5KB/s, el presupuesto de
        // reintentos se renueva (paciencia infinita para transferencias vivas).
        var bestProgress = offset
        var lastError: Exception? = null
        while (attempt < MAX_SEND_ATTEMPTS) {
            // El upload corre FUERA del catch de errores: si el productor se
            // cancela justo después del éxito (carrera clásica de callbackFlow),
            // esa cancelación NO es un fallo de transferencia — antes causaba el
            // falso "no se pudo enviar" tras un 200 OK y disparaba reintentos.
            val outcome = runCatching {
                executeUpload(target, localPath, fileName, sizeBytes, pin, sha256, currentOffset, uploadId)
            }
            if (outcome.isSuccess) {
                println("[Ignite][SND] '$fileName' confirmado por el receptor")
                trySend(1f)
                close()
                runCatching { awaitClose { } }
                return@callbackFlow
            }
            val e = outcome.exceptionOrNull() as? Exception ?: IllegalStateException("Transfer failed")
            lastError = e
            val msg = e.message.orEmpty()
            println("[Ignite][SND] intento ${attempt + 1}/$MAX_SEND_ATTEMPTS falló: ${e::class.simpleName}: $msg")
            e.printStackTrace()

            // Errores definitivos: no tiene sentido insistir.
            if ("PIN incorrecto" in msg || "rechazada" in msg || "Sin espacio" in msg) break

            // 409: el receptor tiene OTRO largo. Antes reseteábamos a 0 y
            // perdíamos todo el progreso; ahora recuperamos el offset real
            // que el servidor reporta en su mensaje.
            if ("Offset mismatch" in msg) {
                currentOffset = Regex("server has (\\d+)").find(msg)
                    ?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                bestProgress = currentOffset
                attempt++
                continue
            }

            // ¿Hubo avance? Renovar presupuesto de reintentos.
            val sentThisAttempt = (e as? AttemptAborted)?.sentBytes ?: currentOffset
            if (sentThisAttempt > bestProgress) {
                bestProgress = sentThisAttempt
                attempt = 0
            } else {
                attempt++
            }
            currentOffset = bestProgress
            val backoffMs = BACKOFF_MS[(attempt - 1).coerceIn(0, BACKOFF_MS.lastIndex)]
            println("[Ignite][SND] reintento en ${backoffMs / 1000}s desde byte $currentOffset")
            kotlinx.coroutines.delay(backoffMs)
        }
        close(lastError ?: IllegalStateException("Transfer failed"))
        awaitClose { }
    }

    private suspend fun kotlinx.coroutines.channels.ProducerScope<Float>.executeUpload(
        target: Device,
        localPath: String,
        fileName: String,
        sizeBytes: Long,
        pin: String?,
        sha256: String?,
        offset: Long,
        uploadId: String,
    ) {
        val remaining = sizeBytes - offset
        var sent = offset
        var lastEmittedBytes = offset
        val t0 = System.currentTimeMillis()
        var lastPct = ((offset * 100) / sizeBytes.coerceAtLeast(1)).toInt()
        println("[Ignite][SND] subiendo '$fileName' desde offset $offset (${remaining / 1024 / 1024}MB restantes)")
        // Report initial progress if resuming
        if (offset > 0) trySend(((offset.toFloat() / sizeBytes) * 0.999f).coerceIn(0f, 0.999f))

        // Cuerpo binario crudo (sin multipart): sin límites de parser ni overhead de boundaries.
        // Los metadatos viajan por headers (fileName, sha256, offset, total bytes).
        val body = object : OutgoingContent.WriteChannelContent() {
            override val contentLength: Long = remaining

            override suspend fun writeTo(channel: ByteWriteChannel) {
                try {
                    // content:// (Android) o path plano (desktop) según plataforma.
                    val input = com.andyl.ignite.data.openTransferStream(localPath)
                    if (input == null) throw java.io.FileNotFoundException(localPath)
                    BufferedInputStream(input, chunkSize).use { stream ->
                        if (offset > 0) {
                            var skipped = 0L
                            while (skipped < offset) {
                                val s = stream.skip(offset - skipped)
                                if (s <= 0) break
                                skipped += s
                            }
                        }
                        val buffer = ByteArray(chunkSize)
                        var read: Int
                        while (stream.read(buffer).also { read = it } != -1) {
                            if (read > 0) {
                                channel.writeFully(buffer, 0, read)
                                sent += read
                                // Throttle: emitir como máximo cada ~0.5% o 64KB
                                // (antes 512KB — un archivo chico no generaba NI
                                // UNA emisión y la barra quedaba congelada).
                                val minDelta = maxOf(64L * 1024L, sizeBytes / 200)
                                if (sent - lastEmittedBytes >= minDelta) {
                                    lastEmittedBytes = sent
                                    // Tope 0.999 mientras falta la confirmación:
                                    // el 100% real llega tras el 200 OK.
                                    trySend(((sent.toFloat() / sizeBytes) * 0.999f).coerceIn(0f, 0.999f))
                                }
                                val pct = ((sent * 100) / sizeBytes.coerceAtLeast(1)).toInt()
                                if (pct >= lastPct + 25) {
                                    lastPct = pct
                                    println("[Ignite][SND] progreso $pct% (${"${sent / 1024 / 1024}MB"}/${sizeBytes / 1024 / 1024}MB)")
                                }
                            }
                        }
                    }
                    println("[Ignite][SND] archivo leído completo: $sent bytes en ${System.currentTimeMillis() - t0}ms")
                } catch (e: Exception) {
                    println("[Ignite][SND] productor cortado a los $sent bytes: ${e::class.simpleName}: ${e.message}")
                    throw e
                }
            }
        }

        val response: HttpResponse = try {
            client.post("http://${dialTarget(target)}/upload") {
                url.parameters.append("fileName", fileName)
                header(HttpHeaders.ContentType, ContentType.Application.OctetStream.toString())
                if (sha256 != null) header(HEADER_SHA256, sha256)
                if (pin != null) header(HEADER_PIN, pin)
                if (offset > 0) header(HEADER_OFFSET, offset.toString())
                header(HEADER_TOTAL_BYTES, sizeBytes.toString())
                header(HEADER_UPLOAD_ID, uploadId)
                // Identidad: le permite al receptor aplicar su política de confianza
                // (aceptar siempre / silencioso) sin preguntar.
                if (deviceInfo != null) {
                    header(HEADER_DEVICE_ID, deviceInfo.deviceId)
                    header(HEADER_DEVICE_NAME, deviceInfo.deviceName)
                }
                setBody(body)
            }
        } catch (e: Exception) {
            // Red cortada a mitad del body: informar hasta dónde llegamos para
            // que la política de reintentos renueve presupuesto si hubo avance.
            println("[Ignite][SND] conexión cortada a los $sent bytes: ${e::class.simpleName}: ${e.message}")
            throw AttemptAborted(sent, e)
        }

        println("[Ignite][SND] respuesta: ${response.status} en ${System.currentTimeMillis() - t0}ms (${sent / 1024 / 1024}MB enviados)")
        when (response.status) {
            HttpStatusCode.OK -> return
            HttpStatusCode.Unauthorized -> throw IllegalStateException("PIN incorrecto - verifica el código en el receptor")
            HttpStatusCode.Forbidden -> throw IllegalStateException("Transferencia rechazada por el receptor")
            HttpStatusCode.InsufficientStorage -> throw IllegalStateException("Sin espacio en el receptor")
            HttpStatusCode.Conflict -> {
                val bodyText = runCatching { response.bodyAsText() }.getOrNull().orEmpty()
                throw IllegalStateException(bodyText.ifBlank { "Offset mismatch" })
            }
            else -> throw IllegalStateException("Transfer failed: ${response.status} ${runCatching { response.bodyAsText() }.getOrNull()}")
        }
    }

    private suspend fun queryOffset(target: Device, fileName: String, pin: String): Long {
        val resp = client.get("http://${dialTarget(target)}/upload/status") {
            url.parameters.append("fileName", fileName)
            header(HEADER_PIN, pin)
        }
        if (resp.status != HttpStatusCode.OK) return 0L
        val body = resp.bodyAsText()
        return runCatching {
            Json.parseToJsonElement(body).jsonObject["offset"]?.jsonPrimitive?.longOrNull
        }.getOrNull() ?: 0L
    }

    private suspend fun sendPreview(target: Device, localPath: String, uploadId: String, pin: String?) {
        val bytes = createThumbnail(localPath, PREVIEW_MAX_PX) ?: return
        println("[Ignite][SND] preview generada (${bytes.size}B) para upload=${uploadId.take(8)}…")
        val response = client.post("http://${dialTarget(target)}/preview") {
            url.parameters.append("uploadId", uploadId)
            if (pin != null) header(HEADER_PIN, pin)
            header(HttpHeaders.ContentType, "image/jpeg")
            setBody(bytes)
        }
        println("[Ignite][SND] preview respuesta: ${response.status}")
    }

    private fun isPreviewable(fileName: String): Boolean {
        val name = fileName.lowercase()
        return PREVIEWABLE_EXTS.any { name.endsWith(it) }
    }

    private companion object {
        const val DEFAULT_CHUNK_SIZE = 64 * 1024

        /** Reintentos SIN progreso; cada avance renueva el contador. */
        const val MAX_SEND_ATTEMPTS = 8

        /** Backoff entre reintentos (indexado por attempt-1). */
        val BACKOFF_MS = longArrayOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L, 15_000L, 15_000L)

        /** Fallo de red a mitad del body: conserva el byte alcanzado. */
        class AttemptAborted(val sentBytes: Long, cause: Exception) : Exception(cause.message, cause)
        const val HEADER_PIN = "X-Ignite-Pin"
        const val HEADER_SHA256 = "X-Ignite-Sha256"
        const val HEADER_OFFSET = "X-Ignite-Offset"
        const val HEADER_TOTAL_BYTES = "X-Ignite-Total-Bytes"
        const val HEADER_UPLOAD_ID = "X-Ignite-Upload-Id"
        const val HEADER_DEVICE_ID = "X-Ignite-Device-Id"
        const val HEADER_DEVICE_NAME = "X-Ignite-Device-Name"

        /** Lado más largo de la miniatura enviada al receptor. */
        const val PREVIEW_MAX_PX = 512

        /** Extensiones con preview: imágenes (todas las plataformas) + video (solo Android genera frame). */
        val PREVIEWABLE_EXTS = listOf(
            ".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp",
            ".mp4", ".mov", ".mkv", ".webm", ".avi",
        )
    }
}