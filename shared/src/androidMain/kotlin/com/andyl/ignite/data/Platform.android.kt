package com.andyl.ignite.data

import com.andyl.ignite.data.db.AndroidContextHolder

import com.andyl.ignite.domain.TrustedDevices

import android.content.Intent
import android.os.Build
import android.provider.DocumentsContract
import androidx.compose.ui.graphics.asImageBitmap
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.filesDir
import io.github.vinceglb.filekit.path
import java.io.File
import java.util.UUID

actual class AppStorage {
    /**
     * SIEMPRE la carpeta privada: es el buffer donde el receptor escribe
     * con java.io.File (resume/dedup). La elección del usuario (SAF o
     * Descargas/Ignite) se aplica al publicar, en publishReceivedFile().
     * Antes devolvía el URI elegido y el receptor moría con ENOENT.
     */
    actual fun receiveDir(): String = File(FileKit.filesDir.path, "received").absolutePath

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

    /**
     * Lo que ve el usuario en el perfil. El buffer interno sigue siendo la
     * carpeta privada (receiveDir); los archivos completos se publican a
     * Descargas/Ignite o al árbol SAF elegido.
     */
    actual fun displayPath(): String {
        val custom = customDir()
        if (custom != null && custom.startsWith("content://")) {
            val uri = android.net.Uri.parse(custom)
            val docId = runCatching { android.provider.DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
            // tree id "primary:Download/apks" → "Download/apks"
            val friendly = docId?.substringAfter(':', missingDelimiterValue = docId)
            return friendly ?: custom
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "Download/Ignite" else receiveDir()
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

/**
 * Abre la carpeta donde quedó el archivo recibido. [path] puede ser un
 * content:// publicado (MediaStore o SAF) o un path de archivo privado.
 * Cadena de intentos con fallback: cada ROM se comporta distinto.
 */
actual fun revealInFileManager(path: String): Boolean {
    val context = AndroidContextHolder.context
    val uri = path.takeIf { it.startsWith("content://") }?.let { android.net.Uri.parse(it) }
    // NEW_TASK obligatorio: arrancamos desde application context (sin Activity detrás).
    val newTask = Intent.FLAG_ACTIVITY_NEW_TASK
    val attempts = buildList {
        // 1) Documento SAF publicado → pedirle al Files app que lo muestre.
        if (uri != null && DocumentsContract.isDocumentUri(context, uri)) {
            add(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or newTask)
                },
            )
        }
        // 2) Descargas/Ignite (MediaStore) o archivo privado → gestor de Descargas.
        add(Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(newTask))
        // 3) Último recurso: abrir el archivo con la app que lo maneje.
        if (uri != null) {
            add(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, context.contentResolver.getType(uri) ?: "*/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or newTask)
                },
            )
        }
    }
    attempts.forEachIndexed { index, intent ->
        runCatching { context.startActivity(intent) }.onSuccess {
            return true
        }.onFailure { e ->
            println("[Ignite][ERROR] abrir carpeta (intento $index): ${e::class.simpleName}: ${e.message}")
        }
    }
    return false
}

actual val supportsCustomDownloadDir: Boolean = true

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
            // Frame ESCALADO: getFrameAtTime() devolvía el frame a resolución
            // completa (un video 4K = bitmap de ~33MB) y ese era el pico de RAM.
            val w = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: maxPx
            val h = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: maxPx
            val scale = minOf(1f, (maxPx * 2f) / maxOf(w, h).coerceAtLeast(1))
            val targetW = (w * scale).toInt().coerceAtLeast(1)
            val targetH = (h * scale).toInt().coerceAtLeast(1)
            retriever.getScaledFrameAtTime(
                0,
                android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                targetW,
                targetH,
            )
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

/** Diagnóstico: heap Java + nativo (el pico de RAM real vive acá). */
actual fun debugMemSnapshot(): String = runCatching {
    val rt = Runtime.getRuntime()
    val javaUsed = (rt.totalMemory() - rt.freeMemory()) / 1048576
    val javaMax = rt.maxMemory() / 1048576
    val native = android.os.Debug.getNativeHeapAllocatedSize() / 1048576
    "java=${javaUsed}/${javaMax}MB native=${native}MB"
}.getOrDefault("mem=?")

/** IPs LAN de este dispositivo (IPv4, sin loopback). */
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
