package com.andyl.ignite.presentation.home

import com.andyl.ignite.domain.model.Device

/**
 * A local file staged for sending, selected by the user via picker or drag & drop.
 */
data class PendingFile(
    val path: String,
    val name: String,
    val sizeBytes: Long,
)

sealed interface HomeEvent {
    data object OnStart : HomeEvent
    data object OnRefresh : HomeEvent
    data class OnDeviceSelected(val device: Device) : HomeEvent
    data class OnFileSelected(val file: PendingFile) : HomeEvent
    data class OnFileCleared(val file: PendingFile) : HomeEvent
    data object OnSendClick : HomeEvent
}

data class HomeState(
    val devices: List<Device> = emptyList(),
    val selectedDevice: Device? = null,
    val pendingFiles: List<PendingFile> = emptyList(),
    val isScanning: Boolean = true,
    val isSending: Boolean = false,
    val progress: Float = 0f,
    val activeFileName: String? = null,
    val error: String? = null,
) {
    val canSend: Boolean
        get() = selectedDevice != null && pendingFiles.isNotEmpty() && !isSending
}

sealed interface HomeEffect {
    data class ShowMessage(val text: String) : HomeEffect
}
