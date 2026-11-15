package com.nutorrent.torrent.peer

import com.nutorrent.core.model.TorrentId
import com.nutorrent.core.util.Lg
import com.nutorrent.core.util.LogCategory
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Handles downloading, assembly, verification, and serving of .torrent metadata (BEP 9).
 * Features pipelined concurrent piece tracking and watchdog timeout recovery.
 */
class MetadataExchange(
    val expectedInfoHash: TorrentId,
    var rawMetadataBytes: ByteArray? = null
) {
    var metadataSize: Int = rawMetadataBytes?.size ?: 0
        private set

    private val receivedPieces = ConcurrentHashMap<Int, ByteArray>()
    private val inFlightRequests = ConcurrentHashMap<Int, Long>()
    private val piece0RequestCount = java.util.concurrent.atomic.AtomicInteger(0)
    var totalPieces: Int = if (metadataSize > 0) calculatePiecesCount(metadataSize) else 0
        private set

    companion object {
        const val MAX_METADATA_SIZE = 10 * 1024 * 1024 // 10 MiB safety bound
        const val PIECE_SIZE = 16384 // 16 KiB
        const val PIECE_REQUEST_TIMEOUT_MS = 1200L // 1.2s timeout for in-flight piece before re-requesting/racing

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

    /**
     * Selects up to [maxCount] metadata pieces to request for a peer.
     * Supports pipelining across multiple pieces, speculative duplicate racing
     * across initial peers for piece 0 (up to 6 peers), and rapid timeout recovery.
     */
    fun getNextNeededPieces(maxCount: Int = 4, allowRacing: Boolean = true): List<Int> {
        val now = System.currentTimeMillis()
        if (totalPieces == 0) {
            if (receivedPieces.containsKey(0)) return emptyList()
            val reqTime = inFlightRequests[0]
            val count = piece0RequestCount.get()
            // Speculative racing: allow requesting piece 0 from up to 6 peers simultaneously
            if (reqTime == null || (now - reqTime) > PIECE_REQUEST_TIMEOUT_MS || (allowRacing && count < 6)) {
                return listOf(0)
            }
            return emptyList()
        }

        val result = mutableListOf<Int>()
        // 1. Unrequested missing pieces or timed-out pieces
        for (i in 0 until totalPieces) {
            if (result.size >= maxCount) break
            if (!receivedPieces.containsKey(i)) {
                val reqTime = inFlightRequests[i]
                if (reqTime == null || (now - reqTime) > PIECE_REQUEST_TIMEOUT_MS) {
                    result.add(i)
                }
            }
        }

        // 2. If we still have pipeline capacity and allowRacing, race pieces pending > 800ms
        if (result.size < maxCount && allowRacing) {
            val pendingCandidates = (0 until totalPieces)
                .filter { !receivedPieces.containsKey(it) && it !in result }
                .mapNotNull { i -> inFlightRequests[i]?.let { time -> i to time } }
                .filter { (now - it.second) > 800L }
                .sortedBy { it.second }
                .map { it.first }

            for (piece in pendingCandidates) {
                if (result.size >= maxCount) break
                result.add(piece)
            }
        }

        return result
    }

    /**
     * Selects the next metadata piece to request.
     * When [excludeInFlight] is true, prioritizes pieces that have not yet been requested from any peer,
     * or whose previous request has timed out (> [PIECE_REQUEST_TIMEOUT_MS]).
     * If all remaining pieces are currently in-flight, returns the oldest pending piece for duplicate racing.
     */
    fun getNextNeededPiece(excludeInFlight: Boolean = true): Int? {
        val pieces = getNextNeededPieces(maxCount = 1, allowRacing = !excludeInFlight)
        return pieces.firstOrNull()
    }

    fun markPieceRequested(pieceIndex: Int) {
        inFlightRequests[pieceIndex] = System.currentTimeMillis()
        if (pieceIndex == 0) {
            piece0RequestCount.incrementAndGet()
        }
    }

    fun onPieceFailed(pieceIndex: Int) {
        inFlightRequests.remove(pieceIndex)
        if (pieceIndex == 0) {
            piece0RequestCount.decrementAndGet().coerceAtLeast(0)
        }
    }

    fun clearInFlight() {
        inFlightRequests.clear()
        piece0RequestCount.set(0)
    }

    /**
     * Adds a received metadata piece. If all pieces are received, verifies SHA-1.
     * Returns assembled verified metadata byte array, or null if incomplete or corrupt.
     */
    @Synchronized
    fun onPieceReceived(pieceIndex: Int, totalSize: Int, data: ByteArray): ByteArray? {
        setMetadataSize(totalSize)
        inFlightRequests.remove(pieceIndex)
        if (pieceIndex == 0) {
            piece0RequestCount.set(0)
        }
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
            inFlightRequests.clear()
            assembled
        } else {
            Lg.w(LogCategory.METAINFO, "Metadata SHA-1 verification FAILED for ${expectedInfoHash.hex}! Discarding.")
            receivedPieces.clear()
            inFlightRequests.clear()
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
