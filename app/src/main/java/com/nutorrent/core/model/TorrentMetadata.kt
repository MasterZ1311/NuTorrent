package com.nutorrent.core.model

/**
 * Parsed and validated BitTorrent v1 metainfo.
 */
data class TorrentMetadata(
    val torrentId: TorrentId,
    val name: String,
    val pieceLength: Long,
    val totalLength: Long,
    val piecesCount: Int,
    val pieceHashes: List<ByteArray>,
    val files: List<TorrentFile>,
    val announceList: List<String>,
    val creationDate: Long? = null,
    val comment: String? = null,
    val createdBy: String? = null,
    val isPrivate: Boolean = false
) {
    val isSingleFile: Boolean = files.size == 1

    fun getPieceLength(pieceIndex: Int): Int {
        require(pieceIndex in 0 until piecesCount) { "Invalid piece index $pieceIndex" }
        return if (pieceIndex == piecesCount - 1) {
            val remainder = (totalLength % pieceLength).toInt()
            if (remainder == 0) pieceLength.toInt() else remainder
        } else {
            pieceLength.toInt()
        }
    }

    fun getPieceOffset(pieceIndex: Int): Long {
        require(pieceIndex in 0 until piecesCount) { "Invalid piece index $pieceIndex" }
        return pieceIndex * pieceLength
    }
}
