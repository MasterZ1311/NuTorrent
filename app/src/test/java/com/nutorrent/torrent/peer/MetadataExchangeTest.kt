package com.nutorrent.torrent.peer

import com.nutorrent.core.model.TorrentId
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class MetadataExchangeTest {

    @Test
    fun `test extension handshake bencode serialization and parsing`() {
        val handshake = ExtensionHandshake(
            extensions = mapOf("ut_metadata" to 1, "ut_pex" to 2),
            metadataSize = 32768,
            clientVersion = "NuTorrent 1.0.0",
            port = 6881
        )
        val bytes = handshake.toBencodeBytes()
        val parsed = ExtensionHandshake.parse(bytes)

        assertEquals(1, parsed.extensions["ut_metadata"])
        assertEquals(2, parsed.extensions["ut_pex"])
        assertEquals(32768, parsed.metadataSize)
        assertEquals("NuTorrent 1.0.0", parsed.clientVersion)
        assertEquals(6881, parsed.port)
    }

    @Test
    fun `test metadata message request and data framing`() {
        val reqBytes = MetadataMessage.createRequestBytes(2)
        val parsedReq = MetadataMessage.parse(reqBytes) as MetadataMessage.Request
        assertEquals(2, parsedReq.piece)

        val chunkData = "Test metadata chunk content".toByteArray()
        val dataBytes = MetadataMessage.createDataBytes(piece = 1, totalSize = 1000, pieceData = chunkData)
        val parsedData = MetadataMessage.parse(dataBytes) as MetadataMessage.Data
        assertEquals(1, parsedData.piece)
        assertEquals(1000, parsedData.totalSize)
        assertArrayEquals(chunkData, parsedData.data)
    }

    @Test
    fun `test metadata exchange assembly and SHA1 verification`() {
        val totalSize = 35000 // 2 full 16 KiB blocks + 1 remaining block of 2232
        val dummyMetadata = ByteArray(totalSize) { (it % 250).toByte() }
        val sha1 = MessageDigest.getInstance("SHA-1").digest(dummyMetadata)
        val infoHash = TorrentId(sha1)

        val exchange = MetadataExchange(expectedInfoHash = infoHash)
        assertEquals(0, exchange.totalPieces)

        // Piece 0: 0..16384
        val piece0 = dummyMetadata.copyOfRange(0, 16384)
        val res0 = exchange.onPieceReceived(0, totalSize, piece0)
        assertNull(res0) // incomplete
        assertEquals(3, exchange.totalPieces)

        // Piece 1: 16384..32768
        val piece1 = dummyMetadata.copyOfRange(16384, 32768)
        val res1 = exchange.onPieceReceived(1, totalSize, piece1)
        assertNull(res1)

        // Piece 2: 32768..35000 (size 2232)
        val piece2 = dummyMetadata.copyOfRange(32768, 35000)
        val res2 = exchange.onPieceReceived(2, totalSize, piece2)
        assertNotNull(res2)
        assertArrayEquals(dummyMetadata, res2)
        assertTrue(exchange.isComplete())

        // Test getPiece for seeding
        val served0 = exchange.getPiece(0)
        assertNotNull(served0)
        assertArrayEquals(piece0, served0)
    }

    @Test
    fun `test corrupt metadata piece fails verification and resets`() {
        val totalSize = 20000
        val dummyMetadata = ByteArray(totalSize) { (it % 200).toByte() }
        val sha1 = MessageDigest.getInstance("SHA-1").digest(dummyMetadata)
        val infoHash = TorrentId(sha1)

        val exchange = MetadataExchange(expectedInfoHash = infoHash)

        val piece0 = dummyMetadata.copyOfRange(0, 16384)
        exchange.onPieceReceived(0, totalSize, piece0)

        // Corrupt piece 1
        val corruptPiece1 = dummyMetadata.copyOfRange(16384, 20000)
        corruptPiece1[10] = (corruptPiece1[10] + 1).toByte()

        val result = exchange.onPieceReceived(1, totalSize, corruptPiece1)
        assertNull(result)
        assertFalse(exchange.isComplete())
    }

    @Test
    fun `test pipelined concurrent piece request selection and in-flight tracking`() {
        val totalSize = 48000 // 3 pieces (16384, 16384, 15232)
        val dummyMetadata = ByteArray(totalSize) { 0x5A }
        val sha1 = MessageDigest.getInstance("SHA-1").digest(dummyMetadata)
        val infoHash = TorrentId(sha1)

        val exchange = MetadataExchange(expectedInfoHash = infoHash)
        exchange.setMetadataSize(totalSize)
        assertEquals(3, exchange.totalPieces)

        // Peer A requests first piece
        val pieceForPeerA = exchange.getNextNeededPiece(excludeInFlight = true)
        assertEquals(0, pieceForPeerA)
        exchange.markPieceRequested(0)

        // Peer B requests next piece concurrently - should receive piece 1 (NOT piece 0!)
        val pieceForPeerB = exchange.getNextNeededPiece(excludeInFlight = true)
        assertEquals(1, pieceForPeerB)
        exchange.markPieceRequested(1)

        // Peer C requests next piece concurrently - should receive piece 2
        val pieceForPeerC = exchange.getNextNeededPiece(excludeInFlight = true)
        assertEquals(2, pieceForPeerC)
        exchange.markPieceRequested(2)

        // With all pieces in-flight, unrequested query returns null
        val noneAvailable = exchange.getNextNeededPiece(excludeInFlight = true)
        assertNull(noneAvailable)

        // If piece 1 fails (rejected/timeout), it is immediately freed for another peer
        exchange.onPieceFailed(1)
        val reRequestedPiece = exchange.getNextNeededPiece(excludeInFlight = true)
        assertEquals(1, reRequestedPiece)
    }
}
