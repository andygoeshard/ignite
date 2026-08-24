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

        /** Fallback directo (sin FGS) para notificar desde segundo plano. */
        const val CHANNEL_ID = "ignite_transfers"
        const val NOTIF_ID_TRANSFER = 4243
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
        // Camino normal: el servicio foreground ya está vivo y actualiza su
        // notificación. Si NO está corriendo (receptor pausado, app en segundo
        // plano con Android 12+ que bloquea startForegroundService), caemos a
        // una notificación DIRECTA del NotificationManager para que el envío
        // igual se vea.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
                return
            } else {
                context.startService(intent)
                return
            }
        } catch (e: Exception) {
            println("[Ignite][ERROR] FGS no disponible (${e::class.simpleName}) — notificación directa")
        }
        postDirectNotification(action, fileName, progress, totalBytes)
    }

    private fun postDirectNotification(action: String, fileName: String?, progress: Float?, totalBytes: Long?) {
        try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.createNotificationChannel(
                    android.app.NotificationChannel(
                        CHANNEL_ID,
                        "Transferencias",
                        android.app.NotificationManager.IMPORTANCE_LOW,
                    ),
                )
            }
            val name = fileName ?: "archivo"
            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                android.app.Notification.Builder(context, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION") android.app.Notification.Builder(context)
            }
            builder.setSmallIcon(android.R.drawable.stat_sys_upload)
                .setOnlyAlertOnce(true)
                .setAutoCancel(action == ACTION_COMPLETED || action == ACTION_FAILED)
                .setContentTitle("Ignite")
                .setContentText(
                    when (action) {
                        ACTION_SENDING -> "Enviando «$name»…"
                        ACTION_COMPLETED -> "«$name» enviado"
                        ACTION_FAILED -> "No se pudo enviar «$name»"
                        else -> "«$name»"
                    },
                )
            if (progress != null && action == ACTION_SENDING) {
                builder.setProgress(100, (progress.coerceIn(0f, 1f) * 100).toInt(), false)
                builder.setOngoing(true)
            }
            val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            if (launchIntent != null) {
                builder.setContentIntent(
                    android.app.PendingIntent.getActivity(
                        context,
                        0,
                        launchIntent,
                        android.app.PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
            }
            manager.notify(NOTIF_ID_TRANSFER, builder.build())
        } catch (e: Exception) {
            println("[Ignite][ERROR] notificación directa falló: ${e.message}")
        }
    }
}
