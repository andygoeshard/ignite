package com.andyl.ignite.domain

/**
 * Bridge that propagates transfer progress to platform-specific UI
 * (e.g. Android foreground notification, Desktop tray).
 * No-op on platforms that don't need it.
 */
interface TransferNotifier {
    fun onIdle(deviceName: String)
    fun onReceiving(fileName: String, receivedBytes: Long, totalBytes: Long, progress: Float)
    fun onSending(fileName: String, progress: Float, totalBytes: Long)
    fun onCompleted(fileName: String, isSending: Boolean)
    fun onFailed(fileName: String, message: String? = null)
}
