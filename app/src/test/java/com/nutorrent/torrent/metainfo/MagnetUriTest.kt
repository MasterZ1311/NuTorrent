package com.nutorrent.torrent.metainfo

import com.nutorrent.core.model.AppError
import com.nutorrent.core.model.TorrentId
import com.nutorrent.core.util.Base32
import org.junit.Assert.*
import org.junit.Test

class MagnetUriTest {

    @Test
    fun `test base32 encode and decode`() {
        val testBytes = ByteArray(20) { it.toByte() }
        val encoded = Base32.encode(testBytes)
        assertEquals(32, encoded.length)

        val decoded = Base32.decode(encoded)
        assertArrayEquals(testBytes, decoded)
    }

    @Test
    fun `test valid 40-hex magnet link parsing`() {
        val magnet = "magnet:?xt=urn:btih:da39a3ee5e6b4b0d3255bfef95601890afd80709&dn=Sample+Linux+ISO&tr=http%3A%2F%2Ftracker.example.com%2Fannounce&tr=udp%3A%2F%2Ftracker.openbittorrent.com%3A80"
        val parsed = MagnetUri.parse(magnet)

        assertEquals("da39a3ee5e6b4b0d3255bfef95601890afd80709", parsed.torrentId.hex)
        assertEquals("Sample Linux ISO", parsed.displayName)
        assertEquals(2, parsed.trackers.size)
        assertEquals("http://tracker.example.com/announce", parsed.trackers[0])
        assertEquals("udp://tracker.openbittorrent.com:80", parsed.trackers[1])
    }

    @Test
    fun `test valid 32-base32 magnet link parsing`() {
        // 20-byte test vector: all 1s
        val testBytes = ByteArray(20) { 1 }
        val id = TorrentId(testBytes)
        val base32 = Base32.encode(testBytes)
        val magnet = "magnet:?xt=urn:btih:$base32&dn=Base32+Torrent"

        val parsed = MagnetUri.parse(magnet)
        assertEquals(id.hex, parsed.torrentId.hex)
        assertEquals("Base32 Torrent", parsed.displayName)
    }

    @Test
    fun `test invalid or missing parameters in magnet`() {
        assertThrows(AppError.InvalidTorrent::class.java) {
            MagnetUri.parse("http://example.com")
        }
        assertThrows(AppError.InvalidTorrent::class.java) {
            MagnetUri.parse("magnet:?dn=MissingXt")
        }
        assertThrows(AppError.InvalidTorrent::class.java) {
            MagnetUri.parse("magnet:?xt=urn:btih:invalid_short_hash")
        }
    }
}
