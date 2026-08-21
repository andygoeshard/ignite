package com.andyl.ignite

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.andyl.ignite.di.initKoin
import io.github.vinceglb.filekit.FileKit

fun main() = application {
    FileKit.init(appId = "com.andyl.ignite")
    initKoin()

    Window(
        onCloseRequest = ::exitApplication,
        title = "Ignite",
        state = androidx.compose.ui.window.rememberWindowState(width = 420.dp, height = 760.dp),
    ) {
        App()
    }
}
