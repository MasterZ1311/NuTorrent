package com.nutorrent.app.torrent.piece

import com.nutorrent.app.core.model.FilePriority
import com.nutorrent.app.core.model.PeerBitfield
import com.nutorrent.app.core.model.PieceState
import com.nutorrent.app.core.model.TorrentFile
import com.nutorrent.app.core.model.TorrentId
import com.nutorrent.app.core.model.TorrentMetadata
import org.junit.Assert.*
import org.junit.Test

class AdvancedSchedulerAndPriorityTest {

    @Test
    fun `test priority selection favors high over normal and skips do not download`() {
        val scheduler = RarestFirstPieceScheduler()
        val peerBitfield = PeerBitfield(4).apply {
            setPiece(0, true)
            setPiece(1, true)
            setPiece(2, true)
            setPiece(3, true)
        }
        val pieceStates = listOf(PieceState.MISSING, PieceState.MISSING, PieceState.MISSING, PieceState.MISSING)
        val availability = intArrayOf(5, 2, 10, 1)
        val priorities = listOf(
            FilePriority.DO_NOT_DOWNLOAD,
            FilePriority.NORMAL,
            FilePriority.HIGH,
            FilePriority.LOW
        )

        // Piece 2 is HIGH priority, so it must be selected even though availability is 10 (higher than piece 1 or 3)
        val selected = scheduler.selectNextPiece(peerBitfield, pieceStates, availability, priorities)
        assertEquals(2, selected)
    }

    @Test
    fun `test file boundary pieces inherit higher priority`() {
        // 2 files:
        // File 0: 20,000 bytes (offset 0 to 20,000) -> DO_NOT_DOWNLOAD
        // File 1: 30,000 bytes (offset 20,000 to 50,000) -> HIGH
        // Piece length: 16,384 bytes
        // Piece 0: 0..16384 (only File 0) -> DO_NOT_DOWNLOAD
        // Piece 1: 16384..32768 (spans File 0 and File 1) -> Must inherit HIGH from File 1!
        // Piece 2: 32768..49152 (only File 1) -> HIGH
        // Piece 3: 49152..50000 (only File 1) -> HIGH
        val files = listOf(
            TorrentFile(0, "skip.dat", 20000, 0, 20000),
            TorrentFile(1, "keep.dat", 30000, 20000, 50000)
        )
        val metadata = TorrentMetadata(
            torrentId = TorrentId(ByteArray(20)),
            name = "multi_test",
            pieceLength = 16384,
            piecesCount = 4,
            pieceHashes = List(4) { ByteArray(20) },
            totalLength = 50000,
            files = files,
            announceList = emptyList()
        )

        val pieceManager = PieceManager(metadata)
        pieceManager.setFilePriority(0, FilePriority.DO_NOT_DOWNLOAD)
        pieceManager.setFilePriority(1, FilePriority.HIGH)

        val computedPriorities = pieceManager.computePiecePriorities()
        assertEquals(FilePriority.DO_NOT_DOWNLOAD, computedPriorities[0])
        assertEquals(FilePriority.HIGH, computedPriorities[1]) // Boundary piece safely protected!
        assertEquals(FilePriority.HIGH, computedPriorities[2])
        assertEquals(FilePriority.HIGH, computedPriorities[3])
    }

    @Test
    fun `test end game mode activates when all pieces active and few blocks remain`() {
        val metadata = TorrentMetadata(
            torrentId = TorrentId(ByteArray(20)),
            name = "endgame_test",
            pieceLength = 32768,
            piecesCount = 1,
            pieceHashes = listOf(ByteArray(20)),
            totalLength = 32768,
            files = listOf(TorrentFile(0, "test.bin", 32768, 0, 32768)),
            announceList = emptyList()
        )
        val pieceManager = PieceManager(metadata)
        val peerBitfield = PeerBitfield(1).apply { setPiece(0, true) }

        // Request first block (starts downloading piece 0)
        val req1 = pieceManager.requestNextBlockForPeer(peerBitfield)
        assertNotNull(req1)
        assertEquals(0, req1!!.pieceIndex)
        assertEquals(0, req1.beginOffset)

        // Request second block
        val req2 = pieceManager.requestNextBlockForPeer(peerBitfield)
        assertNotNull(req2)
        assertEquals(16384, req2!!.beginOffset)

        // Now both blocks are requested, but none downloaded yet
        // In normal mode, no unrequested blocks exist
        // But since remainingBlocksCount is 2 (<= 20) and 0 pieces are MISSING, isEndGame is true!
        assertTrue(pieceManager.isEndGame())

        // In End-Game mode, another peer can request the already-requested pending blocks!
        val reqEndGame = pieceManager.requestNextBlockForPeer(peerBitfield)
        assertNotNull(reqEndGame)
    }

