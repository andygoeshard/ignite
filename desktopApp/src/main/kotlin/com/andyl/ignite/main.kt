package com.andyl.ignite

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.andyl.ignite.di.initKoin
import com.andyl.ignite.domain.DeviceDiscovery
import com.andyl.ignite.domain.FileReceiver
import io.github.vinceglb.filekit.FileKit
import kotlinx.coroutines.runBlocking
import org.koin.core.context.GlobalContext

fun main() = application {
    FileKit.init(appId = "com.andyl.ignite")
    initKoin()

    // Stop Ktor server and UDP discovery limpiamente en SIGTERM / cierre de ventana.
    Runtime.getRuntime().addShutdownHook(Thread {
        runBlocking {
            runCatching { GlobalContext.get().get<FileReceiver>().stop() }
            runCatching { GlobalContext.get().get<DeviceDiscovery>().stop() }
        }
        println("[Ignite] shutdown hook: server & discovery stopped")
    })

    Window(
        onCloseRequest = ::exitApplication,
        title = "Ignite",
        state = androidx.compose.ui.window.rememberWindowState(width = 420.dp, height = 760.dp),
    ) {
        App()
    }
}
