package com.nutorrent.core.util

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

    /**
     * Decodes a URL-encoded URI string into readable text.
     */
    fun decodeUriString(uriString: String): String {
        return try {
            android.net.Uri.decode(uriString) ?: uriString
        } catch (e: Exception) {
            uriString
        }
    }

    /**
     * Formats storage path/URI into a clean human-readable directory name.
     */
    fun formatStoragePath(path: String): String {
        val trimmed = path.trim()
        if (trimmed.startsWith("content://", ignoreCase = true)) {
            val decodedStr = try {
                java.net.URLDecoder.decode(trimmed, "UTF-8")
            } catch (_: Throwable) {
                trimmed
            }
            if (decodedStr.contains("primary:", ignoreCase = true)) {
                val afterPrimary = decodedStr.substringAfter("primary:").substringBefore('?').trim('/')
                val clean = if (afterPrimary.isNotBlank()) "Internal Storage / $afterPrimary" else "Internal Storage"
                return clean.replace(':', '/')
            }
            if (decodedStr.contains("/tree/", ignoreCase = true)) {
                val segment = decodedStr.substringAfterLast("/tree/").substringBefore('?')
                val sub = segment.substringAfterLast(':').substringAfterLast('/')
                if (sub.isNotBlank()) {
                    return "Storage: $sub"
                }
            }
            val uri = try { android.net.Uri.parse(trimmed) } catch (e: Throwable) { null }
            val docId = try {
                if (uri != null && android.provider.DocumentsContract.isTreeUri(uri)) {
                    android.provider.DocumentsContract.getTreeDocumentId(uri)
                } else uri?.lastPathSegment
            } catch (e: Throwable) {
                uri?.lastPathSegment
            }
            if (docId != null) {
                val decoded = decodeUriString(docId)
                val clean = decoded.replace("primary:", "Internal Storage / ").replace(':', '/')
                return clean
            }
            return "SAF: ${uri?.authority ?: "Folder"}"
        }
        if (trimmed.endsWith("/NuTorrent") || trimmed.endsWith("\\NuTorrent") || trimmed.contains("/NuTorrent/") || trimmed.contains("\\NuTorrent\\")) {
            if (trimmed.contains("/Android/data/com.nutorrent/files") || trimmed.contains("\\Android\\data\\com.nutorrent\\files")) {
                return "Internal Storage / NuTorrent (App Storage)"
            }
            if (trimmed.startsWith("/storage/emulated/0/")) {
                return trimmed.replace("/storage/emulated/0/", "Internal Storage / ")
            }
            if (trimmed.startsWith("/storage/emulated/0")) {
                return trimmed.replace("/storage/emulated/0", "Internal Storage / ")
            }
            return "Internal Storage / NuTorrent"
        }
        if (trimmed.contains("/Android/data/com.nutorrent/files") || trimmed.contains("\\Android\\data\\com.nutorrent\\files")) {
            return "Internal Storage / NuTorrent (App Storage)"
        }
        if (trimmed.startsWith("/storage/emulated/0/")) {
            return trimmed.replace("/storage/emulated/0/", "Internal Storage / ")
        }
        if (trimmed.startsWith("/storage/emulated/0")) {
            return trimmed.replace("/storage/emulated/0", "Internal Storage / ")
        }
        return trimmed
    }
}
