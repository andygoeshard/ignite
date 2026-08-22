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
import com.andyl.ignite.domain.model.Device
import com.andyl.ignite.domain.model.Transfer
import com.andyl.ignite.domain.model.TransferError
import com.andyl.ignite.presentation.MviViewModel
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Job
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
) : MviViewModel<HomeEvent, HomeState, HomeEffect>() {

    private val _devices = MutableStateFlow<List<Device>>(emptyList())
    private val lastSeenByDevice = mutableMapOf<String, Long>()
    private val startedAt = System.currentTimeMillis()
    private val json = Json { ignoreUnknownKeys = true }
    private var sendJob: Job? = null
    private var outcomeJob: Job? = null

    override fun initialState(): HomeState = HomeState(
        deviceName = runCatching { deviceInfo.deviceName }.getOrDefault(""),
        localIp = getLocalIp(),
        showWelcome = runCatching { !deviceInfo.hasCustomName }.getOrDefault(false),
        downloadPath = runCatching { storage.receiveDir() }.getOrDefault(""),
        canChooseDownloadDir = supportsCustomDownloadDir,
        myPin = runCatching { pairingManager.getPin() }.getOrDefault(""),
    )

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
                        updateState { state ->
                            state.copy(
                                incoming = IncomingUi.AwaitingApproval(
                                    fileName = event.fileName,
                                    peerName = peerName(event.peerHost),
                                    sizeBytes = event.totalBytes,
                                    transferId = event.transferId,
                                ),
                            )
                        }
                        showNote("«${event.fileName}» de ${peerName(event.peerHost)} quiere enviarte un archivo")
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
                        runCatching { notifier.onReceiving(event.fileName, 0, event.totalBytes, 0f) }
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
                        runCatching { notifier.onReceiving(event.fileName, event.receivedBytes, event.totalBytes, event.progress) }
                    }

                    is IncomingEvent.Completed -> {
                        updateState { state ->
                            state.copy(
                                incoming = null,
                                recentReceived = (
                                    listOf(ReceivedFileUi(event.fileName, event.path, event.sizeBytes)) +
                                        state.recentReceived
                                    ).distinctBy { it.path }.take(MAX_RECENT),
                            )
                        }
                        runCatching { notifier.onCompleted(event.fileName, isSending = false) }
                    }

                    is IncomingEvent.Failed -> {
                        updateState { state -> state.copy(incoming = null) }
                        showNote("No se pudo recibir «${event.fileName}»: ${event.message ?: "error desconocido"}")
                        runCatching { notifier.onFailed(event.fileName, event.message) }
                    }
                }
            }
            .launchIn(viewModelScope)
    }

    private fun peerName(host: String): String =
        _devices.value.firstOrNull { it.host == host }?.name ?: host

    override fun onEventImpl(event: HomeEvent) {
        when (event) {
            HomeEvent.OnRefresh -> refresh()
            is HomeEvent.OnDeviceSelected -> {
                updateState { it.copy(selectedDevice = event.device) }
            }
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
            HomeEvent.OnRejectIncoming -> decideApproval(false)
            HomeEvent.OnTogglePinDialog -> updateState { it.copy(showPinDialog = !it.showPinDialog, myPin = runCatching { pairingManager.getPin() }.getOrDefault(it.myPin)) }
            is HomeEvent.OnManualIpChanged -> updateState { it.copy(manualIp = event.ip.trim().take(45)) }
            HomeEvent.OnManualConnect -> viewModelScope.launch { connectManual() }
            HomeEvent.OnCancelSend -> cancelSend()
            HomeEvent.OnDismissInterrupted -> updateState { it.copy(interrupted = emptyList()) }
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

    private suspend fun connectManual() {
        val ip = state.value.manualIp.trim()
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
        runCatching { deviceInfo.rename(name) }
        val current = deviceInfo.deviceName
        updateState { it.copy(deviceName = current, showProfileDialog = false, showWelcome = false) }
        showNote(if (name.isBlank()) "Nombre restaurado: $current" else "Ahora te ven como «$current»")
        runCatching { notifier.onIdle(current) }
    }

    private fun regeneratePin() {
        val newPin = pairingManager.regenerate()
        updateState { it.copy(myPin = newPin) }
        showNote("Nuevo PIN: $newPin")
    }

    private fun decideApproval(approved: Boolean) {
        val pending = state.value.incoming as? IncomingUi.AwaitingApproval ?: return
        viewModelScope.launch {
            runCatching { receiver.decideApproval(pending.transferId, approved) }
        }
        updateState { it.copy(incoming = null) }
        showNote(if (approved) "Aceptaste «${pending.fileName}» — recibiendo…" else "Rechazaste «${pending.fileName}»")
    }

    private fun openFolder(path: String) {
        val opened = runCatching { revealInFileManager(path) }.getOrDefault(false)
        if (!opened) showNote("Tu sistema no permite abrir la carpeta desde la app")
    }

    private fun setDownloadDir(path: String?) {
        runCatching { storage.setReceiveDir(path) }
        val current = runCatching { storage.receiveDir() }.getOrDefault("")
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
            val outcome = when {
                cancelled -> SendOutcome(files.first().name, target.name, success = false, cancelled = true)
                failedFile == null -> SendOutcome(files.first().name, target.name, success = true, count = files.size)
                else -> SendOutcome(failedFile!!, target.name, success = false, count = 1, error = failedError)
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
                // Reintentar no sirve ante PIN/rechazo del receptor (#26)
                val isUserError = TransferError.from(e) is TransferError.PinRejected
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
        const val PRUNE_INTERVAL_MS = 1_000L
        const val CONNECT_GRACE_MS = 8_000L
        const val OUTCOME_DURATION_MS = 5_000L
        const val MAX_RECENT = 4
        const val MAX_SEND_ATTEMPTS = 4
        const val PROGRESS_PERSIST_STEP = 0.05f
        val RETRY_BACKOFF_MS = longArrayOf(1_500L, 3_000L, 6_000L)
    }
}
