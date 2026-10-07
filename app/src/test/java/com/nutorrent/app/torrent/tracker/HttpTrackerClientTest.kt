package com.nutorrent.app.torrent.tracker

import com.nutorrent.app.core.model.AppError
import com.nutorrent.app.torrent.bencode.BencodeElement
import com.nutorrent.app.torrent.bencode.BencodeEncoder
import org.junit.Assert.*
import org.junit.Test

class HttpTrackerClientTest {

    private val client = HttpTrackerClient()

    @Test
    fun `test peer id generator format and length`() {
        val id1 = PeerIdGenerator.generate()
        val id2 = PeerIdGenerator.generate()

        assertEquals(20, id1.size)
        assertEquals(20, id2.size)

        val str1 = String(id1, Charsets.US_ASCII)
        assertTrue(str1.startsWith("-NU1000-"))
        assertNotEquals(str1, String(id2, Charsets.US_ASCII))
    }

    @Test
    fun `test url encoding binary bytes`() {
        val testBytes = byteArrayOf(0x12, 0x34, 0x56, 0x78, 0x9A.toByte(), 0xBC.toByte(), 0xDE.toByte(), 0xF0.toByte())
        val encoded = client.urlEncodeBytes(testBytes)
        assertEquals("%124Vx%9A%BC%DE%F0", encoded)
    }

    @Test
    fun `test compact peers parsing`() {
        // Construct compact peer binary data: 192.168.1.100:6881 (0x1AE1) and 10.0.0.1:8080 (0x1F90)
        val p1 = byteArrayOf(192.toByte(), 168.toByte(), 1, 100, 0x1A.toByte(), 0xE1.toByte())
        val p2 = byteArrayOf(10, 0, 0, 1, 0x1F.toByte(), 0x90.toByte())
        val peersBinary = p1 + p2

        val responseDict = linkedMapOf<String, BencodeElement>(
            "interval" to BencodeElement.BInteger(900),
            "complete" to BencodeElement.BInteger(45),
            "incomplete" to BencodeElement.BInteger(10),
            "peers" to BencodeElement.BString(peersBinary)
        )
        val responseBytes = BencodeEncoder.encode(BencodeElement.BDictionary(responseDict))

        val parsed = client.parseTrackerResponse(responseBytes, "http://tracker.test")
        assertEquals(900, parsed.intervalSeconds)
        assertEquals(45, parsed.seeders)
        assertEquals(10, parsed.leechers)
        assertEquals(2, parsed.peers.size)

        assertEquals("192.168.1.100", parsed.peers[0].ip)
        assertEquals(6881, parsed.peers[0].port)

        assertEquals("10.0.0.1", parsed.peers[1].ip)
        assertEquals(8080, parsed.peers[1].port)
    }

    @Test
    fun `test dictionary peer list parsing`() {
        val p1 = linkedMapOf<String, BencodeElement>(
            "ip" to BencodeElement.BString("1.2.3.4".toByteArray()),
            "port" to BencodeElement.BInteger(5000)
        )
        val responseDict = linkedMapOf<String, BencodeElement>(
            "interval" to BencodeElement.BInteger(1200),
            "peers" to BencodeElement.BList(listOf(BencodeElement.BDictionary(p1)))
        )
        val responseBytes = BencodeEncoder.encode(BencodeElement.BDictionary(responseDict))

        val parsed = client.parseTrackerResponse(responseBytes, "http://tracker.test")
        assertEquals(1, parsed.peers.size)
        assertEquals("1.2.3.4", parsed.peers[0].ip)
        assertEquals(5000, parsed.peers[0].port)
    }

    @Test
    fun `test tracker failure reason throws TrackerFailure`() {
        val responseDict = linkedMapOf<String, BencodeElement>(
            "failure reason" to BencodeElement.BString("Torrent not registered with this tracker".toByteArray())
        )
        val responseBytes = BencodeEncoder.encode(BencodeElement.BDictionary(responseDict))

        val ex = assertThrows(AppError.TrackerFailure::class.java) {
            client.parseTrackerResponse(responseBytes, "http://tracker.test")
        }
        assertTrue(ex.userMessage.contains("Torrent not registered with this tracker"))
    }
}
