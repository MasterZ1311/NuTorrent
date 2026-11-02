package com.nutorrent.torrent.dht

import java.security.SecureRandom

/**
 * 160-bit Kademlia Node ID (20 bytes).
 * Supports XOR distance metric and prefix calculations.
 */
class DhtNodeId(val bytes: ByteArray) : Comparable<DhtNodeId> {

    init {
        require(bytes.size == 20) { "DHT Node ID must be exactly 20 bytes, got ${bytes.size}" }
    }

    companion object {
        private val random = SecureRandom()

        fun generateRandom(): DhtNodeId {
            val b = ByteArray(20)
            random.nextBytes(b)
            return DhtNodeId(b)
        }

        fun fromBytes(bytes: ByteArray): DhtNodeId {
            val copy = ByteArray(20)
            System.arraycopy(bytes, 0, copy, 0, minOf(20, bytes.size))
            return DhtNodeId(copy)
        }

        fun fromHex(hex: String): DhtNodeId {
            require(hex.length == 40) { "Hex string must be 40 characters" }
            val b = ByteArray(20)
            for (i in 0 until 20) {
                b[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
            return DhtNodeId(b)
        }
    }

    fun distance(other: DhtNodeId): ByteArray {
        val dist = ByteArray(20)
        for (i in 0 until 20) {
            dist[i] = (bytes[i].toInt() xor other.bytes[i].toInt()).toByte()
        }
        return dist
    }

    /**
     * Returns true if bit at [index] (0..159, 0 is MSB of byte 0) is 1.
     */
    fun bitAt(index: Int): Boolean {
        require(index in 0..159)
        val byteIndex = index / 8
        val bitOffset = 7 - (index % 8)
        return ((bytes[byteIndex].toInt() ushr bitOffset) and 1) == 1
    }

    /**
     * Counts the number of leading common bits between this ID and [other].
     */
    fun commonPrefixBits(other: DhtNodeId): Int {
        var count = 0
        for (i in 0 until 20) {
            val x = (bytes[i].toInt() xor other.bytes[i].toInt()) and 0xFF
            if (x == 0) {
                count += 8
            } else {
                count += Integer.numberOfLeadingZeros(x) - 24
                break
            }
        }
        return count
    }

    /**
     * Compares distances from this node to [a] and [b].
     * Returns negative if this is closer to [a] than to [b],
     * positive if this is closer to [b] than to [a], 0 if equal.
     */
    fun compareDistance(a: DhtNodeId, b: DhtNodeId): Int {
        for (i in 0 until 20) {
            val distA = (bytes[i].toInt() xor a.bytes[i].toInt()) and 0xFF
            val distB = (bytes[i].toInt() xor b.bytes[i].toInt()) and 0xFF
            if (distA != distB) {
                return distA.compareTo(distB)
            }
        }
        return 0
    }

    override fun compareTo(other: DhtNodeId): Int {
        for (i in 0 until 20) {
            val b1 = bytes[i].toInt() and 0xFF
            val b2 = other.bytes[i].toInt() and 0xFF
            if (b1 != b2) return b1.compareTo(b2)
        }
        return 0
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DhtNodeId) return false
        return bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int = bytes.contentHashCode()

    fun toHex(): String = bytes.joinToString("") { "%02x".format(it) }

    override fun toString(): String = toHex()
}
