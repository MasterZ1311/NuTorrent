package com.nutorrent.core.util

import com.nutorrent.core.model.TorrentFile
import com.nutorrent.torrent.metainfo.MagnetUri
import com.nutorrent.torrent.tracker.CommunityTrackersSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class MotrixAdaptedAlgorithmsTest {

    // =========================================================================
    // 1. Thunder Link Decoder Tests (Motrix decodeThunderLink)
    // =========================================================================

    @Test
    fun testThunderLinkDecoder_withMagnetLink() {
        val originalMagnet = "magnet:?xt=urn:btih:c12fe1c06bba254a9dc9f519b335380dc1f74c26&dn=Ubuntu"
        // Thunder format: Base64("AA" + url + "ZZ")
        val thunderWrapped = "AA" + originalMagnet + "ZZ"
        val base64Payload = Base64.getEncoder().encodeToString(thunderWrapped.toByteArray(Charsets.UTF_8))
        val thunderUrl = "thunder://$base64Payload"

        assertTrue(ThunderLinkDecoder.isThunderLink(thunderUrl))
        val decoded = ThunderLinkDecoder.decode(thunderUrl)
        assertEquals(originalMagnet, decoded)

        // Verify MagnetUri.parse transparently parses the thunder link
        val magnet = MagnetUri.parse(thunderUrl)
        assertEquals("Ubuntu", magnet.displayName)
        assertEquals("c12fe1c06bba254a9dc9f519b335380dc1f74c26", magnet.torrentId.hex)
    }

    @Test
    fun testThunderLinkDecoder_withHttpUrl() {
        val originalUrl = "https://releases.ubuntu.com/22.04/ubuntu-22.04-desktop-amd64.iso"
        val thunderWrapped = "AA" + originalUrl + "ZZ"
        val base64Payload = Base64.getEncoder().encodeToString(thunderWrapped.toByteArray(Charsets.UTF_8))
        val thunderUrl = "thunder://$base64Payload"

        val decoded = ThunderLinkDecoder.decode(thunderUrl)
        assertEquals(originalUrl, decoded)
    }

    @Test
    fun testThunderLinkDecoder_withNonThunderLink() {
        val normalMagnet = "magnet:?xt=urn:btih:c12fe1c06bba254a9dc9f519b335380dc1f74c26"
        assertFalse(ThunderLinkDecoder.isThunderLink(normalMagnet))
        assertEquals(normalMagnet, ThunderLinkDecoder.decode(normalMagnet))
    }

    @Test
    fun testThunderLinkDecoder_withInvalidBase64() {
        val broken = "thunder://!not_valid_base64!"
        val decoded = ThunderLinkDecoder.decode(broken)
        assertEquals(broken, decoded)
    }

    // =========================================================================
    // 2. Community Trackers Parser & Reducer Tests (Motrix tracker.js)
    // =========================================================================

    @Test
    fun testCommunityTrackers_parseTrackersText() {
        val rawInput = """
            # Top tier public trackers
            udp://tracker.opentrackr.org:1337/announce

            http://tracker.openbittorrent.com:80/announce
            # Comment line
            https://opentracker.i2p.rocks:443/announce,udp://open.stealth.si:80/announce
            ftp://invalid-protocol.com/announce
            invalid_text_line
        """.trimIndent()

        val parsed = CommunityTrackersSync.parseTrackersText(rawInput)
        assertEquals(4, parsed.size)
        assertTrue(parsed.contains("udp://tracker.opentrackr.org:1337/announce"))
        assertTrue(parsed.contains("http://tracker.openbittorrent.com:80/announce"))
        assertTrue(parsed.contains("https://opentracker.i2p.rocks:443/announce"))
        assertTrue(parsed.contains("udp://open.stealth.si:80/announce"))
        assertFalse(parsed.any { it.startsWith("#") || it.startsWith("ftp://") })
    }

    @Test
    fun testCommunityTrackers_reduceTrackers() {
        val trackers = listOf(
            "udp://tracker.one:1337/announce",    // len 31
            "udp://tracker.two:1337/announce",    // len 31 (+1 = 32)
            "udp://tracker.three:1337/announce"  // len 33
        )

        val reduced = CommunityTrackersSync.reduceTrackers(trackers, maxLength = 65)
        assertEquals(2, reduced.size)
        assertEquals("udp://tracker.one:1337/announce", reduced[0])
        assertEquals("udp://tracker.two:1337/announce", reduced[1])
    }

    // =========================================================================
    // 3. File Category Filtering Tests (Motrix utils/index.js)
    // =========================================================================

    @Test
    fun testFileCategoryFilter_categorization() {
        assertEquals(TorrentFileCategory.VIDEO, FileCategoryFilter.categorize("Movies/Avatar.mkv"))
        assertEquals(TorrentFileCategory.VIDEO, FileCategoryFilter.categorize("Subs/avatar_en.srt"))
        assertEquals(TorrentFileCategory.AUDIO, FileCategoryFilter.categorize("Music/track_01.flac"))
        assertEquals(TorrentFileCategory.AUDIO, FileCategoryFilter.categorize("song.mp3"))
        assertEquals(TorrentFileCategory.IMAGE, FileCategoryFilter.categorize("Cover.jpg"))
        assertEquals(TorrentFileCategory.DOCUMENT, FileCategoryFilter.categorize("README.txt"))
        assertEquals(TorrentFileCategory.DOCUMENT, FileCategoryFilter.categorize("Manual.pdf"))
        assertEquals(TorrentFileCategory.OTHER, FileCategoryFilter.categorize("app.exe"))
    }

    @Test
    fun testFileCategoryFilter_filteringFiles() {
        val files = listOf(
            TorrentFile(0, "movie.mp4", 1000L, 0L, 1000L),
            TorrentFile(1, "movie.srt", 100L, 1000L, 1100L),
            TorrentFile(2, "soundtrack.mp3", 500L, 1100L, 1600L),
            TorrentFile(3, "info.txt", 50L, 1600L, 1650L)
        )

        val videos = FileCategoryFilter.filterFiles(files, TorrentFileCategory.VIDEO)
        assertEquals(2, videos.size)
        assertEquals("movie.mp4", videos[0].relativePath)
        assertEquals("movie.srt", videos[1].relativePath)

        val audios = FileCategoryFilter.filterFiles(files, TorrentFileCategory.AUDIO)
        assertEquals(1, audios.size)
        assertEquals("soundtrack.mp3", audios[0].relativePath)

        val all = FileCategoryFilter.filterFiles(files, TorrentFileCategory.ALL)
        assertEquals(4, all.size)
    }

    // =========================================================================
    // 4. Batch Pattern Renaming Tests (Motrix utils/rename.js)
    // =========================================================================

    @Test
    fun testBatchPatternRename_increment() {
        val template = "Series_S01E(01+1).mp4"
        assertTrue(BatchPatternRename.hasPatternRule(template))

        val names = BatchPatternRename.buildOutputNames(template, count = 3)
        assertEquals(3, names.size)
        assertEquals("Series_S01E01.mp4", names[0])
        assertEquals("Series_S01E02.mp4", names[1])
        assertEquals("Series_S01E03.mp4", names[2])
    }

    @Test
    fun testBatchPatternRename_decrement() {
        val template = "Photo_(10-1).jpg"
        val names = BatchPatternRename.buildOutputNames(template, count = 3)
        assertEquals(3, names.size)
        assertEquals("Photo_10.jpg", names[0])
        assertEquals("Photo_09.jpg", names[1])
        assertEquals("Photo_08.jpg", names[2])
    }

    @Test
    fun testBatchPatternRename_stepMoreThanOne() {
        val template = "Sample_(1+2).wav"
        val names = BatchPatternRename.buildOutputNames(template, count = 4)
        assertEquals(4, names.size)
        assertEquals("Sample_1.wav", names[0])
        assertEquals("Sample_3.wav", names[1])
        assertEquals("Sample_5.wav", names[2])
        assertEquals("Sample_7.wav", names[3])
    }

    @Test
    fun testBatchPatternRename_noPatternReturnsOriginal() {
        val template = "SingleFile.zip"
        assertFalse(BatchPatternRename.hasPatternRule(template))
        val names = BatchPatternRename.buildOutputNames(template, count = 2)
        assertEquals(listOf("SingleFile.zip", "SingleFile.zip"), names)
    }
}
