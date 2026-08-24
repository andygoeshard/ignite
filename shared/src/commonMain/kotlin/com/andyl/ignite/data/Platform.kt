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

    /**
     * Ruta amigable para mostrar en el perfil (puede ser una ubicación
     * pública tipo "Download/Ignite" aunque el buffer interno sea privado).
     */
    fun displayPath(): String
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
 * Publica un archivo recibido (que el receptor escribió en su carpeta
 * buffer privada) en la ubicación visible elegida por el usuario
 * (Descargas/Ignite por defecto en Android, o la carpeta SAF elegida).
 * Devuelve la ubicación final para UI/historial. Desktop: identidad.
 * Nunca lanza: ante error devuelve [path] original y loguea.
 */
expect suspend fun publishReceivedFile(path: String): String

/**
 * Snapshot de memoria para diagnóstico ([Ignite][MEM] en logs):
 * heap Java usado/total y nativo asignado cuando la plataforma lo expone.
 */
expect fun debugMemSnapshot(): String

/** IPs locales de esta máquina (para detectar el emulador escondido detrás de nuestro NAT). */
expect fun localLanAddresses(): Set<String>

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

