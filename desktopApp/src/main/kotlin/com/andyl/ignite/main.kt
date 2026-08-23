@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
)

package com.andyl.ignite

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.andyl.ignite.di.initKoin
import com.andyl.ignite.domain.DeviceDiscovery
import com.andyl.ignite.domain.FileReceiver
import io.github.vinceglb.filekit.FileKit
import kotlinx.coroutines.runBlocking
import org.koin.core.context.GlobalContext
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTargetDropEvent

fun main() {
    // Debe correr antes de que AWT cree la ventana: define nombre en Dock y menu bar.
    System.setProperty("apple.awt.application.name", "Ignite")
    AppIcon.installTaskbarIcon()
    application {
        FileKit.init(appId = "com.andyl.ignite")
        initKoin()

        // Stop Ktor server and UDP discovery limpiamente en SIGTERM / cierre del proceso.
        Runtime.getRuntime().addShutdownHook(Thread {
            runBlocking {
                runCatching { GlobalContext.get().get<FileReceiver>().stop() }
                runCatching { GlobalContext.get().get<DeviceDiscovery>().stop() }
            }
            println("[Ignite] shutdown hook: server & discovery stopped")
        })

        val windowState = rememberWindowState(width = 500.dp, height = 860.dp)
        // Fase 2a: cerrar la ventana NO sale — Ignite sigue recibiendo desde el tray.
        var visible by remember { mutableStateOf(true) }
        // Ventana chiquita siempre visible para soltar archivos y mandarlos.
        var dropVisible by remember { mutableStateOf(true) }

        Window(
            onCloseRequest = { visible = false },
            visible = visible,
            title = "Ignite",
            state = windowState,
            icon = AppIcon.painter,
        ) {
            App()
        }

        // Fase 2a — Drop zone (Fase 2a): soltar archivos los agrega a la cola
        // y los envía al dispositivo seleccionado, sin tocar la ventana principal.
        // Ventana DECORADA: se puede mover y minimizar en mac/windows.
        if (dropVisible) {
            Window(
                onCloseRequest = { dropVisible = false },
                title = "Ignite — soltá archivos acá",
                alwaysOnTop = true,
                resizable = false,
                state = rememberWindowState(width = 240.dp, height = 220.dp),
                icon = AppIcon.painter,
            ) {
                val dropTarget = remember {
                    object : DragAndDropTarget {
                        override fun onDrop(event: DragAndDropEvent): Boolean {
                            val native = event.nativeEvent as? DropTargetDropEvent ?: return false
                            return runCatching {
                                native.acceptDrop(DnDConstants.ACTION_COPY)
                                @Suppress("UNCHECKED_CAST")
                                val files = native.transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<java.io.File>
                                com.andyl.ignite.data.DropChannel.offer(files.orEmpty().map { it.absolutePath })
                                native.dropComplete(true)
                                true
                            }.getOrElse {
                                println("[Ignite] drop fallido: ${it.message}")
                                false
                            }
                        }
                    }
                }
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp)
                        .dragAndDropTarget(shouldStartDragAndDrop = { true }, target = dropTarget),
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        // El logo buenote de la app (mismo asset que Dock/Taskbar)
                        Image(
                            painter = AppIcon.painter,
                            contentDescription = "Logo de Ignite",
                            modifier = Modifier.size(84.dp),
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Soltá acá para enviar",
                            style = MaterialTheme.typography.labelLarge,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                }
            }
        }

        val controller = GlobalContext.get().get<com.andyl.ignite.domain.ReceiverController>()
        val receiving by controller.active.collectAsState()
        // Fase 2a: pausado = sin ícono en la barra. Se reactiva abriendo la
        // ventana (Dock) y tocando ⏻ otra vez.
        if (receiving) {
            Tray(
                icon = AppIcon.painter,
                tooltip = "Ignite",
                menu = {
                    Item("Abrir Ignite") { visible = true }
                    Item("Mostrar zona de arrastre") { dropVisible = true }
                    Separator()
                    Item("Salir") {
                        runBlocking {
                            runCatching { GlobalContext.get().get<FileReceiver>().stop() }
                            runCatching { GlobalContext.get().get<DeviceDiscovery>().stop() }
                        }
                        exitApplication()
                    }
                },
            )
        }
    }
}
