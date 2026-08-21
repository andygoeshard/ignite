package com.andyl.ignite.presentation.home

import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.ExperimentalComposeUiApi
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DropTargetDropEvent
import java.io.File

/**
 * Desktop implementation of the OS file drop area, backed by Compose Desktop's
 * [dragAndDropTarget] which surfaces AWT drag & drop events.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
actual fun DroppableArea(onFiles: (List<PendingFile>) -> Unit, content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier.dragAndDropTarget(
            shouldStartDragAndDrop = { true },
            target = object : DragAndDropTarget {
                override fun onDrop(event: DragAndDropEvent): Boolean {
                    val drop = event.nativeEvent as? DropTargetDropEvent ?: return false
                    val files = (runCatching {
                        drop.transferable.getTransferData(DataFlavor.javaFileListFlavor)
                    }.getOrNull() as? List<*>)?.filterIsInstance<File>() ?: emptyList()
                    if (files.isNotEmpty()) {
                        onFiles(files.map { PendingFile(it.absolutePath, it.name, it.length()) })
                        return true
                    }
                    return false
                }
            },
        ),
    ) {
        content()
    }
}