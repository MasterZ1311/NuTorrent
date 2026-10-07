package com.nutorrent.app.core.model

import java.util.Arrays

enum class PieceState {
    MISSING,
    QUEUED,
    DOWNLOADING,
    VERIFYING,
    COMPLETE,
    FAILED
}

/**
 * Represents a piece of the torrent.
 */
data class TorrentPiece(
    val index: Int,
    val length: Int,
    val expectedHash: ByteArray,
    val globalOffset: Long,
    val state: PieceState = PieceState.MISSING
) {
    init {
        require(expectedHash.size == 20) { "Piece hash must be 20 bytes SHA-1" }
        require(length > 0) { "Piece length must be positive" }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TorrentPiece) return false
        return index == other.index &&
                length == other.length &&
                globalOffset == other.globalOffset &&
                state == other.state &&
                Arrays.equals(expectedHash, other.expectedHash)
    }

    override fun hashCode(): Int {
        var result = index
        result = 31 * result + length
        result = 31 * result + globalOffset.hashCode()
        result = 31 * result + state.hashCode()
        result = 31 * result + Arrays.hashCode(expectedHash)
        return result
    }
}
