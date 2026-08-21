package com.andyl.ignite.presentation.home

import androidx.lifecycle.viewModelScope
import com.andyl.ignite.domain.DeviceDiscovery
import com.andyl.ignite.domain.FileReceiver
import com.andyl.ignite.domain.FileSender
import com.andyl.ignite.domain.TransferRepository
import com.andyl.ignite.domain.model.Device
import com.andyl.ignite.domain.model.Transfer
import com.andyl.ignite.presentation.MviViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class HomeViewModel(
    private val discovery: DeviceDiscovery,
    private val sender: FileSender,
    private val receiver: FileReceiver,
    private val repository: TransferRepository,
) : MviViewModel<HomeEvent, HomeState, HomeEffect>() {

    private val _devices = MutableStateFlow<List<Device>>(emptyList())
    private var sendJob: Job? = null

    override fun initialState(): HomeState = HomeState()

    init {
        startInfrastructure()
        collectDevices()
    }

    private fun startInfrastructure() {
        viewModelScope.launch {
            receiver.start()
            discovery.start()
        }
    }

    private fun collectDevices() {
        discovery.devices
            .onEach { device ->
                val updated = (_devices.value.filterNot { it.id == device.id } + device)
                    .sortedBy { it.name.lowercase() }
                _devices.value = updated
                updateState { state -> state.copy(devices = updated) }
            }
            .catch { error ->
                updateState { state -> state.copy(isScanning = false, error = "Discovery failed") }
            }
            .launchIn(viewModelScope)
    }

    override fun onEventImpl(event: HomeEvent) {
        when (event) {
            HomeEvent.OnStart -> Unit
            HomeEvent.OnRefresh -> refresh()
            is HomeEvent.OnDeviceSelected -> {
                updateState { it.copy(selectedDevice = event.device) }
            }
            is HomeEvent.OnFileSelected -> addFile(event.file)
            is HomeEvent.OnFileCleared -> {
                updateState { it.copy(pendingFiles = it.pendingFiles.filterNot { f -> f.path == event.file.path }) }
            }
            HomeEvent.OnSendClick -> send()
        }
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
                error = null,
            )
        }
    }

    private fun send() {
        val target = state.value.selectedDevice ?: return
        val files = state.value.pendingFiles
        if (files.isEmpty() || sendJob != null) return

        updateState { it.copy(isSending = true, progress = 0f, error = null) }

        sendJob = viewModelScope.launch {
            for (file in files) {
                updateState { it.copy(activeFileName = file.name, progress = 0f) }

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
                    sender.send(target, file.path, file.name, file.sizeBytes)
                        .onEach { progress ->
                            updateState { it.copy(progress = progress) }
                            repository.upsert(record.copy(progress = progress))
                        }
                        .catch { e -> throw e }
                        .launchIn(viewModelScope)
                        .join()

                    repository.upsert(record.copy(status = Transfer.Status.COMPLETED, progress = 1f))
                } catch (e: Exception) {
                    repository.upsert(record.copy(status = Transfer.Status.FAILED))
                    sendEffect(HomeEffect.ShowMessage("Error enviando ${file.name}: ${e.message}"))
                    break
                }
            }
            updateState {
                it.copy(
                    isSending = false,
                    progress = 1f,
                    activeFileName = null,
                    pendingFiles = emptyList(),
                )
            }
            sendJob = null
            sendEffect(HomeEffect.ShowMessage("Transferencia completada"))
        }
    }
}
