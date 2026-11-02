package com.nutorrent.torrent.download

import com.nutorrent.core.model.TorrentFile
import com.nutorrent.core.model.TorrentId
import com.nutorrent.core.model.TorrentMetadata
import com.nutorrent.core.model.TorrentState
import com.nutorrent.data.storage.FileTorrentStorage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest

@OptIn(ExperimentalCoroutinesApi::class)
class TorrentSessionRecheckTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `test force recheck discovers valid pieces and updates piece manager`() = runTest {
        val baseDir = tempFolder.newFolder("recheck_test")
        val pieceLen = 16384
        val piece0Data = ByteArray(pieceLen) { 1.toByte() }
        val piece1Data = ByteArray(pieceLen) { 2.toByte() }
        val sha0 = MessageDigest.getInstance("SHA-1").digest(piece0Data)
        val sha1 = MessageDigest.getInstance("SHA-1").digest(piece1Data)

        val meta = TorrentMetadata(
            torrentId = TorrentId(ByteArray(20)),
            name = "test_recheck.bin",
            pieceLength = pieceLen.toLong(),
            totalLength = (pieceLen * 2).toLong(),
            piecesCount = 2,
            pieceHashes = listOf(sha0, sha1),
            files = listOf(TorrentFile(0, "test_recheck.bin", (pieceLen * 2).toLong(), 0, (pieceLen * 2).toLong())),
            announceList = emptyList()
        )

        val storage = FileTorrentStorage(baseDir, meta)
        storage.prepare()
        // Write piece 0 to disk
        storage.writePiece(0L, piece0Data)

        val mockTracker = object : com.nutorrent.torrent.tracker.TrackerClient {
            override suspend fun announce(request: com.nutorrent.torrent.tracker.TrackerAnnounceRequest): com.nutorrent.torrent.tracker.TrackerAnnounceResponse {
                return com.nutorrent.torrent.tracker.TrackerAnnounceResponse(1800, null, 0, 0, emptyList())
            }
        }

        val session = TorrentSession(
            metadata = meta,
            storage = storage,
            trackerClient = mockTracker,
            scope = this
        )

        // Force recheck
        val job = session.forceRecheck()
        job.join()

        // Piece 0 must be complete, piece 1 missing
        assertTrue(session.pieceManager.hasPiece(0))
        assertFalse(session.pieceManager.hasPiece(1))
        assertEquals(1, session.pieceManager.completedPiecesCount)
        assertEquals(pieceLen.toLong(), session.pieceManager.verifiedBytes)

        // Now write piece 1 to disk as well
        storage.writePiece(pieceLen.toLong(), piece1Data)

        // Recheck again
        val job2 = session.forceRecheck()
        job2.join()

        // Both pieces now verified -> torrent becomes SEEDING
        assertTrue(session.pieceManager.hasPiece(0))
        assertTrue(session.pieceManager.hasPiece(1))
        assertEquals(2, session.pieceManager.completedPiecesCount)
        assertEquals(TorrentState.SEEDING, session.state.value)

        session.stop()
    }

    @Test
    fun `test force recheck demotes previously verified pieces if file is truncated or corrupted on disk`() = runTest {
        val baseDir = tempFolder.newFolder("corrupt_recheck_test")
        val pieceLen = 16384
        val piece0Data = ByteArray(pieceLen) { 7.toByte() }
        val sha0 = MessageDigest.getInstance("SHA-1").digest(piece0Data)

        val meta = TorrentMetadata(
            torrentId = TorrentId(ByteArray(20) { 5 }),
            name = "test_corrupt.bin",
            pieceLength = pieceLen.toLong(),
            totalLength = pieceLen.toLong(),
            piecesCount = 1,
            pieceHashes = listOf(sha0),
            files = listOf(TorrentFile(0, "test_corrupt.bin", pieceLen.toLong(), 0, pieceLen.toLong())),
            announceList = emptyList()
        )

        val storage = FileTorrentStorage(baseDir, meta)
        storage.prepare()
        storage.writePiece(0L, piece0Data)

        val mockTracker = object : com.nutorrent.torrent.tracker.TrackerClient {
            override suspend fun announce(request: com.nutorrent.torrent.tracker.TrackerAnnounceRequest): com.nutorrent.torrent.tracker.TrackerAnnounceResponse {
                return com.nutorrent.torrent.tracker.TrackerAnnounceResponse(1800, null, 0, 0, emptyList())
            }
        }

        val session = TorrentSession(
            metadata = meta,
            storage = storage,
            trackerClient = mockTracker,
            scope = this
        )

        // 1. First recheck: piece 0 is valid and verified
        val job1 = session.forceRecheck()
        job1.join()
        assertTrue(session.pieceManager.hasPiece(0))
        assertEquals(1, session.pieceManager.completedPiecesCount)
        assertEquals(TorrentState.SEEDING, session.state.value)

        // 2. Corrupt the data on disk (overwrite with garbage)
        val targetFile = java.io.File(baseDir, "test_corrupt.bin")
        targetFile.writeBytes(ByteArray(pieceLen) { 99.toByte() })

        // 3. Second recheck: piece 0 fails hash check and is downgraded
        val job2 = session.forceRecheck()
        job2.join()

        assertFalse("Corrupted piece must not be marked complete", session.pieceManager.hasPiece(0))
        assertEquals(0, session.pieceManager.completedPiecesCount)
        assertEquals(0L, session.pieceManager.verifiedBytes)
        assertNotEquals("Corrupted torrent must not remain in SEEDING state", TorrentState.SEEDING, session.state.value)

        session.stop()
    }
}
