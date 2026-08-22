package com.andyl.ignite.presentation.home

import com.andyl.ignite.domain.model.Device
import com.andyl.ignite.domain.model.TransferError

/**
 * A local file staged for sending, selected by the user via picker or drag & drop.
 */
data class PendingFile(
    val path: String,
    val name: String,
    val sizeBytes: Long,
)

/**
 * Recepción unificada (#7): un solo patrón para aprobación explícita y
 * progreso no modal, en ambas plataformas.
 */
sealed interface IncomingUi {
    data class AwaitingApproval(
        val fileName: String,
        val peerName: String,
        val sizeBytes: Long,
        val transferId: String,
    ) : IncomingUi

    data class Receiving(
        val fileName: String,
        val peerName: String,
        val sizeBytes: Long,
        val receivedBytes: Long = 0,
        val progress: Float = 0f,
    ) : IncomingUi
}

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
    val cancelled: Boolean = false,
    val error: TransferError? = null,
)

/**
 * Máquina de estados de la sesión de envío (#24). Reemplaza los booleanos
 * sueltos (isSending/isCancelling) y el progreso plano.
 */
sealed interface SendSession {
    data object Idle : SendSession

    /** Preparando: calculando SHA-256 / abriendo conexión con el primero. */
    data class Preparing(
        val targetName: String,
        val fileCount: Int,
        val totalBytes: Long,
    ) : SendSession

    data class Sending(
        val targetName: String,
        val fileIndex: Int, // 0-based
        val fileCount: Int,
        val fileName: String,
        val fileProgress: Float,
        val fileSentBytes: Long,
        val fileTotalBytes: Long,
        val completedBytesBeforeCurrent: Long,
        val totalBytes: Long,
    ) : SendSession {
        val globalSentBytes: Long get() = completedBytesBeforeCurrent + fileSentBytes
        val globalProgress: Float get() = if (totalBytes > 0) globalSentBytes.toFloat() / totalBytes else 0f
    }

    /** El usuario pidió cancelar; el job está terminando de cerrar canales. */
    data class Cancelling(val of: Sending) : SendSession
}

/** Transferencia que quedó a mitad cuando se cerró la app (#27). */
data class InterruptedTransferUi(
    val fileName: String,
    val peerName: String,
    val sizeBytes: Long,
    val progress: Float,
)

sealed interface HomeEvent {
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
    data class OnManualIpChanged(val ip: String) : HomeEvent
    data object OnManualConnect : HomeEvent
    data object OnCancelSend : HomeEvent
    data object OnDismissInterrupted : HomeEvent
}

data class HomeState(
    val devices: List<Device> = emptyList(),
    val selectedDevice: Device? = null,
    val pendingFiles: List<PendingFile> = emptyList(),
    val isScanning: Boolean = true,
    val sendSession: SendSession = SendSession.Idle,
    val interrupted: List<InterruptedTransferUi> = emptyList(),
    val error: String? = null,
    val deviceName: String = "",
    val localIp: String = "",
    val showWelcome: Boolean = false,
    val showProfileDialog: Boolean = false,
    val showPinDialog: Boolean = false,
    val myPin: String = "",
    val targetPin: String = "",
    val manualIp: String = "",
    val incoming: IncomingUi? = null,
    val recentReceived: List<ReceivedFileUi> = emptyList(),
    val sendOutcome: SendOutcome? = null,
    val downloadPath: String = "",
    val canChooseDownloadDir: Boolean = false,
) {
    val canSend: Boolean
        get() = selectedDevice != null &&
            pendingFiles.isNotEmpty() &&
            sendSession is SendSession.Idle &&
            targetPin.length == 6

    val isSendActive: Boolean
        get() = sendSession !is SendSession.Idle
}

sealed interface HomeEffect {
    /** Mensajes transitorios de feedback (#28). Los errores con decisión viven en State. */
    data class ShowSnackbar(val message: String) : HomeEffect
}
