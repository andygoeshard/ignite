package com.andyl.ignite

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.andyl.ignite.data.db.AndroidContextHolder
import com.andyl.ignite.di.initKoin

class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ -> startTransferService() }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // IgniteApplication ya inicializó Koin; runCatching hace idempotente el segundo init.
        AndroidContextHolder.context = applicationContext
        initKoin()
        acquireMulticastLock()
        ensureNotificationPermissionAndStartService()

        setContent {
            App()
        }
    }

    override fun onDestroy() {
        // No detenemos el servicio aquí: debe sobrevivir si hay transferencias
        // activas. El sistema lo matará si hace falta. Si se quiere stop
        // explícito al salir, descomentar la línea de abajo.
        // TransferForegroundService.stop(this)
        super.onDestroy()
    }

    private fun ensureNotificationPermissionAndStartService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)) {
                PackageManager.PERMISSION_GRANTED -> startTransferService()
                else -> notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            startTransferService()
        }
    }

    private fun startTransferService() {
        // El nombre real se actualizará cuando HomeViewModel llame a notifier.onIdle()
        TransferForegroundService.start(this)
    }

    private fun acquireMulticastLock() {
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("ignite-discovery").apply {
            setReferenceCounted(true)
        }
        lock.acquire()
    }
}
