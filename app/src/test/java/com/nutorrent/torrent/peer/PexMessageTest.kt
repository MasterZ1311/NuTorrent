package com.nutorrent.torrent.peer

import com.nutorrent.core.model.PeerEndpoint
import org.junit.Assert.*
import org.junit.Test

class PexMessageTest {

    @Test
    fun `test pex encoding and decoding roundtrip`() {
        val p1 = PeerEndpoint("192.168.1.10", 6881)
        val p2 = PeerEndpoint("10.0.0.5", 51413)
        val dropped = PeerEndpoint("172.16.0.1", 8080)

        val original = PexMessage(
            added = listOf(p1, p2),
            dropped = listOf(dropped)
        )

        val encoded = original.toBencodeBytes()
        val parsed = PexMessage.parse(encoded)

        assertNotNull(parsed)
        assertEquals(2, parsed!!.added.size)
        assertEquals("192.168.1.10", parsed.added[0].ip)
        assertEquals(6881, parsed.added[0].port)
        assertEquals("10.0.0.5", parsed.added[1].ip)
        assertEquals(51413, parsed.added[1].port)

        assertEquals(1, parsed.dropped.size)
        assertEquals("172.16.0.1", parsed.dropped[0].ip)
        assertEquals(8080, parsed.dropped[0].port)
    }

    @Test
    fun `test pex empty message`() {
        val original = PexMessage()
        val encoded = original.toBencodeBytes()
        val parsed = PexMessage.parse(encoded)

        assertNotNull(parsed)
        assertTrue(parsed!!.added.isEmpty())
        assertTrue(parsed.dropped.isEmpty())
    }

    @Test
    fun `test pex corrupt payload returns null`() {
        val corrupt = byteArrayOf(0x00, 0x01, 0x02)
        val parsed = PexMessage.parse(corrupt)
        assertNull(parsed)
    }

    @Test
    fun `test pex handles ipv6 and malformed ips safely without crashing`() {
        val ipv6Endpoint = PeerEndpoint("2001:db8::1", 6881)
        val malformedEndpoint = PeerEndpoint("999.999.999.999.999", 6881)
        val validEndpoint = PeerEndpoint("1.2.3.4", 80)

        val msg = PexMessage(
            added = listOf(ipv6Endpoint, malformedEndpoint, validEndpoint),
            dropped = listOf(ipv6Endpoint)
        )
        val bytes = msg.toBencodeBytes()
        assertNotNull(bytes)

        val parsed = PexMessage.parse(bytes)
        assertNotNull(parsed)
        assertEquals(1, parsed!!.added.size)
        assertEquals("1.2.3.4", parsed.added[0].ip)
        assertEquals(80, parsed.added[0].port)
        assertTrue(parsed.dropped.isEmpty())
    }
}
