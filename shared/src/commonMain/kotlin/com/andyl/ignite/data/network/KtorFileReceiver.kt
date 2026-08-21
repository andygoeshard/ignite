package com.andyl.ignite.data.network

import com.andyl.ignite.data.AppStorage
import com.andyl.ignite.domain.FileReceiver
import com.andyl.ignite.domain.TransferRepository
import com.andyl.ignite.domain.model.Transfer
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.exception
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File
import java.io.FileOutputStream

/**
 * Embedded Ktor HTTP server that receives file uploads and stores them in the
 * local storage directory.
 */
class KtorFileReceiver(
    private val storage: AppStorage,
    private val repository: TransferRepository,
    private val port: Int,
    private val engineFactory: (Application.() -> Unit, Int) -> EmbeddedServer<*, *>,
) : FileReceiver {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _receivedTransfers = MutableSharedFlow<Transfer>(extraBufferCapacity = 32)
    override val receivedTransfers: SharedFlow<Transfer> = _receivedTransfers.asSharedFlow()

    private var engine: EmbeddedServer<*, *>? = null

    override suspend fun start() {
        if (engine != null) return
        engine = engineFactory({ module() }, port).also { it.start(wait = false) }
    }

    override suspend fun stop() {
        engine?.stop(gracePeriodMillis = 500, timeoutMillis = 1000)
        engine = null
    }

    private fun Application.module() {
        install(CallLogging)
        install(StatusPages) {
            exception<Throwable> { call, _ ->
                call.respond(HttpStatusCode.InternalServerError)
            }
        }
        routing {
            get("/") {
                call.respond(HttpStatusCode.OK)
            }
            post("/upload") {
                val requestedName = call.request.queryParameters["fileName"]
                val totalBytes = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: 0L
                val peer = call.request.local.remoteHost
                var saved = false

                val multipart = call.receiveMultipart()
                multipart.forEachPart { part ->
                    if (part is PartData.FileItem) {
                        val fileName = part.originalFileName
                            ?: requestedName
                            ?: "received_${System.currentTimeMillis()}"
                        val target = File(storage.receiveDir(), fileName)
                        target.parentFile?.mkdirs()

                        val record = Transfer(
                            id = 0,
                            fileName = fileName,
                            sizeBytes = totalBytes,
                            direction = Transfer.Direction.RECEIVED,
                            peerName = peer,
                            peerHost = peer,
                            status = Transfer.Status.IN_PROGRESS,
                            progress = 0f,
                            createdAt = System.currentTimeMillis(),
                        )
                        repository.upsert(record)

                        val channel = part.provider()
                        var received = 0L
                        FileOutputStream(target).use { out ->
                            val buffer = ByteArray(DEFAULT_CHUNK_SIZE)
                            while (true) {
                                val read = channel.readAvailable(buffer, 0, buffer.size)
                                if (read == -1) break
                                if (read > 0) {
                                    out.write(buffer, 0, read)
                                    received += read
                                    if (totalBytes > 0) {
                                        _receivedTransfers.tryEmit(
                                            record.copy(
                                                progress = (received.toFloat() / totalBytes).coerceIn(0f, 1f),
                                            ),
                                        )
                                    }
                                }
                            }
                        }

                        val progress = if (totalBytes > 0) (received.toFloat() / totalBytes).coerceIn(0f, 1f) else 1f
                        val done = record.copy(
                            sizeBytes = received,
                            status = Transfer.Status.COMPLETED,
                            progress = progress,
                        )
                        repository.upsert(done)
                        _receivedTransfers.tryEmit(done)
                        saved = true
                    }
                    part.dispose()
                }

                if (saved) call.respond(HttpStatusCode.OK)
                else call.respond(HttpStatusCode.BadRequest, "No file part received")
            }
        }
    }

    private companion object {
        const val DEFAULT_CHUNK_SIZE = 64 * 1024
    }
}
