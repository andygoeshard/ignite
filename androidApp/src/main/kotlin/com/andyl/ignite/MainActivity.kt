package com.andyl.ignite

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.andyl.ignite.data.db.AndroidContextHolder
import com.andyl.ignite.di.initKoin

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        AndroidContextHolder.context = applicationContext
        initKoin()

        acquireMulticastLock()

        setContent {
            App()
        }
    }

    private fun acquireMulticastLock() {
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("ignite-discovery").apply {
            setReferenceCounted(true)
        }
        lock.acquire()
    }
}