    @Test
    fun `test sequential mode selects pieces in strictly ascending order`() {
        val scheduler = RarestFirstPieceScheduler(isSequential = true)
        val peerBitfield = PeerBitfield(5).apply {
            for (i in 0 until 5) setPiece(i, true)
        }
        val availability = intArrayOf(10, 1, 8, 2, 4) // Rarest is piece 1, then 3
        val priorities = List(5) { FilePriority.NORMAL }

        // In sequential mode, piece 0 must be chosen first, NOT piece 1 (the rarest)
        val p0 = scheduler.selectNextPiece(peerBitfield, listOf(PieceState.MISSING, PieceState.MISSING, PieceState.MISSING, PieceState.MISSING, PieceState.MISSING), availability, priorities)
        assertEquals(0, p0)

        // When piece 0 is complete, next is 1
        val p1 = scheduler.selectNextPiece(peerBitfield, listOf(PieceState.COMPLETE, PieceState.MISSING, PieceState.MISSING, PieceState.MISSING, PieceState.MISSING), availability, priorities)
        assertEquals(1, p1)

        // When piece 1 is also complete, next is 2
        val p2 = scheduler.selectNextPiece(peerBitfield, listOf(PieceState.COMPLETE, PieceState.COMPLETE, PieceState.MISSING, PieceState.MISSING, PieceState.MISSING), availability, priorities)
        assertEquals(2, p2)
    }

    @Test
    fun `test availableVerifiedRange calculates contiguous range from file start`() {
        val files = listOf(
            TorrentFile(0, "movie_part1.mp4", 32768, 0, 32768),
            TorrentFile(1, "movie_part2.mp4", 32768, 32768, 65536)
        )
        val metadata = TorrentMetadata(
            torrentId = TorrentId(ByteArray(20)),
            name = "streaming_test",
            pieceLength = 16384,
            piecesCount = 4,
            pieceHashes = List(4) { ByteArray(20) },
            totalLength = 65536,
            files = files,
            announceList = emptyList()
        )
        val pieceManager = PieceManager(metadata)

        // Initially 0 bytes verified for both files
        assertEquals(0L, pieceManager.availableVerifiedRange(0))
        assertEquals(0L, pieceManager.availableVerifiedRange(1))

        // Complete piece 0
        pieceManager.markPieceCompleteFromStorage(0)
        assertEquals(16384L, pieceManager.availableVerifiedRange(0))
        assertEquals(0L, pieceManager.availableVerifiedRange(1))

        // Complete piece 1 -> File 0 is fully complete (32768 bytes)
        pieceManager.markPieceCompleteFromStorage(1)
        assertEquals(32768L, pieceManager.availableVerifiedRange(0))

        // If piece 3 is completed but piece 2 is MISSING, contiguous range for File 1 is still 0
        pieceManager.markPieceCompleteFromStorage(3)
        assertEquals(0L, pieceManager.availableVerifiedRange(1))

        // Once piece 2 is completed, File 1 is fully contiguous
        pieceManager.markPieceCompleteFromStorage(2)
        assertEquals(32768L, pieceManager.availableVerifiedRange(1))
    }

    @Test
    fun `test rarest-first scheduler handles zero or uninitialized availability correctly`() {
        val scheduler = RarestFirstPieceScheduler()
        val peerBitfield = PeerBitfield(3).apply {
            setPiece(0, true)
            setPiece(1, true)
            setPiece(2, true)
        }
        val pieceStates = listOf(PieceState.MISSING, PieceState.MISSING, PieceState.MISSING)
        // Piece 0 has 0 recorded availability, piece 1 has 1, piece 2 has 2
        val availability = intArrayOf(0, 1, 2)
        val priorities = listOf(FilePriority.NORMAL, FilePriority.NORMAL, FilePriority.NORMAL)

        // The scheduler should gracefully select an available piece without crashing or getting stuck
        val selected = scheduler.selectNextPiece(peerBitfield, pieceStates, availability, priorities)
        assertNotNull(selected)
        assertTrue(selected in 0..2)
    }
}
