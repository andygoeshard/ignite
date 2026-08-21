package com.andyl.ignite.data

/**
 * Provides the storage directory where received files are saved.
 */
expect class AppStorage {
    fun receiveDir(): String
}

/**
 * Returns a stable, friendly device name and a stable device id used for
 * discovery.
 */
expect class DeviceInfo {
    val deviceId: String
    val deviceName: String
}

/**
 * Returns the app ID used by FileKit to resolve application directories.
 */
expect val appId: String

expect fun createAppStorage(): AppStorage

expect fun createDeviceInfo(): DeviceInfo

