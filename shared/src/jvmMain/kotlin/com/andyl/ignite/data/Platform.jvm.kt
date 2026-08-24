package com.andyl.ignite.data

import com.andyl.ignite.domain.TrustedDevices
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.filesDir
import io.github.vinceglb.filekit.path
import java.io.File
import java.net.InetAddress
import java.util.UUID

actual class AppStorage {
    actual fun receiveDir(): String = customDir() ?: File(FileKit.filesDir.path, "received").absolutePath

    actual fun setReceiveDir(path: String?) {
        val file = File(FileKit.filesDir.path, "download_dir")
        if (path.isNullOrBlank()) {
            file.delete()
        } else {
            file.parentFile?.mkdirs()
            file.writeText(path.trim())
        }
    }

    private fun customDir(): String? {
        val file = File(FileKit.filesDir.path, "download_dir")
        val path = if (file.exists()) file.readText().trim() else ""
        return path.ifBlank { null }
    }

    actual fun displayPath(): String = receiveDir()
}

actual class DeviceInfo {
    actual val deviceId: String by lazy { resolveDeviceId() }
    actual val deviceName: String get() = customName() ?: defaultName()
    actual val hasCustomName: Boolean get() = nameFile().exists()

    actual fun rename(name: String) {
        val file = nameFile()
        if (name.isBlank()) {
            file.delete()
        } else {
            file.writeText(name.trim())
        }
    }

    private fun defaultName(): String =
        runCatching { InetAddress.getLocalHost().hostName }.getOrElse { "desktop" }

    private fun customName(): String? {
        val file = nameFile()
        if (!file.exists()) return null
        return file.readText().trim().ifBlank { null }
    }

    private fun nameFile() = File(FileKit.filesDir.path, "device_name")

    private fun resolveDeviceId(): String {
        val idFile = File(FileKit.filesDir.path, "device_id")
        if (idFile.exists()) return idFile.readText().trim()
        val id = UUID.randomUUID().toString()
        idFile.writeText(id)
        return id
    }
}

actual val appId: String = "com.andyl.ignite"

actual fun createAppStorage(): AppStorage = AppStorage()

actual fun createDeviceInfo(): DeviceInfo = DeviceInfo()

actual fun revealInFileManager(path: String): Boolean = runCatching {
    val target = File(path)
    val folder = if (target.isDirectory) target else target.parentFile ?: return@runCatching false
    if (!folder.exists()) return@runCatching false
    java.awt.Desktop.getDesktop().open(folder)
    true
}.getOrDefault(false)

/** Desktop: el archivo ya está donde el usuario lo ve — identidad. */
actual suspend fun publishReceivedFile(path: String): String = path

actual val supportsCustomDownloadDir: Boolean = true

actual fun createTrustedDevices(): TrustedDevices {
    val file = File(FileKit.filesDir.path, "trusted_devices.json")
    return TrustedDevices(
        readRaw = { file.takeIf { it.exists() }?.readText() },
        writeRaw = { text ->
            file.parentFile?.mkdirs()
            file.writeText(text)
        },
    )
}

/** Miniatura JPEG vía ImageIO (solo imágenes; video no soportado en JVM puro). */
actual fun createThumbnail(path: String, maxPx: Int): ByteArray? = runCatching {
    val source = File(path)
    if (!source.exists() || source.length() == 0L) return@runCatching null
    val img = javax.imageio.ImageIO.read(source) ?: return@runCatching null
    val scale = minOf(1f, maxPx.toFloat() / maxOf(img.width, img.height).coerceAtLeast(1))
    val w = (img.width * scale).toInt().coerceIn(1, maxPx)
    val h = (img.height * scale).toInt().coerceIn(1, maxPx)
    val out = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB)
    val g = out.createGraphics()
    g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
    g.drawImage(img, 0, 0, w, h, null)
    g.dispose()
    val bytes = java.io.ByteArrayOutputStream().use { buffer ->
        javax.imageio.ImageIO.write(out, "jpg", buffer)
        buffer.toByteArray()
    }
    bytes.takeIf { it.isNotEmpty() }
}.getOrNull()

/** Decode de miniaturas vía Skia (desktop). */
actual fun decodePreview(bytes: ByteArray): ImageBitmap? = runCatching {
    org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
}.getOrNull()

/** QR vía ZXing → BufferedImage → PNG → Skia. */
actual fun generateQr(content: String, sizePx: Int): ImageBitmap? = runCatching {
    val modules = com.andyl.ignite.data.qrModules(content) ?: return@runCatching null
    // Fondo blanco OPACO + zona de silencio de 4 módulos: sin esto el QR es
    // negro sobre el surface oscuro del diálogo y no se ve nada.
    val img = java.awt.image.BufferedImage(sizePx, sizePx, java.awt.image.BufferedImage.TYPE_INT_RGB)
    val total = com.andyl.ignite.data.forEachQrPixel(modules, sizePx) { x, y, isDark ->
        img.setRGB(x, y, if (isDark) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
    }
    val png = java.io.ByteArrayOutputStream().use { buffer ->
        javax.imageio.ImageIO.write(img.getSubimage(0, 0, total, total), "png", buffer)
        buffer.toByteArray()
    }
    org.jetbrains.skia.Image.makeFromEncoded(png).toComposeImageBitmap()
}.getOrNull()

/** Diagnóstico: solo heap JVM disponible en desktop. */
actual fun debugMemSnapshot(): String = runCatching {
    val rt = Runtime.getRuntime()
    "heap=${(rt.totalMemory() - rt.freeMemory()) / 1048576}/${rt.maxMemory() / 1048576}MB"
}.getOrDefault("heap=?")

/** IPs LAN/loopback-externas de esta máquina (IPv4, sin loopback). */
actual fun localLanAddresses(): Set<String> {
    val out = mutableSetOf<String>()
    runCatching {
        val nis = java.net.NetworkInterface.getNetworkInterfaces() ?: return emptySet()
        while (nis.hasMoreElements()) {
            val addrs = nis.nextElement().inetAddresses ?: continue
            while (addrs.hasMoreElements()) {
                val a = addrs.nextElement()
                if (a.isLoopbackAddress) continue
                val host = a.hostAddress ?: continue
                if (host.indexOf(':') < 0) out += host
            }
        }
    }
    return out
}
