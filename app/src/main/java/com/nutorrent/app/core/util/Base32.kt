package com.nutorrent.app.core.util

import java.io.ByteArrayOutputStream

/**
 * Standard RFC 4648 Base32 decoding and encoding for BitTorrent magnet info hashes.
 */
object Base32 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    private val DECODE_TABLE = IntArray(128) { -1 }

    init {
        for (i in ALPHABET.indices) {
            val c = ALPHABET[i]
            DECODE_TABLE[c.code] = i
            // Allow lowercase letters as well
            if (c in 'A'..'Z') {
                DECODE_TABLE[c.lowercaseChar().code] = i
            }
        }
    }

    fun decode(encoded: String): ByteArray {
        val clean = encoded.trim().replace("=", "")
        var buffer = 0
        var bitsLeft = 0
        val out = ByteArrayOutputStream()

        for (c in clean) {
            if (c.code >= 128 || DECODE_TABLE[c.code] == -1) {
                throw IllegalArgumentException("Illegal character in Base32 string: $c")
            }
            val value = DECODE_TABLE[c.code]
            buffer = (buffer shl 5) or value
            bitsLeft += 5

            if (bitsLeft >= 8) {
                bitsLeft -= 8
                out.write((buffer shr bitsLeft) and 0xFF)
            }
        }

        return out.toByteArray()
    }

    fun encode(data: ByteArray): String {
        val sb = StringBuilder()
        var buffer = 0
        var bitsLeft = 0

        for (b in data) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bitsLeft += 8
            while (bitsLeft >= 5) {
                bitsLeft -= 5
                val index = (buffer shr bitsLeft) and 0x1F
                sb.append(ALPHABET[index])
            }
        }

        if (bitsLeft > 0) {
            val index = (buffer shl (5 - bitsLeft)) and 0x1F
            sb.append(ALPHABET[index])
        }

        return sb.toString()
    }
}
