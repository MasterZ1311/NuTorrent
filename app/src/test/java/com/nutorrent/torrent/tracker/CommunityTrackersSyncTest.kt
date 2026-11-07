package com.nutorrent.torrent.tracker

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

class CommunityTrackersSyncTest {

    @Test
    fun `test isValidTrackerUrl recognizes valid tracker protocols`() {
        assertTrue(CommunityTrackersSync.isValidTrackerUrl("udp://tracker.opentrackr.org:1337/announce"))
        assertTrue(CommunityTrackersSync.isValidTrackerUrl("http://tracker.open.org:80/announce"))
        assertTrue(CommunityTrackersSync.isValidTrackerUrl("https://tracker.tamers.org/announce"))
        assertTrue(CommunityTrackersSync.isValidTrackerUrl("wss://tracker.btorrent.xyz"))

        assertFalse(CommunityTrackersSync.isValidTrackerUrl(""))
        assertFalse(CommunityTrackersSync.isValidTrackerUrl("# comment line"))
        assertFalse(CommunityTrackersSync.isValidTrackerUrl("ftp://invalid.tracker.com"))
        assertFalse(CommunityTrackersSync.isValidTrackerUrl("file:///etc/passwd"))
    }

    @Test
    fun `test parseTrackersText filters and deduplicates`() {
        val raw = """
            # Best trackers list
            udp://tracker.opentrackr.org:1337/announce
            
            http://tracker.open.org:80/announce
            udp://tracker.opentrackr.org:1337/announce
            # another comment
            https://tracker.tamers.org/announce
        """.trimIndent()

        val parsed = CommunityTrackersSync.parseTrackersText(raw)
        assertEquals(3, parsed.size)
        assertEquals("udp://tracker.opentrackr.org:1337/announce", parsed[0])
        assertEquals("http://tracker.open.org:80/announce", parsed[1])
        assertEquals("https://tracker.tamers.org/announce", parsed[2])
    }

    @Test
    fun `test readBoundedTrackers truncates oversized streams safely`() {
        // Generate tracker stream with many lines
        val lines = (1..100).map { "udp://tracker$it.example.com:1337/announce" }
        val streamContent = lines.joinToString("\n")
        val streamBytes = streamContent.toByteArray(StandardCharsets.UTF_8)

        // Strict max bytes of 200 bytes (fits only first few trackers)
        val bounded = CommunityTrackersSync.readBoundedTrackers(ByteArrayInputStream(streamBytes), maxBytes = 200)
        assertTrue(bounded.isNotEmpty())
        assertTrue("Bounded result size (${bounded.size}) should be much less than total lines (${lines.size})", bounded.size < 10)
    }

    @Test
    fun `test reduceTrackers enforces maxLength boundary`() {
        val trackers = listOf(
            "udp://tracker1.org:1337/announce",
            "udp://tracker2.org:1337/announce",
            "udp://tracker3.org:1337/announce"
        )
        // With small max length, only first tracker should fit
        val reduced = CommunityTrackersSync.reduceTrackers(trackers, maxLength = 40)
        assertEquals(1, reduced.size)
        assertEquals("udp://tracker1.org:1337/announce", reduced[0])
    }
}
