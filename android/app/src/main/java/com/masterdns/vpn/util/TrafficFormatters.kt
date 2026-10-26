package com.masterdns.vpn.util

import java.util.Locale

object TrafficFormatters {
    fun formatSpeed(bps: Long): String {
        val safeBps = bps.coerceAtLeast(0L)
        val kb = 1024.0
        val mb = kb * 1024.0
        return when {
            safeBps >= mb -> String.format(Locale.US, "%.2f MB/s", safeBps / mb)
            safeBps >= kb -> String.format(Locale.US, "%.1f KB/s", safeBps / kb)
            else -> "$safeBps B/s"
        }
    }

    fun formatBytes(bytes: Long): String {
        val safeBytes = bytes.coerceAtLeast(0L)
        val kb = 1024.0
        val mb = kb * 1024.0
        val gb = mb * 1024.0
        return when {
            safeBytes >= gb -> String.format(Locale.US, "%.2f GB", safeBytes / gb)
            safeBytes >= mb -> String.format(Locale.US, "%.2f MB", safeBytes / mb)
            safeBytes >= kb -> String.format(Locale.US, "%.1f KB", safeBytes / kb)
            else -> "$safeBytes B"
        }
    }

    fun formatDuration(seconds: Long): String {
        val safeSeconds = seconds.coerceAtLeast(0L)
        val hours = safeSeconds / 3600L
        val minutes = (safeSeconds % 3600L) / 60L
        val secs = safeSeconds % 60L
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, secs)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, secs)
        }
    }
}
