package com.andyl.ignite

import android.app.Application
import android.net.wifi.WifiManager
import com.andyl.ignite.data.db.AndroidContextHolder
import com.andyl.ignite.di.initKoin

class IgniteApplication : Application() {
    private var multicastLock: WifiManager.MulticastLock? = null

    override fun onCreate() {
        super.onCreate()
        AndroidContextHolder.context = applicationContext
        initKoin()
        acquireMulticastLock()
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
