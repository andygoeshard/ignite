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

fun main() {
    // Debe correr antes de que AWT cree la ventana: define nombre en Dock y menu bar.
    System.setProperty("apple.awt.application.name", "Ignite")
    AppIcon.installTaskbarIcon()
    application {
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

        val windowState = androidx.compose.ui.window.rememberWindowState(width = 500.dp, height = 860.dp)
        // Permite resize y adapta el contenido via BoxWithConstraints en HomeScreen
        Window(
            onCloseRequest = ::exitApplication,
            title = "Ignite",
            state = windowState,
            icon = AppIcon.painter,
        ) {
            // En desktop el contenido ya es scrollable y responsive (verticalScroll + BoxWithConstraints >720dp = 2 columnas)
            App()
        }
    }
}
