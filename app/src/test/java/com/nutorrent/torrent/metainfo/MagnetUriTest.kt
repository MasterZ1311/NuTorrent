package com.nutorrent.torrent.metainfo

import com.nutorrent.core.model.AppError
import com.nutorrent.core.model.TorrentId
import com.nutorrent.core.util.Base32
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

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
    fun `test magnet without question mark`() {
        val magnet = "magnet:xt=urn:btih:da39a3ee5e6b4b0d3255bfef95601890afd80709&dn=No+Question+Mark"
        val parsed = MagnetUri.parse(magnet)
        assertEquals("da39a3ee5e6b4b0d3255bfef95601890afd80709", parsed.torrentId.hex)
        assertEquals("No Question Mark", parsed.displayName)
    }

    @Test
    fun `test thunder link decoding to magnet`() {
        val rawMagnet = "magnet:?xt=urn:btih:da39a3ee5e6b4b0d3255bfef95601890afd80709&dn=Thunder+Torrent"
        val thunderPayload = Base64.getEncoder().encodeToString("AA${rawMagnet}ZZ".toByteArray(Charsets.UTF_8))
        val thunderUrl = "thunder://$thunderPayload"

        val parsed = MagnetUri.parse(thunderUrl)
        assertEquals("da39a3ee5e6b4b0d3255bfef95601890afd80709", parsed.torrentId.hex)
        assertEquals("Thunder Torrent", parsed.displayName)
    }

    @Test
    fun `test flashget link decoding to magnet`() {
        val rawMagnet = "magnet:?xt=urn:btih:da39a3ee5e6b4b0d3255bfef95601890afd80709&dn=FlashGet+Torrent"
        val payload = Base64.getEncoder().encodeToString("[FLASHGET]${rawMagnet}[FLASHGET]".toByteArray(Charsets.UTF_8))
        val flashgetUrl = "flashget://$payload&12345"

        val parsed = MagnetUri.parse(flashgetUrl)
        assertEquals("da39a3ee5e6b4b0d3255bfef95601890afd80709", parsed.torrentId.hex)
        assertEquals("FlashGet Torrent", parsed.displayName)
    }

    @Test
    fun `test qqdl link decoding to magnet`() {
        val rawMagnet = "magnet:?xt=urn:btih:da39a3ee5e6b4b0d3255bfef95601890afd80709&dn=QQDL+Torrent"
        val payload = Base64.getEncoder().encodeToString(rawMagnet.toByteArray(Charsets.UTF_8))
        val qqdlUrl = "qqdl://$payload"

        val parsed = MagnetUri.parse(qqdlUrl)
        assertEquals("da39a3ee5e6b4b0d3255bfef95601890afd80709", parsed.torrentId.hex)
        assertEquals("QQDL Torrent", parsed.displayName)
    }

    @Test
    fun `test bitcomet link decoding to magnet`() {
        val hex = "da39a3ee5e6b4b0d3255bfef95601890afd80709"
        val bcUrl = "bc://bt/LinuxDistro/$hex"

        val parsed = MagnetUri.parse(bcUrl)
        assertEquals(hex, parsed.torrentId.hex)
    }

    @Test
    fun `test bare 40-character hex info hash`() {
        val hex = "da39a3ee5e6b4b0d3255bfef95601890afd80709"
        val parsed = MagnetUri.parse(hex)
        assertEquals(hex, parsed.torrentId.hex)

        // With 0x prefix
        val parsed0x = MagnetUri.parse("0x$hex")
        assertEquals(hex, parsed0x.torrentId.hex)

        // Mixed case with spaces
        val parsedMixed = MagnetUri.parse("DA39A3EE 5E6B4B0D 3255BFEF 95601890 AFD80709")
        assertEquals(hex, parsedMixed.torrentId.hex)
    }

    @Test
    fun `test bare 32-character base32 info hash`() {
        val testBytes = ByteArray(20) { 0x55.toByte() }
        val id = TorrentId(testBytes)
        val base32 = Base32.encode(testBytes)

        val parsed = MagnetUri.parse(base32)
        assertEquals(id.hex, parsed.torrentId.hex)
    }

    @Test
    fun `test bittorrent v2 multihash magnet`() {
        // SHA-256 is 32 bytes (64 hex characters)
        val v2Sha256Hex = "da39a3ee5e6b4b0d3255bfef95601890afd807090102030405060708090a0b0c"
        val v2Magnet = "magnet:?xt=urn:btmh:1220$v2Sha256Hex&dn=V2+Only+Torrent"

        val parsed = MagnetUri.parse(v2Magnet)
        // First 40 hex chars map to 20-byte TorrentId
        assertEquals("da39a3ee5e6b4b0d3255bfef95601890afd80709", parsed.torrentId.hex)
        assertEquals("V2 Only Torrent", parsed.displayName)
    }

    @Test
    fun `test hybrid v1 and v2 magnet picks v1 btih`() {
        val v1Hex = "1111111111111111111111111111111111111111"
        val v2Hex = "2222222222222222222222222222222222222222333333333333333333333333"
        val hybridMagnet = "magnet:?xt=urn:btih:$v1Hex&xt=urn:btmh:1220$v2Hex&dn=Hybrid+Torrent"

        val parsed = MagnetUri.parse(hybridMagnet)
        assertEquals(v1Hex, parsed.torrentId.hex)
        assertEquals("Hybrid Torrent", parsed.displayName)
    }

    @Test
    fun `test multiple xt parameters with non-btih first`() {
        val hex = "da39a3ee5e6b4b0d3255bfef95601890afd80709"
        val magnet = "magnet:?xt=urn:ed2k:31D6CFE0D16AE931B73C59D7E0C089C0&xt=urn:btih:$hex&dn=Multiple+XT"

        val parsed = MagnetUri.parse(magnet)
        assertEquals(hex, parsed.torrentId.hex)
        assertEquals("Multiple XT", parsed.displayName)
    }

    @Test
    fun `test url-encoded magnet string`() {
        val raw = "magnet:?xt=urn:btih:da39a3ee5e6b4b0d3255bfef95601890afd80709&dn=Encoded+Magnet"
        val encoded = java.net.URLEncoder.encode(raw, "UTF-8")

        val parsed = MagnetUri.parse(encoded)
        assertEquals("da39a3ee5e6b4b0d3255bfef95601890afd80709", parsed.torrentId.hex)
        assertEquals("Encoded Magnet", parsed.displayName)
    }

    @Test
    fun `test html entities in magnet`() {
        val magnet = "magnet:?xt=urn:btih:da39a3ee5e6b4b0d3255bfef95601890afd80709&amp;dn=Entity+Test&amp;tr=http://tracker.com/announce"
        val parsed = MagnetUri.parse(magnet)

        assertEquals("da39a3ee5e6b4b0d3255bfef95601890afd80709", parsed.torrentId.hex)
        assertEquals("Entity Test", parsed.displayName)
        assertEquals(1, parsed.trackers.size)
        assertEquals("http://tracker.com/announce", parsed.trackers[0])
    }

    @Test
    fun `test embedded magnet in markdown and html`() {
        val raw = "magnet:?xt=urn:btih:da39a3ee5e6b4b0d3255bfef95601890afd80709&dn=Markdown+Link"
        val markdown = "Click here to [Download Torrent]($raw) now!"

        val parsedFromMd = MagnetUri.parse(markdown)
        assertEquals("da39a3ee5e6b4b0d3255bfef95601890afd80709", parsedFromMd.torrentId.hex)

        val html = "<a href=\"$raw\">Click Here</a>"
        val parsedFromHtml = MagnetUri.parse(html)
        assertEquals("da39a3ee5e6b4b0d3255bfef95601890afd80709", parsedFromHtml.torrentId.hex)

        val forumPost = "Hey check out this cool file: $raw ... awesome speed!"
        val parsedFromForum = MagnetUri.parse(forumPost)
        assertEquals("da39a3ee5e6b4b0d3255bfef95601890afd80709", parsedFromForum.torrentId.hex)
    }

    @Test
    fun `test advanced magnet parameters`() {
        val magnet = "magnet:?xt=urn:btih:da39a3ee5e6b4b0d3255bfef95601890afd80709" +
            "&dn=Advanced+Torrent" +
            "&xl=104857600" +
            "&ws=http://seed1.org/data.iso,http://seed2.org/data.iso" +
            "&xs=https://sources.org/torrent.torrent" +
            "&x.pe=192.168.1.50:6881;10.0.0.1:8080" +
            "&so=0,2,4-6" +
            "&kt=linux+ubuntu+iso"

        val parsed = MagnetUri.parse(magnet)
        assertEquals("da39a3ee5e6b4b0d3255bfef95601890afd80709", parsed.torrentId.hex)
        assertEquals("Advanced Torrent", parsed.displayName)
        assertEquals(104857600L, parsed.exactLength)
        assertEquals(2, parsed.webSeeds.size)
        assertEquals("http://seed1.org/data.iso", parsed.webSeeds[0])
        assertEquals("http://seed2.org/data.iso", parsed.webSeeds[1])
        assertEquals(1, parsed.exactSources.size)
        assertEquals("https://sources.org/torrent.torrent", parsed.exactSources[0])
        assertEquals(2, parsed.peerHints.size)
        assertEquals("192.168.1.50:6881", parsed.peerHints[0])
        assertEquals("10.0.0.1:8080", parsed.peerHints[1])
        assertEquals(listOf(0, 2, 4, 5, 6), parsed.selectedIndices)
        assertEquals(listOf("linux", "ubuntu", "iso"), parsed.keywords)
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
