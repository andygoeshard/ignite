package com.andyl.ignite.domain.model

/**
 * Item in the clipboard sync history.
 * Each entry remembers what was copied, from which device, and when.
 */
data class ClipboardItem(
    val content: String,
    val sourceName: String,
    val sourceHost: String,
    val timestamp: Long = System.currentTimeMillis(),
)
