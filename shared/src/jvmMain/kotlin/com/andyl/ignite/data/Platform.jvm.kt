package com.andyl.ignite.data

import com.andyl.ignite.domain.TrustedDevices
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
