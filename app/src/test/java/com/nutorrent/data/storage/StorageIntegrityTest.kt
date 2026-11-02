package com.nutorrent.data.storage

import com.nutorrent.core.model.AppError
import com.nutorrent.core.model.TorrentFile
import com.nutorrent.core.model.TorrentId
import com.nutorrent.core.model.TorrentMetadata
import com.nutorrent.core.util.FileOpener
import com.nutorrent.torrent.bencode.BencodeElement
import com.nutorrent.torrent.bencode.BencodeEncoder
import com.nutorrent.torrent.metainfo.MetainfoParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

class StorageIntegrityTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `test deletion safety never deletes root download directory when name is blank or dot`() {
        val rootDir = tempFolder.newFolder("downloads_root")
        val importantUserFile = File(rootDir, "my_important_photo.jpg").apply {
            writeBytes("photo_bytes".toByteArray())
        }

        val testNames = listOf("", "   ", ".", "..", "./", "../", "subdir/../../escaping")

        for (unsafeName in testNames) {
            val name = unsafeName.trim()
            var deleted = false
            if (name.isNotEmpty() && name != "." && name != ".." && !name.contains("/") && !name.contains("\\")) {
                val dir = rootDir.canonicalFile
                val targetFile = File(dir, name).canonicalFile
                val dirPath = dir.path.let { if (it.endsWith(File.separator)) it else it + File.separator }
                if (targetFile != dir && targetFile.path.startsWith(dirPath)) {
                    if (targetFile.exists()) {
                        targetFile.deleteRecursively()
                        deleted = true
                    }
                }
            }
            assertFalse("Unsafe name '$unsafeName' must not trigger deletion", deleted)
            assertTrue("Root directory must remain intact", rootDir.exists())
            assertTrue("User files in root must never be wiped", importantUserFile.exists())
        }
    }

    @Test
    fun `test deletion safely deletes valid torrent files or directories without escaping root`() {
        val rootDir = tempFolder.newFolder("downloads_root_valid")
        val torrentFolder = File(rootDir, "ValidMovie2026").apply { mkdirs() }
        val torrentFile = File(torrentFolder, "movie.mp4").apply { writeBytes("video".toByteArray()) }
        val siblingFolder = File(rootDir, "UnrelatedFolder").apply { mkdirs() }

        val name = "ValidMovie2026"
        val dir = rootDir.canonicalFile
        val targetFile = File(dir, name).canonicalFile
        val dirPath = dir.path.let { if (it.endsWith(File.separator)) it else it + File.separator }
        if (targetFile != dir && targetFile.path.startsWith(dirPath)) {
            if (targetFile.exists()) {
                targetFile.deleteRecursively()
            }
        }

        assertFalse("Target torrent directory should be deleted", torrentFolder.exists())
        assertTrue("Parent downloads directory must remain intact", rootDir.exists())
        assertTrue("Sibling folder must remain untouched", siblingFolder.exists())
    }

    @Test
    fun `test TorrentFile invariant requirements reject negative offsets and inconsistent endByte`() {
        // Negative index
        assertThrows(IllegalArgumentException::class.java) {
            TorrentFile(index = -1, relativePath = "file.txt", length = 100, startByte = 0, endByte = 100)
        }

        // Negative length
        assertThrows(IllegalArgumentException::class.java) {
            TorrentFile(index = 0, relativePath = "file.txt", length = -5, startByte = 0, endByte = -5)
        }

        // Negative startByte
        assertThrows(IllegalArgumentException::class.java) {
            TorrentFile(index = 0, relativePath = "file.txt", length = 100, startByte = -10, endByte = 90)
        }

        // endByte before startByte
        assertThrows(IllegalArgumentException::class.java) {
            TorrentFile(index = 0, relativePath = "file.txt", length = 100, startByte = 100, endByte = 50)
        }

        // Inconsistent endByte != startByte + length
        assertThrows(IllegalArgumentException::class.java) {
            TorrentFile(index = 0, relativePath = "file.txt", length = 100, startByte = 0, endByte = 200)
        }

        // Valid file succeeds
        val validFile = TorrentFile(index = 0, relativePath = "file.txt", length = 100, startByte = 0, endByte = 100)
        assertEquals(0L, validFile.startByte)
        assertEquals(100L, validFile.endByte)
    }

    @Test
    fun `test MetainfoParser rejects multi-file totalLength overflow`() {
        val rootDict = mutableMapOf<String, BencodeElement>()
        rootDict["announce"] = BencodeElement.BString("http://tracker.example.com/announce".toByteArray())

        val infoDict = mutableMapOf<String, BencodeElement>()
        infoDict["name"] = BencodeElement.BString("overflow_torrent".toByteArray())
        infoDict["piece length"] = BencodeElement.BInteger(16384L)
        infoDict["pieces"] = BencodeElement.BString(ByteArray(20))

        val file1 = mutableMapOf<String, BencodeElement>()
        file1["length"] = BencodeElement.BInteger(Long.MAX_VALUE - 1000L)
        file1["path"] = BencodeElement.BList(listOf(BencodeElement.BString("file1.bin".toByteArray())))

        val file2 = mutableMapOf<String, BencodeElement>()
        file2["length"] = BencodeElement.BInteger(5000L) // Overflows Long.MAX_VALUE
        file2["path"] = BencodeElement.BList(listOf(BencodeElement.BString("file2.bin".toByteArray())))

        infoDict["files"] = BencodeElement.BList(listOf(BencodeElement.BDictionary(file1), BencodeElement.BDictionary(file2)))
        rootDict["info"] = BencodeElement.BDictionary(infoDict)

        val torrentBytes = BencodeEncoder.encode(BencodeElement.BDictionary(rootDict))

        val ex = assertThrows(AppError.InvalidTorrent::class.java) {
            MetainfoParser.parse(torrentBytes)
        }
        assertTrue("Error must mention overflow", ex.message?.contains("overflow", ignoreCase = true) == true)
    }

    @Test
    fun `test FileTorrentStorage periodic space check throws InsufficientStorage when space drops below safety margin`() = runBlocking {
        val baseDir = tempFolder.newFolder("space_check_test")
        val pieceLen = 1024 * 1024 // 1 MB piece
        val pieceData = ByteArray(pieceLen) { 42.toByte() }
        val sha = MessageDigest.getInstance("SHA-1").digest(pieceData)

        val meta = TorrentMetadata(
            torrentId = TorrentId(ByteArray(20) { 8 }),
            name = "test_space.bin",
            pieceLength = pieceLen.toLong(),
            totalLength = (pieceLen * 2).toLong(),
            piecesCount = 2,
            pieceHashes = listOf(sha, sha),
            files = listOf(TorrentFile(0, "test_space.bin", (pieceLen * 2).toLong(), 0, (pieceLen * 2).toLong())),
            announceList = emptyList()
        )

        // Subclass FileTorrentStorage to simulate dynamic disk space exhaustion mid-download
        var simulatedAvailableSpace = 50 * 1024 * 1024L // 50 MB initial
        val storage = object : FileTorrentStorage(baseDir, meta) {
            override fun getAvailableSpace(): Long = simulatedAvailableSpace
        }

        storage.prepare()

        // Write piece 1 (1 MB) -> space check passes
        storage.writePiece(0L, pieceData)

        // Now simulate disk drops to 5 MB free (below MIN_FREE_SPACE_BYTES of 15 MB)
        simulatedAvailableSpace = 5 * 1024 * 1024L

        val err = assertThrows(AppError.InsufficientStorage::class.java) {
            runBlocking {
                storage.writePiece(pieceLen.toLong(), pieceData)
            }
        }
        assertEquals(5 * 1024 * 1024L, err.availableBytes)
        assertEquals(10 * 1024 * 1024L, err.shortfallBytes)
        storage.close()
    }

    @Test
    fun `test FileTorrentStorage prepare throws StorageFull when needed space exceeds available`() = runBlocking {
        val baseDir = tempFolder.newFolder("storage_full_test")
        val totalSize = 500 * 1024 * 1024L // 500 MB

        val meta = TorrentMetadata(
            torrentId = TorrentId(ByteArray(20) { 3 }),
            name = "huge.bin",
            pieceLength = 16384L,
            totalLength = totalSize,
            piecesCount = 1,
            pieceHashes = listOf(ByteArray(20)),
            files = listOf(TorrentFile(0, "huge.bin", totalSize, 0, totalSize)),
            announceList = emptyList()
        )

        val storage = object : FileTorrentStorage(baseDir, meta) {
            override fun getAvailableSpace(): Long = 100 * 1024 * 1024L // Only 100 MB available
        }

        val err = assertThrows(AppError.StorageFull::class.java) {
            runBlocking {
                storage.prepare()
            }
        }
        assertEquals(totalSize, err.requiredBytes)
        assertEquals(100 * 1024 * 1024L, err.availableBytes)
        storage.close()
    }
}
