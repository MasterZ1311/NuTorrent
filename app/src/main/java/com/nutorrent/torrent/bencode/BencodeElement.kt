package com.nutorrent.torrent.bencode

import java.nio.charset.StandardCharsets
import java.util.Arrays

/**
 * Represents a Bencoded data structure as defined by the BitTorrent specification.
 * Supports byte strings, integers, lists, and dictionaries.
 */
sealed class BencodeElement {

    data class BInteger(val value: Long) : BencodeElement()

    class BString(val value: ByteArray) : BencodeElement() {
        val asUtf8String: String by lazy {
            try {
                String(value, StandardCharsets.UTF_8)
            } catch (e: Exception) {
                ""
            }
        }

        fun asStringOrNull(): String? {
            return try {
                val decoder = StandardCharsets.UTF_8.newDecoder()
                decoder.decode(java.nio.ByteBuffer.wrap(value)).toString()
            } catch (e: Exception) {
                null
            }
        }

        fun asHexString(): String {
            return value.joinToString("") { "%02x".format(it) }
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is BString) return false
            return Arrays.equals(value, other.value)
        }

        override fun hashCode(): Int {
            return Arrays.hashCode(value)
        }

        override fun toString(): String {
            val text = asStringOrNull()
            return if (text != null && text.all { it.code in 32..126 }) {
                "\"$text\""
            } else {
                "[bytes: ${value.size}]"
            }
        }
    }

    data class BList(val value: List<BencodeElement>) : BencodeElement()

    data class BDictionary(
        val value: Map<String, BencodeElement>,
        val rawEntries: List<Pair<BString, BencodeElement>> = emptyList()
    ) : BencodeElement() {
        operator fun get(key: String): BencodeElement? = value[key]
        fun getInteger(key: String): Long? = (value[key] as? BInteger)?.value
        fun getString(key: String): String? = (value[key] as? BString)?.asUtf8String
        fun getByteArray(key: String): ByteArray? = (value[key] as? BString)?.value
        fun getList(key: String): List<BencodeElement>? = (value[key] as? BList)?.value
        fun getDict(key: String): BDictionary? = value[key] as? BDictionary
    }
}
