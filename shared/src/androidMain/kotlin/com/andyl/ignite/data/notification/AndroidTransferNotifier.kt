package com.andyl.ignite.data.notification

import android.content.Context
import android.content.Intent
import android.os.Build
import com.andyl.ignite.domain.TransferNotifier

class AndroidTransferNotifier(
    private val context: Context,
) : TransferNotifier {

    companion object {
        const val SERVICE_CLASS = "com.andyl.ignite.TransferForegroundService"
        const val ACTION_IDLE = "com.andyl.ignite.action.IDLE"
        const val ACTION_RECEIVING = "com.andyl.ignite.action.RECEIVING"
        const val ACTION_SENDING = "com.andyl.ignite.action.SENDING"
        const val ACTION_COMPLETED = "com.andyl.ignite.action.COMPLETED"
        const val ACTION_FAILED = "com.andyl.ignite.action.FAILED"
        const val EXTRA_FILE_NAME = "fileName"
        const val EXTRA_PROGRESS = "progress"
        const val EXTRA_TOTAL = "total"
        const val EXTRA_DEVICE_NAME = "deviceName"
        const val EXTRA_IS_SENDING = "isSending"
    }

    override fun onIdle(deviceName: String) {
        send(ACTION_IDLE, deviceName = deviceName)
    }

    override fun onReceiving(fileName: String, receivedBytes: Long, totalBytes: Long, progress: Float) {
        send(ACTION_RECEIVING, fileName = fileName, progress = progress, totalBytes = totalBytes)
    }

    override fun onSending(fileName: String, progress: Float, totalBytes: Long) {
        send(ACTION_SENDING, fileName = fileName, progress = progress, totalBytes = totalBytes)
    }

    override fun onCompleted(fileName: String, isSending: Boolean) {
        send(ACTION_COMPLETED, fileName = fileName, isSending = isSending)
    }

    override fun onFailed(fileName: String, message: String?) {
        send(ACTION_FAILED, fileName = fileName)
    }

    private fun send(action: String, fileName: String? = null, progress: Float? = null, totalBytes: Long? = null, deviceName: String? = null, isSending: Boolean? = null) {
        val intent = Intent().apply {
            setClassName(context.packageName, SERVICE_CLASS)
            this.action = action
            fileName?.let { putExtra(EXTRA_FILE_NAME, it) }
            progress?.let { putExtra(EXTRA_PROGRESS, it) }
            totalBytes?.let { putExtra(EXTRA_TOTAL, it) }
            deviceName?.let { putExtra(EXTRA_DEVICE_NAME, it) }
            isSending?.let { putExtra(EXTRA_IS_SENDING, it) }
        }
        // startService is safe here because service is already in foreground
        // after MainActivity's startForegroundService call. For updates we use startService.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (_: Exception) {
        }
    }
}
