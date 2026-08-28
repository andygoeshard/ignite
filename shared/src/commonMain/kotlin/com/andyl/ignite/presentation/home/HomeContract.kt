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
    /** Ruta relativa dentro de una carpeta (vacío si es suelto). */
    val relativePath: String = "",
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
    val sha256: String? = null,
)

data class SendOutcome(
    val fileName: String,
    val targetName: String,
    val success: Boolean,
    val count: Int = 1,
    val cancelled: Boolean = false,
    val error: TransferError? = null,
    val sha256: String? = null,
)

/**
 * Progreso de un target individual durante un envío fan-out 1→N.
 */
data class FanOutTarget(
    val deviceName: String,
    val progress: Float = 0f,
    val sentBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val completed: Boolean = false,
    val failed: Boolean = false,
    val error: String? = null,
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

    /** Fan-out 1→N: envío paralelo a múltiples targets. */
    data class FanOutSending(
        val fileIndex: Int,
        val fileCount: Int,
        val fileName: String,
        val targets: Map<String, FanOutTarget>, // deviceId → progress
        val totalBytes: Long,
    ) : SendSession {
        val completedTargets: Int get() = targets.count { it.value.completed || it.value.failed }
        val totalTargets: Int get() = targets.size
        val globalProgress: Float
            get() = if (totalTargets == 0) 0f
            else targets.values.sumOf { it.sentBytes }.toFloat() / totalTargets / (if (totalBytes > 0) totalBytes / totalTargets else 1L)
    }

    /** El usuario pidió cancelar; el job está terminando de cerrar canales. */
    data class Cancelling(val of: SendSession) : SendSession
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
    /** El usuario eligió una carpeta para enviar (escaneo recursivo). */
    data class OnFolderSelected(val folderPath: String) : HomeEvent
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
    /** Toggle sincronización de clipboard. */
    data object OnToggleClipboardSync : HomeEvent
    /** Pegar un item del historial de clipboard al clipboard local. */
    data class OnPasteFromClipboardHistory(val index: Int) : HomeEvent
    /** Limpiar historial de clipboard. */
    data object OnClearClipboardHistory : HomeEvent
}

data class HomeState(
    val devices: List<Device> = emptyList(),
    val selectedDevice: Device? = null,
    val selectedDevices: Set<Device> = emptySet(),
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
    /** Sincronización de clipboard activa. */
    val clipboardSyncEnabled: Boolean = false,
    /** Historial de clipboard recibido de pares. */
    val clipboardHistory: List<com.andyl.ignite.domain.model.ClipboardItem> = emptyList(),
    /** El clipboard local fue actualizado remotamente (para evitar eco). */
    var lastRemoteClipboard: String? = null,
) {
    val canSend: Boolean
        get() = selectedDevices.isNotEmpty() &&
            pendingFiles.isNotEmpty() &&
            sendSession is SendSession.Idle &&
            targetPin.length == 6

    val isSendActive: Boolean
        get() = sendSession !is SendSession.Idle

    /** Listo para enviar texto: al menos un dispositivo seleccionado, texto no vacío, PIN de 6. */
    val canSendText: Boolean
        get() = selectedDevices.isNotEmpty() &&
            textInput.isNotBlank() &&
            targetPin.length == 6

    /** Nombre del target para mostrar (uno o "N dispositivos"). */
    val targetDisplayName: String
        get() = when (selectedDevices.size) {
            0 -> ""
            1 -> selectedDevices.first().name
            else -> "${selectedDevices.size} dispositivos"
        }
}

sealed interface HomeEffect {
    /** Mensajes transitorios de feedback (#28). Los errores con decisión viven en State. */
    data class ShowSnackbar(val message: String) : HomeEffect
}
