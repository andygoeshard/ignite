package com.andyl.ignite.data

import android.os.Build
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.filesDir
import io.github.vinceglb.filekit.path
import java.io.File
import java.util.UUID

actual class AppStorage {
    actual fun receiveDir(): String = File(FileKit.filesDir.path, "received").absolutePath
}

actual class DeviceInfo {
    actual val deviceId: String by lazy { resolveDeviceId() }
    actual val deviceName: String get() = Build.MODEL

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
