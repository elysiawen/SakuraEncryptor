package com.sakura.encryptor.ui.util

import java.util.Locale

/** Human-readable byte size, e.g. `12.4 MB`. */
fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "--"
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
    val gb = mb / 1024.0
    if (gb < 1024) return String.format(Locale.US, "%.2f GB", gb)
    return String.format(Locale.US, "%.2f TB", gb / 1024.0)
}

/** `mm:ss`, or `h:mm:ss` past an hour. */
fun formatDuration(millis: Long): String {
    if (millis <= 0) return "00:00"
    val totalSeconds = millis / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}

/** Percentage label used by progress rows. */
fun formatPercent(value: Float): String =
    String.format(Locale.US, "%d%%", (value.coerceIn(0f, 1f) * 100).toInt())
