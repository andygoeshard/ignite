package com.andyl.ignite.data.network

import com.andyl.ignite.domain.FileSender
import com.andyl.ignite.domain.model.Device
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.ChannelProvider
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream

class KtorFileSender(
    private val client: HttpClient,
    private val chunkSize: Int = DEFAULT_CHUNK_SIZE,
) : FileSender {

    override suspend fun send(
        target: Device,
        localPath: String,
        fileName: String,
        sizeBytes: Long,
        pin: String?,
    ): Flow<Float> = callbackFlow {
        // 1) Compute SHA-256 upfront (for integrity header)
        println("[Ignite][SND] inicio: '$fileName' (${sizeBytes / 1024 / 1024}MB) → ${target.host}:${target.port} pin=${pin != null}")
        val t0Sha = System.currentTimeMillis()
        val sha256 = runCatching { sha256File(File(localPath)) }
            .onFailure {
                println("[Ignite][SND] sha256File falló para $localPath: ${it.message}")
                it.printStackTrace()
            }
            .getOrNull()
        println("[Ignite][SND] sha256 calculado en ${System.currentTimeMillis() - t0Sha}ms: ${sha256?.take(12)}…")

        // 2) Query resumption offset (best-effort, falls back to 0)
        var offset = 0L
        if (pin != null) {
            offset = runCatching { queryOffset(target, fileName, pin) }
                .onFailure { println("[Ignite][SND] queryOffset falló: ${it.message}") }
                .getOrDefault(0L)
            println("[Ignite][SND] offset remoto: $offset")
            if (offset >= sizeBytes) offset = 0L // already complete / bogus
        }

        var attempt = 0
        var currentOffset = offset
        var lastError: Exception? = null
        while (attempt < 2) {
            try {
                executeUpload(target, localPath, fileName, sizeBytes, pin, sha256, currentOffset)
                trySend(1f)
                close()
                awaitClose { }
                return@callbackFlow
            } catch (e: Exception) {
                lastError = e
                println("[Ignite][SND] intento $attempt falló: ${e::class.simpleName}: ${e.message}")
                e.printStackTrace()
                // If offset mismatch (409) retry once without offset
                if (e.message?.contains("Offset mismatch") == true && currentOffset > 0) {
                    currentOffset = 0L
                    attempt++
                    continue
                }
                // 401 invalid PIN should not retry
                break
            }
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
    ) {
        val remaining = sizeBytes - offset
        var sent = offset
        val t0 = System.currentTimeMillis()
        var lastPct = ((offset * 100) / sizeBytes.coerceAtLeast(1)).toInt()
        println("[Ignite][SND] subiendo '$fileName' desde offset $offset (${remaining / 1024 / 1024}MB restantes)")
        // Report initial progress if resuming
        if (offset > 0) trySend((offset.toFloat() / sizeBytes).coerceIn(0f, 1f))

        val provider = ChannelProvider(size = remaining) {
            val channel = ByteChannel()
            // launch producer
            launch(Dispatchers.IO) {
                try {
                    BufferedInputStream(FileInputStream(localPath), chunkSize).use { input ->
                        if (offset > 0) {
                            var skipped = 0L
                            while (skipped < offset) {
                                val s = input.skip(offset - skipped)
                                if (s <= 0) break
                                skipped += s
                            }
                        }
                        val buffer = ByteArray(chunkSize)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            if (read > 0) {
                                channel.writeFully(buffer, 0, read)
                                sent += read
                                trySend((sent.toFloat() / sizeBytes).coerceIn(0f, 1f))
                                val pct = ((sent * 100) / sizeBytes.coerceAtLeast(1)).toInt()
                                if (pct >= lastPct + 25) {
                                    lastPct = pct
                                    println("[Ignite][SND] progreso $pct% (${"${sent / 1024 / 1024}MB"}/${sizeBytes / 1024 / 1024}MB)")
                                }
                            }
                        }
                    }
                    println("[Ignite][SND] archivo leído completo: $sent bytes en ${System.currentTimeMillis() - t0}ms")
                    channel.close()
                } catch (e: Exception) {
                    println("[Ignite][SND] productor cortado a los $sent bytes: ${e::class.simpleName}: ${e.message}")
                    channel.cancel(e)
                }
            }
            channel
        }

        val response: HttpResponse = client.submitFormWithBinaryData(
            url = "http://${target.host}:${target.port}/upload",
            formData = formData {
                append("file", provider, Headers.build {
                    append(HttpHeaders.ContentType, "application/octet-stream")
                    append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                })
            },
        ) {
            url.parameters.append("fileName", fileName)
            if (sha256 != null) header(HEADER_SHA256, sha256)
            if (pin != null) header(HEADER_PIN, pin)
            if (offset > 0) header(HEADER_OFFSET, offset.toString())
            header(HEADER_TOTAL_BYTES, sizeBytes.toString())
        }

        println("[Ignite][SND] respuesta: ${response.status} en ${System.currentTimeMillis() - t0}ms (${sent / 1024 / 1024}MB enviados)")
        when (response.status) {
            HttpStatusCode.OK -> return
            HttpStatusCode.Unauthorized -> throw IllegalStateException("PIN incorrecto - verifica el código en el receptor")
            HttpStatusCode.Forbidden -> throw IllegalStateException("Transferencia rechazada por el receptor")
            HttpStatusCode.InsufficientStorage -> throw IllegalStateException("Sin espacio en el receptor")
            HttpStatusCode.Conflict -> throw IllegalStateException("Offset mismatch")
            else -> throw IllegalStateException("Transfer failed: ${response.status} ${runCatching { response.bodyAsText() }.getOrNull()}")
        }
    }

    private suspend fun queryOffset(target: Device, fileName: String, pin: String): Long {
        val resp = client.get("http://${target.host}:${target.port}/upload/status") {
            url.parameters.append("fileName", fileName)
            header(HEADER_PIN, pin)
        }
        if (resp.status != HttpStatusCode.OK) return 0L
        val body = resp.bodyAsText()
        return runCatching {
            Json.parseToJsonElement(body).jsonObject["offset"]?.jsonPrimitive?.longOrNull
        }.getOrNull() ?: 0L
    }

    private companion object {
        const val DEFAULT_CHUNK_SIZE = 64 * 1024
        const val HEADER_PIN = "X-Ignite-Pin"
        const val HEADER_SHA256 = "X-Ignite-Sha256"
        const val HEADER_OFFSET = "X-Ignite-Offset"
        const val HEADER_TOTAL_BYTES = "X-Ignite-Total-Bytes"
    }
}