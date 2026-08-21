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

data class IncomingFileUi(
    val fileName: String,
    val peerName: String,
    val sizeBytes: Long,
    val receivedBytes: Long = 0,
    val progress: Float = 0f,
)

data class ReceivedFileUi(
    val fileName: String,
    val path: String,
    val sizeBytes: Long,
)

data class SendOutcome(
    val fileName: String,
    val targetName: String,
    val success: Boolean,
    val count: Int = 1,
)

data class PendingApprovalUi(
    val fileName: String,
    val peerName: String,
    val sizeBytes: Long,
    val transferId: String,
)

sealed interface HomeEvent {
    data object OnStart : HomeEvent
    data object OnRefresh : HomeEvent
    data class OnDeviceSelected(val device: Device) : HomeEvent
    data class OnFileSelected(val file: PendingFile) : HomeEvent
    data class OnFileCleared(val file: PendingFile) : HomeEvent
    data object OnSendClick : HomeEvent
    data object OnProfileClick : HomeEvent
    data object OnDialogDismiss : HomeEvent
    data class OnRenameConfirm(val name: String) : HomeEvent
    data object OnDismissIncoming : HomeEvent
    data class OnOpenReceivedFolder(val file: ReceivedFileUi) : HomeEvent
    data class OnDownloadDirPicked(val path: String?) : HomeEvent
    data class OnTargetPinChanged(val pin: String) : HomeEvent
    data object OnRegeneratePin : HomeEvent
    data object OnApproveIncoming : HomeEvent
    data object OnRejectIncoming : HomeEvent
    data object OnTogglePinDialog : HomeEvent
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
    val deviceName: String = "",
    val showWelcome: Boolean = false,
    val showProfileDialog: Boolean = false,
    val showPinDialog: Boolean = false,
    val myPin: String = "",
    val targetPin: String = "",
    val pendingApproval: PendingApprovalUi? = null,
    val incoming: IncomingFileUi? = null,
    val recentReceived: List<ReceivedFileUi> = emptyList(),
    val sendOutcome: SendOutcome? = null,
    val note: String? = null,
    val downloadPath: String = "",
    val canChooseDownloadDir: Boolean = false,
) {
    val canSend: Boolean
        get() = selectedDevice != null && pendingFiles.isNotEmpty() && !isSending && targetPin.length == 6
}

sealed interface HomeEffect
