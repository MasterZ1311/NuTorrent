package com.nutorrent.data.storage

import com.nutorrent.core.model.TorrentFile
import com.nutorrent.core.model.TorrentId
import com.nutorrent.core.model.TorrentMetadata
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class FileTorrentStorageTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `test single file write and read`() = runBlocking {
        val baseDir = tempFolder.newFolder("single_file_test")
        val pieceLen = 16384
        val data = ByteArray(pieceLen) { (it % 256).toByte() }
        val sha1 = MessageDigest.getInstance("SHA-1").digest(data)

        val meta = TorrentMetadata(
            torrentId = TorrentId(ByteArray(20)),
            name = "file.iso",
            pieceLength = pieceLen.toLong(),
            totalLength = pieceLen.toLong(),
            piecesCount = 1,
            pieceHashes = listOf(sha1),
            files = listOf(TorrentFile(0, "file.iso", pieceLen.toLong(), 0, pieceLen.toLong())),
            announceList = emptyList()
        )

        val storage = FileTorrentStorage(baseDir, meta)
        storage.prepare()
        storage.writePiece(0L, data)

        val readBack = storage.readBytes(0L, pieceLen)
        assertArrayEquals(data, readBack)

        var verified = false
        storage.verifyStoredPieces { idx, valid ->
            if (idx == 0 && valid) verified = true
        }
        assertTrue(verified)
        storage.close()
    }

    @Test
    fun `test piece crossing file boundaries in multi-file torrent`() = runBlocking {
        val baseDir = tempFolder.newFolder("multi_file_test")

        // Total 2 files:
        // File 1: 10,000 bytes
        // File 2: 15,000 bytes
        // Total size = 25,000 bytes
        // Piece length = 16,384 bytes
        // Piece 0 spans: [0..16384) -> File 1: [0..10000), File 2: [0..6384)
        // Piece 1 spans: [16384..25000) -> File 2: [6384..15000)
        val file1Len = 10000L
        val file2Len = 15000L
        val totalLen = 25000L
        val pieceLen = 16384L

        val fullData = ByteArray(totalLen.toInt()) { (it % 256).toByte() }
        val piece0Data = fullData.copyOfRange(0, 16384)
        val piece1Data = fullData.copyOfRange(16384, 25000)

        val sha1 = MessageDigest.getInstance("SHA-1")
        val hash0 = sha1.digest(piece0Data)
        sha1.reset()
        val hash1 = sha1.digest(piece1Data)

        val files = listOf(
            TorrentFile(0, "dir" + File.separator + "f1.bin", file1Len, 0, file1Len),
            TorrentFile(1, "dir" + File.separator + "f2.bin", file2Len, file1Len, totalLen)
        )

        val meta = TorrentMetadata(
            torrentId = TorrentId(ByteArray(20)),
            name = "dir",
            pieceLength = pieceLen,
            totalLength = totalLen,
            piecesCount = 2,
            pieceHashes = listOf(hash0, hash1),
            files = files,
            announceList = emptyList()
        )

        val storage = FileTorrentStorage(baseDir, meta)
        storage.prepare()

        // Write piece 0 (crosses boundary!)
        storage.writePiece(0L, piece0Data)
        // Write piece 1
        storage.writePiece(16384L, piece1Data)

        // Verify individual file sizes on disk
        val f1 = File(baseDir, files[0].relativePath)
        val f2 = File(baseDir, files[1].relativePath)
        assertEquals(file1Len, f1.length())
        assertEquals(file2Len, f2.length())

        // Read entire torrent content back across files
        val readBack = storage.readBytes(0L, totalLen.toInt())
        assertArrayEquals(fullData, readBack)

        var completedPieces = 0
        storage.verifyStoredPieces { _, valid ->
            if (valid) completedPieces++
        }
        assertEquals(2, completedPieces)

        storage.close()
    }

    @Test
    fun `test fast recheck reports progress and verifies partially downloaded files`() = runBlocking {
        val baseDir = tempFolder.newFolder("fast_recheck_test")
        val pieceLen = 16384
        val totalPieces = 4
        val totalLen = (pieceLen * totalPieces).toLong()

        val piecesData = List(totalPieces) { idx ->
            ByteArray(pieceLen) { ((idx * 31 + it) % 256).toByte() }
        }
        val sha1 = MessageDigest.getInstance("SHA-1")
        val pieceHashes = piecesData.map { data ->
            sha1.reset()
            sha1.digest(data)
        }

        val meta = TorrentMetadata(
            torrentId = TorrentId(ByteArray(20)),
            name = "recheck_test.bin",
            pieceLength = pieceLen.toLong(),
            totalLength = totalLen,
            piecesCount = totalPieces,
            pieceHashes = pieceHashes,
            files = listOf(TorrentFile(0, "recheck_test.bin", totalLen, 0, totalLen)),
            announceList = emptyList()
        )

        val storage = FileTorrentStorage(baseDir, meta)
        storage.prepare()

        // Only write piece 1 and piece 3
        storage.writePiece(pieceLen.toLong(), piecesData[1])
        storage.writePiece((pieceLen * 3).toLong(), piecesData[3])

        val progressUpdates = mutableListOf<Float>()
        val verifiedPieces = mutableMapOf<Int, Boolean>()

        storage.verifyStoredPieces(
            onProgress = { checked, total, pct ->
                assertEquals(totalPieces, total)
                progressUpdates.add(pct)
            },
            onPieceResult = { idx, valid ->
                verifiedPieces[idx] = valid
            }
        )

        assertEquals(totalPieces, progressUpdates.size)
        assertEquals(100f, progressUpdates.last(), 0.01f)

        assertFalse(verifiedPieces[0] ?: true)
        assertTrue(verifiedPieces[1] ?: false)
        assertFalse(verifiedPieces[2] ?: true)
        assertTrue(verifiedPieces[3] ?: false)

        storage.close()
    }

    @Test
    fun `test storage space validation throws StorageFull when space is insufficient`() = runBlocking {
        val baseDir = tempFolder.newFolder("storage_space_test")
        // Require an absurdly huge size (exceeding any physical disk)
        val hugeLength = Long.MAX_VALUE - 1000L

        val meta = TorrentMetadata(
            torrentId = TorrentId(ByteArray(20)),
            name = "huge.bin",
            pieceLength = 16384L,
            totalLength = hugeLength,
            piecesCount = 1,
            pieceHashes = listOf(ByteArray(20)),
            files = listOf(TorrentFile(0, "huge.bin", hugeLength, 0, hugeLength)),
            announceList = emptyList()
        )

        val storage = FileTorrentStorage(baseDir, meta)
        try {
            storage.prepare()
            fail("Expected AppError.StorageFull to be thrown")
        } catch (e: com.nutorrent.core.model.AppError.StorageFull) {
            assertTrue(e.message?.contains("Insufficient storage space") == true)
        } finally {
            storage.close()
        }
    }

    @Test
    fun `test path traversal attempts throw StorageError`() = runBlocking {
        val baseDir = tempFolder.newFolder("path_traversal_test")
        val meta = TorrentMetadata(
            torrentId = TorrentId(ByteArray(20)),
            name = "traversal_test",
            pieceLength = 16384L,
            totalLength = 100L,
            piecesCount = 1,
            pieceHashes = listOf(ByteArray(20)),
            files = listOf(TorrentFile(0, ".." + File.separator + "escape.bin", 100L, 0, 100L)),
            announceList = emptyList()
        )

        val storage = FileTorrentStorage(baseDir, meta)
        try {
            storage.prepare()
            fail("Expected AppError.StorageError for path traversal attempt")
        } catch (e: com.nutorrent.core.model.AppError.StorageSecurityError) {
            assertTrue(e.message?.contains("Path traversal rejected") == true)
        } finally {
            storage.close()
        }
    }
}
