package com.nutorrent.app.torrent.piece

import com.nutorrent.app.core.model.PeerBitfield
import com.nutorrent.app.core.model.PieceState
import com.nutorrent.app.core.model.TorrentFile
import com.nutorrent.app.core.model.TorrentId
import com.nutorrent.app.core.model.TorrentMetadata
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class PieceSystemTest {

    @Test
    fun `test piece downloader block splitting`() {
        // Piece length 40,000 bytes with 16,384 block size -> blocks of 16384, 16384, 7232
        val pieceLen = 40000
        val fakeHash = ByteArray(20)
        val downloader = PieceDownloader(0, pieceLen, fakeHash, blockSize = 16384)

        assertEquals(3, downloader.blocks.size)
        assertEquals(0, downloader.blocks[0].offset)
        assertEquals(16384, downloader.blocks[0].length)

        assertEquals(16384, downloader.blocks[1].offset)
        assertEquals(16384, downloader.blocks[1].length)

        assertEquals(32768, downloader.blocks[2].offset)
        assertEquals(7232, downloader.blocks[2].length)
    }

    @Test
    fun `test piece assembly and valid sha1 verification`() {
        val payload = ByteArray(32768) { (it % 256).toByte() }
        val sha1 = MessageDigest.getInstance("SHA-1").digest(payload)

        val downloader = PieceDownloader(0, payload.size, sha1, blockSize = 16384)

        val block0 = payload.copyOfRange(0, 16384)
        val block1 = payload.copyOfRange(16384, 32768)

        assertTrue(downloader.addBlock(0, block0))
        assertFalse(downloader.isAllBlocksDownloaded())

        assertTrue(downloader.addBlock(16384, block1))
        assertTrue(downloader.isAllBlocksDownloaded())

        val verified = downloader.assembleAndVerify()
        assertNotNull(verified)
        assertArrayEquals(payload, verified)
    }

    @Test
    fun `test corrupt piece block fails verification`() {
        val payload = ByteArray(32768) { (it % 256).toByte() }
        val sha1 = MessageDigest.getInstance("SHA-1").digest(payload)

        val downloader = PieceDownloader(0, payload.size, sha1, blockSize = 16384)

        val block0 = payload.copyOfRange(0, 16384)
        // Introduce 1 corrupt byte into block 1
        val corruptBlock1 = payload.copyOfRange(16384, 32768)
        corruptBlock1[100] = (corruptBlock1[100] + 1).toByte()

        assertTrue(downloader.addBlock(0, block0))
        assertTrue(downloader.addBlock(16384, corruptBlock1))

        val verified = downloader.assembleAndVerify()
        assertNull(verified) // Must be discarded!
        assertFalse(downloader.isAllBlocksDownloaded()) // Must be reset
    }

    @Test
    fun `test piece manager rarest first scheduling`() {
        val fakeHash1 = ByteArray(20) { 1 }
        val fakeHash2 = ByteArray(20) { 2 }
        val meta = TorrentMetadata(
            torrentId = TorrentId(ByteArray(20)),
            name = "test",
            pieceLength = 16384,
            totalLength = 32768,
            piecesCount = 2,
            pieceHashes = listOf(fakeHash1, fakeHash2),
            files = listOf(TorrentFile(0, "test", 32768, 0, 32768)),
            announceList = emptyList()
        )

        val manager = PieceManager(meta)

        // Peer 1 has both piece 0 and piece 1
        val bf1 = PeerBitfield(2)
        bf1.setPiece(0, true)
        bf1.setPiece(1, true)
        manager.onPeerConnected(bf1)

        // Peer 2 has only piece 0 (making piece 0 availability = 2, piece 1 availability = 1)
        val bf2 = PeerBitfield(2)
        bf2.setPiece(0, true)
        manager.onPeerConnected(bf2)

        // When requesting for Peer 1, rarest first should pick piece 1 because availability is 1 < 2
        val request = manager.requestNextBlockForPeer(bf1)
        assertNotNull(request)
        assertEquals(1, request?.pieceIndex)
    }

    @Test
    fun `test peer disconnect immediately releases requested blocks`() {
        val meta = TorrentMetadata(
            torrentId = TorrentId(ByteArray(20)),
            name = "test",
            pieceLength = 32768,
            totalLength = 65536,
            piecesCount = 2,
            pieceHashes = listOf(ByteArray(20), ByteArray(20)),
            files = listOf(TorrentFile(0, "test", 65536, 0, 65536)),
            announceList = emptyList()
        )
        val manager = PieceManager(meta)
        // Peer only has piece 0 (piece 1 remains MISSING, preventing endgame mode)
        val bf = PeerBitfield(2).apply { setPiece(0, true) }
        manager.onPeerConnected(bf)

        // Request first block (0..16384)
        val req1 = manager.requestNextBlockForPeer(bf)
        assertNotNull(req1)
        assertEquals(0, req1?.beginOffset)

        // Request second block (16384..32768)
        val req2 = manager.requestNextBlockForPeer(bf)
        assertNotNull(req2)
        assertEquals(16384, req2?.beginOffset)

        // All blocks are requested, next request should return null (non-endgame)
        val req3 = manager.requestNextBlockForPeer(bf)
        assertNull(req3)

        // Peer disconnects with pending req1 and req2
        manager.onPeerDisconnected(bf, listOf(req1!!, req2!!))

        // Reconnect peer: blocks should be available again immediately!
        manager.onPeerConnected(bf)
        val reReq1 = manager.requestNextBlockForPeer(bf)
        assertNotNull(reReq1)
        assertEquals(0, reReq1?.beginOffset)

        val reReq2 = manager.requestNextBlockForPeer(bf)
        assertNotNull(reReq2)
        assertEquals(16384, reReq2?.beginOffset)
    }

    @Test
    fun `test piece verification and storage write lifecycle`() {
        val payload = ByteArray(16384) { (it % 256).toByte() }
        val sha1 = MessageDigest.getInstance("SHA-1").digest(payload)
        val meta = TorrentMetadata(
            torrentId = TorrentId(ByteArray(20)),
            name = "test",
            pieceLength = 16384,
            totalLength = 16384,
            piecesCount = 1,
            pieceHashes = listOf(sha1),
            files = listOf(TorrentFile(0, "test", 16384, 0, 16384)),
            announceList = emptyList()
        )
        val manager = PieceManager(meta)
        val bf = PeerBitfield(1).apply { setPiece(0, true) }
        manager.onPeerConnected(bf)

        val req = manager.requestNextBlockForPeer(bf)
        assertNotNull(req)

        // Block received & verified, but NOT yet written to storage:
        val verifiedResult = manager.onBlockReceived(0, 0, payload)
        assertNotNull(verifiedResult)
        assertArrayEquals(payload, verifiedResult?.verifiedData)

        // Piece must not be marked complete or verified before storage write!
        assertEquals(0, manager.completedPiecesCount)
        assertEquals(0L, manager.verifiedBytes)
        assertFalse(manager.isComplete())
        assertFalse(manager.ourBitfield.hasPiece(0))

        // After storage write succeeds:
        manager.onPieceWritten(0, payload.size)
        assertEquals(1, manager.completedPiecesCount)
        assertEquals(16384L, manager.verifiedBytes)
        assertTrue(manager.isComplete())
        assertTrue(manager.ourBitfield.hasPiece(0))
    }
}
