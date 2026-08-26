package com.andyl.ignite.data.network

import com.andyl.ignite.data.AppStorage
import com.andyl.ignite.data.DeviceInfo
import com.andyl.ignite.domain.FileReceiver
import com.andyl.ignite.domain.IncomingEvent
import com.andyl.ignite.domain.PairingManager
import com.andyl.ignite.domain.TransferRepository
import com.andyl.ignite.domain.TrustPolicy
import com.andyl.ignite.domain.TrustedDevices
import com.andyl.ignite.domain.model.Beacon
import com.andyl.ignite.domain.model.Transfer
import com.andyl.ignite.domain.model.TransferDefaults
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.exception
import io.ktor.server.request.header
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.uri
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.readRemaining
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
import kotlinx.io.readByteArray
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** Identidad declarada por el emisor en el handshake /pair. */
private data class PairIdentity(val id: String?, val name: String?, val pin: String? = null)

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
    private val trustedDevices: TrustedDevices? = null,
    override val requiresApproval: Boolean = true,
) : FileReceiver {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _incomingEvents = MutableSharedFlow<IncomingEvent>(extraBufferCapacity = 64)
    override val incomingEvents: SharedFlow<IncomingEvent> = _incomingEvents.asSharedFlow()

    private var engine: EmbeddedServer<*, *>? = null

    /**
     * Aprobación pendiente por uploadId (estable entre reintentos del sender).
     * [deadline] se extiende cada vez que el mismo archivo vuelve a llegar,
     * así la ventana de "Más tarde" corre desde la última actividad.
     */
    private class PendingApproval(val transferId: String) {
        val deferred = CompletableDeferred<Boolean>()
        @Volatile var deadline: Long = System.currentTimeMillis() + APPROVAL_WINDOW_MS
    }

    private val pendingApprovals = mutableMapOf<String, PendingApproval>()

    /** uploadIds completados hace poco → timestamp. Permite éxito idempotente
     *  si un reintento llega después de que la transferencia ya terminó bien. */
    private val recentlyCompleted = mutableMapOf<String, Long>()

    /** uploadIds aprobados hace poco → timestamp. Una reanudación tras un corte
     *  de red no vuelve a preguntar: ya dijiste que sí a este archivo. */
    private val recentlyApproved = mutableMapOf<String, Long>()

    /** Miniaturas por uploadId (llegan ANTES del /upload, para el diálogo). */
    private class PreviewEntry(val bytes: ByteArray) {
        val at: Long = System.currentTimeMillis()
    }
    private val pendingPreviews = linkedMapOf<String, PreviewEntry>()

    @Volatile
    private var activeReceivingUploadId: String? = null

    private val startMutex = Mutex()

    private fun isRecentlyCompleted(uploadId: String): Boolean = synchronized(recentlyCompleted) {
        val at = recentlyCompleted[uploadId] ?: return false
        System.currentTimeMillis() - at < COMPLETED_TTL_MS
    }

    private fun isRecentlyApproved(uploadId: String): Boolean = synchronized(recentlyApproved) {
        val at = recentlyApproved[uploadId] ?: return false
        System.currentTimeMillis() - at < COMPLETED_TTL_MS
    }

    private fun markCompleted(uploadId: String) = synchronized(recentlyCompleted) {
        recentlyCompleted[uploadId] = System.currentTimeMillis()
        Unit
    }

    private fun markApproved(uploadId: String) = synchronized(recentlyApproved) {
        recentlyApproved[uploadId] = System.currentTimeMillis()
        Unit
    }

    private fun pruneCompleted() {
        val cutoff = System.currentTimeMillis() - COMPLETED_TTL_MS
        synchronized(recentlyCompleted) { recentlyCompleted.values.removeAll { it < cutoff } }
        synchronized(recentlyApproved) { recentlyApproved.values.removeAll { it < cutoff } }
        synchronized(pendingPreviews) {
            pendingPreviews.entries.removeIf { it.value.at < cutoff }
        }
    }

    private fun storePendingPreview(uploadId: String, bytes: ByteArray) {
        if (bytes.isEmpty() || bytes.size > MAX_PREVIEW_BYTES) return
        synchronized(pendingPreviews) {
            pendingPreviews[uploadId] = PreviewEntry(bytes)
            // Tope duro de memoria: las más viejas se descartan
            while (pendingPreviews.size > MAX_PENDING_PREVIEWS) {
                pendingPreviews.remove(pendingPreviews.keys.first())
            }
        }
    }

    override suspend fun pendingPreview(transferId: String): ByteArray? =
        synchronized(pendingPreviews) { pendingPreviews[transferId]?.bytes }

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
            pendingApprovals.values.forEach { it.deferred.complete(false) }
            pendingApprovals.clear()
        }
    }

    override suspend fun decideApproval(transferId: String, approved: Boolean) {
        val pending = synchronized(pendingApprovals) {
            // transferId == uploadId; si no está, ya venció o se resolvió
            pendingApprovals.entries.firstOrNull { it.value.transferId == transferId }?.let { entry ->
                pendingApprovals.remove(entry.key)
                entry.value
            }
        }
        pending?.deferred?.complete(approved)
    }

    /**
     * Espera la decisión del usuario hasta el deadline (que los reintentos
     * extienden). Devuelve false por timeout o rechazo.
     */
    private suspend fun awaitDecision(uploadId: String, pending: PendingApproval): Boolean {
        while (true) {
            val remaining = pending.deadline - System.currentTimeMillis()
            if (remaining <= 0) break
            val decided = withTimeoutOrNull(remaining) { pending.deferred.await() }
            if (decided != null) return decided
        }
        synchronized(pendingApprovals) { pendingApprovals.remove(uploadId, pending) }
        return false
    }

    private fun Application.module() {
        install(CallLogging)
        install(StatusPages) {
            exception<Throwable> { call, cause ->
                println("[Ignite][RCV] error no manejado en ${call.request.uri}: ${cause::class.simpleName}: ${cause.message}")
                cause.printStackTrace()
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
            post("/pair") {
                // Handshake bidireccional: valida el PIN, guarda al emisor como
                // confiable (AUTO) y devuelve identidad propia para que el otro
                // lado también confíe. Emparejar desde UN dispositivo alcanza.
                val pin = call.request.header(HEADER_PIN) ?: call.request.queryParameters["pin"]
                if (!pairingManager.validate(pin)) {
                    call.respond(HttpStatusCode.Unauthorized, "Invalid PIN")
                    return@post
                }
                val bodyText = runCatching { call.receiveChannel().readRemaining().readByteArray().decodeToString() }.getOrNull().orEmpty()
                val identity = runCatching {
                    Json.parseToJsonElement(bodyText).jsonObject.let {
                        PairIdentity(
                            id = it["deviceId"]?.jsonPrimitive?.contentOrNull,
                            name = it["name"]?.jsonPrimitive?.contentOrNull,
                            // PIN del ESCANEAADOR: sin esto la confianza era
                            // asimétrica — el escaneado confiaba para recibir
                            // pero no podía enviar (no conocía nuestro PIN).
                            pin = it["pin"]?.jsonPrimitive?.contentOrNull,
                        )
                    }
                }.getOrNull()
                if (identity == null || identity.id.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, "deviceId required")
                    return@post
                }
                val peer = call.request.local.remoteHost
                trustedDevices?.remember(
                    deviceId = identity.id,
                    name = identity.name ?: peer,
                    host = peer,
                    pin = identity.pin?.takeIf { it.length == 6 },
                    policy = TrustPolicy.AUTO,
                )
                println("[Ignite][RCV] emparejado con ${identity.name} (${identity.id}) vía /pair")
                call.respondText(
                    Json.encodeToString(Beacon(deviceId = deviceInfo.deviceId, deviceName = deviceInfo.deviceName, port = TransferDefaults.PORT)),
                    io.ktor.http.ContentType.Application.Json,
                )
            }
            post("/preview") {
                // Miniatura para el diálogo de aprobación: el emisor la manda
                // justo antes del /upload. Best-effort en ambos lados.
                val pin = call.request.header(HEADER_PIN) ?: call.request.queryParameters["pin"]
                if (!pairingManager.validate(pin)) {
                    call.respond(HttpStatusCode.Unauthorized, "Invalid PIN")
                    return@post
                }
                val uploadId = call.request.queryParameters["uploadId"]?.takeIf { it.isNotBlank() }
                if (uploadId == null) {
                    call.respond(HttpStatusCode.BadRequest, "uploadId required")
                    return@post
                }
                val channel = call.receiveChannel()
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(DEFAULT_CHUNK_SIZE)
                var overflow = false
                while (true) {
                    val read = channel.readAvailable(buffer, 0, buffer.size)
                    if (read == -1) break
                    if (read > 0) {
                        // Cap real (antes MAX_PREVIEW_BYTES existía pero no se aplicaba).
                        if (out.size() + read > MAX_PREVIEW_BYTES) {
                            overflow = true
                        } else {
                            out.write(buffer, 0, read)
                        }
                    } else if (read == 0) {
                        kotlinx.coroutines.delay(10) // anti-spin en red lenta
                    }
                }
                if (!overflow) storePendingPreview(uploadId, out.toByteArray())
                println("[Ignite][RCV] preview recibida para upload=${uploadId.take(8)}… (${out.size()}B)")
                call.respond(HttpStatusCode.OK)
            }
            // Push de texto rápido (Fase 3): mensaje corto sin archivos.
            // Sin approval: el texto aparece directo como banner en el receptor.
            post("/message") {
                val pin = call.request.header(HEADER_PIN) ?: call.request.queryParameters["pin"]
                if (!pairingManager.validate(pin)) {
                    call.respond(HttpStatusCode.Unauthorized, "Invalid PIN")
                    return@post
                }
                val bodyText = runCatching {
                    call.receiveChannel().readRemaining().readByteArray().decodeToString()
                }.getOrNull().orEmpty()
                val text = runCatching {
                    Json.parseToJsonElement(bodyText).jsonObject["text"]?.jsonPrimitive?.contentOrNull
                }.getOrNull()
                if (text.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, "text required")
                    return@post
                }
                val senderName = call.request.header(HEADER_DEVICE_NAME) ?: call.request.local.remoteHost
                val senderDeviceId = call.request.header(HEADER_DEVICE_ID)
                println("[Ignite][RCV] /message de $senderName: «${text.take(80)}»")
                _incomingEvents.tryEmit(
                    IncomingEvent.TextMessageReceived(
                        text = text,
                        senderName = senderName,
                        peerHost = call.request.local.remoteHost,
                        peerDeviceId = senderDeviceId,
                    ),
                )
                call.respond(HttpStatusCode.OK)
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
                // Identidad del emisor (Ignite >= 1.1): habilita políticas de confianza
                val peerDeviceId = call.request.header(HEADER_DEVICE_ID)?.takeIf { it.isNotBlank() }
                val peerDeviceName = call.request.header(HEADER_DEVICE_NAME)?.takeIf { it.isNotBlank() }
                println(
                    "[Ignite][RCV] /upload de $peer (${peerDeviceName ?: "?"}): name=${requestedName ?: "?"} total=${totalBytes / 1024 / 1024}MB " +
                        "offset=$offset sha=${expectedSha?.take(12)}… contentLength=${call.request.headers[HttpHeaders.ContentLength]}",
                )
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

                // 3) Éxito idempotente para TODAS las políticas: si el archivo ya
                //    llegó completo (respuesta perdida tras éxito), OK sin tocar disco.
                val probeFileName = requestedName ?: "archivo"
                // uploadId estable: reintentos del mismo archivo comparten aprobación
                // (bug de duplicados: cada POST generaba una solicitud nueva)
                val uploadId = call.request.header(HEADER_UPLOAD_ID)?.takeIf { it.isNotBlank() }
                    ?: "$peer-$probeFileName-${System.currentTimeMillis()}"
                pruneCompleted()
                if (isRecentlyCompleted(uploadId)) {
                    println("[Ignite][RCV] '$probeFileName' ya se recibió antes — OK idempotente (upload=${uploadId.take(8)}…)")
                    call.respond(HttpStatusCode.OK)
                    return@post
                }

                // 4) Approval gate — solo para pares con política ASK (o desconocidos).
                val peerPolicy = peerDeviceId
                    ?.let { runCatching { trustedDevices?.policyFor(it) }.getOrNull() }
                    ?: TrustPolicy.ASK
                if (requiresApproval && peerPolicy == TrustPolicy.ASK) {
                    // Reanudación del mismo archivo en curso, o ya aprobado hace
                    // poco (corte de red a mitad): sigue derecho sin re-preguntar.
                    val resuming = activeReceivingUploadId == uploadId || isRecentlyApproved(uploadId)

                    var pending = synchronized(pendingApprovals) { pendingApprovals[uploadId] }
                    if (!resuming && pending == null) {
                        // Ocupado: otra solicitud distinta espera decisión o hay recepción activa
                        val busy = synchronized(pendingApprovals) {
                            pendingApprovals.isNotEmpty() || activeReceivingUploadId != null
                        }
                        if (busy) {
                            println("[Ignite][RCV] ocupado — rechazo inmediato de '$probeFileName' (upload=${uploadId.take(8)}…)")
                            _incomingEvents.tryEmit(
                                IncomingEvent.Failed(probeFileName, peer, "El receptor tiene otra solicitud en curso", peerDeviceId),
                            )
                            call.respond(HttpStatusCode.Conflict, "El receptor ya está atendiendo otra solicitud. Esperá y volvé a enviar.")
                            return@post
                        }
                        pending = PendingApproval(transferId = uploadId)
                        synchronized(pendingApprovals) { pendingApprovals[uploadId] = pending }
                        _incomingEvents.tryEmit(
                            IncomingEvent.AwaitingApproval(probeFileName, peer, totalBytes, uploadId, peerDeviceId, peerDeviceName),
                        )
                    } else if (pending != null) {
                        // Mismo archivo reintentando: extiende la ventana, no apila prompts
                        println("[Ignite][RCV] reintento de '${probeFileName}' (upload=${uploadId.take(8)}…) — ventana extendida")
                        pending.deadline = System.currentTimeMillis() + APPROVAL_WINDOW_MS
                    }

                    val approved = resuming || try {
                        awaitDecision(uploadId, pending!!)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        // El cliente cortó la conexión mientras esperábamos: no dejar
                        // el pendiente colgado para siempre (envenenaría reenvíos futuros)
                        synchronized(pendingApprovals) { pendingApprovals.remove(uploadId) }
                        throw e
                    }
                    if (!resuming) {
                        println("[Ignite][RCV] aprobación '$probeFileName' → ${if (approved) "aceptada" else "rechazada/timeout (${APPROVAL_WINDOW_MS / 1000}s)"}")
                        if (approved) markApproved(uploadId)
                    }
                    if (!approved) {
                        synchronized(pendingApprovals) { pendingApprovals.remove(uploadId) }
                        _incomingEvents.tryEmit(IncomingEvent.Failed(probeFileName, peer, "Rechazado por el usuario"))
                        call.respond(HttpStatusCode.Forbidden, "Transfer rejected by user")
                        return@post
                    }
                }

                activeReceivingUploadId = uploadId
                try {
                    runCatching {
                        val channel = call.receiveChannel()
                        savedFile = receiveFile(channel, requestedName, peer, totalBytes, offset, expectedSha, uploadId, peerDeviceId)
                    }.onFailure { error ->
                        val name = savedFile?.name ?: requestedName ?: "archivo"
                        println("[Ignite][RCV] recepción falló '$name': ${error::class.simpleName}: ${error.message}")
                        error.printStackTrace()
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
                        _incomingEvents.tryEmit(IncomingEvent.Failed(name, peer, error.message, peerDeviceId))
                        // Re-lanzar solo si no hemos respondido ya
                        if (call.response.status() == null) throw error else return@post
                    }
                } finally {
                    if (activeReceivingUploadId == uploadId) activeReceivingUploadId = null
                }

                if (savedFile != null) {
                    markCompleted(uploadId)
                    if (expectedSha != null) call.response.header(HEADER_SHA256, expectedSha)
                    call.respond(HttpStatusCode.OK)
                } else call.respond(HttpStatusCode.BadRequest, "No file body received")
            }
        }
    }

    private suspend fun receiveFile(
        channel: io.ktor.utils.io.ByteReadChannel,
        requestedName: String?,
        peer: String,
        totalBytes: Long,
        offset: Long,
        expectedSha256: String?,
        transferId: String,
        peerDeviceId: String? = null,
    ): File {
        val fileName = requestedName
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
        _incomingEvents.tryEmit(IncomingEvent.Started(fileName, peer, totalBytes, peerDeviceId))
        println("[Ignite][RCV] recibiendo '$fileName' → ${target.absolutePath} (offset=$offset total=${totalBytes / 1024 / 1024}MB)")
        val t0 = System.currentTimeMillis()

        var received = offset
        var lastEmitted = record.progress
        var lastLoggedPct = if (totalBytes > 0) ((received * 100) / totalBytes).toInt() else 0
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
                } else if (read == 0) {
                    kotlinx.coroutines.delay(10) // anti-spin esperando datos en red lenta
                    continue
                }
                val fraction = if (totalBytes > 0) {
                    (received.toFloat() / totalBytes).coerceIn(0f, 1f)
                } else {
                    continue
                }
                if (fraction - lastEmitted >= PROGRESS_STEP) {
                    lastEmitted = fraction
                    repository.upsert(record.copy(progress = fraction))
                    _incomingEvents.tryEmit(
                        IncomingEvent.Progress(fileName, peer, received, totalBytes, fraction, peerDeviceId),
                    )
                }
                if (totalBytes > 0) {
                    val pct = ((received * 100) / totalBytes).toInt()
                    if (pct >= lastLoggedPct + 20) {
                        lastLoggedPct = pct
                        println("[Ignite][RCV] '$fileName' $pct% (${"${received / 1024 / 1024}MB"}/${totalBytes / 1024 / 1024}MB)")
                    }
                }
            }
        }

        // Detección de stream cortado: el sender murió antes de mandar todo
        val expectedRemaining = totalBytes - offset
        if (totalBytes > 0 && received < expectedRemaining) {
            println("[Ignite][RCV] STREAM CORTADO '$fileName': $received/${expectedRemaining} bytes (faltan ${(expectedRemaining - received) / 1024 / 1024}MB)")
            repository.upsert(record.copy(status = Transfer.Status.FAILED, progress = 0f))
            _incomingEvents.tryEmit(
                IncomingEvent.Failed(fileName, peer, "Conexión cortada a ${received * 100 / expectedRemaining}% — se reanuda al reintentar", peerDeviceId),
            )
            throw IllegalStateException("Stream cortado: $received/$expectedRemaining bytes")
        }
        println("[Ignite][RCV] '$fileName' completo: $received bytes en ${System.currentTimeMillis() - t0}ms")

        // SHA-256 verification
        if (expectedSha256 != null && digest != null) {
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actual.equals(expectedSha256, ignoreCase = true)) {
                println("[Ignite][RCV] SHA-256 MISMATCH '$fileName': esperado=${expectedSha256.take(12)}… real=${actual.take(12)}…")
                target.delete()
                repository.upsert(record.copy(status = Transfer.Status.FAILED, progress = 0f))
                _incomingEvents.tryEmit(IncomingEvent.Failed(fileName, peer, "SHA-256 mismatch: esperado $expectedSha256, recibido $actual", peerDeviceId))
                throw IllegalStateException("SHA-256 verification failed")
            }
            println("[Ignite][RCV] SHA-256 OK '$fileName'")
        }

        println("[Ignite][RCV] '$fileName' COMPLETADO y guardado en ${target.absolutePath}")

        repository.upsert(
            record.copy(sizeBytes = received, status = Transfer.Status.COMPLETED, progress = 1f),
        )
        _incomingEvents.tryEmit(IncomingEvent.Completed(fileName, peer, target.absolutePath, received, peerDeviceId))
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

        /** Ventana de aprobación: "Más tarde" mantiene la conexión hasta 2 minutos. */
        const val APPROVAL_WINDOW_MS = 120_000L

        /** Cuánto tiempo un uploadId completado recuerda serlo (éxito idempotente). */
        const val COMPLETED_TTL_MS = 5 * 60_000L

        /** Tope de una miniatura individual y de la caché completa. */
        const val MAX_PREVIEW_BYTES = 256 * 1024
        const val MAX_PENDING_PREVIEWS = 8
        const val HEADER_PIN = "X-Ignite-Pin"
        const val HEADER_SHA256 = "X-Ignite-Sha256"
        const val HEADER_OFFSET = "X-Ignite-Offset"
        const val HEADER_TOTAL_BYTES = "X-Ignite-Total-Bytes"
        const val HEADER_UPLOAD_ID = "X-Ignite-Upload-Id"
        const val HEADER_DEVICE_ID = "X-Ignite-Device-Id"
        const val HEADER_DEVICE_NAME = "X-Ignite-Device-Name"
    }
}
