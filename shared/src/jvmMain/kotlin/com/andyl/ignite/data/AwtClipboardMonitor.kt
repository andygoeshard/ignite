package com.andyl.ignite.data

import com.andyl.ignite.domain.ClipboardMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.awt.Toolkit
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.ClipboardOwner
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable

/**
 * Desktop clipboard monitor using java.awt.
 * Polls for changes since AWT FlavorListener is unreliable across platforms.
 */
class AwtClipboardMonitor : ClipboardMonitor {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _changes = Channel<String>(Channel.BUFFERED)
    override val changes = _changes.receiveAsFlow()

    private val clipboard: Clipboard = Toolkit.getDefaultToolkit().systemClipboard
    private var lastContent: String? = null
    private var polling = false
    private var pollJob: kotlinx.coroutines.Job? = null

    override fun start() {
        if (polling) return
        polling = true
        pollJob = scope.launch {
            lastContent = readText()
            while (polling) {
                kotlinx.coroutines.delay(500)
                val current = readText()
                if (current != null && current != lastContent) {
                    lastContent = current
                    _changes.trySend(current)
                }
            }
        }
    }

    override fun stop() {
        polling = false
        pollJob?.cancel()
        pollJob = null
    }

    override fun setText(text: String) {
        clipboard.setContents(StringSelection(text), ClipboardOwner { _, _ -> })
        lastContent = text
    }

    private fun readText(): String? = try {
        val transferable = clipboard.getContents(null) ?: return null
        if (transferable.isDataFlavorSupported(DataFlavor.stringFlavor)) {
            transferable.getTransferData(DataFlavor.stringFlavor) as? String
        } else null
    } catch (_: Exception) {
        null
    }

    private class StringSelection(private val text: String) : Transferable, ClipboardOwner {
        override fun getTransferData(flavor: DataFlavor?): Any = text
        override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.stringFlavor)
        override fun isDataFlavorSupported(flavor: DataFlavor?): Boolean = flavor == DataFlavor.stringFlavor
        override fun lostOwnership(clipboard: Clipboard?, contents: Transferable?) {}
    }
}
