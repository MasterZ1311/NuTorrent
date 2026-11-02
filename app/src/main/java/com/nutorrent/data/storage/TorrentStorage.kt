package com.nutorrent.data.storage

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.documentfile.provider.DocumentFile
import com.nutorrent.core.model.AppError
import com.nutorrent.core.model.TorrentMetadata
import com.nutorrent.core.util.Lg
import com.nutorrent.core.util.LogCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.nutorrent.core.util.FileOpener
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
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
    suspend fun areFilesComplete(): Boolean = false
    suspend fun hasExistingDiskData(): Boolean = false
    fun getAvailableSpace(): Long? = null
    var selectedFileIndices: Set<Int>?
        get() = null
        set(_) {}
    fun close()
}

/**
 * File-based random-access storage engine supporting single-file and multi-file torrents.
 * Correctly handles pieces spanning across file boundaries.
 */
@android.annotation.SuppressLint("UsableSpace")
open class FileTorrentStorage(
    val baseDir: File,
    val metadata: TorrentMetadata,
    override var selectedFileIndices: Set<Int>? = null
) : TorrentStorage {

    private val openFiles = mutableMapOf<Int, RandomAccessFile>()

    private fun resolveAndValidateFile(relativePath: String): File {
        val targetFile = File(baseDir, relativePath)
        val canonicalBase = baseDir.canonicalFile
        val canonicalTarget = targetFile.canonicalFile
        val basePath = canonicalBase.path.let { if (it.endsWith(File.separator)) it else it + File.separator }
        if (!canonicalTarget.path.startsWith(basePath) && canonicalTarget.path != canonicalBase.path) {
            throw AppError.StorageSecurityError("Path traversal rejected: '$relativePath' attempts to escape root directory '${baseDir.path}'")
        }
        return targetFile
    }

    override fun getAvailableSpace(): Long? {
        return try {
            val checkDir = if (baseDir.exists()) {
                baseDir
            } else {
                var d: File? = baseDir
                while (d != null && !d.exists()) {
                    d = d.parentFile
                }
                d
            }
            if (checkDir != null && checkDir.exists()) {
                checkDir.usableSpace
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun prepare() = withContext(Dispatchers.IO) {
        if (!baseDir.exists()) {
            if (!baseDir.mkdirs() && !baseDir.exists()) {
                throw AppError.FileCreationFailed(baseDir.absolutePath, "Cannot create base download directory")
            }
        }

        if (!baseDir.canWrite()) {
            throw AppError.DestinationNotWritable(baseDir.absolutePath)
        }

        val activeFiles = metadata.files.filter { selectedFileIndices?.contains(it.index) ?: true }
        val totalActiveLength = activeFiles.sumOf { it.length }

        // FAT32 4 GB limit check
        if (StorageCalculator.isFat32(baseDir.absolutePath)) {
            for (fileMeta in activeFiles) {
                if (fileMeta.length >= 4L * 1024 * 1024 * 1024L) {
                    throw AppError.FileTooLargeForFilesystem(fileMeta.relativePath, fileMeta.length)
                }
            }
        }

        // Validate available disk space
        val usable = getAvailableSpace()
        if (usable != null) {
            var existingBytes = 0L
            for (fileMeta in activeFiles) {
                val targetFile = resolveAndValidateFile(fileMeta.relativePath)
                if (targetFile.exists()) {
                    existingBytes += targetFile.length()
                }
            }
            val needed = (totalActiveLength - existingBytes).coerceAtLeast(0L)
            if (usable < needed) {
                throw AppError.StorageFull(
                    requiredBytes = totalActiveLength,
                    availableBytes = usable
                )
            }
        }

        // Pre-create target files and parent directories only for active files
        for (fileMeta in activeFiles) {
            val targetFile = resolveAndValidateFile(fileMeta.relativePath)
            targetFile.parentFile?.mkdirs()
            if (!targetFile.exists()) {
                try {
                    targetFile.createNewFile()
                } catch (e: IOException) {
                    throw AppError.fromThrowable(e, usable, totalActiveLength)
                }
            }
        }
    }

    private fun getOrOpenFile(fileIndex: Int): RandomAccessFile {
        return openFiles.getOrPut(fileIndex) {
            val fileMeta = metadata.files[fileIndex]
            val targetFile = resolveAndValidateFile(fileMeta.relativePath)
            targetFile.parentFile?.mkdirs()
            try {
                RandomAccessFile(targetFile, "rw")
            } catch (e: IOException) {
                throw AppError.InaccessibleFile(targetFile.absolutePath, e.message ?: "Cannot open file", e)
            }
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
            val usable = getAvailableSpace()
            if (usable != null && usable < MIN_FREE_SPACE_BYTES) {
                throw AppError.InsufficientStorage(
                    requiredBytes = metadata.totalLength,
                    availableBytes = usable,
                    shortfallBytes = (MIN_FREE_SPACE_BYTES - usable).coerceAtLeast(0L)
                )
            }
        }

        val rangeEnd = globalOffset + data.size

        for (fileMeta in metadata.files) {
            val slice = fileMeta.intersection(globalOffset, rangeEnd) ?: continue
            val raf = getOrOpenFile(fileMeta.index)
            synchronized(raf) {
                try {
                    raf.seek(slice.fileOffset)
                    raf.write(data, slice.bufferOffset, slice.byteCount)
                } catch (e: IOException) {
                    throw AppError.fromThrowable(e, getAvailableSpace(), metadata.totalLength)
                }
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
                try {
                    raf.seek(slice.fileOffset)
                    raf.readFully(buffer, slice.bufferOffset, slice.byteCount)
                } catch (e: IOException) {
                    throw AppError.FileReadFailed(fileMeta.relativePath, e.message ?: "Failed reading file", e)
                }
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

    override suspend fun areFilesComplete(): Boolean = withContext(Dispatchers.IO) {
        try {
            val activeFiles = metadata.files.filter { selectedFileIndices?.contains(it.index) ?: true }
            if (activeFiles.isEmpty()) return@withContext false
            for (fileMeta in activeFiles) {
                val targetFile = resolveAndValidateFile(fileMeta.relativePath)
                if (!targetFile.exists() || targetFile.length() < fileMeta.length) {
                    return@withContext false
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun hasExistingDiskData(): Boolean = withContext(Dispatchers.IO) {
        try {
            val activeFiles = metadata.files.filter { selectedFileIndices?.contains(it.index) ?: true }
            for (fileMeta in activeFiles) {
                val targetFile = resolveAndValidateFile(fileMeta.relativePath)
                if (targetFile.exists() && targetFile.length() > 0L) {
                    return@withContext true
                }
            }
            false
        } catch (e: Exception) {
            false
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

/**
 * Storage Access Framework (SAF) DocumentFile implementation of [TorrentStorage].
 * Enables persistent downloading to user-selected folders across Internal Storage, SD Cards, and USB drives.
 * Random-access reads and writes are performed through [ParcelFileDescriptor] and seekable NIO [java.nio.channels.FileChannel].
 */
class CachedPfdChannel(
    val pfd: ParcelFileDescriptor
) {
    val outputStream = FileOutputStream(pfd.fileDescriptor)
    val writeChannel: java.nio.channels.FileChannel = outputStream.channel

    val inputStream = FileInputStream(pfd.fileDescriptor)
    val readChannel: java.nio.channels.FileChannel = inputStream.channel

    fun close() {
        try { writeChannel.close() } catch (_: Exception) {}
        try { readChannel.close() } catch (_: Exception) {}
        try { outputStream.close() } catch (_: Exception) {}
        try { inputStream.close() } catch (_: Exception) {}
        try { pfd.close() } catch (_: Exception) {}
    }
}

class DocumentFileTorrentStorage(
    private val context: Context,
    val treeUri: Uri,
    val metadata: TorrentMetadata,
    override var selectedFileIndices: Set<Int>? = null
) : TorrentStorage {

    private val fileUriMap = mutableMapOf<Int, Uri>()
    private val openPfds = mutableMapOf<Int, ParcelFileDescriptor>()
    private val openChannels = mutableMapOf<Int, CachedPfdChannel>()

    private fun getOrOpenChannel(fileIndex: Int): CachedPfdChannel {
        return openChannels.getOrPut(fileIndex) {
            val uri = fileUriMap[fileIndex]
                ?: throw AppError.InaccessibleFile("file #$fileIndex", "File URI not resolved")
            val pfd = try {
                context.contentResolver.openFileDescriptor(uri, "rw")
                    ?: throw AppError.InaccessibleFile("file #$fileIndex", "ContentResolver returned null descriptor")
            } catch (e: Exception) {
                throw AppError.InaccessibleFile("file #$fileIndex", e.message ?: "Failed opening file descriptor", e)
            }
            openPfds[fileIndex] = pfd
            CachedPfdChannel(pfd)
        }
    }

    override fun getAvailableSpace(): Long? {
        return StorageCalculator.getAvailableBytes(context, treeUri.toString())
    }

    private fun getOrCreateDocumentFile(root: DocumentFile, relativePath: String): DocumentFile {
        val segments = relativePath.replace('\\', '/').split('/').filter { it.isNotEmpty() }
        require(segments.isNotEmpty()) { "Relative path cannot be empty" }

        for (segment in segments) {
            if (segment == "." || segment == "..") {
                throw AppError.StorageSecurityError("Path traversal rejected: '$relativePath' contains invalid directory segment '$segment'")
            }
        }

        var current = root
        for (i in 0 until segments.size - 1) {
            val segment = segments[i]
            val next = current.findFile(segment) ?: current.createDirectory(segment)
                ?: throw AppError.FileCreationFailed(relativePath, "Failed to create directory '$segment'")
            current = next
        }

        val fileName = segments.last()
        val mimeType = FileOpener.getMimeType(fileName)
        return current.findFile(fileName) ?: current.createFile(mimeType, fileName)
            ?: throw AppError.FileCreationFailed(relativePath, "Failed to create file '$fileName'")
    }

    override suspend fun prepare() = withContext(Dispatchers.IO) {
        val rootDoc = try {
            DocumentFile.fromTreeUri(context, treeUri)
        } catch (e: Exception) {
            throw AppError.StorageProviderUnavailable(treeUri.toString(), e)
        } ?: throw AppError.StorageProviderUnavailable(treeUri.toString())

        if (!rootDoc.exists() || !rootDoc.canWrite()) {
            throw AppError.DestinationPermissionRevoked(treeUri.toString())
        }

        val activeFiles = metadata.files.filter { selectedFileIndices?.contains(it.index) ?: true }
        val totalActiveLength = activeFiles.sumOf { it.length }

        // FAT32 4 GB limit check
        if (StorageCalculator.isFat32(treeUri.toString())) {
            for (fileMeta in activeFiles) {
                if (fileMeta.length >= 4L * 1024 * 1024 * 1024L) {
                    throw AppError.FileTooLargeForFilesystem(fileMeta.relativePath, fileMeta.length)
                }
            }
        }

        // Pre-create active files and cache document URIs
        var existingBytes = 0L
        for (fileMeta in activeFiles) {
            val fileDoc = getOrCreateDocumentFile(rootDoc, fileMeta.relativePath)
            fileUriMap[fileMeta.index] = fileDoc.uri
            val pfd = try { getOrOpenPfd(fileMeta.index) } catch (e: Exception) { null }
            val len = pfd?.statSize ?: -1L
            if (len > 0L) {
                existingBytes += len
            }
        }

        // Validate available space if determinable
        val usable = getAvailableSpace()
        if (usable != null) {
            val needed = (totalActiveLength - existingBytes).coerceAtLeast(0L)
            if (usable < needed) {
                throw AppError.StorageFull(
                    requiredBytes = totalActiveLength,
                    availableBytes = usable
                )
            }
        }
    }

    private fun getOrOpenPfd(fileIndex: Int): ParcelFileDescriptor {
        return openPfds.getOrPut(fileIndex) {
            val uri = fileUriMap[fileIndex]
                ?: throw AppError.InaccessibleFile("file #$fileIndex", "File URI not resolved")
            try {
                context.contentResolver.openFileDescriptor(uri, "rw")
                    ?: throw AppError.InaccessibleFile("file #$fileIndex", "ContentResolver returned null descriptor")
            } catch (e: Exception) {
                throw AppError.InaccessibleFile("file #$fileIndex", e.message ?: "Failed opening file descriptor", e)
            }
        }
    }

    private var bytesWrittenSinceLastSpaceCheck = 0L

    override suspend fun writePiece(globalOffset: Long, data: ByteArray) = withContext(Dispatchers.IO) {
        bytesWrittenSinceLastSpaceCheck += data.size
        if (bytesWrittenSinceLastSpaceCheck >= FileTorrentStorage.SPACE_CHECK_INTERVAL_BYTES) {
            bytesWrittenSinceLastSpaceCheck = 0L
            val usable = getAvailableSpace()
            if (usable != null && usable < FileTorrentStorage.MIN_FREE_SPACE_BYTES) {
                throw AppError.InsufficientStorage(
                    requiredBytes = metadata.totalLength,
                    availableBytes = usable,
                    shortfallBytes = (FileTorrentStorage.MIN_FREE_SPACE_BYTES - usable).coerceAtLeast(0L)
                )
            }
        }

        val rangeEnd = globalOffset + data.size

        for (fileMeta in metadata.files) {
            val slice = fileMeta.intersection(globalOffset, rangeEnd) ?: continue
            val channelHandle = getOrOpenChannel(fileMeta.index)
            synchronized(channelHandle) {
                try {
                    channelHandle.writeChannel.position(slice.fileOffset)
                    val buffer = ByteBuffer.wrap(data, slice.bufferOffset, slice.byteCount)
                    while (buffer.hasRemaining()) {
                        channelHandle.writeChannel.write(buffer)
                    }
                } catch (e: IOException) {
                    throw AppError.fromThrowable(e, getAvailableSpace(), metadata.totalLength)
                }
            }
        }
    }

    override suspend fun readBytes(globalOffset: Long, length: Int): ByteArray = withContext(Dispatchers.IO) {
        val rangeEnd = globalOffset + length
        val buffer = ByteArray(length)

        for (fileMeta in metadata.files) {
            val slice = fileMeta.intersection(globalOffset, rangeEnd) ?: continue
            val channelHandle = getOrOpenChannel(fileMeta.index)
            synchronized(channelHandle) {
                try {
                    channelHandle.readChannel.position(slice.fileOffset)
                    val byteBuf = ByteBuffer.wrap(buffer, slice.bufferOffset, slice.byteCount)
                    while (byteBuf.hasRemaining()) {
                        val read = channelHandle.readChannel.read(byteBuf)
                        if (read == -1) break
                    }
                } catch (e: Exception) {
                    throw AppError.FileReadFailed(fileMeta.relativePath, e.message ?: "Failed reading document", e)
                }
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

        val fileLengths = metadata.files.map { fileMeta ->
            val pfd = try { getOrOpenPfd(fileMeta.index) } catch (e: Exception) { null }
            pfd?.statSize ?: -1L
        }

        val sha1 = MessageDigest.getInstance("SHA-1")

        for (i in 0 until totalPieces) {
            val offset = metadata.getPieceOffset(i)
            val len = metadata.getPieceLength(i)
            val expectedHash = metadata.pieceHashes[i]
            val rangeEnd = offset + len

            var hasMissingData = false
            for (fileMeta in metadata.files) {
                val slice = fileMeta.intersection(offset, rangeEnd) ?: continue
                val actualLength = fileLengths[fileMeta.index]
                if (actualLength >= 0L && actualLength < slice.fileOffset + slice.byteCount) {
                    hasMissingData = true
                    break
                }
            }

            if (hasMissingData) {
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

    override suspend fun areFilesComplete(): Boolean = withContext(Dispatchers.IO) {
        try {
            val activeFiles = metadata.files.filter { selectedFileIndices?.contains(it.index) ?: true }
            if (activeFiles.isEmpty()) return@withContext false
            val rootDoc = try {
                DocumentFile.fromTreeUri(context, treeUri)
            } catch (e: Exception) {
                null
            } ?: return@withContext false
            if (!rootDoc.exists()) return@withContext false

            for (fileMeta in activeFiles) {
                val fileDoc = getOrCreateDocumentFile(rootDoc, fileMeta.relativePath)
                fileUriMap[fileMeta.index] = fileDoc.uri
                val docLen = fileDoc.length()
                val pfd = try { getOrOpenPfd(fileMeta.index) } catch (e: Exception) { null }
                val pfdLen = pfd?.statSize ?: -1L
                val effectiveLen = if (pfdLen >= 0L) maxOf(docLen, pfdLen) else docLen
                if (effectiveLen < fileMeta.length) {
                    return@withContext false
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun hasExistingDiskData(): Boolean = withContext(Dispatchers.IO) {
        try {
            val activeFiles = metadata.files.filter { selectedFileIndices?.contains(it.index) ?: true }
            val rootDoc = try {
                DocumentFile.fromTreeUri(context, treeUri)
            } catch (e: Exception) {
                null
            } ?: return@withContext false
            if (!rootDoc.exists()) return@withContext false

            for (fileMeta in activeFiles) {
                val fileDoc = getOrCreateDocumentFile(rootDoc, fileMeta.relativePath)
                val docLen = fileDoc.length()
                val pfd = try { getOrOpenPfd(fileMeta.index) } catch (e: Exception) { null }
                val pfdLen = pfd?.statSize ?: -1L
                val effectiveLen = if (pfdLen >= 0L) maxOf(docLen, pfdLen) else docLen
                if (effectiveLen > 0L) {
                    return@withContext true
                }
            }
            false
        } catch (e: Exception) {
            false
        }
    }

    override fun close() {
        for (handle in openChannels.values) {
            handle.close()
        }
        openChannels.clear()
        openPfds.clear()
        fileUriMap.clear()
    }
}

/**
 * Storage factory creating the appropriate [TorrentStorage] based on destination URI/path.
 */
object TorrentStorageFactory {
    fun create(
        context: Context,
        destinationPath: String,
        metadata: TorrentMetadata,
        selectedFileIndices: Set<Int>? = null
    ): TorrentStorage {
        val trimmed = destinationPath.trim()
        return if (trimmed.startsWith("content://", ignoreCase = true)) {
            DocumentFileTorrentStorage(context, Uri.parse(trimmed), metadata, selectedFileIndices)
        } else {
            val baseDir = if (trimmed.startsWith("file://", ignoreCase = true)) {
                File(Uri.parse(trimmed).path ?: trimmed.removePrefix("file://"))
            } else {
                File(trimmed)
            }
            FileTorrentStorage(baseDir, metadata, selectedFileIndices)
        }
    }
}
