package com.nutorrent.torrent.download

import com.nutorrent.core.model.AppError
import com.nutorrent.core.model.ErrorAction
import com.nutorrent.core.model.TorrentFile
import com.nutorrent.core.model.TorrentId
import com.nutorrent.core.model.TorrentMetadata
import com.nutorrent.core.model.TorrentState
import com.nutorrent.data.storage.TorrentStorage
import com.nutorrent.torrent.tracker.TrackerAnnounceRequest
import com.nutorrent.torrent.tracker.TrackerAnnounceResponse
import com.nutorrent.torrent.tracker.TrackerClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.security.MessageDigest

@OptIn(ExperimentalCoroutinesApi::class)
class TorrentSessionErrorTest {

    private fun createMetadata(pieceLength: Int, piecesCount: Int): TorrentMetadata {
        val totalLen = (pieceLength * piecesCount).toLong()
        val dummyHashes = (0 until piecesCount).map {
            MessageDigest.getInstance("SHA-1").digest(ByteArray(pieceLength) { it.toByte() })
        }
        return TorrentMetadata(
            torrentId = TorrentId(ByteArray(20) { 1 }),
            name = "test_error_handling.iso",
            pieceLength = pieceLength.toLong(),
            totalLength = totalLen,
            piecesCount = piecesCount,
            pieceHashes = dummyHashes,
            files = listOf(TorrentFile(0, "test_error_handling.iso", totalLen, 0, totalLen)),
            announceList = emptyList()
        )
    }

    @Test
    fun `test force recheck storage failure transitions to ERROR state with AppError`() = runTest {
        val meta = createMetadata(16384, 2)

        val failingStorage = object : TorrentStorage {
            override suspend fun prepare() {}
            override suspend fun writePiece(globalOffset: Long, data: ByteArray) {}
            override suspend fun readBytes(globalOffset: Long, length: Int): ByteArray = ByteArray(length)
            override suspend fun verifyStoredPieces(onPieceResult: (Int, Boolean) -> Unit) {
                throw AppError.DestinationUnavailable("content://fake/path", "Folder unmounted")
            }
            override fun getAvailableSpace(): Long? = 0L
            override fun close() {}
        }

        val mockTracker = object : TrackerClient {
            override suspend fun announce(request: TrackerAnnounceRequest): TrackerAnnounceResponse {
                return TrackerAnnounceResponse(1800, null, 0, 0, emptyList())
            }
        }

        val session = TorrentSession(
            metadata = meta,
            storage = failingStorage,
            trackerClient = mockTracker,
            scope = this
        )

        val job = session.forceRecheck()
        job.join()

        assertEquals(TorrentState.ERROR, session.state.value)
        val error = session.lastError.value
        assertNotNull("lastError must be populated on failure", error)
        assertTrue("Error should be an AppError", error is AppError)
        assertEquals(ErrorAction.CHOOSE_LOCATION, error?.action)

        session.stop()
    }

    @Test
    fun `test resume clears lastError and transitions out of ERROR state`() = runTest {
        val meta = createMetadata(16384, 1)

        val storage = object : TorrentStorage {
            override suspend fun prepare() {}
            override suspend fun writePiece(globalOffset: Long, data: ByteArray) {}
            override suspend fun readBytes(globalOffset: Long, length: Int): ByteArray = ByteArray(length)
            override suspend fun verifyStoredPieces(onPieceResult: (Int, Boolean) -> Unit) {
                throw IOException("Temporary I/O error")
            }
            override fun getAvailableSpace(): Long? = 100_000_000L
            override fun close() {}
        }

        val mockTracker = object : TrackerClient {
            override suspend fun announce(request: TrackerAnnounceRequest): TrackerAnnounceResponse {
                return TrackerAnnounceResponse(1800, null, 0, 0, emptyList())
            }
        }

        val session = TorrentSession(
            metadata = meta,
            storage = storage,
            trackerClient = mockTracker,
            scope = backgroundScope
        )

        session.forceRecheck().join()
        assertEquals(TorrentState.ERROR, session.state.value)
        assertNotNull(session.lastError.value)

        // Resuming must clear error and transition state
        session.resume()
        assertNull("Resuming should clear lastError", session.lastError.value)
        assertNotEquals(TorrentState.ERROR, session.state.value)

        session.stop()
    }

    @Test
    fun `test tracker seeds count updates availableSeeds metric in TorrentProgress`() = runTest {
        val baseMeta = createMetadata(16384, 2)
        val meta = baseMeta.copy(announceList = listOf("http://tracker.example.com:80/announce"))

        val storage = object : TorrentStorage {
            override suspend fun prepare() {}
            override suspend fun writePiece(globalOffset: Long, data: ByteArray) {}
            override suspend fun readBytes(globalOffset: Long, length: Int): ByteArray = ByteArray(length)
            override suspend fun verifyStoredPieces(onPieceResult: (Int, Boolean) -> Unit) {
                onPieceResult(0, false)
                onPieceResult(1, false)
            }
            override fun getAvailableSpace(): Long? = 500_000_000L
            override fun close() {}
        }

        val mockTracker = object : TrackerClient {
            override suspend fun announce(request: TrackerAnnounceRequest): TrackerAnnounceResponse {
                return TrackerAnnounceResponse(
                    intervalSeconds = 1800,
                    minIntervalSeconds = null,
                    seeders = 48,
                    leechers = 10,
                    peers = emptyList()
                )
            }
        }

        val session = TorrentSession(
            metadata = meta,
            storage = storage,
            trackerClient = mockTracker,
            scope = this
        )

        // Announce to tracker and await completion
        session.announceToTrackers(com.nutorrent.torrent.tracker.TrackerEvent.STARTED)

        assertEquals(48, session.trackerReportedSeeds)

        session.stop()
    }
}
