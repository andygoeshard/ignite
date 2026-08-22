package com.andyl.ignite.presentation.format

/**
 * Shared presentation formatters. Single source of truth for byte sizes and
 * relative timestamps used across screens.
 */
internal fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    return "%.2f GB".format(mb / 1024.0)
}

internal fun formatRelativeTime(timestamp: Long, now: Long = System.currentTimeMillis()): String {
    val elapsed = (now - timestamp).coerceAtLeast(0L)
    val minutes = elapsed / 60_000
    val hours = minutes / 60
    val days = hours / 24
    return when {
        minutes < 1 -> "recién"
        minutes == 1L -> "hace 1 min"
        hours < 1 -> "hace $minutes min"
        hours == 1L -> "hace 1 hora"
        days < 1 -> "hace $hours horas"
        days == 1L -> "ayer"
        else -> "hace $days días"
    }
}
