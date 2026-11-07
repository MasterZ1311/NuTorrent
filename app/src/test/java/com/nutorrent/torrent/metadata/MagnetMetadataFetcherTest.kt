package com.nutorrent.torrent.metadata

import com.nutorrent.core.model.PeerEndpoint
import com.nutorrent.core.model.TorrentId
import com.nutorrent.core.model.TorrentMetadata
import com.nutorrent.torrent.bencode.BencodeElement
import com.nutorrent.torrent.bencode.BencodeEncoder
import com.nutorrent.torrent.metainfo.MetainfoParser
import com.nutorrent.torrent.peer.ExtensionHandshake
import com.nutorrent.torrent.peer.MetadataMessage
import com.nutorrent.torrent.tracker.TrackerAnnounceRequest
import com.nutorrent.torrent.tracker.TrackerAnnounceResponse
import com.nutorrent.torrent.tracker.TrackerClient
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
            onResolved = { meta, raw, _ ->
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

    @Test
    fun `test 3-parameter onResolved receives discoveredPeers and listenPort is passed`() = runTest {
        val fakeHash1 = ByteArray(20) { 8 }
        val infoMap = linkedMapOf<String, BencodeElement>(
            "length" to BencodeElement.BInteger(16384),
            "name" to BencodeElement.BString("test.iso".toByteArray()),
            "piece length" to BencodeElement.BInteger(16384),
            "pieces" to BencodeElement.BString(fakeHash1)
        )
        val infoBytes = BencodeEncoder.encode(BencodeElement.BDictionary(infoMap))
        val infoHash = MessageDigest.getInstance("SHA-1").digest(infoBytes)
        val torrentId = TorrentId(infoHash)

        var resolvedPeers: Set<PeerEndpoint>? = null

        val fetcher = MagnetMetadataFetcher(
            torrentId = torrentId,
            initialTrackers = listOf("udp://tracker.open.org:1337/announce"),
            scope = this,
            listenPort = 6882,
            onResolved = { _, _, peers ->
                resolvedPeers = peers
            }
        )

        assertEquals(6882, fetcher.listenPort)

        // Verify incoming peer rejection when not started
        val dummySocket = java.net.Socket()
        val dummyHandshake = com.nutorrent.torrent.peer.PeerHandshake(
            infoHash = torrentId.bytes,
            peerId = ByteArray(20) { 1 }
        )
        val accepted = fetcher.acceptIncomingPeer(dummySocket, dummyHandshake)
        assertFalse("Should not accept incoming peer when fetcher is not started", accepted)
    }
    @Test
    fun `test readBoundedStream enforces limits and rejects oversized streams`() {
        val smallData = ByteArray(1024) { 42 }
        val smallResult = MagnetMetadataFetcher.readBoundedStream(java.io.ByteArrayInputStream(smallData), 2048)
        assertArrayEquals(smallData, smallResult)

        val oversizedData = ByteArray(4096) { 1 }
        val ex = assertThrows(java.io.IOException::class.java) {
            MagnetMetadataFetcher.readBoundedStream(java.io.ByteArrayInputStream(oversizedData), 2048)
        }
        assertTrue(ex.message!!.contains("exceeded maximum permitted size"))
    }
}
