package com.andyl.ignite.data

import com.andyl.ignite.domain.TrustedDevices

/**
 * Provides the storage directory where received files are saved.
 */
expect class AppStorage {
    fun receiveDir(): String

    /**
     * Overrides the download directory. Pass null to fall back to the default.
     */
    fun setReceiveDir(path: String?)
}

/**
 * Returns a stable, friendly device name and a stable device id used for
 * discovery.
 */
expect class DeviceInfo {
    val deviceId: String
    val deviceName: String
    val hasCustomName: Boolean

    fun rename(name: String)
}

/**
 * Returns the app ID used by FileKit to resolve application directories.
 */
expect val appId: String

expect fun createAppStorage(): AppStorage

expect fun createDeviceInfo(): DeviceInfo

/**
 * Store persistente de dispositivos emparejados (PIN recordado).
 * Archivo JSON en el directorio de la app.
 */
expect fun createTrustedDevices(): TrustedDevices

/**
 * Opens the system file manager showing [path]'s parent folder.
 * Returns false when the platform cannot do it.
 */
expect fun revealInFileManager(path: String): Boolean

/**
 * Whether the platform supports picking a custom download directory.
 */
expect val supportsCustomDownloadDir: Boolean

/**
 * Genera una miniatura JPEG (lado más largo ≤ [maxPx]) para previsualizar
 * un archivo entrante antes de aprobarlo. Devuelve null si la plataforma
 * no sabe leer ese formato (ej.: video en Desktop) o si falla.
 */
expect fun createThumbnail(path: String, maxPx: Int = 512): ByteArray?

/** Decodifica los bytes de una miniatura a un bitmap de Compose. Null si no puede. */
expect fun decodePreview(bytes: ByteArray): androidx.compose.ui.graphics.ImageBitmap?

/**
 * Genera un código QR como bitmap cuadrado (~[sizePx] px) para emparejar.
 * Null si el contenido excede la capacidad del QR.
 */
expect fun generateQr(content: String, sizePx: Int = 512): androidx.compose.ui.graphics.ImageBitmap?

