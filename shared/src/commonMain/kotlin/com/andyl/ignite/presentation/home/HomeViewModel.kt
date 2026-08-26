package com.andyl.ignite.presentation.home

import androidx.lifecycle.viewModelScope
import com.andyl.ignite.data.AppStorage
import com.andyl.ignite.data.DeviceInfo
import com.andyl.ignite.data.revealInFileManager
import com.andyl.ignite.data.supportsCustomDownloadDir
import com.andyl.ignite.domain.DeviceDiscovery
import com.andyl.ignite.domain.FileReceiver
import com.andyl.ignite.domain.FileSender
import com.andyl.ignite.domain.IncomingEvent
import com.andyl.ignite.domain.PairingManager
import com.andyl.ignite.domain.TransferNotifier
import com.andyl.ignite.domain.TransferRepository
import com.andyl.ignite.domain.TrustPolicy
import com.andyl.ignite.domain.TrustedDevices
import com.andyl.ignite.domain.model.Device
import com.andyl.ignite.domain.model.Transfer
import com.andyl.ignite.domain.model.TransferError
import com.andyl.ignite.presentation.MviViewModel
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class HomeViewModel(
    private val discovery: DeviceDiscovery,
    private val sender: FileSender,
    private val receiver: FileReceiver,
    private val repository: TransferRepository,
    private val deviceInfo: DeviceInfo,
    private val storage: AppStorage,
    private val notifier: TransferNotifier,
    private val pairingManager: PairingManager,
    private val httpClient: io.ktor.client.HttpClient? = null,
    private val enablePrune: Boolean = true,
    private val trustedDevices: TrustedDevices? = null,
    private val receiverController: com.andyl.ignite.domain.ReceiverController? = null,
    private val textSender: com.andyl.ignite.domain.TextSender? = null,
    /** Drops de la ventana drop zone (desktop); null en Android. */
    externalDrops: kotlinx.coroutines.flow.Flow<List<String>>? = null,
) : MviViewModel<HomeEvent, HomeState, HomeEffect>() {

    private val _devices = MutableStateFlow<List<Device>>(emptyList())
    private val lastSeenByDevice = mutableMapOf<String, Long>()
    private val startedAt = System.currentTimeMillis()
    private val json = Json { ignoreUnknownKeys = true }
    private var sendJob: Job? = null
    private var outcomeJob: Job? = null
    private var approvalTimerJob: Job? = null

    override fun initialState(): HomeState {
        val trusted = runCatching { trustedDevices?.all().orEmpty() }.getOrDefault(emptyList())
        return HomeState(
            deviceName = runCatching { deviceInfo.deviceName }.getOrDefault(""),
            localIp = getLocalIp(),
            showWelcome = runCatching { !deviceInfo.hasCustomName }.getOrDefault(false),
            downloadPath = runCatching { storage.displayPath() }.getOrDefault(""),
            canChooseDownloadDir = supportsCustomDownloadDir,
            myPin = runCatching { pairingManager.getPin() }.getOrDefault(""),
            trustedIds = trusted.map { it.deviceId }.toSet(),
            devicePolicies = trusted.associate { it.deviceId to it.policy },
        )
    }

    private fun isPhysicalWifiInterface(ni: java.net.NetworkInterface): Boolean {
        val n = ni.name.lowercase()
        return !(n.startsWith("utun") || n.startsWith("feth") || n.startsWith("awdl") || n.startsWith("llw") || n.startsWith("bridge") || n == "lo0")
    }

    private fun getLocalIp(): String = runCatching {
        // Prioriza en0/wlan0 (Wi-Fi real) y evita 10.243.x de VPN
        java.net.NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback && isPhysicalWifiInterface(it) }
            .flatMap { it.inetAddresses.asSequence() }
            .filter { !it.isLoopbackAddress && it is java.net.Inet4Address && it.hostAddress?.startsWith("192.168.") == true }
            .map { it.hostAddress ?: "" }
            .firstOrNull() ?: run {
            // Fallback a cualquier 192.168
            java.net.NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.asSequence() }
                .filter { !it.isLoopbackAddress && it is java.net.Inet4Address }
                .map { it.hostAddress ?: "" }
                .firstOrNull { it.startsWith("192.168.") } ?: run {
                // Último recurso (emulador: 10.0.2.15): cualquier IPv4 no-loopback
                java.net.NetworkInterface.getNetworkInterfaces().asSequence()
                    .filter { it.isUp && !it.isLoopback }
                    .flatMap { it.inetAddresses.asSequence() }
                    .filter { !it.isLoopbackAddress && it is java.net.Inet4Address }
                    .mapNotNull { it.hostAddress }
                    .firstOrNull() ?: ""
            }
        }
    }.getOrDefault("")

    init {
        startInfrastructure()
        collectDevices()
        if (enablePrune) pruneStaleDevices()
        collectIncomingEvents()
        recoverInterrupted()
        observeReceiverPower()
        externalDrops?.onEach { paths -> onExternalDrop(paths) }?.launchIn(viewModelScope)
    }

    /** Fase 2a: archivos soltados en la drop zone → cola + envío automático. */
    private fun onExternalDrop(paths: List<String>) {
        var added = 0
        paths.forEach { path ->
            val meta = runCatching { com.andyl.ignite.data.transferMeta(path) }.getOrNull() ?: return@forEach
            addFile(com.andyl.ignite.presentation.home.PendingFile(path, meta.first, meta.second))
            added++
        }
        if (added == 0) return
        if (state.value.selectedDevice != null) {
            send()
        } else {
            showNote("$added archivo(s) listos — elegí un dispositivo")
        }
    }

    /** El estado ⏻ vive en ReceiverController: la UI refleja cambios hechos desde el tray. */
    private fun observeReceiverPower() {
        receiverController?.active?.onEach { active ->
            updateState { it.copy(receiverActive = active) }
        }?.launchIn(viewModelScope)
    }

    /**
     * #27: al arrancar, las transferencias que quedaron IN_PROGRESS de una
     * sesión anterior se marcan INTERRUPTED y se avisan en la UI. El protocolo
     * permite reanudar por offset, pero no persistimos la ruta de origen, así
     * que v1 sólo informa y deja el registro visible en historial.
     */
    private fun recoverInterrupted() {
        viewModelScope.launch {
            runCatching {
                val stale = repository.observeTransfers().first()
                    .filter { it.status == Transfer.Status.IN_PROGRESS }
                stale.forEach { repository.upsert(it.copy(status = Transfer.Status.INTERRUPTED)) }
                if (stale.isNotEmpty()) {
                    println("[Ignite][VM] ${stale.size} transferencias interrumpidas de la sesión anterior")
                    updateState { s ->
                        s.copy(
                            interrupted = stale.map {
                                InterruptedTransferUi(it.fileName, it.peerName, it.sizeBytes, it.progress)
                            },
                        )
                    }
                }
            }.onFailure { println("[Ignite][VM] sweep de interrumpidas falló: ${it.message}") }
        }
    }

    private fun startInfrastructure() {
        viewModelScope.launch {
            receiver.start()
            discovery.start()
            runCatching { notifier.onIdle(deviceInfo.deviceName) }
        }
    }

    private fun collectDevices() {
        discovery.devices
            .onEach { device ->
                val isNew = device.id !in lastSeenByDevice
                lastSeenByDevice[device.id] = System.currentTimeMillis()
                val updated = (_devices.value.filterNot { it.id == device.id } + device)
                    .sortedBy { it.name.lowercase() }
                _devices.value = updated
                updateState { state -> state.copy(devices = updated) }
                if (isNew && System.currentTimeMillis() - startedAt > CONNECT_GRACE_MS) {
                    showNote("${device.name} se conectó")
                }
            }
            .catch { error ->
                updateState { state -> state.copy(isScanning = false, error = "Discovery failed") }
            }
            .launchIn(viewModelScope)
    }

    private fun pruneStaleDevices() {
        viewModelScope.launch {
            while (isActive) {
                delay(PRUNE_INTERVAL_MS)
                val cutoff = System.currentTimeMillis() - STALE_AFTER_MS
                val staleIds = lastSeenByDevice.filterValues { it < cutoff }.keys.toList()
                if (staleIds.isEmpty()) continue
                staleIds.forEach { lastSeenByDevice.remove(it) }
                val staleNames = _devices.value.filter { it.id in staleIds }.map { it.name }
                val updated = _devices.value.filter { it.id !in staleIds }
                _devices.value = updated
                updateState { state ->
                    state.copy(
                        devices = updated,
                        selectedDevice = state.selectedDevice?.takeIf { it.id !in staleIds },
                    )
                }
                if (!state.value.showWelcome) {
                    showNote("${staleNames.joinToString(", ")} se desconectó")
                }
            }
        }
    }

    private fun collectIncomingEvents() {
        receiver.incomingEvents
            .onEach { event ->
                when (event) {
                    is IncomingEvent.AwaitingApproval -> {
                        val existing = state.value.incoming
                        // Mismo upload reintentando: no re-emitir prompt ni nota
                        if (existing is IncomingUi.AwaitingApproval && existing.transferId == event.transferId) {
                            return@onEach
                        }
                        // Solicitud nueva mientras había una (defered o no): la anterior muere
                        if (existing is IncomingUi.AwaitingApproval) {
                            viewModelScope.launch {
                                runCatching { receiver.decideApproval(existing.transferId, false) }
                            }
                            approvalTimerJob?.cancel()
                        }
                        updateState { state ->
                            state.copy(
                                incoming = IncomingUi.AwaitingApproval(
                                    fileName = event.fileName,
                                    peerName = event.peerDeviceName?.takeIf { it.isNotBlank() } ?: peerName(event.peerHost),
                                    sizeBytes = event.totalBytes,
                                    transferId = event.transferId,
                                    peerHost = event.peerHost,
                                    peerDeviceId = event.peerDeviceId,
                                    deferred = false,
                                    expiresAtMillis = System.currentTimeMillis() + APPROVAL_WINDOW_MS,
                                ),
                            )
                        }
                        showNote("«${event.fileName}» de ${event.peerDeviceName?.takeIf { it.isNotBlank() } ?: peerName(event.peerHost)} quiere enviarte un archivo")

                        // La miniatura puede haber llegado antes que el upload; si no,
                        // reintenta un par de veces antes de rendirse (icono genérico).
                        val transferId = event.transferId
                        viewModelScope.launch {
                            repeat(PREVIEW_POLL_ATTEMPTS) {
                                val bytes = runCatching { receiver.pendingPreview(transferId) }.getOrNull()
                                if (bytes != null) {
                                    updateState { state ->
                                        val current = state.incoming as? IncomingUi.AwaitingApproval
                                        if (current?.transferId == transferId) {
                                            state.copy(incoming = current.copy(previewBytes = bytes))
                                        } else {
                                            state
                                        }
                                    }
                                    return@launch
                                }
                                delay(PREVIEW_POLL_MS)
                            }
                        }
                    }

                    is IncomingEvent.Started -> {
                        updateState { state ->
                            state.copy(
                                incoming = IncomingUi.Receiving(
                                    fileName = event.fileName,
                                    peerName = peerName(event.peerHost),
                                    sizeBytes = event.totalBytes,
                                ),
                            )
                        }
                        notifyIncoming(event.peerDeviceId) { notifier.onReceiving(event.fileName, 0, event.totalBytes, 0f) }
                    }

                    is IncomingEvent.Progress -> {
                        updateState { state ->
                            val current = state.incoming
                            if (current is IncomingUi.Receiving && current.fileName == event.fileName) {
                                state.copy(
                                    incoming = current.copy(
                                        receivedBytes = event.receivedBytes,
                                        sizeBytes = event.totalBytes,
                                        progress = event.progress,
                                    ),
                                )
                            } else {
                                state
                            }
                        }
                        notifyIncoming(event.peerDeviceId) {
                            notifier.onReceiving(event.fileName, event.receivedBytes, event.totalBytes, event.progress)
                        }
                    }

                    is IncomingEvent.Completed -> {
                        approvalTimerJob?.cancel()
                        // Publicar en la carpeta visible del usuario (Descargas/Ignite
                        // o la carpeta que eligió). Desktop: identidad.
                        val finalPath = withContext(Dispatchers.IO) {
                            runCatching { com.andyl.ignite.data.publishReceivedFile(event.path) }
                                .onFailure { println("[Ignite][ERROR] no se pudo publicar '${event.fileName}': ${it.message}") }
                                .getOrElse { event.path }
                        }
                        updateState { state ->
                            state.copy(
                                incoming = null,
                                recentReceived = (
                                    listOf(ReceivedFileUi(event.fileName, finalPath, event.sizeBytes)) +
                                        state.recentReceived
                                    ).distinctBy { it.path }.take(MAX_RECENT),
                            )
                        }
                        notifyIncoming(event.peerDeviceId) { notifier.onCompleted(event.fileName, isSending = false) }
                    }

                    is IncomingEvent.Failed -> {
                        approvalTimerJob?.cancel()
                        updateState { state -> state.copy(incoming = null) }
                        showNote("No se pudo recibir «${event.fileName}»: ${event.message ?: "error desconocido"}")
                        runCatching { notifier.onFailed(event.fileName, event.message) }
                    }

                    is IncomingEvent.TextMessageReceived -> {
                        val msg = TextMessageUi(
                            text = event.text,
                            senderName = event.senderName,
                            senderHost = event.peerHost,
                            timestamp = System.currentTimeMillis(),
                        )
                        updateState { s ->
                            s.copy(receivedTextMessages = listOf(msg) + s.receivedTextMessages)
                        }
                        showNote("📝 Mensaje de ${event.senderName}: «${event.text.take(60)}${if (event.text.length > 60) "…" else ""}»")
                    }
                }
            }
            .launchIn(viewModelScope)
    }

    private fun peerName(host: String): String =
        _devices.value.firstOrNull { it.host == host }?.name ?: host

    /** Política de recepción vigente para un par (ASK si es desconocido). */
    private fun policyOf(peerDeviceId: String?): TrustPolicy =
        peerDeviceId
            ?.let { runCatching { trustedDevices?.policyFor(it) }.getOrNull() }
            ?: TrustPolicy.ASK

    /** SILENT = sin ruido de sistema; el progreso in-app queda. */
    private fun notifyIncoming(silentPeerId: String?, block: () -> Unit) {
        if (policyOf(silentPeerId) == TrustPolicy.SILENT) return
        runCatching { block() }
    }

    override fun onEventImpl(event: HomeEvent) {
        when (event) {
            HomeEvent.OnRefresh -> refresh()
            is HomeEvent.OnDeviceSelected -> onDeviceSelected(event.device)
            is HomeEvent.OnFileSelected -> addFile(event.file)
            is HomeEvent.OnFileCleared -> {
                updateState { it.copy(pendingFiles = it.pendingFiles.filterNot { f -> f.path == event.file.path }) }
            }
            HomeEvent.OnSendClick -> send()
            HomeEvent.OnProfileClick -> updateState { it.copy(showProfileDialog = true) }
            HomeEvent.OnDialogDismiss -> updateState { it.copy(showProfileDialog = false, showWelcome = false, showPinDialog = false) }
            is HomeEvent.OnRenameConfirm -> rename(event.name)
            HomeEvent.OnDismissIncoming -> updateState { it.copy(incoming = null) }
            is HomeEvent.OnOpenReceivedFolder -> openFolder(event.file.path)
            is HomeEvent.OnDownloadDirPicked -> setDownloadDir(event.path)
            is HomeEvent.OnTargetPinChanged -> updateState { it.copy(targetPin = event.pin.filter { c -> c.isDigit() }.take(6)) }
            HomeEvent.OnRegeneratePin -> regeneratePin()
            HomeEvent.OnApproveIncoming -> decideApproval(true)
            is HomeEvent.OnApproveIncomingAlways -> approveAndTrust(event.deviceId)
            HomeEvent.OnRejectIncoming -> decideApproval(false)
            HomeEvent.OnIncomingDeferred -> deferApproval()
            is HomeEvent.OnForgetDevice -> forgetDevice(event.deviceId, event.name)
            is HomeEvent.OnCycleDevicePolicy -> cyclePolicy(event.deviceId)
            HomeEvent.OnTogglePinDialog -> updateState { it.copy(showPinDialog = !it.showPinDialog, myPin = runCatching { pairingManager.getPin() }.getOrDefault(it.myPin)) }
            HomeEvent.OnShowPairQr -> updateState {
                it.copy(
                    showPairQrDialog = !it.showPairQrDialog,
                    myPin = runCatching { pairingManager.getPin() }.getOrDefault(it.myPin),
                )
            }
            is HomeEvent.OnQrScanned -> viewModelScope.launch { pairFromQr(event.raw) }
            HomeEvent.OnToggleReceiverActive -> viewModelScope.launch { toggleReceiver() }
            is HomeEvent.OnManualIpChanged -> updateState { it.copy(manualIp = event.ip.trim().take(45)) }
            HomeEvent.OnManualConnect -> viewModelScope.launch { connectManual() }
            HomeEvent.OnCancelSend -> cancelSend()
            HomeEvent.OnDismissInterrupted -> updateState { it.copy(interrupted = emptyList()) }
            HomeEvent.OnToggleTextMode -> updateState { it.copy(isTextMode = !it.isTextMode) }
            is HomeEvent.OnTextInputChanged -> updateState { it.copy(textInput = event.text) }
            HomeEvent.OnSendText -> sendText()
            is HomeEvent.OnDismissTextMessage -> updateState { s ->
                s.copy(receivedTextMessages = s.receivedTextMessages.toMutableList().apply { removeAt(event.index) })
            }
            is HomeEvent.OnEditTextMessage -> updateState { it.copy(isTextMode = true, textInput = event.text) }
        }
    }

    /** Cancelación del usuario: transición inmediata a Cancelling, idempotente. */
    private fun cancelSend() {
        val current = state.value.sendSession
        if (current !is SendSession.Sending) return
        val job = sendJob ?: return
        updateState { it.copy(sendSession = SendSession.Cancelling(current)) }
        showNote("Cancelando transferencia…")
        job.cancel()
    }

    /**
     * Selección de dispositivo: si ya le pusimos el PIN alguna vez, se precarga
     * (auto-conexión). Si no es confiable, se limpia cualquier PIN viejo.
     */
    private fun onDeviceSelected(device: Device) {
        val trustedPin = runCatching { trustedDevices?.pinFor(device.id) }.getOrNull()
        updateState { state ->
            state.copy(
                selectedDevice = device,
                targetPin = trustedPin?.pin ?: "",
                pinRememberedFor = trustedPin?.let { device.name },
            )
        }
        if (trustedPin != null) showNote("PIN recordado de ${device.name} — listo para enviar")
    }

    private fun forgetDevice(deviceId: String, name: String) {
        val removed = runCatching { trustedDevices?.forget(deviceId) ?: false }.getOrDefault(false)
        if (!removed) return
        updateState { state ->
            state.copy(
                trustedIds = state.trustedIds - deviceId,
                devicePolicies = state.devicePolicies - deviceId,
                targetPin = if (state.selectedDevice?.id == deviceId) "" else state.targetPin,
                pinRememberedFor = state.pinRememberedFor?.takeIf { state.selectedDevice?.id != deviceId },
            )
        }
        showNote("Olvidaste a $name — va a pedir su PIN de nuevo y a preguntar antes de aceptar")
    }

    private fun decideApproval(approved: Boolean) {
        val pending = state.value.incoming as? IncomingUi.AwaitingApproval ?: return
        approvalTimerJob?.cancel()
        viewModelScope.launch {
            runCatching { receiver.decideApproval(pending.transferId, approved) }
        }
        updateState { it.copy(incoming = null) }
        showNote(if (approved) "Aceptaste «${pending.fileName}» — recibiendo…" else "Rechazaste «${pending.fileName}»")
    }

    /**
     * Momento AirDrop: aprobar Y marcar al emisor para que la próxima entre
     * sin preguntar (política AUTO).
     */
    private fun approveAndTrust(deviceId: String) {
        val pending = state.value.incoming as? IncomingUi.AwaitingApproval ?: return
        if (deviceId != pending.peerDeviceId) {
            // Identidad desalineada: aprobación simple, sin tocar confianza.
            decideApproval(true)
            return
        }
        val ok = runCatching {
            val existing = trustedDevices?.pinFor(deviceId)
            if (existing != null) {
                trustedDevices?.setPolicy(deviceId, TrustPolicy.AUTO) != null
            } else {
                trustedDevices?.remember(
                    deviceId = deviceId,
                    name = pending.peerName,
                    host = pending.peerHost.ifBlank { "desconocido" },
                    pin = null,
                    policy = TrustPolicy.AUTO,
                ) != null || true // remember() es Unit; la confianza quedó registrada igual
            }
        }.getOrDefault(false)
        decideApproval(true)
        if (ok) {
            updateState { it.copy(trustedIds = it.trustedIds + deviceId, devicePolicies = it.devicePolicies + (deviceId to TrustPolicy.AUTO)) }
            showNote("Listo — a partir de ahora ${pending.peerName} entra sin preguntar")
        }
    }

    /** ASK → AUTO → SILENT → ASK sobre un dispositivo confiable. */
    private fun cyclePolicy(deviceId: String) {
        val current = state.value.devicePolicies[deviceId] ?: TrustPolicy.ASK
        val next = when (current) {
            TrustPolicy.ASK -> TrustPolicy.AUTO
            TrustPolicy.AUTO -> TrustPolicy.SILENT
            TrustPolicy.SILENT -> TrustPolicy.ASK
        }
        if (next == TrustPolicy.ASK) {
            // Volver a preguntar = seguir confiado pero con prompt (pin recordado intacto)
            val ok = runCatching { trustedDevices?.setPolicy(deviceId, TrustPolicy.ASK) != null }.getOrDefault(false)
            if (!ok) {
                showNote("Primero enviá o recibí algo de ese dispositivo")
                return
            }
        } else {
            val existing = runCatching { trustedDevices?.pinFor(deviceId) }.getOrNull()
            if (existing == null) {
                showNote("Todavía no hay confianza con ese dispositivo — enviale algo primero")
                return
            }
            runCatching { trustedDevices?.setPolicy(deviceId, next) }
        }
        updateState { it.copy(devicePolicies = it.devicePolicies + (deviceId to next)) }
        showNote(
            when (next) {
                TrustPolicy.ASK -> "Volverá a preguntarte antes de aceptar"
                TrustPolicy.AUTO -> "Aceptará sus archivos sin preguntar"
                TrustPolicy.SILENT -> "Modo silencioso: recibe todo sin avisar"
            },
        )
    }

    /**
     * "Más tarde": la conexión queda abierta hasta que decidas o venza la
     * ventana (2 min). El diálogo se reemplaza por un banner con cuenta atrás.
     */
    private fun deferApproval() {
        val pending = state.value.incoming as? IncomingUi.AwaitingApproval ?: return
        approvalTimerJob?.cancel()
        updateState { it.copy(incoming = pending.copy(deferred = true)) }
        showNote("«${pending.fileName}» en espera — tenés 2 minutos para decidir")
        approvalTimerJob = viewModelScope.launch {
            delay((pending.expiresAtMillis - System.currentTimeMillis()).coerceAtLeast(0))
            val stillPending = state.value.incoming as? IncomingUi.AwaitingApproval ?: return@launch
            if (stillPending.transferId == pending.transferId) {
                runCatching { receiver.decideApproval(pending.transferId, false) }
                updateState { it.copy(incoming = null) }
                showNote("Se venció el tiempo para «${pending.fileName}» — pedile al otro que lo envíe de nuevo")
            }
        }
    }

    /** Contenido del QR propio: identidad local + PIN actual + IP preferente. */
    fun pairingQrContent(): String {
        val payload = com.andyl.ignite.domain.model.PairingPayload(
            id = runCatching { deviceInfo.deviceId }.getOrDefault(""),
            name = runCatching { deviceInfo.deviceName }.getOrDefault("Ignite"),
            host = state.value.localIp,
            port = com.andyl.ignite.domain.model.TransferDefaults.PORT,
            pin = state.value.myPin.ifBlank { runCatching { pairingManager.getPin() }.getOrDefault("") },
        )
        return json.encodeToString(payload)
    }

    /**
     * Emparejamiento por QR (Fase 1d): escaneamos el código del otro, le
     * hablamos a su /pair con nuestro PIN y, si valida, AMBOS lados quedan
     * confiándose en AUTO — enviar y recibir sin preguntar jamás.
     */
    /**
     * Pausar/reactivar el receptor: al pausar bajamos el server HTTP y el
     * anuncio UDP, así desaparecemos de la lista del otro y no aceptamos nada.
     * Igual que "Receiving Off" de AirDrop.
     */
    private suspend fun toggleReceiver() {
        val controller = receiverController
        if (controller == null) {
            showNote("No se pudo cambiar el estado del receptor")
            return
        }
        if (state.value.receiverActive) {
            // Si hay una aprobación pendiente, la cancelamos para no colgar al emisor.
            (state.value.incoming as? IncomingUi.AwaitingApproval)?.let { pending ->
                runCatching { receiver.decideApproval(pending.transferId, false) }
            }
            updateState { it.copy(incoming = null) }
            if (controller.pause()) showNote("Pausado — no recibís nada hasta que lo reactivés")
            else showNote("Pausado con errores — revisá el log")
        } else {
            if (controller.resume()) showNote("Activo de nuevo")
            else showNote("Reactivado con errores — revisá el log")
        }
    }

    private suspend fun pairFromQr(raw: String) {
        val client = httpClient ?: run {
            showNote("El emparejamiento por QR no está disponible acá")
            return
        }
        updateState { it.copy(showPairQrDialog = false) }
        val payload = runCatching { json.decodeFromString<com.andyl.ignite.domain.model.PairingPayload>(raw) }.getOrNull()
        if (payload == null || payload.id.isBlank() || payload.pin.length != 6) {
            showNote("Ese QR no es de Ignite")
            return
        }
        if (payload.id == deviceInfo.deviceId) {
            showNote("¡Es tu propio código! Escaneá el de la otra máquina")
            return
        }
        val response = kotlinx.coroutines.withTimeoutOrNull(PAIR_TIMEOUT_MS) {
            runCatching {
                client.post("http://${payload.host}:${payload.port}/pair?pin=${payload.pin}") {
                    contentType(ContentType.Application.Json)
                    // Mandamos TAMBIÉN nuestro propio PIN: el otro lado lo guarda
                    // y puede enviarnos sin tipear nada (confianza simétrica).
                    setBody(
                        json.encodeToString(
                            mapOf(
                                "deviceId" to deviceInfo.deviceId,
                                "name" to deviceInfo.deviceName,
                                "pin" to state.value.myPin.ifBlank { runCatching { pairingManager.getPin() }.getOrDefault("") },
                            ),
                        ),
                    )
                }.bodyAsText()
            }.getOrNull()
        }
        if (response == null) {
            showNote("${payload.name} no respondió — fijate que esté abierta y en la misma red")
            return
        }
        val peer = runCatching { json.decodeFromString<com.andyl.ignite.domain.model.Beacon>(response) }.getOrNull()
        // Confianza mutua: guardo al otro (con SU pin para poder enviarle ya)
        runCatching {
            trustedDevices?.remember(
                deviceId = payload.id,
                name = peer?.deviceName ?: payload.name,
                host = payload.host,
                pin = payload.pin,
                policy = TrustPolicy.AUTO,
            )
        }
        updateState { state ->
            state.copy(
                trustedIds = state.trustedIds + payload.id,
                devicePolicies = state.devicePolicies + (payload.id to TrustPolicy.AUTO),
            )
        }
        showNote("Emparejado con ${peer?.deviceName ?: payload.name} — se van a mandar todo sin preguntar")
    }

    private suspend fun connectManual() {        val ip = state.value.manualIp.trim()
        if (!isValidIp(ip)) {
            showNote("IP inválida: $ip")
            return
        }
        // Validamos que haya un Ignite en esa IP antes de agregarlo (#4)
        val device = probeManualDevice(ip)
        if (device == null) {
            showNote("$ip no responde — revisá la IP, que la app esté abierta allá y el firewall (puerto ${com.andyl.ignite.domain.model.TransferDefaults.PORT})")
            return
        }
        val updated = (_devices.value.filterNot { it.id == device.id } + device).sortedBy { it.name.lowercase() }
        _devices.value = updated
        lastSeenByDevice[device.id] = System.currentTimeMillis()
        updateState { it.copy(devices = updated, selectedDevice = device, manualIp = "") }
        showNote("Conectado a ${device.name} ($ip) — ahora ingresá su PIN y enviá")
    }

    private suspend fun probeManualDevice(ip: String): com.andyl.ignite.domain.model.Device? {
        val client = httpClient ?: return manualDevice(ip) // sin cliente (tests): comportamiento anterior
        return kotlinx.coroutines.withTimeoutOrNull(2_000) {
            runCatching {
                val body = client.get("http://$ip:${com.andyl.ignite.domain.model.TransferDefaults.PORT}/beacon").bodyAsText()
                val beacon = json.decodeFromString<com.andyl.ignite.domain.model.Beacon>(body)
                com.andyl.ignite.domain.model.Device(
                    id = beacon.deviceId,
                    name = beacon.deviceName,
                    host = ip,
                    port = beacon.port,
                )
            }.getOrNull()
        } ?: run { println("[Ignite][VM] probe a $ip no respondió /beacon en 2s"); null }
    }

    private fun manualDevice(ip: String) = com.andyl.ignite.domain.model.Device(
        id = "manual-$ip",
        name = "Manual ($ip)",
        host = ip,
        port = com.andyl.ignite.domain.model.TransferDefaults.PORT,
    )

    private fun isValidIp(ip: String): Boolean {
        if (ip.isBlank()) return false
        val ipv4 = ip.matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))
        val ipv6 = ip.contains(":")
        return ipv4 || ipv6
    }

    private fun rename(name: String) {
        val trimmed = name.trim()
        // Vacío = no romper nada: cerramos sin tocar el nombre actual.
        // (Antes rename("") borraba el nombre custom y volvía al default.)
        if (trimmed.isEmpty() || trimmed == deviceInfo.deviceName) {
            updateState { it.copy(showProfileDialog = false, showWelcome = false, deviceName = deviceInfo.deviceName) }
            return
        }
        runCatching { deviceInfo.rename(trimmed) }
        val current = deviceInfo.deviceName
        updateState { it.copy(deviceName = current, showProfileDialog = false, showWelcome = false) }
        showNote("Ahora te ven como «$current»")
        runCatching { notifier.onIdle(current) }
    }

    private fun regeneratePin() {
        val newPin = pairingManager.regenerate()
        updateState { it.copy(myPin = newPin) }
        showNote("Nuevo PIN: $newPin")
    }

    private fun openFolder(path: String) {
        val opened = runCatching { revealInFileManager(path) }.getOrDefault(false)
        if (!opened) showNote("Tu sistema no permite abrir la carpeta desde la app")
    }

    private fun setDownloadDir(path: String?) {
        runCatching { storage.setReceiveDir(path) }
        val current = runCatching { storage.displayPath() }.getOrDefault("")
        updateState { it.copy(downloadPath = current) }
        showNote(if (path == null) "Descargas en carpeta por defecto" else "Descargas en $current")
    }

    private fun refresh() {
        updateState { it.copy(isScanning = true, error = null) }
        viewModelScope.launch {
            discovery.stop()
            discovery.start()
        }
    }

    private fun addFile(file: PendingFile) {
        updateState {
            it.copy(
                pendingFiles = (it.pendingFiles + file).distinctBy { f -> f.path },
                sendOutcome = null,
            )
        }
    }

    private fun send() {
        // #22 política de concurrencia v1: una sola sesión activa.
        if (state.value.sendSession != SendSession.Idle) {
            showNote("Ya hay una transferencia en curso — esperá que termine o cancelala")
            return
        }
        val target = state.value.selectedDevice ?: return
        val files = state.value.pendingFiles
        if (files.isEmpty()) return
        val pin = state.value.targetPin
        if (pin.length != 6) {
            showNote("Ingresá el PIN de 6 dígitos del receptor")
            return
        }
        val queueTotalBytes = files.sumOf { it.sizeBytes }

        updateState {
            it.copy(
                sendSession = SendSession.Preparing(target.name, files.size, queueTotalBytes),
                sendOutcome = null,
            )
        }

        sendJob = viewModelScope.launch {
            var failedFile: String? = null
            var failedError: TransferError? = null
            var cancelled = false
            var completedBytes = 0L

            for ((index, file) in files.withIndex()) {
                updateState {
                    it.copy(
                        sendSession = SendSession.Sending(
                            targetName = target.name,
                            fileIndex = index,
                            fileCount = files.size,
                            fileName = file.name,
                            fileProgress = 0f,
                            fileSentBytes = 0L,
                            fileTotalBytes = file.sizeBytes,
                            completedBytesBeforeCurrent = completedBytes,
                            totalBytes = queueTotalBytes,
                        ),
                    )
                }

                val record = Transfer(
                    id = 0,
                    fileName = file.name,
                    sizeBytes = file.sizeBytes,
                    direction = Transfer.Direction.SENT,
                    peerName = target.name,
                    peerHost = target.host,
                    status = Transfer.Status.IN_PROGRESS,
                    progress = 0f,
                    createdAt = System.currentTimeMillis(),
                )
                repository.upsert(record)

                try {
                    var lastPersisted = 0f
                    sendFileWithRetry(target, file, pin) { progress ->
                        val sentBytes = (file.sizeBytes * progress).toLong()
                        updateState { s ->
                            val session = s.sendSession
                            if (session is SendSession.Sending && session.fileName == file.name) {
                                s.copy(
                                    sendSession = session.copy(
                                        fileProgress = progress,
                                        fileSentBytes = sentBytes,
                                    ),
                                )
                            } else {
                                s
                            }
                        }
                        if (progress - lastPersisted >= PROGRESS_PERSIST_STEP || progress >= 1f) {
                            lastPersisted = progress
                            repository.upsert(record.copy(progress = progress))
                        }
                        runCatching { notifier.onSending(file.name, progress, file.sizeBytes) }
                    }
                    repository.upsert(record.copy(status = Transfer.Status.COMPLETED, progress = 1f))
                    runCatching { notifier.onCompleted(file.name, isSending = true) }
                    completedBytes += file.sizeBytes
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // Cancelación del usuario (o de la pantalla): NO es error.
                    cancelled = true
                    repository.upsert(record.copy(status = Transfer.Status.CANCELLED))
                    runCatching { notifier.onFailed(file.name, "Cancelado por el usuario") }
                    break
                } catch (e: Exception) {
                    println("[Ignite][VM] envío falló «${file.name}» → ${target.host}: ${e::class.simpleName}: ${e.message}")
                    e.printStackTrace()
                    val error = TransferError.from(e)
                    repository.upsert(record.copy(status = Transfer.Status.FAILED))
                    failedFile = file.name
                    failedError = error
                    runCatching { notifier.onFailed(file.name, e.message) }
                    showNote(error.userMessage(target.name))
                    break
                }
            }

            updateState {
                it.copy(
                    sendSession = SendSession.Idle,
                    // En cancel conservamos la cola para reintentar fácil
                    pendingFiles = if (cancelled) it.pendingFiles else emptyList(),
                )
            }
            // PIN recordado: si la cola completa salió bien, este dispositivo queda emparejado
            if (!cancelled && failedFile == null) {
                val remembered = runCatching {
                    trustedDevices?.remember(target.id, target.name, target.host, pin)
                    true
                }.getOrDefault(false)
                if (remembered) {
                    updateState { it.copy(trustedIds = it.trustedIds + target.id, pinRememberedFor = target.name) }
                }
            }
            val outcome = when {
                cancelled -> SendOutcome(files.first().name, target.name, success = false, cancelled = true)
                failedFile == null -> SendOutcome(files.first().name, target.name, success = true, count = files.size)
                else -> SendOutcome(failedFile!!, target.name, success = false, count = 1, error = failedError)
            }
            // Log grepeable del resultado (logcat/consola): [Ignite][ERROR] o [Ignite][VM]
            if (!outcome.success && !outcome.cancelled) {
                println(
                    "[Ignite][ERROR] envío de '${outcome.fileName}' a ${target.name} (${target.host}) falló: " +
                        "error=${failedError?.let { it::class.simpleName } ?: "?"} detalle=${failedError?.detail ?: "sin detalle"}",
                )
            } else if (outcome.success) {
                println("[Ignite][VM] envío completado: ${files.size} archivo(s) → ${target.name} OK")
            }
            showOutcome(outcome)
            sendJob = null
        }
    }

    /**
     * Envía un archivo reintentando automáticamente ante cortes de red.
     * Cada reintento consulta el offset remoto (queryOffset en KtorFileSender)
     * y retoma desde donde quedó, sin reenviar bytes.
     */
    private suspend fun sendFileWithRetry(
        target: Device,
        file: PendingFile,
        pin: String,
        onProgress: suspend (Float) -> Unit,
    ) {
        repeat(MAX_SEND_ATTEMPTS) { attempt ->
            try {
                sender.send(target, file.path, file.name, file.sizeBytes, pin).collect { onProgress(it) }
                return
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                val isLastAttempt = attempt == MAX_SEND_ATTEMPTS - 1
                // Reintentar no sirve ante PIN/rechazo/receptor ocupado (#26)
                val error = TransferError.from(e)
                val isUserError = error is TransferError.PinRejected || error is TransferError.Busy
                if (isUserError || isLastAttempt) throw e
                val backoffMs = RETRY_BACKOFF_MS[attempt]
                println("[Ignite][VM] reintento ${attempt + 1}/$MAX_SEND_ATTEMPTS de '${file.name}' en ${backoffMs}ms: ${e.message}")
                showNote(
                    "Se cortó el envío de «${file.name}» — reintentando (${attempt + 2}° intento, sigue desde donde quedó)…",
                )
                delay(backoffMs)
            }
        }
    }

    /** Feedback transitorio vía effect (#28): la UI decide cómo mostrarlo. */
    private fun showNote(text: String) {
        sendEffect(HomeEffect.ShowSnackbar(text))
    }

    private fun sendText() {
        val target = state.value.selectedDevice ?: return
        val text = state.value.textInput.trim()
        if (text.isBlank()) return
        val pin = state.value.targetPin
        if (pin.length != 6) {
            showNote("Ingresá el PIN de 6 dígitos del receptor")
            return
        }
        if (textSender == null) {
            showNote("Envío de texto no disponible")
            return
        }

        viewModelScope.launch {
            showNote("Enviando mensaje a ${target.name}…")
            runCatching {
                textSender.send(target, text, pin)
                showNote("✅ Mensaje enviado a ${target.name}")
                updateState { it.copy(textInput = "") }
            }.onFailure { e ->
                val msg = e.message ?: "error desconocido"
                println("[Ignite][TXT] envío falló a ${target.name}: $msg")
                showNote("No se pudo enviar el mensaje: $msg")
            }
        }
    }

    private fun showOutcome(outcome: SendOutcome) {
        outcomeJob?.cancel()
        updateState { it.copy(sendOutcome = outcome) }
        outcomeJob = viewModelScope.launch {
            delay(OUTCOME_DURATION_MS)
            updateState { s -> s.copy(sendOutcome = null) }
        }
    }

    private companion object {
        const val STALE_AFTER_MS = 7_000L
        const val PRUNE_INTERVAL_MS = 5_000L
        const val CONNECT_GRACE_MS = 8_000L
        const val OUTCOME_DURATION_MS = 5_000L
        const val MAX_RECENT = 4
        const val MAX_SEND_ATTEMPTS = 4
        const val PROGRESS_PERSIST_STEP = 0.05f

        /** Igual que el receptor: ventana total de "Más tarde". */
        const val APPROVAL_WINDOW_MS = 120_000L

        /** Polling de la miniatura mientras el diálogo está abierto. */
        const val PREVIEW_POLL_ATTEMPTS = 5
        const val PREVIEW_POLL_MS = 150L

        /** Timeout del handshake /pair. */
        const val PAIR_TIMEOUT_MS = 4_000L
        val RETRY_BACKOFF_MS = longArrayOf(1_500L, 3_000L, 6_000L)
    }
}
