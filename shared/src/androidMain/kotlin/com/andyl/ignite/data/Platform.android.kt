package com.andyl.ignite.data

import com.andyl.ignite.data.db.AndroidContextHolder

import com.andyl.ignite.domain.TrustedDevices

import android.os.Build
import androidx.compose.ui.graphics.asImageBitmap
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.filesDir
import io.github.vinceglb.filekit.path
import java.io.File
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
}

actual class DeviceInfo {
    actual val deviceId: String by lazy { resolveDeviceId() }
    actual val deviceName: String get() = customName() ?: Build.MODEL
    actual val hasCustomName: Boolean get() = nameFile().exists()

    actual fun rename(name: String) {
        val file = nameFile()
        if (name.isBlank()) {
            file.delete()
        } else {
            file.writeText(name.trim())
        }
    }

    private fun customName(): String? {
        val file = nameFile()
        if (!file.exists()) return null
        return file.readText().trim().ifBlank { null }
    }

    // filesDir del Context (disponible desde Application.onCreate) y no
    // FileKit.filesDir, que puede no estar listo al construir el ViewModel:
    // si fallaba, el diálogo de perfil abría con el campo vacío.
    private fun nameFile() = File(AndroidContextHolder.context.filesDir, "device_name")

    private fun resolveDeviceId(): String {
        val idFile = File(AndroidContextHolder.context.filesDir, "device_id")
        if (idFile.exists()) return idFile.readText().trim()
        val id = UUID.randomUUID().toString()
        idFile.writeText(id)
        return id
    }
}

actual val appId: String = "com.andyl.ignite"

actual fun createAppStorage(): AppStorage = AppStorage()

actual fun createDeviceInfo(): DeviceInfo = DeviceInfo()

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

actual fun revealInFileManager(path: String): Boolean = false

actual val supportsCustomDownloadDir: Boolean = false

private val PREVIEW_IMAGE_EXTS = listOf(".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp", ".heic", ".heif")
private val PREVIEW_VIDEO_EXTS = listOf(".mp4", ".mov", ".mkv", ".webm", ".avi", ".3gp")

/** Miniatura JPEG: BitmapFactory con inSampleSize para imágenes, frame de MediaMetadataRetriever para video. */
actual fun createThumbnail(path: String, maxPx: Int): ByteArray? = runCatching {
    val isContent = path.startsWith("content://")
    val file = java.io.File(path)
    if (!isContent && (!file.exists() || file.length() == 0L)) return@runCatching null
    val name = if (isContent) {
        queryContentName(path) ?: ""
    } else {
        file.name.lowercase()
    }
    val isVideo = PREVIEW_VIDEO_EXTS.any { name.endsWith(it) }
    if (!isVideo && PREVIEW_IMAGE_EXTS.none { name.endsWith(it) }) return@runCatching null

    val source = if (isVideo) {
        val retriever = android.media.MediaMetadataRetriever()
        try {
            if (isContent) {
                retriever.setDataSource(
                    AndroidContextHolder.context,
                    android.net.Uri.parse(path),
                )
            } else {
                retriever.setDataSource(path)
            }
            retriever.getFrameAtTime(0)
        } finally {
            retriever.release()
        }
    } else {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        decodeBitmap(path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxPx && bounds.outHeight / (sample * 2) >= maxPx) sample *= 2
        decodeBitmap(path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
    } ?: return@runCatching null

    val scale = minOf(1f, maxPx.toFloat() / maxOf(source.width, source.height).coerceAtLeast(1))
    val bitmap = if (scale < 1f) {
        android.graphics.Bitmap.createScaledBitmap(
            source,
            (source.width * scale).toInt().coerceIn(1, maxPx),
            (source.height * scale).toInt().coerceIn(1, maxPx),
            true,
        )
    } else {
        source
    }
    val bytes = java.io.ByteArrayOutputStream().use { buffer ->
        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, buffer)
        buffer.toByteArray()
    }
    bytes.takeIf { it.isNotEmpty() }
}.getOrNull()

/** Decode de miniaturas vía BitmapFactory (Android). */
actual fun decodePreview(bytes: ByteArray): androidx.compose.ui.graphics.ImageBitmap? = runCatching {
    val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    bmp?.asImageBitmap()
}.getOrNull()

/** Decodifica soportando content:// URIs y paths de archivo. */
private fun decodeBitmap(path: String, options: android.graphics.BitmapFactory.Options): android.graphics.Bitmap? {
    return if (path.startsWith("content://")) {
        com.andyl.ignite.data.openTransferStream(path)?.use { input ->
            android.graphics.BitmapFactory.decodeStream(input, null, options)
        }
    } else {
        android.graphics.BitmapFactory.decodeFile(path, options)
    }
}

/** DISPLAY_NAME de un content:// URI; null si no se puede consultar. */
private fun queryContentName(path: String): String? = runCatching {
    val resolver = AndroidContextHolder.context.contentResolver
    resolver.query(
        android.net.Uri.parse(path),
        arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
        null, null, null,
    )?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
}.getOrNull()?.lowercase()

/** QR vía ZXing → Bitmap, fondo blanco opaco + zona de silencio (ver QrCodeCore). */
actual fun generateQr(content: String, sizePx: Int): androidx.compose.ui.graphics.ImageBitmap? = runCatching {
    val modules = com.andyl.ignite.data.qrModules(content) ?: return@runCatching null
    val bitmap = android.graphics.Bitmap.createBitmap(sizePx, sizePx, android.graphics.Bitmap.Config.ARGB_8888)
    com.andyl.ignite.data.forEachQrPixel(modules, sizePx) { x, y, isDark ->
        bitmap.setPixel(x, y, if (isDark) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
    }
    bitmap.asImageBitmap()
}.getOrNull()
