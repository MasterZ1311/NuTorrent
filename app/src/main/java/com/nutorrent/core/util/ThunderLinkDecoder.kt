package com.nutorrent.core.util

import java.util.Base64

/**
 * Proprietary link decoder adapted from Motrix (`src/shared/utils/index.js:decodeThunderLink`).
 * Decodes Thunder / Xunlei links (`thunder://...`) into standard protocols
 * (magnet:, https://, http://, ftp://, ed2k://).
 *
 * Thunder links encode the target URI in Base64 wrapped between "AA" and "ZZ":
 *   Base64("AA" + actual_url + "ZZ")
 */
object ThunderLinkDecoder {

    private const val THUNDER_PREFIX = "thunder://"

    /**
     * Checks whether the provided URL string is a Thunder-encoded link.
     */
    fun isThunderLink(url: String): Boolean {
        return url.trim().startsWith(THUNDER_PREFIX, ignoreCase = true)
    }

    /**
     * Decodes a Thunder URL to its original raw URL.
     * If the URL is not a Thunder link, or if decoding fails, returns the original trimmed string.
     */
    fun decode(url: String): String {
        val trimmed = url.trim()
        if (!isThunderLink(trimmed)) return trimmed

        return try {
            val base64Payload = trimmed.substring(THUNDER_PREFIX.length).trim()
            val decodedBytes = Base64.getDecoder().decode(base64Payload)
            val raw = String(decodedBytes, Charsets.UTF_8).trim()

            if (raw.startsWith("AA") && raw.endsWith("ZZ") && raw.length >= 4) {
                raw.substring(2, raw.length - 2).trim()
            } else {
                raw
            }
        } catch (e: Exception) {
            Lg.w(LogCategory.NETWORK, "Failed to decode Thunder link: ${e.message}")
            trimmed
        }
    }
}
