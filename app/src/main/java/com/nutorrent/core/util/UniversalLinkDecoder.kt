package com.nutorrent.core.util

import java.net.URLDecoder
import java.util.Base64

/**
 * Universal link decoder that decodes any proprietary or non-standard download link
 * into a clean standard BitTorrent magnet URI or URL:
 * - Thunder / Xunlei (`thunder://...`)
 * - FlashGet (`flashget://...`)
 * - QQDownload (`qqdl://...`)
 * - BitComet (`bc://bt/...`)
 * - URL-encoded or double URL-encoded magnets (`magnet%3A%3F...`)
 * - Embedded magnets in messy forum text, markdown links `[Title](magnet:?...)`, and HTML tags `<a href="magnet:?...">`
 * - Bare info hashes (40-hex, 40-hex with 0x, 32-base32, 64-hex BitTorrent v2)
 */
object UniversalLinkDecoder {

    private val HEX_40_REGEX = Regex("""^[0-9a-fA-F]{40}$""")
    private val HEX_40_WITH_0X_REGEX = Regex("""^0x[0-9a-fA-F]{40}$""", RegexOption.IGNORE_CASE)
    private val BASE32_REGEX = Regex("""^[2-7a-zA-Z]{32}={0,6}$""")
    private val HEX_64_REGEX = Regex("""^[0-9a-fA-F]{64}$""")

    /**
     * Decodes any proprietary download link or extracts an embedded magnet link from messy text.
     */
    fun decode(input: String): String {
        var str = input.trim()
        if (str.isEmpty()) return str

        // 1. Recursive URL-decode if input is URL-encoded
        str = unescapeUrlEncoding(str)

        // 2. Decode Thunder links
        if (ThunderLinkDecoder.isThunderLink(str)) {
            str = ThunderLinkDecoder.decode(str)
        }

        // 3. Decode FlashGet links: flashget://[Base64([FLASHGET]url[FLASHGET])]
        if (str.startsWith("flashget://", ignoreCase = true)) {
            str = decodeFlashGet(str)
        }

        // 4. Decode QQDownload links: qqdl://[Base64(url)]
        if (str.startsWith("qqdl://", ignoreCase = true)) {
            str = decodeQqdl(str)
        }

        // 5. Decode BitComet links: bc://bt/...
        if (str.startsWith("bc://bt/", ignoreCase = true)) {
            str = decodeBitComet(str)
        }

        // 6. Extract magnet link from HTML/Markdown or surrounding text
        str = extractMagnetFromText(str)

        // 7. Check if the string is a bare info hash (hex or base32)
        val bareMagnet = normalizeBareInfoHash(str)
        if (bareMagnet != null) {
            return bareMagnet
        }

        return str
    }

    private fun unescapeUrlEncoding(input: String): String {
        var str = input
        for (i in 0..2) {
            if (str.startsWith("magnet%3A", ignoreCase = true) ||
                str.startsWith("thunder%3A", ignoreCase = true) ||
                str.contains("%3Fxt%3D", ignoreCase = true) ||
                str.contains("%26tr%3D", ignoreCase = true) ||
                str.contains("%26dn%3D", ignoreCase = true)
            ) {
                str = try {
                    URLDecoder.decode(str, "UTF-8")
                } catch (_: Exception) {
                    str
                }
            } else {
                break
            }
        }
        return str
    }

    private fun decodeFlashGet(url: String): String {
        return try {
            val payload = url.substring("flashget://".length).substringBefore('&').trim()
            val decoded = String(Base64.getDecoder().decode(payload), Charsets.UTF_8).trim()
            if (decoded.startsWith("[FLASHGET]") && decoded.endsWith("[FLASHGET]") && decoded.length >= 20) {
                decoded.substring(10, decoded.length - 10).trim()
            } else {
                decoded
            }
        } catch (_: Exception) {
            url
        }
    }

