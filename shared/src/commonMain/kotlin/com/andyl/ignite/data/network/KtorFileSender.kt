package com.andyl.ignite.data.network

import com.andyl.ignite.domain.FileSender
import com.andyl.ignite.domain.model.Device
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.ChannelProvider
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.statement.HttpResponse
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
import java.io.BufferedInputStream
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
    ): Flow<Float> = callbackFlow {
        var sent = 0L
        val producer = this

        // Stream the file through a Ktor ByteChannel, reporting progress as
        // bytes are produced, while the HTTP client consumes it in parallel.
        val provider = ChannelProvider(size = sizeBytes) {
            val channel = ByteChannel()
            producer.launch(Dispatchers.IO) {
                try {
                    BufferedInputStream(FileInputStream(localPath), chunkSize).use { input ->
                        val buffer = ByteArray(chunkSize)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            if (read > 0) {
                                channel.writeFully(buffer, 0, read)
                                sent += read
                                trySend((sent.toFloat() / sizeBytes).coerceIn(0f, 1f))
                            }
                        }
                    }
                    channel.close()
                } catch (e: Exception) {
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
        }

        if (response.status == HttpStatusCode.OK) {
            trySend(1f)
        } else {
            close(IllegalStateException("Transfer failed: ${response.status}"))
        }
        close()
        awaitClose { }
    }

    private companion object {
        const val DEFAULT_CHUNK_SIZE = 64 * 1024
    }
}