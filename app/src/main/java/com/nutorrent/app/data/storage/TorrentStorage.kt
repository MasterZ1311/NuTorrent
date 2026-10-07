package com.nutorrent.app.data.storage

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.nutorrent.app.core.model.AppError
import com.nutorrent.app.core.model.TorrentMetadata
import com.nutorrent.app.core.util.Lg
import com.nutorrent.app.core.util.LogCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

interface TorrentStorage {
    suspend fun prepare()
    suspend fun writePiece(globalOffset: Long, data: ByteArray)
    suspend fun readBytes(globalOffset: Long, length: Int): ByteArray
    suspend fun verifyStoredPieces(onPieceResult: (pieceIndex: Int, isComplete: Boolean) -> Unit)
    suspend fun verifyStoredPieces(
        onProgress: ((checkedPieces: Int, totalPieces: Int, percent: Float) -> Unit)?,
        onPieceResult: (pieceIndex: Int, isComplete: Boolean) -> Unit
    ) {
        verifyStoredPieces(onPieceResult)
    }
    fun close()
}

/**
 * File-based random-access storage engine supporting single-file and multi-file torrents.
 * Correctly handles pieces spanning across file boundaries.
 */
@android.annotation.SuppressLint("UsableSpace")
class FileTorrentStorage(
    val baseDir: File,
    val metadata: TorrentMetadata
) : TorrentStorage {

    private val openFiles = mutableMapOf<Int, RandomAccessFile>()

    private fun resolveAndValidateFile(relativePath: String): File {
        val targetFile = File(baseDir, relativePath)
        val canonicalBase = baseDir.canonicalFile
        val canonicalTarget = targetFile.canonicalFile
        if (!canonicalTarget.path.startsWith(canonicalBase.path)) {
            throw AppError.StorageSecurityError("Path traversal rejected: '$relativePath' attempts to escape root directory '${baseDir.path}'")
        }
        return targetFile
    }

    override suspend fun prepare() = withContext(Dispatchers.IO) {
        if (!baseDir.exists()) {
            baseDir.mkdirs()
        }

        // Validate available disk space
        val usable = baseDir.usableSpace
        if (usable > 0) {
            var existingBytes = 0L
            for (fileMeta in metadata.files) {
                val targetFile = resolveAndValidateFile(fileMeta.relativePath)
                if (targetFile.exists()) {
                    existingBytes += targetFile.length()
                }
            }
            val needed = (metadata.totalLength - existingBytes).coerceAtLeast(0L)
            if (usable < needed) {
                throw AppError.StorageFull(metadata.totalLength, usable)
            }
        }

        // Pre-create target files and parent directories
        for (fileMeta in metadata.files) {
            val targetFile = resolveAndValidateFile(fileMeta.relativePath)
            targetFile.parentFile?.mkdirs()
            if (!targetFile.exists()) {
                targetFile.createNewFile()
            }
        }
    }

    private fun getOrOpenFile(fileIndex: Int): RandomAccessFile {
        return openFiles.getOrPut(fileIndex) {
            val fileMeta = metadata.files[fileIndex]
            val targetFile = resolveAndValidateFile(fileMeta.relativePath)
            targetFile.parentFile?.mkdirs()
            RandomAccessFile(targetFile, "rw")
        }
    }

    companion object {
        const val MIN_FREE_SPACE_BYTES = 15 * 1024 * 1024L // 15 MB safety margin
        const val SPACE_CHECK_INTERVAL_BYTES = 1024 * 1024L // Check every 1 MB
    }

    private var bytesWrittenSinceLastSpaceCheck = 0L

    override suspend fun writePiece(globalOffset: Long, data: ByteArray) = withContext(Dispatchers.IO) {
        bytesWrittenSinceLastSpaceCheck += data.size
        if (bytesWrittenSinceLastSpaceCheck >= SPACE_CHECK_INTERVAL_BYTES) {
            bytesWrittenSinceLastSpaceCheck = 0L
            val usable = baseDir.usableSpace
            if (usable in 1 until MIN_FREE_SPACE_BYTES) {
                throw AppError.StorageFull(metadata.totalLength, usable)
            }
        }

        val rangeEnd = globalOffset + data.size

        for (fileMeta in metadata.files) {
            val slice = fileMeta.intersection(globalOffset, rangeEnd) ?: continue
            val raf = getOrOpenFile(fileMeta.index)
            synchronized(raf) {
                raf.seek(slice.fileOffset)
                raf.write(data, slice.bufferOffset, slice.byteCount)
            }
        }
    }

    override suspend fun readBytes(globalOffset: Long, length: Int): ByteArray = withContext(Dispatchers.IO) {
        val rangeEnd = globalOffset + length
        val buffer = ByteArray(length)

        for (fileMeta in metadata.files) {
            val slice = fileMeta.intersection(globalOffset, rangeEnd) ?: continue
            val raf = getOrOpenFile(fileMeta.index)
            synchronized(raf) {
                raf.seek(slice.fileOffset)
                raf.readFully(buffer, slice.bufferOffset, slice.byteCount)
            }
        }
        buffer
    }

    override suspend fun verifyStoredPieces(onPieceResult: (pieceIndex: Int, isComplete: Boolean) -> Unit) {
        verifyStoredPieces(null, onPieceResult)
    }

    override suspend fun verifyStoredPieces(
        onProgress: ((checkedPieces: Int, totalPieces: Int, percent: Float) -> Unit)?,
        onPieceResult: (pieceIndex: Int, isComplete: Boolean) -> Unit
    ) = withContext(Dispatchers.IO) {
        val totalPieces = metadata.piecesCount
        if (totalPieces == 0) return@withContext

        // Pre-cache file lengths to perform fast piece check without redundant disk reads
        val fileLengths = metadata.files.map { fileMeta ->
            val targetFile = resolveAndValidateFile(fileMeta.relativePath)
            if (targetFile.exists()) targetFile.length() else 0L
        }

        val sha1 = MessageDigest.getInstance("SHA-1")

        for (i in 0 until totalPieces) {
            val offset = metadata.getPieceOffset(i)
            val len = metadata.getPieceLength(i)
            val expectedHash = metadata.pieceHashes[i]
            val rangeEnd = offset + len

            // Fast recheck: verify if files containing this piece have sufficient allocated length
            var hasMissingOrTruncatedData = false
            for (fileMeta in metadata.files) {
                val slice = fileMeta.intersection(offset, rangeEnd) ?: continue
                val actualLength = fileLengths[fileMeta.index]
                if (actualLength < slice.fileOffset + slice.byteCount) {
                    hasMissingOrTruncatedData = true
                    break
                }
            }

            if (hasMissingOrTruncatedData) {
                onPieceResult(i, false)
            } else {
                try {
                    val data = readBytes(offset, len)
                    sha1.reset()
                    val computedHash = sha1.digest(data)
                    val isValid = MessageDigest.isEqual(computedHash, expectedHash)
                    onPieceResult(i, isValid)
                } catch (e: Exception) {
                    onPieceResult(i, false)
                }
            }

            val checked = i + 1
            onProgress?.invoke(checked, totalPieces, (checked.toFloat() / totalPieces) * 100f)
        }
    }

    override fun close() {
        for (raf in openFiles.values) {
            try {
                raf.close()
            } catch (e: Exception) {
                Lg.w(LogCategory.STORAGE, "Error closing file", e)
            }
        }
        openFiles.clear()
    }
}
