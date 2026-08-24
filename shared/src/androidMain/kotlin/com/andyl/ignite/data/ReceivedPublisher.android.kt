package com.andyl.ignite.data

import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import com.andyl.ignite.data.db.AndroidContextHolder
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.filesDir
import io.github.vinceglb.filekit.path
import java.io.File

/**
 * Publica el archivo recibido (buffer privado de la app) en la carpeta que el
 * usuario ve: Descargas/Ignite por defecto (API 29+, vía MediaStore, sin
 * permisos) o el árbol SAF que eligió en el perfil. El receptor sigue escribiendo
 * a disco con java.io.File — solo movemos el archivo YA completo.
 */
actual suspend fun publishReceivedFile(path: String): String {
    val source = File(path)
    if (!source.exists() || source.length() == 0L) return path
    val customTree = readCustomTreeUri()
    return runCatching {
        when {
            customTree != null -> publishToSaf(source, customTree)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> publishToMediaStore(source)
            else -> path // API 28 sin carpeta elegida: queda en la privada
        }.also { final ->
            if (final != path) {
                source.delete()
                println("[Ignite][RCV] '${source.name}' publicado en $final")
            }
        }
    }.getOrElse {
        println("[Ignite][ERROR] publicando '${source.name}' en carpeta visible: ${it.message}")
        path
    }
}

/** URI del árbol SAF elegido en el perfil; null si no hay o es un path plano. */
internal fun readCustomTreeUri(): Uri? = runCatching {
    val file = File(FileKit.filesDir.path, "download_dir")
    val raw = if (file.exists()) file.readText().trim() else ""
    if (raw.startsWith("content://")) Uri.parse(raw) else null
}.getOrNull()

private fun mimeFor(name: String): String =
    MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.').lowercase())
        ?: "application/octet-stream"

private fun publishToMediaStore(source: File): String {
    val resolver = AndroidContextHolder.context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, source.name)
        put(MediaStore.Downloads.MIME_TYPE, mimeFor(source.name))
        put(MediaStore.Downloads.RELATIVE_PATH, "Download/Ignite")
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    @Suppress("NewApi")
    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        ?: throw IllegalStateException("MediaStore rechazó la inserción")
    copyInto(resolver.openOutputStream(uri), source)
    values.clear()
    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
    @Suppress("NewApi")
    resolver.update(uri, values, null, null)
    return uri.toString()
}

private fun publishToSaf(source: File, treeUri: Uri): String {
    val resolver = AndroidContextHolder.context.contentResolver
    val rootDoc = DocumentsContract.buildDocumentUriUsingTree(
        treeUri,
        DocumentsContract.getTreeDocumentId(treeUri),
    )
    val docUri = DocumentsContract.createDocument(resolver, rootDoc, mimeFor(source.name), source.name)
        ?: throw IllegalStateException("SAF no creó el documento en $treeUri")
    copyInto(resolver.openOutputStream(docUri), source)
    return docUri.toString()
}

private fun copyInto(output: java.io.OutputStream?, source: File) {
    if (output == null) throw IllegalStateException("sin OutputStream para escribir")
    output.use { out ->
        source.inputStream().use { input ->
            input.copyTo(out, 64 * 1024)
        }
    }
}
