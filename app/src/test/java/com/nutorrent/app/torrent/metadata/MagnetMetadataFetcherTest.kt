package com.nutorrent.app.torrent.metadata

import com.nutorrent.app.core.model.PeerEndpoint
import com.nutorrent.app.core.model.TorrentId
import com.nutorrent.app.core.model.TorrentMetadata
import com.nutorrent.app.torrent.bencode.BencodeElement
import com.nutorrent.app.torrent.bencode.BencodeEncoder
import com.nutorrent.app.torrent.metainfo.MetainfoParser
import com.nutorrent.app.torrent.peer.ExtensionHandshake
import com.nutorrent.app.torrent.peer.MetadataMessage
import com.nutorrent.app.torrent.tracker.TrackerAnnounceRequest
import com.nutorrent.app.torrent.tracker.TrackerAnnounceResponse
import com.nutorrent.app.torrent.tracker.TrackerClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

@OptIn(ExperimentalCoroutinesApi::class)
class MagnetMetadataFetcherTest {

    @Test
    fun `test metadata exchange reassembles and verifies magnet info dict`() = runTest {
        val fakeHash1 = ByteArray(20) { 7 }
        val infoMap = linkedMapOf<String, BencodeElement>(
            "length" to BencodeElement.BInteger(16384),
            "name" to BencodeElement.BString("archlinux.iso".toByteArray()),
            "piece length" to BencodeElement.BInteger(16384),
            "pieces" to BencodeElement.BString(fakeHash1)
        )
        val infoBytes = BencodeEncoder.encode(BencodeElement.BDictionary(infoMap))
        val infoHash = MessageDigest.getInstance("SHA-1").digest(infoBytes)
        val torrentId = TorrentId(infoHash)

        var resolvedMeta: TorrentMetadata? = null
        var resolvedBytes: ByteArray? = null

        val fetcher = MagnetMetadataFetcher(
            torrentId = torrentId,
            initialTrackers = listOf("udp://tracker.open.org:1337/announce"),
            scope = this,
            onResolved = { meta, raw ->
                resolvedMeta = meta
                resolvedBytes = raw
            }
        )

        // Simulate receiving metadata piece from peer
        val verifiedInfo = fetcher.metadataExchange.onPieceReceived(0, infoBytes.size, infoBytes)
        assertNotNull(verifiedInfo)
        assertArrayEquals(infoBytes, verifiedInfo)

        val meta = MetainfoParser.parseFromInfoBytes(verifiedInfo!!, fetcher.allTrackers)
        assertEquals("archlinux.iso", meta.name)
        assertEquals(torrentId, meta.torrentId)
        assertEquals(16384L, meta.totalLength)

        val fullTorrent = MetainfoParser.createTorrentBytesFromInfoDict(verifiedInfo, fetcher.allTrackers)
        val parsedFull = MetainfoParser.parse(fullTorrent)
        assertEquals(torrentId, parsedFull.torrentId)
        assertEquals("archlinux.iso", parsedFull.name)
    }
}