    private fun decodeQqdl(url: String): String {
        return try {
            val payload = url.substring("qqdl://".length).substringBefore('&').trim()
            String(Base64.getDecoder().decode(payload), Charsets.UTF_8).trim()
        } catch (_: Exception) {
            url
        }
    }

    private fun decodeBitComet(url: String): String {
        return try {
            val payload = url.substring("bc://bt/".length).trim()
            val decoded = try {
                String(Base64.getDecoder().decode(payload), Charsets.UTF_8).trim()
            } catch (_: Exception) {
                payload
            }

            when {
                decoded.startsWith("magnet:", ignoreCase = true) -> decoded
                HEX_40_REGEX.matches(decoded) -> "magnet:?xt=urn:btih:$decoded"
                else -> {
                    // Check if payload contains an info hash in path format: bc://bt/Name/Hash
                    val parts = payload.split('/')
                    val hashPart = parts.lastOrNull()?.trim() ?: ""
                    if (HEX_40_REGEX.matches(hashPart)) {
                        "magnet:?xt=urn:btih:$hashPart"
                    } else {
                        url
                    }
                }
            }
        } catch (_: Exception) {
            url
        }
    }

    /**
     * Extracts a magnet link embedded in markdown (`[text](magnet:?...)`),
     * HTML tags (`<a href="magnet:?...">`), or plain text with extra surrounding words.
     */
    fun extractMagnetFromText(text: String): String {
        val trimmed = text.trim()

        // Markdown format: [...](magnet:?...) or [...](thunder://...)
        val mdMatch = Regex("""\[.*?\]\(((?:magnet:|thunder:|flashget:|qqdl:)[^\)]+)\)""", RegexOption.IGNORE_CASE).find(trimmed)
        if (mdMatch != null) {
            return mdMatch.groupValues[1].trim()
        }

        // HTML href attribute: href="magnet:?..." or href='magnet:?...'
        val htmlMatch = Regex("""href=["']((?:magnet:|thunder:|flashget:|qqdl:)[^"']+)["']""", RegexOption.IGNORE_CASE).find(trimmed)
        if (htmlMatch != null) {
            return htmlMatch.groupValues[1].trim()
        }

        // Embedded magnet anywhere in text: magnet:?[^\s"'>]+
        val embeddedMatch = Regex("""(magnet:\?[^\s"'>]+)""", RegexOption.IGNORE_CASE).find(trimmed)
        if (embeddedMatch != null) {
            return embeddedMatch.groupValues[1].trim()
        }

        // Embedded magnet without question mark: magnet:xt=[^\s"'>]+
        val noQMatch = Regex("""(magnet:xt=[^\s"'>]+)""", RegexOption.IGNORE_CASE).find(trimmed)
        if (noQMatch != null) {
            return noQMatch.groupValues[1].trim()
        }

        return trimmed
    }

    /**
     * If input is a bare info hash without the `magnet:?xt=` wrapper,
     * normalizes it into a valid magnet link.
     */
    fun normalizeBareInfoHash(input: String): String? {
        val clean = input.trim().replace("-", "").replace(" ", "")

        // 40-character hex with optional 0x
        if (HEX_40_WITH_0X_REGEX.matches(clean)) {
            val hex = clean.substring(2)
            return "magnet:?xt=urn:btih:$hex"
        }
        if (HEX_40_REGEX.matches(clean)) {
            return "magnet:?xt=urn:btih:$clean"
        }

        // 64-character hex (BitTorrent v2 SHA-256) -> take first 40 hex chars for v1 compatibility + v2 xt
        if (HEX_64_REGEX.matches(clean)) {
            val v1Truncated = clean.substring(0, 40)
            return "magnet:?xt=urn:btih:$v1Truncated&xt=urn:btmh:1220$clean"
        }

        // 32-character base32 (with optional padding)
        val withoutPadding = clean.replace("=", "")
        if (withoutPadding.length == 32 && withoutPadding.all { it in "234567abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ" }) {
            return "magnet:?xt=urn:btih:$withoutPadding"
        }

        return null
    }
}
