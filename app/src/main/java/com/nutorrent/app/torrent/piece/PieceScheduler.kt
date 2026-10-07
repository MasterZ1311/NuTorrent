package com.nutorrent.app.torrent.piece

import com.nutorrent.app.core.model.BlockRequest
import com.nutorrent.app.core.model.FilePriority
import com.nutorrent.app.core.model.PeerBitfield
import com.nutorrent.app.core.model.PieceState

interface PieceScheduler {
    /**
     * Chooses the next piece to start downloading from this peer, or null if no eligible piece.
     */
    fun selectNextPiece(
        peerBitfield: PeerBitfield,
        pieceStates: List<PieceState>,
        pieceAvailability: IntArray,
        piecePriorities: List<FilePriority> = emptyList()
    ): Int?

    /**
     * Selects the next block to request for an active piece downloader.
     */
    fun selectNextBlock(pieceDownloader: PieceDownloader, isEndGame: Boolean = false): BlockRequest?
}

/**
 * Advanced Rarest-First scheduling algorithm with priority grouping and End-Game support.
 * Prioritizes pieces by file priority (HIGH > NORMAL > LOW), skips DO_NOT_DOWNLOAD pieces,
 * and breaks ties by rarest-first (fewest peer availability) or sequentially from the head
 * when sequential mode is enabled.
 */
class RarestFirstPieceScheduler(
    var isSequential: Boolean = false
) : PieceScheduler {

    override fun selectNextPiece(
        peerBitfield: PeerBitfield,
        pieceStates: List<PieceState>,
        pieceAvailability: IntArray,
        piecePriorities: List<FilePriority>
    ): Int? {
        if (isSequential) {
            return selectNextSequentialPiece(peerBitfield, pieceStates, piecePriorities)
        }

        var bestPriorityWeight = -1
        var minAvailability = Int.MAX_VALUE
        var selectedPieceIndex: Int? = null

        for (i in pieceStates.indices) {
            val state = pieceStates[i]
            if (state != PieceState.MISSING && state != PieceState.FAILED) continue
            if (!peerBitfield.hasPiece(i)) continue

            val priority = piecePriorities.getOrNull(i) ?: FilePriority.NORMAL
            if (priority == FilePriority.DO_NOT_DOWNLOAD) continue

            val priorityWeight = priority.weight
            val rawAvail = pieceAvailability.getOrElse(i) { 0 }
            val avail = if (rawAvail > 0) rawAvail else 1

            if (priorityWeight > bestPriorityWeight) {
                bestPriorityWeight = priorityWeight
                minAvailability = avail
                selectedPieceIndex = i
            } else if (priorityWeight == bestPriorityWeight) {
                if (avail < minAvailability) {
                    minAvailability = avail
                    selectedPieceIndex = i
                } else if (selectedPieceIndex == null) {
                    selectedPieceIndex = i
                }
            }
        }

        return selectedPieceIndex
    }

    private fun selectNextSequentialPiece(
        peerBitfield: PeerBitfield,
        pieceStates: List<PieceState>,
        piecePriorities: List<FilePriority>
    ): Int? {
        var bestPriorityWeight = -1
        var selectedPieceIndex: Int? = null

        for (i in pieceStates.indices) {
            val state = pieceStates[i]
            if (state != PieceState.MISSING && state != PieceState.FAILED) continue
            if (!peerBitfield.hasPiece(i)) continue

            val priority = piecePriorities.getOrNull(i) ?: FilePriority.NORMAL
            if (priority == FilePriority.DO_NOT_DOWNLOAD) continue

            val priorityWeight = priority.weight
            if (priorityWeight > bestPriorityWeight) {
                bestPriorityWeight = priorityWeight
                selectedPieceIndex = i
                if (priority == FilePriority.HIGH) {
                    return i
                }
            } else if (priorityWeight == bestPriorityWeight && selectedPieceIndex == null) {
                selectedPieceIndex = i
            }
        }

        return selectedPieceIndex
    }

    override fun selectNextBlock(pieceDownloader: PieceDownloader, isEndGame: Boolean): BlockRequest? {
        val block = pieceDownloader.getNextUnrequestedBlock()
        if (block != null) return block
        if (isEndGame) {
            return pieceDownloader.getNextPendingBlockForEndGame()
        }
        return null
    }
}
