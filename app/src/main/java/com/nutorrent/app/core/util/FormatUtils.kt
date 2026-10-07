package com.nutorrent.app.core.util

import java.text.DecimalFormat
import java.util.Locale

object FormatUtils {
    private val decimalFormat = DecimalFormat("#,##0.#")

    /**
     * Formats bytes into human-readable string (e.g., 512 B, 12.4 KB, 847.2 MB, 2.3 GB)
     * Using decimal (SI 1000) or binary (IEC 1024) standard. BitTorrent commonly uses binary or decimal.
     * We use standard binary units (1024) labeled cleanly as B, KB, MB, GB, TB.
     */
    fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
        val value = bytes / Math.pow(1024.0, digitGroups.toDouble())
        return "${decimalFormat.format(value)} ${units[digitGroups]}"
    }

    /**
     * Formats download/upload transfer speed in bytes per second (e.g. 12.4 MB/s)
     */
    fun formatSpeed(bytesPerSecond: Long): String {
        if (bytesPerSecond <= 0) return "0 B/s"
        return "${formatBytes(bytesPerSecond)}/s"
    }

    /**
     * Formats remaining time in human-readable format (e.g., 12m 41s, 2h 15m, 3d 4h)
     */
    fun formatDuration(seconds: Long?): String {
        if (seconds == null || seconds < 0 || seconds >= 365 * 24 * 3600L) {
            return "—"
        }
        if (seconds == 0L) return "0s"

        val s = seconds % 60
        val m = (seconds / 60) % 60
        val h = (seconds / 3600) % 24
        val d = seconds / 86400

        return when {
            d > 0 -> "${d}d ${h}h"
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m}m ${s}s"
            else -> "${s}s"
        }
    }

    /**
     * Formats percentage (e.g., "68.2%")
     */
    fun formatPercent(fraction: Float): String {
        val pct = (fraction * 100.0).coerceIn(0.0, 100.0)
        return String.format(Locale.US, "%.1f%%", pct)
    }

    /**
     * Formats peer counts: connected / total
     */
    fun formatPeers(connected: Int, total: Int): String {
        return if (total > 0) "$connected ($total)" else "$connected"
    }
}
