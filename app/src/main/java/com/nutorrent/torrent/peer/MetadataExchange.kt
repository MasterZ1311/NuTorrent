package com.nutorrent.torrent.peer

import com.nutorrent.core.model.TorrentId
import com.nutorrent.core.util.Lg
import com.nutorrent.core.util.LogCategory
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Handles downloading, assembly, verification, and serving of .torrent metadata (BEP 9).
 */
class MetadataExchange(
    val expectedInfoHash: TorrentId,
    var rawMetadataBytes: ByteArray? = null
) {
    var metadataSize: Int = rawMetadataBytes?.size ?: 0
        private set

    private val receivedPieces = ConcurrentHashMap<Int, ByteArray>()
    var totalPieces: Int = if (metadataSize > 0) calculatePiecesCount(metadataSize) else 0
        private set

    companion object {
        const val MAX_METADATA_SIZE = 10 * 1024 * 1024 // 10 MiB safety bound
        const val PIECE_SIZE = 16384 // 16 KiB

        fun calculatePiecesCount(totalSize: Int): Int {
            return (totalSize + PIECE_SIZE - 1) / PIECE_SIZE
        }
    }

    fun setMetadataSize(size: Int) {
        if (size <= 0 || size > MAX_METADATA_SIZE) {
            Lg.w(LogCategory.METAINFO, "Invalid metadata size: $size")
            return
        }
        if (metadataSize == 0) {
            metadataSize = size
            totalPieces = calculatePiecesCount(size)
        }
    }

    fun isComplete(): Boolean {
        if (rawMetadataBytes != null) return true
        if (totalPieces == 0) return false
        return receivedPieces.size == totalPieces
    }

    fun getNextNeededPiece(): Int? {
        if (totalPieces == 0) return null
        for (i in 0 until totalPieces) {
            if (!receivedPieces.containsKey(i)) {
                return i
            }
        }
        return null
    }

    /**
     * Adds a received metadata piece. If all pieces are received, verifies SHA-1.
     * Returns assembled verified metadata byte array, or null if incomplete or corrupt.
     */
    @Synchronized
    fun onPieceReceived(pieceIndex: Int, totalSize: Int, data: ByteArray): ByteArray? {
        setMetadataSize(totalSize)
        if (totalPieces == 0 || pieceIndex < 0 || pieceIndex >= totalPieces) {
            Lg.w(LogCategory.METAINFO, "Metadata piece index out of bounds: $pieceIndex (total: $totalPieces)")
            return null
        }

        // Validate chunk size
        val expectedPieceLen = if (pieceIndex == totalPieces - 1) {
            val rem = totalSize % PIECE_SIZE
            if (rem == 0) PIECE_SIZE else rem
        } else {
            PIECE_SIZE
        }

        if (data.size != expectedPieceLen) {
            Lg.w(LogCategory.METAINFO, "Metadata piece length mismatch: expected $expectedPieceLen, got ${data.size}")
            return null
        }

        receivedPieces[pieceIndex] = data

        if (receivedPieces.size == totalPieces) {
            return assembleAndVerify()
        }

        return null
    }

    @Synchronized
    private fun assembleAndVerify(): ByteArray? {
        val assembled = ByteArray(metadataSize)
        for (i in 0 until totalPieces) {
            val chunk = receivedPieces[i] ?: return null
            System.arraycopy(chunk, 0, assembled, i * PIECE_SIZE, chunk.size)
        }

        val sha1 = MessageDigest.getInstance("SHA-1")
        val computedHash = sha1.digest(assembled)

        return if (MessageDigest.isEqual(computedHash, expectedInfoHash.bytes)) {
            Lg.i(LogCategory.METAINFO, "Metadata assembled and verified successfully for ${expectedInfoHash.hex}")
            rawMetadataBytes = assembled
            assembled
        } else {
            Lg.w(LogCategory.METAINFO, "Metadata SHA-1 verification FAILED for ${expectedInfoHash.hex}! Discarding.")
            receivedPieces.clear()
            null
        }
    }

    /**
     * For seeding/uploading: retrieves a 16 KiB slice of the metadata to serve to peers.
     */
    fun getPiece(pieceIndex: Int): ByteArray? {
        val bytes = rawMetadataBytes ?: return null
        val count = calculatePiecesCount(bytes.size)
        if (pieceIndex < 0 || pieceIndex >= count) return null

        val start = pieceIndex * PIECE_SIZE
        val end = minOf(bytes.size, start + PIECE_SIZE)
        return bytes.copyOfRange(start, end)
    }

    fun getProgress(): Float {
        if (rawMetadataBytes != null) return 1f
        if (totalPieces == 0) return 0f
        return (receivedPieces.size.toFloat() / totalPieces.toFloat()).coerceIn(0f, 1f)
    }
}
