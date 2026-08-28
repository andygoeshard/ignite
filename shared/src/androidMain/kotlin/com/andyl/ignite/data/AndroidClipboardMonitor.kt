package com.andyl.ignite.data

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.andyl.ignite.domain.ClipboardMonitor
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Android clipboard monitor using ClipboardManager.
 * Listener fires on clipboard changes; we track last value to avoid echoes.
 */
class AndroidClipboardMonitor(
    private val context: Context,
) : ClipboardMonitor {

    private val _changes = Channel<String>(Channel.BUFFERED)
    override val changes = _changes.receiveAsFlow()

    private var lastContent: String? = null
    private var listening = false

    private val listener = ClipboardManager.OnPrimaryClipChangedListener {
        val current = readText()
        if (current != null && current != lastContent) {
            lastContent = current
            _changes.trySend(current)
        }
    }

    override fun start() {
        if (listening) return
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        lastContent = readText()
        cm.addPrimaryClipChangedListener(listener)
        listening = true
    }

    override fun stop() {
        if (!listening) return
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.removePrimaryClipChangedListener(listener)
        listening = false
    }

    override fun setText(text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText("Ignite", text))
        lastContent = text
    }

    private fun readText(): String? = try {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
        cm.primaryClip?.getItemAt(0)?.text?.toString()
    } catch (_: Exception) {
        null
    }
}
