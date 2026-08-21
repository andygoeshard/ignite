package com.andyl.ignite.data.network

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Zipea una carpeta al vuelo preservando estructura, para enviar como un solo archivo.
 * Retorna el archivo zip temporal. Caller debe borrarlo al terminar.
 */
fun zipFolderToTemp(sourceDir: File, prefix: String = "ignite_folder"): File {
    require(sourceDir.isDirectory) { "sourceDir must be a directory" }
    val tmp = File.createTempFile(prefix, ".zip")
    ZipOutputStream(FileOutputStream(tmp)).use { zos ->
        sourceDir.walkTopDown().forEach { file ->
            if (file == sourceDir) return@forEach
            val rel = sourceDir.toRelativeString(file)
            if (file.isDirectory) {
                zos.putNextEntry(ZipEntry("$rel/"))
                zos.closeEntry()
            } else {
                zos.putNextEntry(ZipEntry(rel))
                BufferedInputStream(FileInputStream(file)).use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
    }
    return tmp
}

fun unzipToDir(zipFile: File, targetDir: File) {
    targetDir.mkdirs()
    java.util.zip.ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
        var entry = zis.nextEntry
        while (entry != null) {
            val out = File(targetDir, entry.name)
            if (entry.isDirectory) out.mkdirs()
            else {
                out.parentFile?.mkdirs()
                FileOutputStream(out).use { zis.copyTo(it) }
            }
            entry = zis.nextEntry
        }
    }
}
