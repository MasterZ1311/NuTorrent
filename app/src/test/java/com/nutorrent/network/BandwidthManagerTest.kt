package com.nutorrent.network

import com.nutorrent.core.model.PeerEndpoint
import com.nutorrent.torrent.network.BandwidthManager
import com.nutorrent.torrent.network.TokenBucket
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BandwidthManagerTest {

    @Test
    fun `test token bucket rate limiting permits unlimited when rate is zero`() = runBlocking {
        val bucket = TokenBucket(0L)
        val start = System.currentTimeMillis()
        bucket.acquire(1024 * 1024)
        val elapsed = System.currentTimeMillis() - start
        assertTrue("Unlimited should complete instantly", elapsed < 100)
    }

    @Test
    fun `test token bucket throttles when rate limit exceeded`() = runBlocking {
        val bucket = TokenBucket(10_000L) // 10,000 bytes/sec
        // First chunk fills tokens
        bucket.acquire(5_000)
        val start = System.currentTimeMillis()
        // Second chunk requires more than remaining tokens, forcing a delay
        bucket.acquire(10_000)
        val elapsed = System.currentTimeMillis() - start
        assertTrue("Should delay for tokens to refill (elapsed: $elapsed ms)", elapsed >= 400)
    }

    @Test
    fun `test bandwidth manager hierarchical limits`() = runBlocking {
        val manager = BandwidthManager()
        val infoHash = "0123456789abcdef0123456789abcdef01234567"
        manager.setGlobalDownloadLimit(50_000L)
        manager.setTorrentDownloadLimit(infoHash, 25_000L)

        val start = System.currentTimeMillis()
        manager.acquireDownload(infoHash, 5_000)
        val elapsed = System.currentTimeMillis() - start
        assertTrue("Initial download should complete quickly", elapsed < 200)
    }

    @Test
    fun `test peer endpoint ipv6 formatting and parsing`() {
        val v4 = PeerEndpoint.parse("192.168.1.100:6881")
        assertNotNull(v4)
        assertEquals("192.168.1.100", v4!!.ip)
        assertEquals(6881, v4.port)
        assertFalse(v4.isIpv6)
        assertEquals("192.168.1.100:6881", v4.toString())

        val v6 = PeerEndpoint.parse("[2001:db8::1]:51413")
        assertNotNull(v6)
        assertEquals("2001:db8::1", v6!!.ip)
        assertEquals(51413, v6.port)
        assertTrue(v6.isIpv6)
        assertEquals("[2001:db8::1]:51413", v6.toString())

        val rawV6 = ByteArray(18)
        rawV6[15] = 1 // ::1
        rawV6[16] = 0x1A // 6881 = 0x1AE1
        rawV6[17] = 0xE1.toByte()

        val compactV6 = PeerEndpoint.fromCompactIpv6(rawV6, 0)
        assertTrue(compactV6.isIpv6)
        assertEquals(6881, compactV6.port)
    }
}
