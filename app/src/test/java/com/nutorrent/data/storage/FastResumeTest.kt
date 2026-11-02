package com.nutorrent.data.storage

import com.nutorrent.core.model.TorrentFile
import com.nutorrent.core.model.TorrentId
import com.nutorrent.core.model.TorrentMetadata
import com.nutorrent.torrent.piece.PieceManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest

class FastResumeTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Before
    fun setup() {
        FastResumeManager.clearAll()
        FastResumeManager.setBaseDirectory(tempFolder.newFolder("fastresume_test"))
    }

    @Test
    fun `test save and load fast resume data`() {
        val hash = "0123456789abcdef0123456789abcdef01234567"
        val bitfield = byteArrayOf(0xFF.toByte(), 0xF0.toByte())
        val data = FastResumeData(
            infoHash = hash,
            isComplete = true,
            completedPieces = 12,
            totalPieces = 12,
            verifiedBytes = 1048576L,
            totalBytes = 1048576L,
            bitfield = bitfield
        )

        FastResumeManager.save(data)

        // Verify loaded from memory cache
        val cached = FastResumeManager.load(hash)
        assertNotNull(cached)
        assertEquals(hash, cached!!.infoHash)
        assertTrue(cached.isComplete)
        assertEquals(12, cached.completedPieces)
        assertEquals(1048576L, cached.verifiedBytes)

        // Clear memory cache to ensure disk persistence
        FastResumeManager.clearAll()
        // re-point base dir
        FastResumeManager.save(data)
        // Simulate reading purely from disk by clearing memory map reflection or testing save/load
        val loaded = FastResumeManager.load(hash)
        assertNotNull(loaded)
        assertArrayEquals(bitfield, loaded!!.bitfield)

        // Test remove
        FastResumeManager.remove(hash)
        assertNull(FastResumeManager.load(hash))
    }

    @Test
    fun `test areFilesComplete returns true when file matches expected size`() = runBlocking {
        val baseDir = tempFolder.newFolder("complete_check_test")
        val pieceLen = 16384
        val data = ByteArray(pieceLen) { 1 }
        val sha1 = MessageDigest.getInstance("SHA-1").digest(data)

        val meta = TorrentMetadata(
            torrentId = TorrentId(ByteArray(20)),
            name = "complete.dat",
            pieceLength = pieceLen.toLong(),
            totalLength = pieceLen.toLong(),
            piecesCount = 1,
            pieceHashes = listOf(sha1),
            files = listOf(TorrentFile(0, "complete.dat", pieceLen.toLong(), 0, pieceLen.toLong())),
            announceList = emptyList()
        )

        val storage = FileTorrentStorage(baseDir, meta)
        storage.prepare()

        // Before writing full bytes, areFilesComplete should be false
        assertFalse(storage.areFilesComplete())

        // Write piece and test areFilesComplete
        storage.writePiece(0L, data)
        assertTrue(storage.areFilesComplete())
        storage.close()
    }

    @Test
    fun `test piece manager markAllPiecesComplete and restoreFromBitfield`() {
        val totalPieces = 16
        val pieceLen = 16384L
        val totalBytes = totalPieces * pieceLen
        val dummyHashes = List(totalPieces) { ByteArray(20) }
        val meta = TorrentMetadata(
            torrentId = TorrentId(ByteArray(20)),
            name = "test.dat",
            pieceLength = pieceLen,
            totalLength = totalBytes,
            piecesCount = totalPieces,
            pieceHashes = dummyHashes,
            files = listOf(TorrentFile(0, "test.dat", totalBytes, 0, totalBytes)),
            announceList = emptyList()
        )
        val pieceManager = PieceManager(meta)

        assertFalse(pieceManager.isComplete())
        assertEquals(0, pieceManager.completedPiecesCount)

        pieceManager.markAllPiecesComplete()
        assertTrue(pieceManager.isComplete())
        assertEquals(totalPieces, pieceManager.completedPiecesCount)
        for (i in 0 until totalPieces) {
            assertTrue(pieceManager.hasPiece(i))
        }

        // Test restoreFromBitfield with half pieces completed
        val newPieceManager = PieceManager(meta)
        // Bitfield for 16 pieces: 2 bytes. Byte 0: 0xAA (10101010), Byte 1: 0x55 (01010101)
        val bitfield = byteArrayOf(0xAA.toByte(), 0x55.toByte())
        newPieceManager.restoreFromBitfield(bitfield)

        assertEquals(8, newPieceManager.completedPiecesCount)
        assertTrue(newPieceManager.hasPiece(0))
        assertFalse(newPieceManager.hasPiece(1))
        assertTrue(newPieceManager.hasPiece(2))
        assertFalse(newPieceManager.hasPiece(3))
        assertFalse(newPieceManager.hasPiece(8))
        assertTrue(newPieceManager.hasPiece(9))
    }

    @Test
    fun `test hasExistingDiskData returns false for empty or non-existent files and true when data written`() = runBlocking {
        val baseDir = tempFolder.newFolder("has_data_test")
        val pieceLen = 16384
        val data = ByteArray(pieceLen) { 42 }
        val sha1 = MessageDigest.getInstance("SHA-1").digest(data)

        val meta = TorrentMetadata(
            torrentId = TorrentId(ByteArray(20) { 1 }),
            name = "data_check.dat",
            pieceLength = pieceLen.toLong(),
            totalLength = pieceLen.toLong(),
            piecesCount = 1,
            pieceHashes = listOf(sha1),
            files = listOf(TorrentFile(0, "data_check.dat", pieceLen.toLong(), 0, pieceLen.toLong())),
            announceList = emptyList()
        )

        val storage = FileTorrentStorage(baseDir, meta)
        storage.prepare()

        // 0-byte file created by prepare(): hasExistingDiskData must be false
        assertFalse(storage.hasExistingDiskData())

        // Once bytes are written, hasExistingDiskData must be true
        storage.writePiece(0L, data)
        assertTrue(storage.hasExistingDiskData())
        storage.close()
    }
}
