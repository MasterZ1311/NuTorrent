package com.nutorrent.core.model

import java.util.Arrays

/**
 * Value-representing identifier for a torrent, uniquely defined by its 20-byte SHA-1 info hash.
 */
data class TorrentId(val bytes: ByteArray) {
    init {
        require(bytes.size == 20) { "TorrentId info hash must be exactly 20 bytes, got ${bytes.size}" }
    }

    val hex: String by lazy {
        bytes.joinToString("") { "%02x".format(it) }
    }

    fun toUrlEncoded(): String {
        val sb = StringBuilder(bytes.size * 3)
        for (b in bytes) {
            val c = b.toInt().toChar()
            if ((c in 'a'..'z') || (c in 'A'..'Z') || (c in '0'..'9') || c == '-' || c == '_' || c == '.' || c == '~') {
                sb.append(c)
            } else {
                sb.append("%%%02X".format(b))
            }
        }
        return sb.toString()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TorrentId) return false
        return Arrays.equals(bytes, other.bytes)
    }

    override fun hashCode(): Int {
        return Arrays.hashCode(bytes)
    }

    override fun toString(): String = hex

    companion object {
        fun fromHex(hex: String): TorrentId {
            require(hex.length == 40) { "Hex representation must be exactly 40 characters" }
            val bytes = ByteArray(20)
            for (i in 0 until 20) {
                bytes[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
            return TorrentId(bytes)
        }

        fun fromBase32(base32Str: String): TorrentId {
            val bytes = com.nutorrent.core.util.Base32.decode(base32Str)
            require(bytes.size == 20) { "Base32 info hash must decode to exactly 20 bytes, got ${bytes.size}" }
            return TorrentId(bytes)
        }

        fun parse(str: String): TorrentId {
            val clean = str.trim()
            return when {
                clean.length == 40 -> fromHex(clean)
                clean.length == 32 -> fromBase32(clean)
                else -> throw IllegalArgumentException("Info hash must be 40-character hex or 32-character Base32 string (got length ${clean.length})")
            }
        }
    }
}
