package com.andyl.ignite

import android.app.Application
import android.net.wifi.WifiManager
import com.andyl.ignite.data.db.AndroidContextHolder
import com.andyl.ignite.di.initKoin
import com.andyl.ignite.domain.ReceiverController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

class IgniteApplication : Application() {
    private var multicastLock: WifiManager.MulticastLock? = null
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        // Evita crash por BindException async de Ktor CIO cuando el puerto está en TIME_WAIT
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val isBind = throwable is java.net.BindException ||
                throwable.cause is java.net.BindException ||
                throwable.message?.contains("Address already in use") == true ||
                throwable.cause?.message?.contains("Address already in use") == true
            if (isBind) {
                android.util.Log.w("Ignite", "BindException ignorado (puerto en TIME_WAIT, se reintentará): $throwable")
            } else {
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
        AndroidContextHolder.context = applicationContext
        initKoin()
        acquireMulticastLock()
        // Fase 2a: pausado = sin notificación en la barra. El ⏻ de la UI (o el
        // tray en desktop) mueve ReceiverController; acá reflejamos el estado
        // con el foreground service.
        observeReceiverPower()
    }

    private fun observeReceiverPower() {
        appScope.launch {
            val controller = runCatching { GlobalContext.get().get<ReceiverController>() }.getOrNull()
                ?: return@launch
            controller.active.collect { active ->
                if (active) {
                    TransferForegroundService.start(this@IgniteApplication)
                } else {
                    TransferForegroundService.stop(this@IgniteApplication)
                }
            }
        }
    }

    private fun acquireMulticastLock() {
        runCatching {
            val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("ignite-discovery").apply {
                setReferenceCounted(true)
                acquire()
            }
        }
    }
}
