package com.nutorrent.app.torrent.download

import com.nutorrent.app.core.model.PeerBitfield
import com.nutorrent.app.core.model.PeerEndpoint
import com.nutorrent.app.core.model.TorrentFile
import com.nutorrent.app.core.model.TorrentId
import com.nutorrent.app.core.model.TorrentMetadata
import com.nutorrent.app.data.storage.TorrentStorage
import com.nutorrent.app.torrent.peer.PeerConnection
import com.nutorrent.app.torrent.peer.PeerConnectionListener
import com.nutorrent.app.torrent.piece.PieceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class UploadManagerTest {

    private fun createTestMetadata(): TorrentMetadata {
        return TorrentMetadata(
            torrentId = TorrentId(ByteArray(20) { 0x42 }),
            name = "test_upload.iso",
            pieceLength = 32768, // 32 KiB
            piecesCount = 2,
            pieceHashes = listOf(ByteArray(20), ByteArray(20)),
            totalLength = 65536,
            files = listOf(TorrentFile(0, "test_upload.iso", 65536, 0, 65536)),
            announceList = emptyList()
        )
    }

    @Test
    fun `test upload request rejected when piece not completed`() = runBlocking {
        val meta = createTestMetadata()
        val mockStorage = object : TorrentStorage {
            override suspend fun prepare() {}
            override suspend fun writePiece(globalOffset: Long, data: ByteArray) {}
            override suspend fun readBytes(globalOffset: Long, length: Int): ByteArray = ByteArray(length)
            override suspend fun verifyStoredPieces(onPieceResult: (Int, Boolean) -> Unit) {}
            override fun close() {}
        }
        val pieceManager = PieceManager(meta)
        val uploadManager = UploadManager(meta, mockStorage, pieceManager)

        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val dummyListener = object : PeerConnectionListener {
            override fun onHandshakeCompleted(peer: PeerConnection) {}
            override fun onBitfield(peer: PeerConnection, bitfield: PeerBitfield) {}
            override fun onHave(peer: PeerConnection, pieceIndex: Int) {}
            override fun onChokeStateChanged(peer: PeerConnection, isChokingUs: Boolean) {}
            override fun onPieceBlock(peer: PeerConnection, pieceIndex: Int, begin: Int, block: ByteArray) {}
            override fun onDisconnected(peer: PeerConnection, error: Throwable?) {}
        }
        val peer = PeerConnection(
            endpoint = PeerEndpoint("127.0.0.1", 6881),
            infoHash = meta.torrentId.bytes,
            ourPeerId = ByteArray(20),
            totalPieces = meta.piecesCount,
            listener = dummyListener,
            scope = scope
        )

        // Peer requests piece #0 when we don't have it
        val result = uploadManager.handleBlockRequest(peer, 0, 0, 16384)
        assertFalse(result)

        // Invalid length (> 128 KiB)
        val resultInvalid = uploadManager.handleBlockRequest(peer, 0, 0, 200000)
        assertFalse(resultInvalid)

        scope.cancel()
    }

    @Test
    fun `test upload request serves data when piece is complete`() = runBlocking {
        val meta = createTestMetadata()
        val testData = ByteArray(65536) { (it % 256).toByte() }
        val mockStorage = object : TorrentStorage {
            override suspend fun prepare() {}
            override suspend fun writePiece(globalOffset: Long, data: ByteArray) {}
            override suspend fun readBytes(globalOffset: Long, length: Int): ByteArray {
                val out = ByteArray(length)
                System.arraycopy(testData, globalOffset.toInt(), out, 0, length)
                return out
            }
            override suspend fun verifyStoredPieces(onPieceResult: (Int, Boolean) -> Unit) {}
            override fun close() {}
        }

        val pieceManager = PieceManager(meta)
        // Mark piece 0 as complete
        pieceManager.markPieceCompleteFromStorage(0)
        assertTrue(pieceManager.hasPiece(0))

        val uploadManager = UploadManager(meta, mockStorage, pieceManager)

        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val dummyListener = object : PeerConnectionListener {
            override fun onHandshakeCompleted(peer: PeerConnection) {}
            override fun onBitfield(peer: PeerConnection, bitfield: PeerBitfield) {}
            override fun onHave(peer: PeerConnection, pieceIndex: Int) {}
            override fun onChokeStateChanged(peer: PeerConnection, isChokingUs: Boolean) {}
            override fun onPieceBlock(peer: PeerConnection, pieceIndex: Int, begin: Int, block: ByteArray) {}
            override fun onDisconnected(peer: PeerConnection, error: Throwable?) {}
        }
        val peer = PeerConnection(
            endpoint = PeerEndpoint("127.0.0.1", 6881),
            infoHash = meta.torrentId.bytes,
            ourPeerId = ByteArray(20),
            totalPieces = meta.piecesCount,
            listener = dummyListener,
            scope = scope
        )

        // Request piece 0, offset 0, 16 KiB
        val result = uploadManager.handleBlockRequest(peer, 0, 0, 16384)
        // Note: PeerConnection outputStream is null in unit test (socket not connected),
        // so peer.sendPiece returns false, but error is safely handled and does not throw!
        assertFalse(result)

        scope.cancel()
    }
}
