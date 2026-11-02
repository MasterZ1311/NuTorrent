package com.nutorrent.torrent.piece

import com.nutorrent.core.model.AppError
import com.nutorrent.core.model.BlockRequest
import com.nutorrent.core.model.TorrentMetadata
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
 * Manages the downloading, block assembly, and SHA-1 verification of an individual piece.
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

    fun isAllBlocksDownloaded(): Boolean = blocks.all { it.isDownloaded }

    fun remainingBlocksCount(): Int = blocks.count { !it.isDownloaded }

    fun getNextUnrequestedBlock(): BlockRequest? {
        val block = blocks.firstOrNull { !it.isDownloaded && !it.isRequested } ?: return null
        block.isRequested = true
        return BlockRequest(pieceIndex, block.offset, block.length)
    }

    fun getNextPendingBlockForEndGame(): BlockRequest? {
        val block = blocks.firstOrNull { !it.isDownloaded } ?: return null
        return BlockRequest(pieceIndex, block.offset, block.length)
    }

    fun markBlockRequestFailed(offset: Int) {
        val block = blocks.firstOrNull { it.offset == offset }
        if (block != null && !block.isDownloaded) {
            block.isRequested = false
        }
    }

    fun addBlock(offset: Int, data: ByteArray): Boolean {
        if (offset < 0 || offset + data.size > pieceLength) {
            Lg.w(LogCategory.PIECE, "Block out of bounds: offset $offset, size ${data.size}, pieceLength $pieceLength")
            return false
        }
        val block = blocks.firstOrNull { it.offset == offset }
        if (block == null) {
            Lg.w(LogCategory.PIECE, "No matching block for offset $offset in piece $pieceIndex")
            return false
        }
        if (block.length != data.size) {
            Lg.w(LogCategory.PIECE, "Block size mismatch: expected ${block.length}, got ${data.size}")
            return false
        }

        block.data = data
        block.isDownloaded = true
        block.isRequested = false
        return true
    }

    /**
     * Reconstructs the complete piece and verifies its SHA-1 hash against expectedHash.
     * Returns the verified byte array if valid, or null if corrupted.
     */
    fun assembleAndVerify(): ByteArray? {
        if (!isAllBlocksDownloaded()) {
            return null
        }

        val assembled = ByteArray(pieceLength)
        for (block in blocks) {
            val bData = block.data ?: return null
            System.arraycopy(bData, 0, assembled, block.offset, block.length)
        }

        val sha1 = MessageDigest.getInstance("SHA-1")
        val computedHash = sha1.digest(assembled)

        if (MessageDigest.isEqual(computedHash, expectedHash)) {
            Lg.d(LogCategory.PIECE, "[VERIFY] piece=$pieceIndex PASS")
            return assembled
        } else {
            Lg.w(LogCategory.PIECE, "[VERIFY] piece=$pieceIndex FAIL")
            reset()
            return null
        }
    }

    fun reset() {
        for (block in blocks) {
            block.isDownloaded = false
            block.isRequested = false
            block.data = null
        }
    }
}
