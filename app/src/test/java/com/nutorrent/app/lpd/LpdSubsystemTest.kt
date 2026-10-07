package com.nutorrent.app.lpd

import com.nutorrent.app.torrent.lpd.LpdSearchMessage
import com.nutorrent.app.torrent.lpd.LpdSubsystem
import org.junit.Assert.*
import org.junit.Test
import java.nio.charset.StandardCharsets

class LpdSubsystemTest {

    private val sampleInfoHash = "4a44dc15364204a80fe80e90394ff50123456789"

    @Test
    fun `test serialize LpdSearchMessage matches BEP 14 format`() {
        val msg = LpdSearchMessage(
            port = 6881,
            infoHashHex = sampleInfoHash,
            cookie = "abc12345"
        )
        val serialized = String(msg.serialize(), StandardCharsets.US_ASCII)

        assertTrue(serialized.startsWith("BT-SEARCH * HTTP/1.1\r\n"))
        assertTrue(serialized.contains("Host: ${LpdSubsystem.MULTICAST_IP}:${LpdSubsystem.MULTICAST_PORT}\r\n"))
        assertTrue(serialized.contains("Port: 6881\r\n"))
        assertTrue(serialized.contains("Infohash: $sampleInfoHash\r\n"))
        assertTrue(serialized.contains("cookie: abc12345\r\n"))
    }

    @Test
    fun `test parse valid BEP 14 search message`() {
        val raw = "BT-SEARCH * HTTP/1.1\r\n" +
                "Host: 239.192.152.143:6771\r\n" +
                "Port: 51413\r\n" +
                "Infohash: $sampleInfoHash\r\n" +
                "cookie: ffee9900\r\n\r\n\r\n"

        val parsed = LpdSearchMessage.parse(raw)
        assertNotNull(parsed)
        assertEquals(51413, parsed!!.port)
        assertEquals(sampleInfoHash.lowercase(), parsed.infoHashHex)
        assertEquals("ffee9900", parsed.cookie)
    }

    @Test
    fun `test parse handles case insensitive headers`() {
        val raw = "BT-SEARCH * HTTP/1.1\r\n" +
                "host: 239.192.152.143:6771\r\n" +
                "port: 6889\r\n" +
                "infohash: $sampleInfoHash\r\n\r\n"

        val parsed = LpdSearchMessage.parse(raw)
        assertNotNull(parsed)
        assertEquals(6889, parsed!!.port)
        assertEquals(sampleInfoHash.lowercase(), parsed.infoHashHex)
        assertNull(parsed.cookie)
    }

    @Test
    fun `test parse rejects invalid messages`() {
        // Missing start line
        val noStartLine = "Host: 239.192.152.143:6771\r\nPort: 6881\r\nInfohash: $sampleInfoHash\r\n"
        assertNull(LpdSearchMessage.parse(noStartLine))

        // Invalid port
        val badPort = "BT-SEARCH * HTTP/1.1\r\nPort: invalid\r\nInfohash: $sampleInfoHash\r\n"
        assertNull(LpdSearchMessage.parse(badPort))

        // Short infohash
        val shortHash = "BT-SEARCH * HTTP/1.1\r\nPort: 6881\r\nInfohash: 1234abcd\r\n"
        assertNull(LpdSearchMessage.parse(shortHash))
    }
}
