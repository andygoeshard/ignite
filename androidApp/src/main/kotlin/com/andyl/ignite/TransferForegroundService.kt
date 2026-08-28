package com.andyl.ignite

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.andyl.ignite.data.DeviceInfo
import com.andyl.ignite.data.db.AndroidContextHolder
import com.andyl.ignite.di.initKoin
import com.andyl.ignite.domain.DeviceDiscovery
import com.andyl.ignite.domain.FileReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

class TransferForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var currentDeviceName: String = "Ignite"

    override fun onCreate() {
        super.onCreate()
        createChannel()
        createMessageChannel()
        // Garantiza que Koin y el contexto estén listos si el sistema recrea el servicio sin pasar por Application.
        runCatching {
            if (GlobalContext.getOrNull() == null) {
                AndroidContextHolder.context = applicationContext
                initKoin()
            }
        }
        // El receiver/discovery ahora viven en el Service, no solo en el ViewModel.
        // Si la Activity muere, el server sigue en foreground.
        scope.launch(Dispatchers.IO) {
            runCatching { ensureInfrastructure() }
        }
        // Notificación idle inmediata para poder entrar en foreground rápido (Android 14+ exige en <10s)
        startForeground(NOTIF_ID, buildIdleNotification(currentDeviceName))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        when (action) {
            ACTION_IDLE -> {
                currentDeviceName = intent.getStringExtra(EXTRA_DEVICE_NAME) ?: currentDeviceName
                startForeground(NOTIF_ID, buildIdleNotification(currentDeviceName))
            }
            ACTION_RECEIVING -> {
                val fileName = intent.getStringExtra(EXTRA_FILE_NAME) ?: "archivo"
                val progress = intent.getFloatExtra(EXTRA_PROGRESS, 0f)
                startForeground(NOTIF_ID, buildProgressNotification("Recibiendo $fileName", progress, isReceiving = true))
            }
            ACTION_SENDING -> {
                val fileName = intent.getStringExtra(EXTRA_FILE_NAME) ?: "archivo"
                val progress = intent.getFloatExtra(EXTRA_PROGRESS, 0f)
                startForeground(NOTIF_ID, buildProgressNotification("Enviando $fileName", progress, isReceiving = false))
            }
            ACTION_COMPLETED -> {
                val fileName = intent.getStringExtra(EXTRA_FILE_NAME) ?: "archivo"
                val isSending = intent.getBooleanExtra(EXTRA_IS_SENDING, false)
                val text = if (isSending) "Enviado $fileName" else "Recibido $fileName"
                startForeground(NOTIF_ID, buildCompletedNotification(text))
                // Vuelve a idle después de 3s
                scope.launch {
                    delay(3000)
                    startForeground(NOTIF_ID, buildIdleNotification(currentDeviceName))
                }
            }
            ACTION_FAILED -> {
                val fileName = intent.getStringExtra(EXTRA_FILE_NAME) ?: "archivo"
                startForeground(NOTIF_ID, buildFailedNotification("Error: $fileName"))
                scope.launch {
                    delay(3000)
                    startForeground(NOTIF_ID, buildIdleNotification(currentDeviceName))
                }
            }
            ACTION_TEXT_RECEIVED -> {
                val senderName = intent.getStringExtra(EXTRA_SENDER_NAME) ?: "desconocido"
                val text = intent.getStringExtra(EXTRA_TEXT) ?: ""
                showMessageNotification(senderName, text)
            }
            else -> {
                // Arranque inicial sin acción: notificación idle
                if (action == null) {
                    startForeground(NOTIF_ID, buildIdleNotification(currentDeviceName))
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        // Parada limpia del server: evita puerto zombie.
        scope.launch(Dispatchers.IO) {
            runCatching { GlobalContext.getOrNull()?.get<FileReceiver>()?.stop() }
            runCatching { GlobalContext.getOrNull()?.get<DeviceDiscovery>()?.stop() }
        }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Transferencias Ignite",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Progreso de envío/recepción de archivos en segundo plano"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun createMessageChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_MESSAGES,
                "Mensajes Ignite",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Mensajes de texto recibidos de otros dispositivos"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 200, 100, 200)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildIdleNotification(deviceName: String): Notification {
        val pendingIntent = pendingMainIntent()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Ignite activo")
            .setContentText("Visible como «$deviceName» en la red local")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun buildProgressNotification(title: String, progress: Float, isReceiving: Boolean): Notification {
        val pct = (progress.coerceIn(0f, 1f) * 100).toInt()
        val pendingIntent = pendingMainIntent()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText("$pct%")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, pct, false)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun buildCompletedNotification(text: String): Notification {
        val pendingIntent = pendingMainIntent()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(text)
            .setContentText("Completado")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(false)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun buildFailedNotification(text: String): Notification {
        val pendingIntent = pendingMainIntent()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(text)
            .setContentText("Falló la transferencia")
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setOngoing(false)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun showMessageNotification(senderName: String, text: String) {
        val snippet = if (text.length > 200) text.take(200) + "…" else text
        val pendingIntent = pendingMainIntent()
        val notification = NotificationCompat.Builder(this, CHANNEL_MESSAGES)
            .setContentTitle("Mensaje de $senderName")
            .setContentText(snippet)
            .setStyle(NotificationCompat.BigTextStyle().bigText(snippet))
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setContentIntent(pendingIntent)
            .build()
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID_MSG, notification)
    }

    private suspend fun ensureInfrastructure() {
        val koin = GlobalContext.getOrNull() ?: return
        val receiver = koin.get<FileReceiver>()
        val discovery = koin.get<DeviceDiscovery>()
        receiver.start()
        discovery.start()
        currentDeviceName = runCatching { koin.get<DeviceInfo>().deviceName }.getOrDefault(currentDeviceName)
        // Refresca la notificación idle con el nombre real una vez que Koin está listo
        runCatching {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIF_ID, buildIdleNotification(currentDeviceName))
        }
    }

    private fun pendingMainIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val CHANNEL_ID = "ignite_transfer"
        const val CHANNEL_MESSAGES = "ignite_messages"
        const val NOTIF_ID = 1001
        const val NOTIF_ID_MSG = 1002

        const val ACTION_IDLE = "com.andyl.ignite.action.IDLE"
        const val ACTION_RECEIVING = "com.andyl.ignite.action.RECEIVING"
        const val ACTION_SENDING = "com.andyl.ignite.action.SENDING"
        const val ACTION_COMPLETED = "com.andyl.ignite.action.COMPLETED"
        const val ACTION_FAILED = "com.andyl.ignite.action.FAILED"
        const val ACTION_TEXT_RECEIVED = "com.andyl.ignite.action.TEXT_RECEIVED"
        const val ACTION_STOP = "com.andyl.ignite.action.STOP"

        const val EXTRA_FILE_NAME = "fileName"
        const val EXTRA_PROGRESS = "progress"
        const val EXTRA_TOTAL = "total"
        const val EXTRA_DEVICE_NAME = "deviceName"
        const val EXTRA_IS_SENDING = "isSending"
        const val EXTRA_SENDER_NAME = "senderName"
        const val EXTRA_TEXT = "text"

        fun start(context: android.content.Context, deviceName: String? = null) {
            val intent = Intent(context, TransferForegroundService::class.java).apply {
                action = if (deviceName != null) ACTION_IDLE else null
                deviceName?.let { putExtra(EXTRA_DEVICE_NAME, it) }
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {}
        }

        fun stop(context: android.content.Context) {
            context.stopService(Intent(context, TransferForegroundService::class.java))
        }
    }
}
