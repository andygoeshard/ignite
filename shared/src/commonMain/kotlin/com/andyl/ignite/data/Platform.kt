package com.andyl.ignite.data

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
 * Opens the system file manager showing [path]'s parent folder.
 * Returns false when the platform cannot do it.
 */
expect fun revealInFileManager(path: String): Boolean

/**
 * Whether the platform supports picking a custom download directory.
 */
expect val supportsCustomDownloadDir: Boolean

