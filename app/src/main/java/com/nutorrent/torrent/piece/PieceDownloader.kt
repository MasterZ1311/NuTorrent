package com.nutorrent.torrent.piece

import com.nutorrent.core.model.BlockRequest
import com.nutorrent.core.util.Lg
import com.nutorrent.core.util.LogCategory
import java.security.MessageDigest
import java.util.Arrays

data class BlockEntry(
    val offset: Int,
    val length: Int,
    var isDownloaded: Boolean = false,
    var isRequested: Boolean = false,
    var data: ByteArray? = null
)

/**
 * High-performance Piece Downloader.
 * Directly streams incoming blocks into a single pre-allocated piece buffer,
 * avoiding per-block allocations, secondary arraycopy passes, and O(N) block scans.
 */
class PieceDownloader(
    val pieceIndex: Int,
    val pieceLength: Int,
    val expectedHash: ByteArray,
    val blockSize: Int = BlockRequest.DEFAULT_BLOCK_SIZE
) {
    init {
        require(pieceLength > 0) { "Piece length must be positive" }
        require(expectedHash.size == 20) { "Expected hash must be 20 bytes" }
    }

    val blocks: List<BlockEntry> = createBlocks()
    val pieceData: ByteArray = ByteArray(pieceLength)
    private val requestTimestamps: LongArray = LongArray(blocks.size)
    private var downloadedCount: Int = 0
    private var nextUnrequestedIndex: Int = 0

    private fun createBlocks(): List<BlockEntry> {
        val list = mutableListOf<BlockEntry>()
        var offset = 0
        while (offset < pieceLength) {
            val length = minOf(blockSize, pieceLength - offset)
            list.add(BlockEntry(offset = offset, length = length))
            offset += length
        }
        return list
    }

    fun isAllBlocksDownloaded(): Boolean = downloadedCount == blocks.size

    fun remainingBlocksCount(): Int = blocks.size - downloadedCount

    fun getNextUnrequestedBlock(): BlockRequest? {
        for (i in nextUnrequestedIndex until blocks.size) {
            val block = blocks[i]
            if (!block.isDownloaded && !block.isRequested) {
                block.isRequested = true
                requestTimestamps[i] = System.currentTimeMillis()
                nextUnrequestedIndex = i + 1
                return BlockRequest(pieceIndex, block.offset, block.length)
            }
        }
        for (i in 0 until nextUnrequestedIndex.coerceAtMost(blocks.size)) {
            val block = blocks[i]
            if (!block.isDownloaded && !block.isRequested) {
                block.isRequested = true
                requestTimestamps[i] = System.currentTimeMillis()
                return BlockRequest(pieceIndex, block.offset, block.length)
            }
        }
        return null
    }

    fun getNextPendingBlockForEndGame(): BlockRequest? {
        val block = blocks.firstOrNull { !it.isDownloaded } ?: return null
        return BlockRequest(pieceIndex, block.offset, block.length)
    }

    fun getStalledPendingBlock(stalledThresholdMs: Long = 4000L): BlockRequest? {
        if (remainingBlocksCount() > 2) return null
        val now = System.currentTimeMillis()
        for (i in blocks.indices) {
            val block = blocks[i]
            if (!block.isDownloaded && block.isRequested && (now - requestTimestamps[i] > stalledThresholdMs)) {
                return BlockRequest(pieceIndex, block.offset, block.length)
            }
        }
        return null
    }

    fun markBlockRequestFailed(offset: Int) {
        val blockIndex = offset / blockSize
        val block = if (blockIndex in blocks.indices && blocks[blockIndex].offset == offset) {
            blocks[blockIndex]
        } else {
            blocks.firstOrNull { it.offset == offset }
        }
        if (block != null && !block.isDownloaded) {
            block.isRequested = false
            if (blockIndex in requestTimestamps.indices) {
                requestTimestamps[blockIndex] = 0L
            }
            if (blockIndex < nextUnrequestedIndex) {
                nextUnrequestedIndex = blockIndex
            }
        }
    }

    fun addBlock(offset: Int, data: ByteArray): Boolean {
        if (offset < 0 || offset + data.size > pieceLength) {
            Lg.w(LogCategory.PIECE, "Block out of bounds: offset $offset, size ${data.size}, pieceLength $pieceLength")
            return false
        }
        val blockIndex = offset / blockSize
        val block = if (blockIndex in blocks.indices && blocks[blockIndex].offset == offset) {
            blocks[blockIndex]
        } else {
            blocks.firstOrNull { it.offset == offset }
        }
        if (block == null) {
            Lg.w(LogCategory.PIECE, "No matching block for offset $offset in piece $pieceIndex")
            return false
        }
        if (block.length != data.size) {
            Lg.w(LogCategory.PIECE, "Block size mismatch: expected ${block.length}, got ${data.size}")
            return false
        }

        if (!block.isDownloaded) {
            System.arraycopy(data, 0, pieceData, offset, data.size)
            block.data = data
            block.isDownloaded = true
            block.isRequested = false
            downloadedCount++
        }
        return true
    }

    /**
     * Verifies the complete piece SHA-1 hash against expectedHash.
     * pieceData is already assembled in-place during addBlock with zero copy overhead.
     */
    fun assembleAndVerify(): ByteArray? {
        if (!isAllBlocksDownloaded()) {
            return null
        }

        val sha1 = MessageDigest.getInstance("SHA-1")
        val computedHash = sha1.digest(pieceData)

        if (MessageDigest.isEqual(computedHash, expectedHash)) {
            Lg.d(LogCategory.PIECE, "[VERIFY] piece=$pieceIndex PASS")
            return pieceData
        } else {
            Lg.w(LogCategory.PIECE, "[VERIFY] piece=$pieceIndex FAIL")
            reset()
            return null
        }
    }

    fun reset() {
        downloadedCount = 0
        nextUnrequestedIndex = 0
        for (i in blocks.indices) {
            val block = blocks[i]
            block.isDownloaded = false
            block.isRequested = false
            block.data = null
            requestTimestamps[i] = 0L
        }
        Arrays.fill(pieceData, 0.toByte())
    }
}
