package com.andyl.ignite.presentation.home

import com.andyl.ignite.domain.TrustPolicy
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
        /** IP del emisor (para "aceptar siempre" si no hay deviceId). */
        val peerHost: String = "",
        /** Identidad declarada por el emisor (header X-Ignite-Device-Id). */
        val peerDeviceId: String? = null,
        /** Miniatura JPEG enviada por el emisor; null = mostrar icono genérico. */
        val previewBytes: ByteArray? = null,
        /** El usuario eligió "Más tarde": el diálogo se cierra, queda el banner. */
        val deferred: Boolean = false,
        /** Límite (epoch ms) en que la conexión se corta si no hay decisión. */
        val expiresAtMillis: Long = 0L,
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

/** Mensaje de texto recibido de un par (Fase 3). */
data class TextMessageUi(
    val text: String,
    val senderName: String,
    val senderHost: String,
    val timestamp: Long,
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
    /** Aprobar y marcar al emisor como "aceptar siempre" (momento AirDrop). */
    data class OnApproveIncomingAlways(val deviceId: String) : HomeEvent
    data object OnRejectIncoming : HomeEvent
    data object OnIncomingDeferred : HomeEvent
    data class OnForgetDevice(val deviceId: String, val name: String) : HomeEvent
    /** Rota la política de recepción de un par: ASK → AUTO → SILENT → ASK. */
    data class OnCycleDevicePolicy(val deviceId: String) : HomeEvent
    data object OnTogglePinDialog : HomeEvent
    /** Mostrar el QR de emparejamiento de este dispositivo. */
    data object OnShowPairQr : HomeEvent
    /** El escáner devolvió el contenido crudo de un QR (payload o basura). */
    data class OnQrScanned(val raw: String) : HomeEvent
    data class OnManualIpChanged(val ip: String) : HomeEvent
    /** Pausar/reactivar el receptor (dejar de estar visible y de aceptar archivos). */
    data object OnToggleReceiverActive : HomeEvent
    data object OnManualConnect : HomeEvent
    data object OnCancelSend : HomeEvent
    data object OnDismissInterrupted : HomeEvent
    /** Toggle entre modo archivos y modo texto. */
    data object OnToggleTextMode : HomeEvent
    /** Texto que el usuario está escribiendo. */
    data class OnTextInputChanged(val text: String) : HomeEvent
    /** Enviar el texto al dispositivo seleccionado. */
    data object OnSendText : HomeEvent
    /** Descartar un mensaje de texto recibido. */
    data class OnDismissTextMessage(val index: Int) : HomeEvent
    /** Editar y reenviar: pre-llena el campo de texto con el contenido recibido. */
    data class OnEditTextMessage(val text: String) : HomeEvent
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
    /** Diálogo con el QR de emparejamiento propio. */
    val showPairQrDialog: Boolean = false,
    /** Receptor encendido: false = invisible en la red y no acepta archivos. */
    val receiverActive: Boolean = true,
    val myPin: String = "",
    val targetPin: String = "",
    val manualIp: String = "",
    val incoming: IncomingUi? = null,
    val recentReceived: List<ReceivedFileUi> = emptyList(),
    val sendOutcome: SendOutcome? = null,
    val downloadPath: String = "",
    val canChooseDownloadDir: Boolean = false,
    /** Dispositivos con PIN recordado (ids) para mostrar el candadito en la lista. */
    val trustedIds: Set<String> = emptySet(),
    /** Nombre del dispositivo cuyo PIN se precargó automáticamente. */
    val pinRememberedFor: String? = null,
    /** Política de recepción por deviceId (solo pares confiables). */
    val devicePolicies: Map<String, TrustPolicy> = emptyMap(),
    /** Modo texto activo (vs modo archivos). */
    val isTextMode: Boolean = false,
    /** Texto que el usuario está escribiendo para enviar. */
    val textInput: String = "",
    /** Mensajes de texto recibidos de pares. */
    val receivedTextMessages: List<TextMessageUi> = emptyList(),
) {
    val canSend: Boolean
        get() = selectedDevice != null &&
            pendingFiles.isNotEmpty() &&
            sendSession is SendSession.Idle &&
            targetPin.length == 6

    val isSendActive: Boolean
        get() = sendSession !is SendSession.Idle

    /** Listo para enviar texto: dispositivo seleccionado, texto no vacío, PIN de 6. */
    val canSendText: Boolean
        get() = selectedDevice != null &&
            textInput.isNotBlank() &&
            targetPin.length == 6
}

sealed interface HomeEffect {
    /** Mensajes transitorios de feedback (#28). Los errores con decisión viven en State. */
    data class ShowSnackbar(val message: String) : HomeEffect
}
