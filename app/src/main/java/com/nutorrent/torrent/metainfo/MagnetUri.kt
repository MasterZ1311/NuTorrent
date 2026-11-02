package com.nutorrent.torrent.metainfo

import com.nutorrent.core.model.AppError
import com.nutorrent.core.model.TorrentId
import java.net.URLDecoder

data class MagnetUri(
    val torrentId: TorrentId,
    val displayName: String?,
    val trackers: List<String>,
    val exactLength: Long? = null,
    val rawUri: String
) {
    companion object {
        private const val SCHEME = "magnet:?"
        private const val BTIH_PREFIX = "urn:btih:"

        fun parse(uriString: String): MagnetUri {
            val trimmed = uriString.trim()
            if (!trimmed.startsWith("magnet:?", ignoreCase = true)) {
                throw AppError.InvalidTorrent("Not a valid magnet URI (must start with 'magnet:?')")
            }

            val queryString = trimmed.substring(SCHEME.length)
            if (queryString.isEmpty()) {
                throw AppError.InvalidTorrent("Empty magnet query string")
            }

            var torrentId: TorrentId? = null
            var displayName: String? = null
            val trackers = mutableListOf<String>()
            var exactLength: Long? = null

            val pairs = queryString.split('&')
            for (pair in pairs) {
                if (pair.isEmpty()) continue
                val idx = pair.indexOf('=')
                val key = if (idx > 0) pair.substring(0, idx).lowercase() else pair.lowercase()
                val rawValue = if (idx > 0 && idx < pair.length - 1) pair.substring(idx + 1) else ""
                val value = try {
                    URLDecoder.decode(rawValue, "UTF-8")
                } catch (e: Exception) {
                    rawValue
                }

                when (key) {
                    "xt" -> {
                        // Exact Topic: should be urn:btih:<hash>
                        val lowerVal = value.trim()
                        val btihIdx = lowerVal.indexOf(BTIH_PREFIX, ignoreCase = true)
                        if (btihIdx >= 0) {
                            val hashStr = lowerVal.substring(btihIdx + BTIH_PREFIX.length).trim()
                            try {
                                torrentId = TorrentId.parse(hashStr)
                            } catch (e: Exception) {
                                throw AppError.InvalidTorrent("Invalid info hash in magnet link: $hashStr (${e.message})")
                            }
                        }
                    }
                    "dn" -> {
                        displayName = value.trim()
                    }
                    "tr" -> {
                        val trUrl = value.trim()
                        if (trUrl.isNotEmpty() && !trackers.contains(trUrl)) {
                            trackers.add(trUrl)
                        }
                    }
                    "xl" -> {
                        exactLength = value.trim().toLongOrNull()
                    }
                }
            }

            if (torrentId == null) {
                throw AppError.InvalidTorrent("Magnet URI missing valid 'xt=urn:btih:' info hash parameter")
            }

            return MagnetUri(
                torrentId = torrentId,
                displayName = displayName,
                trackers = trackers,
                exactLength = exactLength,
                rawUri = trimmed
            )
        }
    }
}
