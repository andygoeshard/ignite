package com.andyl.ignite.domain

import kotlinx.coroutines.flow.Flow

/**
 * Monitors the local system clipboard for text changes.
 * Emits new text content whenever the clipboard is updated.
 */
interface ClipboardMonitor {
    /** Stream of clipboard text changes from the local device. */
    val changes: Flow<String>

    /** Start monitoring. Idempotent. */
    fun start()

    /** Stop monitoring. Idempotent. */
    fun stop()

    /** Write text to the local system clipboard. */
    fun setText(text: String)
}
