package com.andyl.ignite

import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.Taskbar
import javax.imageio.ImageIO

/** Icono de la app para ventana, Dock/Taskbar y bundles empaquetados. */
object AppIcon {

    private val awtImage by lazy {
        ImageIO.read(
            AppIcon::class.java.classLoader.getResourceAsStream("branding/icon-256.png")!!
        )
    }

    /** Painter listo para `Window(icon = ...)` de Compose Desktop. */
    val painter: BitmapPainter by lazy { BitmapPainter(awtImage.toComposeImageBitmap()) }

    /**
     * Fija el icono del Dock (macOS) / Taskbar (Windows) cuando la app corre
     * sin empaquetar (ej: ./gradlew :desktopApp:run).
     */
    fun installTaskbarIcon() {
        runCatching {
            if (Taskbar.isTaskbarSupported()) {
                Taskbar.getTaskbar().setIconImage(awtImage)
            }
        }
    }
}
