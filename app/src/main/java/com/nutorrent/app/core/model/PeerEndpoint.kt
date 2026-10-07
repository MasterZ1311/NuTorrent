package com.nutorrent.app.core.model

import java.net.InetAddress
import java.util.Arrays

data class PeerEndpoint(
    val ip: String,
    val port: Int
) {
    init {
        require(port in 1..65535) { "Invalid port: $port" }
    }

    val isIpv6: Boolean
        get() = ip.contains(":")

    override fun toString(): String = if (isIpv6) "[$ip]:$port" else "$ip:$port"

    companion object {
        fun fromCompactIpv4(bytes: ByteArray, offset: Int): PeerEndpoint {
            require(offset + 6 <= bytes.size) { "Compact peer must be at least 6 bytes" }
            val ip = "${bytes[offset].toInt() and 0xFF}.${bytes[offset + 1].toInt() and 0xFF}.${bytes[offset + 2].toInt() and 0xFF}.${bytes[offset + 3].toInt() and 0xFF}"
            val port = ((bytes[offset + 4].toInt() and 0xFF) shl 8) or (bytes[offset + 5].toInt() and 0xFF)
            return PeerEndpoint(ip, port)
        }

        fun fromCompactIpv6(bytes: ByteArray, offset: Int): PeerEndpoint {
            require(offset + 18 <= bytes.size) { "Compact IPv6 peer must be at least 18 bytes" }
            val rawIp = bytes.copyOfRange(offset, offset + 16)
            val ip = InetAddress.getByAddress(rawIp).hostAddress ?: "::"
            val port = ((bytes[offset + 16].toInt() and 0xFF) shl 8) or (bytes[offset + 17].toInt() and 0xFF)
            return PeerEndpoint(ip, port)
        }

        fun parse(str: String): PeerEndpoint? {
            val trimmed = str.trim()
            return try {
                if (trimmed.startsWith("[")) {
                    val closeIdx = trimmed.indexOf("]:")
                    if (closeIdx == -1) return null
                    val ip = trimmed.substring(1, closeIdx)
                    val port = trimmed.substring(closeIdx + 2).toInt()
                    PeerEndpoint(ip, port)
                } else {
                    val colonIdx = trimmed.lastIndexOf(":")
                    if (colonIdx == -1) return null
                    val ip = trimmed.substring(0, colonIdx)
                    val port = trimmed.substring(colonIdx + 1).toInt()
                    PeerEndpoint(ip, port)
                }
            } catch (e: Exception) {
                null
            }
        }
    }
}

class PeerBitfield(
    val size: Int,
    val bytes: ByteArray = ByteArray((size + 7) / 8)
) {
    fun hasPiece(index: Int): Boolean {
        if (index < 0 || index >= size) return false
        val byteIndex = index / 8
        val bitIndex = 7 - (index % 8)
        if (byteIndex >= bytes.size) return false
        return (bytes[byteIndex].toInt() and (1 shl bitIndex)) != 0
    }

    fun setPiece(index: Int, has: Boolean = true) {
        if (index < 0 || index >= size) return
        val byteIndex = index / 8
        val bitIndex = 7 - (index % 8)
        if (byteIndex >= bytes.size) return
        if (has) {
            bytes[byteIndex] = (bytes[byteIndex].toInt() or (1 shl bitIndex)).toByte()
        } else {
            bytes[byteIndex] = (bytes[byteIndex].toInt() and (1 shl bitIndex).inv()).toByte()
        }
    }

    fun setAll(has: Boolean) {
        val b = if (has) 0xFF.toByte() else 0x00.toByte()
        bytes.fill(b)
        if (has && size % 8 != 0) {
            val trailingBits = 8 - (size % 8)
            val mask = ((0xFF shl trailingBits) and 0xFF).toByte()
            bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() and mask.toInt()).toByte()
        }
    }

    fun countPieces(): Int {
        var count = 0
        for (i in 0 until size) {
            if (hasPiece(i)) count++
        }
        return count
    }

    fun isComplete(): Boolean = countPieces() == size

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PeerBitfield) return false
        return size == other.size && Arrays.equals(bytes, other.bytes)
    }

    override fun hashCode(): Int {
        var result = size
        result = 31 * result + Arrays.hashCode(bytes)
        return result
    }
}
