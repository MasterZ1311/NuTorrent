package com.nutorrent.torrent.metainfo

import com.nutorrent.core.model.AppError
import com.nutorrent.core.model.TorrentId
import com.nutorrent.core.util.UniversalLinkDecoder
import java.net.URLDecoder

data class MagnetUri(
    val torrentId: TorrentId,
    val displayName: String?,
    val trackers: List<String>,
    val exactLength: Long? = null,
    val rawUri: String,
    val webSeeds: List<String> = emptyList(),
    val exactSources: List<String> = emptyList(),
    val peerHints: List<String> = emptyList(),
    val selectedIndices: List<Int> = emptyList(),
    val keywords: List<String> = emptyList()
) {
    companion object {
        private const val BTIH_PREFIX = "urn:btih:"
        private const val BTMH_PREFIX = "urn:btmh:"

        /**
         * Parses any type of magnet URI, decoded proprietary link, or bare info hash.
         * Supports:
         * - Standard BitTorrent v1 magnets (`xt=urn:btih:<40-hex>` or `<32-base32>`)
         * - BitTorrent v2 magnets (`xt=urn:btmh:1220<64-hex>`) & hybrid v1/v2 magnets
         * - Multi-xt parameters (e.g. ed2k, sha1, btih)
         * - Alternative schemes: missing '?', Thunder links, FlashGet, QQDL, BitComet
         * - URL-encoded or double URL-encoded magnet links
         * - HTML entities (`&amp;`, `&#38;`)
         * - Web seeds (`ws=`, `as=`), exact sources (`xs=`), peer hints (`x.pe=`), selected files (`so=`)
         */
        fun parse(uriString: String): MagnetUri {
            val decodedUri = UniversalLinkDecoder.decode(uriString)
            val trimmed = decodedUri.trim().replace("\r", "").replace("\n", "")

            if (!trimmed.startsWith("magnet:", ignoreCase = true)) {
                throw AppError.InvalidTorrent("Not a valid magnet URI (must start with 'magnet:')")
            }

            val queryString = when {
                trimmed.startsWith("magnet:?", ignoreCase = true) -> trimmed.substring(8)
                trimmed.startsWith("magnet:", ignoreCase = true) -> trimmed.substring(7)
                else -> throw AppError.InvalidTorrent("Not a valid magnet URI")
            }

            if (queryString.isEmpty()) {
                throw AppError.InvalidTorrent("Empty magnet query string")
            }

            val cleanQuery = queryString
                .replace("&amp;", "&")
                .replace("&#38;", "&")

            var torrentId: TorrentId? = null
            var displayName: String? = null
            val trackers = mutableListOf<String>()
            var exactLength: Long? = null
            val webSeeds = mutableListOf<String>()
            val exactSources = mutableListOf<String>()
            val peerHints = mutableListOf<String>()
            val selectedIndices = mutableListOf<Int>()
            val keywords = mutableListOf<String>()

            val pairs = cleanQuery.split('&')
            for (pair in pairs) {
                if (pair.isEmpty()) continue
                val idx = pair.indexOf('=')
                val key = if (idx > 0) pair.substring(0, idx).trim().lowercase() else pair.trim().lowercase()
                val rawValue = if (idx > 0 && idx < pair.length - 1) pair.substring(idx + 1).trim() else ""
                val value = try {
                    URLDecoder.decode(rawValue.replace("+", " "), "UTF-8")
                } catch (_: Exception) {
                    rawValue
                }

                when (key) {
                    "xt" -> {
                        val lowerVal = value.trim()
                        val btihIdx = lowerVal.indexOf(BTIH_PREFIX, ignoreCase = true)
                        if (btihIdx >= 0) {
                            val hashStr = lowerVal.substring(btihIdx + BTIH_PREFIX.length)
                                .substringBefore('&')
                                .substringBefore(';')
                                .trim()
                            try {
                                torrentId = TorrentId.parse(hashStr)
                            } catch (e: Exception) {
                                // If already parsed from another xt parameter, keep it
                                if (torrentId == null) {
                                    throw AppError.InvalidTorrent("Invalid info hash in magnet link: $hashStr (${e.message})")
                                }
                            }
                        } else if (torrentId == null && lowerVal.contains(BTMH_PREFIX, ignoreCase = true)) {
                            // BitTorrent v2 multihash (BEP 52)
                            val btmhStr = lowerVal.substringAfter(BTMH_PREFIX, "")
                                .substringBefore('&')
                                .substringBefore(';')
                                .trim()
                            // multihash 1220 = 0x12 (sha2-256), 0x20 (32 bytes)
                            if (btmhStr.startsWith("1220", ignoreCase = true) && btmhStr.length >= 44) {
                                val sha256Hex = btmhStr.substring(4)
                                val v1Truncated = sha256Hex.take(40)
                                try {
                                    torrentId = TorrentId.fromHex(v1Truncated)
                                } catch (_: Exception) {}
                            } else if (btmhStr.length >= 40) {
                                try {
                                    torrentId = TorrentId.parse(btmhStr.take(40))
                                } catch (_: Exception) {}
                            }
                        } else if (torrentId == null && lowerVal.contains("urn:sha1:", ignoreCase = true)) {
                            val sha1Str = lowerVal.substringAfter("urn:sha1:", "")
                                .substringBefore('&')
                                .substringBefore(';')
                                .trim()
                            try {
                                torrentId = TorrentId.parse(sha1Str)
                            } catch (_: Exception) {}
                        } else if (torrentId == null && (lowerVal.length == 40 || lowerVal.length == 32)) {
                            try {
                                torrentId = TorrentId.parse(lowerVal)
                            } catch (_: Exception) {}
                        }
                    }
                    "dn" -> {
                        if (displayName == null) {
                            displayName = value.trim().removeSurrounding("\"").removeSurrounding("'")
                        }
                    }
                    "tr" -> {
                        val trUrls = value.split(',', '|', '\n', '\r').map { it.trim() }
                        for (trUrl in trUrls) {
                            if (trUrl.isNotEmpty() && !trackers.contains(trUrl)) {
                                trackers.add(trUrl)
                            }
                        }
                    }
                    "xl" -> {
                        exactLength = value.trim().toLongOrNull()
                    }
                    "ws", "as" -> {
                        val wsUrls = value.split(',', '|').map { it.trim() }
                        for (ws in wsUrls) {
                            if (ws.isNotEmpty() && !webSeeds.contains(ws)) {
                                webSeeds.add(ws)
                            }
                        }
                    }
                    "xs" -> {
                        val xsUrls = value.split(',', '|').map { it.trim() }
                        for (xs in xsUrls) {
                            if (xs.isNotEmpty() && !exactSources.contains(xs)) {
                                exactSources.add(xs)
                            }
                        }
                    }
                    "x.pe", "pe" -> {
                        val peers = value.split(',', ';').map { it.trim() }
                        for (p in peers) {
                            if (p.isNotEmpty() && !peerHints.contains(p)) {
                                peerHints.add(p)
                            }
                        }
                    }
                    "so" -> {
                        val tokens = value.split(',', ';')
                        for (token in tokens) {
                            val t = token.trim()
                            if (t.contains('-')) {
                                val range = t.split('-')
                                val start = range.getOrNull(0)?.trim()?.toIntOrNull()
                                val end = range.getOrNull(1)?.trim()?.toIntOrNull()
                                if (start != null && end != null && start <= end) {
                                    for (i in start..end) selectedIndices.add(i)
                                }
                            } else {
                                t.toIntOrNull()?.let { selectedIndices.add(it) }
                            }
                        }
                    }
                    "kt" -> {
                        val kts = value.split('+', ' ', ',')
                        for (k in kts) {
                            val clean = k.trim()
                            if (clean.isNotEmpty() && !keywords.contains(clean)) {
                                keywords.add(clean)
                            }
                        }
                    }
                }
            }

            if (torrentId == null) {
                throw AppError.InvalidTorrent("Magnet URI missing valid 'xt=urn:btih:' or info hash parameter")
            }

            return MagnetUri(
                torrentId = torrentId,
                displayName = displayName,
                trackers = trackers,
                exactLength = exactLength,
                rawUri = trimmed,
                webSeeds = webSeeds,
                exactSources = exactSources,
                peerHints = peerHints,
                selectedIndices = selectedIndices,
                keywords = keywords
            )
        }
    }
}
