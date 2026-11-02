package com.nutorrent.torrent.dht

import org.junit.Assert.*
import org.junit.Test

class DhtNodeIdTest {

    @Test
    fun `test random generation and size`() {
        val id1 = DhtNodeId.generateRandom()
        val id2 = DhtNodeId.generateRandom()
        assertEquals(20, id1.bytes.size)
        assertEquals(20, id2.bytes.size)
        assertNotEquals(id1, id2)
    }

    @Test
    fun `test hex conversion roundtrip`() {
        val hex = "0123456789abcdef0123456789abcdef01234567"
        val id = DhtNodeId.fromHex(hex)
        assertEquals(hex, id.toHex())
    }

    @Test
    fun `test xor distance calculation`() {
        val id1 = DhtNodeId(ByteArray(20) { 0x00 })
        val id2 = DhtNodeId(ByteArray(20) { 0xFF.toByte() })
        val dist = id1.distance(id2)
        assertEquals(20, dist.size)
        for (b in dist) {
            assertEquals(0xFF.toByte(), b)
        }
    }

    @Test
    fun `test bit at and common prefix`() {
        val b1 = ByteArray(20)
        b1[0] = 0b11000000.toByte()
        val id1 = DhtNodeId(b1)

        assertTrue(id1.bitAt(0))
        assertTrue(id1.bitAt(1))
        assertFalse(id1.bitAt(2))

        val b2 = ByteArray(20)
        b2[0] = 0b11100000.toByte()
        val id2 = DhtNodeId(b2)

        // They share bits 0 and 1, differ at bit 2
        assertEquals(2, id1.commonPrefixBits(id2))
    }

    @Test
    fun `test compare distance`() {
        val origin = DhtNodeId(ByteArray(20) { 0x00 })
        val closer = DhtNodeId(ByteArray(20).apply { this[0] = 0x01 })
        val farther = DhtNodeId(ByteArray(20).apply { this[0] = 0x0F })

        assertTrue(origin.compareDistance(closer, farther) < 0)
        assertTrue(origin.compareDistance(farther, closer) > 0)
        assertEquals(0, origin.compareDistance(closer, closer))
    }
}
