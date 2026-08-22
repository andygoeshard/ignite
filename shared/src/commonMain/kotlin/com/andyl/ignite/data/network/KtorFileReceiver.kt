package com.andyl.ignite.data.network

import com.andyl.ignite.data.AppStorage
import com.andyl.ignite.data.DeviceInfo
import com.andyl.ignite.domain.FileReceiver
import com.andyl.ignite.domain.IncomingEvent
import com.andyl.ignite.domain.PairingManager
import com.andyl.ignite.domain.TransferRepository
import com.andyl.ignite.domain.model.Beacon
import com.andyl.ignite.domain.model.Transfer
import com.andyl.ignite.domain.model.TransferDefaults
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.exception
import io.ktor.server.request.header
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Embedded Ktor HTTP server that receives file uploads and stores them in the
 * local storage directory.
 */
class KtorFileReceiver(
    private val storage: AppStorage,
    private val repository: TransferRepository,
    private val pairingManager: PairingManager,
    private val deviceInfo: DeviceInfo,
    private val port: Int,
    private val engineFactory: (Application.() -> Unit, Int) -> EmbeddedServer<*, *>,
    override val requiresApproval: Boolean = true,
) : FileReceiver {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _incomingEvents = MutableSharedFlow<IncomingEvent>(extraBufferCapacity = 64)
    override val incomingEvents: SharedFlow<IncomingEvent> = _incomingEvents.asSharedFlow()

    private var engine: EmbeddedServer<*, *>? = null

    private val pendingApprovals = mutableMapOf<String, CompletableDeferred<Boolean>>()
    private val startMutex = Mutex()

    override suspend fun start() = startMutex.withLock {
        if (engine != null) return@withLock
        var last: Exception? = null
        repeat(3) { attempt ->
            try {
                engine = engineFactory({ module() }, port).also { it.start(wait = false) }
                // CIO hace bind async en worker; esperamos y hacemos health-check a localhost
                delay(400)
                val ok = runCatching {
                    java.net.Socket().use { s ->
                        s.connect(java.net.InetSocketAddress("127.0.0.1", port), 500)
                        true
                    }
                }.getOrDefault(false)
                if (!ok) throw java.net.BindException("Health-check falló en :$port (TIME_WAIT)")
                return@withLock
            } catch (e: java.net.BindException) {
                last = e
                runCatching { engine?.stop(0, 0) }
                engine = null
                delay(800L * (attempt + 1))
            } catch (e: Exception) {
                if (e.message?.contains("Address already in use") == true || e is java.net.BindException) {
                    last = e
                    runCatching { engine?.stop(0, 0) }
                    engine = null
                    delay(800L * (attempt + 1))
                } else throw e
            }
        }
        throw last ?: IllegalStateException("No se pudo bindear :$port")
    }

    override suspend fun stop() {
        engine?.stop(gracePeriodMillis = 500, timeoutMillis = 1000)
        engine = null
        synchronized(pendingApprovals) {
            pendingApprovals.values.forEach { it.complete(false) }
            pendingApprovals.clear()
        }
    }

    override suspend fun decideApproval(transferId: String, approved: Boolean) {
        val deferred = synchronized(pendingApprovals) { pendingApprovals.remove(transferId) }
        deferred?.complete(approved)
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
            get("/beacon") {
                val beacon = Beacon(deviceId = deviceInfo.deviceId, deviceName = deviceInfo.deviceName, port = TransferDefaults.PORT)
                call.respondText(Json.encodeToString(beacon), io.ktor.http.ContentType.Application.Json)
            }
            // Offset query for resumption: returns existing bytes for given fileName
            get("/upload/status") {
                val fileName = call.request.queryParameters["fileName"]
                if (fileName.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, "fileName required")
                    return@get
                }
                // PIN check also for status
                val pin = call.request.header(HEADER_PIN) ?: call.request.queryParameters["pin"]
                if (!pairingManager.validate(pin)) {
                    call.respond(HttpStatusCode.Unauthorized, "Invalid PIN")
                    return@get
                }
                val target = File(storage.receiveDir(), fileName)
                val existing = if (target.exists()) target.length() else 0L
                call.response.header(HEADER_OFFSET, existing.toString())
                val json = """{"fileName":"$fileName","offset":$existing}"""
                call.respondText(json, io.ktor.http.ContentType.Application.Json)
            }
            get("/pin") {
                // Debug helper - in production remove; shows that server is up
                call.respondText("""{"requiresPin":true}""", io.ktor.http.ContentType.Application.Json)
            }
            post("/upload") {
                // 1) PIN validation
                val pin = call.request.header(HEADER_PIN) ?: call.request.queryParameters["pin"]
                if (!pairingManager.validate(pin)) {
                    call.respond(HttpStatusCode.Unauthorized, "Invalid PIN - mostrar el código en pantalla del receptor")
                    return@post
                }
                val requestedName = call.request.queryParameters["fileName"]
                val expectedSha = call.request.header(HEADER_SHA256) ?: call.request.queryParameters["sha256"]
                val offsetHeader = call.request.header(HEADER_OFFSET) ?: call.request.header(HttpHeaders.ContentRange)
                val offset = offsetHeader?.let { parseOffset(it) } ?: call.request.queryParameters["offset"]?.toLongOrNull() ?: 0L
                val totalBytesHeader = call.request.header(HEADER_TOTAL_BYTES)?.toLongOrNull()
                val totalBytes = totalBytesHeader ?: call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: 0L
                val peer = call.request.local.remoteHost
                var savedFile: File? = null

                // 2) Storage space check (before writing)
                if (totalBytes > 0) {
                    val dir = File(storage.receiveDir())
                    dir.mkdirs()
                    val usable = dir.usableSpace
                    if (usable in 1 until totalBytes) {
                        call.respond(HttpStatusCode.InsufficientStorage, "Sin espacio: ${usable / 1024 / 1024}MB libres, necesitas ${totalBytes / 1024 / 1024}MB")
                        return@post
                    }
                }

                // 3) Approval gate
                val probeFileName = requestedName ?: "archivo"
                val transferId = "$peer-$probeFileName-${System.currentTimeMillis()}"
                if (requiresApproval) {
                    val deferred = CompletableDeferred<Boolean>()
                    synchronized(pendingApprovals) { pendingApprovals[transferId] = deferred }
                    _incomingEvents.tryEmit(IncomingEvent.AwaitingApproval(probeFileName, peer, totalBytes, transferId))
                    val approved = withTimeoutOrNull(60_000) { deferred.await() } ?: false
                    if (!approved) {
                        synchronized(pendingApprovals) { pendingApprovals.remove(transferId) }
                        _incomingEvents.tryEmit(IncomingEvent.Failed(probeFileName, peer, "Rechazado por el usuario"))
                        call.respond(HttpStatusCode.Forbidden, "Transfer rejected by user")
                        return@post
                    }
                }

                runCatching {
                    val multipart = call.receiveMultipart()
                    multipart.forEachPart { part ->
                        if (part is PartData.FileItem) {
                            savedFile = receivePart(part, requestedName, peer, totalBytes, offset, expectedSha, transferId)
                        }
                        part.dispose()
                    }
                }.onFailure { error ->
                    val name = savedFile?.name ?: requestedName ?: "archivo"
                    // No borrar archivo parcial si es por corte de red: dejar para reanudación
                    val isResumeCandidate = error.message?.contains("SHA") == false
                    if (!isResumeCandidate) savedFile?.delete()
                    repository.upsert(
                        Transfer(
                            id = 0,
                            fileName = name,
                            sizeBytes = 0,
                            direction = Transfer.Direction.RECEIVED,
                            peerName = peer,
                            peerHost = peer,
                            status = Transfer.Status.FAILED,
                            progress = 0f,
                            createdAt = System.currentTimeMillis(),
                        ),
                    )
                    _incomingEvents.tryEmit(IncomingEvent.Failed(name, peer, error.message))
                    // Re-lanzar solo si no hemos respondido ya
                    if (call.response.status() == null) throw error else return@post
                }

                if (savedFile != null) {
                    if (expectedSha != null) call.response.header(HEADER_SHA256, expectedSha)
                    call.respond(HttpStatusCode.OK)
                } else call.respond(HttpStatusCode.BadRequest, "No file part received")
            }
        }
    }

    private suspend fun receivePart(
        part: PartData.FileItem,
        requestedName: String?,
        peer: String,
        totalBytes: Long,
        offset: Long,
        expectedSha256: String?,
        transferId: String,
    ): File {
        val fileName = part.originalFileName
            ?: requestedName
            ?: "received_${System.currentTimeMillis()}"
        // For resumption we must use deterministic target, not uniqueTarget with (1)
        val baseTarget = File(storage.receiveDir(), fileName)
        val target = if (offset > 0) baseTarget else uniqueTarget(baseTarget)
        target.parentFile?.mkdirs()

        // Verify offset matches existing file length for resumption
        if (offset > 0) {
            val existingLen = if (target.exists()) target.length() else 0L
            if (existingLen != offset) {
                throw IllegalStateException("Offset mismatch: server has $existingLen but client sent $offset. Reintenta sin reanudación.")
            }
        } else {
            // Fresh transfer: ensure we don't clobber existing file with offset 0, already handled by uniqueTarget
            if (target.exists() && target.length() > 0) {
                // uniqueTarget already gave us a new file, ok
            }
        }

        val record = Transfer(
            id = 0,
            fileName = fileName,
            sizeBytes = if (totalBytes > 0) totalBytes else offset,
            direction = Transfer.Direction.RECEIVED,
            peerName = peer,
            peerHost = peer,
            status = Transfer.Status.IN_PROGRESS,
            progress = if (offset > 0 && totalBytes > 0) (offset.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f,
            createdAt = System.currentTimeMillis(),
        )
        repository.upsert(record)
        _incomingEvents.tryEmit(IncomingEvent.Started(fileName, peer, totalBytes))

        var lastEmitted = record.progress
        val channel = part.provider()
        var received = offset
        val digest = if (expectedSha256 != null) MessageDigest.getInstance("SHA-256") else null
        // If resuming, we need to feed existing bytes into digest if SHA will be verified over whole file.
        // For simplicity we verify only the final file after transfer; so we digest existing part now.
        if (offset > 0 && digest != null && target.exists()) {
            // Digest existing prefix
            target.inputStream().use { input ->
                val buf = ByteArray(DEFAULT_CHUNK_SIZE)
                var remaining = offset
                while (remaining > 0) {
                    val toRead = minOf(buf.size.toLong(), remaining).toInt()
                    val r = input.read(buf, 0, toRead)
                    if (r <= 0) break
                    digest.update(buf, 0, r)
                    remaining -= r
                }
            }
        }
        FileOutputStream(target, offset > 0).use { out ->
            val buffer = ByteArray(DEFAULT_CHUNK_SIZE)
            while (true) {
                val read = channel.readAvailable(buffer, 0, buffer.size)
                if (read == -1) break
                if (read > 0) {
                    out.write(buffer, 0, read)
                    digest?.update(buffer, 0, read)
                    received += read
                    val fraction = if (totalBytes > 0) {
                        (received.toFloat() / totalBytes).coerceIn(0f, 1f)
                    } else {
                        continue
                    }
                    if (fraction - lastEmitted >= PROGRESS_STEP) {
                        lastEmitted = fraction
                        repository.upsert(record.copy(progress = fraction))
                        _incomingEvents.tryEmit(
                            IncomingEvent.Progress(fileName, peer, received, totalBytes, fraction),
                        )
                    }
                }
            }
        }

        // SHA-256 verification
        if (expectedSha256 != null && digest != null) {
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actual.equals(expectedSha256, ignoreCase = true)) {
                target.delete()
                repository.upsert(record.copy(status = Transfer.Status.FAILED, progress = 0f))
                _incomingEvents.tryEmit(IncomingEvent.Failed(fileName, peer, "SHA-256 mismatch: esperado $expectedSha256, recibido $actual"))
                throw IllegalStateException("SHA-256 verification failed")
            }
        }

        repository.upsert(
            record.copy(sizeBytes = received, status = Transfer.Status.COMPLETED, progress = 1f),
        )
        _incomingEvents.tryEmit(IncomingEvent.Completed(fileName, peer, target.absolutePath, received))
        // Cleanup pending map
        synchronized(pendingApprovals) { pendingApprovals.remove(transferId) }
        return target
    }

    private fun uniqueTarget(target: File): File {
        if (!target.exists()) return target
        val parent = target.parentFile ?: return target
        val base = target.nameWithoutExtension
        val ext = target.extension
        var index = 1
        while (true) {
            val candidate = if (ext.isBlank()) File(parent, "$base ($index)")
            else File(parent, "$base ($index).$ext")
            if (!candidate.exists()) return candidate
            index++
        }
    }

    private fun parseOffset(value: String): Long {
        // Supports "bytes 0-1023/2048" or "bytes=0-1023/2048" or plain "1024"
        val trimmed = value.trim()
        if (trimmed.matches(Regex("\\d+"))) return trimmed.toLongOrNull() ?: 0L
        val rangeRegex = Regex("""bytes[= ](\d+)-.*""")
        val m = rangeRegex.find(trimmed)
        if (m != null) return m.groupValues[1].toLongOrNull() ?: 0L
        // Content-Range: bytes 1024-2047/4096 -> offset is start
        return 0L
    }

    private companion object {
        const val DEFAULT_CHUNK_SIZE = 64 * 1024
        const val PROGRESS_STEP = 0.02f
        const val HEADER_PIN = "X-Ignite-Pin"
        const val HEADER_SHA256 = "X-Ignite-Sha256"
        const val HEADER_OFFSET = "X-Ignite-Offset"
        const val HEADER_TOTAL_BYTES = "X-Ignite-Total-Bytes"
    }
}
