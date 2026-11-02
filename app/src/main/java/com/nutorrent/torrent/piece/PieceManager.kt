package com.nutorrent.torrent.piece

import com.nutorrent.core.model.BlockRequest
import com.nutorrent.core.model.FilePriority
import com.nutorrent.core.model.PeerBitfield
import com.nutorrent.core.model.PieceState
import com.nutorrent.core.model.TorrentMetadata
import com.nutorrent.core.util.Lg
import com.nutorrent.core.util.LogCategory
import java.util.concurrent.ConcurrentHashMap

data class VerifiedPieceResult(
    val pieceIndex: Int,
    val globalOffset: Long,
    val verifiedData: ByteArray
)

class PieceManager(
    val metadata: TorrentMetadata,
    val scheduler: PieceScheduler = RarestFirstPieceScheduler()
) {
    val totalPieces: Int = metadata.piecesCount
    val totalBytes: Long = metadata.totalLength

    private val pieceStates = Array(totalPieces) { PieceState.MISSING }
    private val pieceAvailability = IntArray(totalPieces) { 0 }
    private val activeDownloaders = ConcurrentHashMap<Int, PieceDownloader>()
    private val filePriorities = Array(metadata.files.size) { FilePriority.NORMAL }
    val ourBitfield = PeerBitfield(totalPieces)

    var completedPiecesCount: Int = 0
        private set

    var verifiedBytes: Long = 0L
        private set

    @Synchronized
    fun setFilePriority(fileIndex: Int, priority: FilePriority) {
        if (fileIndex in filePriorities.indices) {
            filePriorities[fileIndex] = priority
        }
    }

    @Synchronized
    fun getFilePriorities(): List<FilePriority> = filePriorities.toList()

    @Synchronized
    fun getPieceStates(): List<PieceState> = pieceStates.toList()

    @Synchronized
    fun computePiecePriorities(): List<FilePriority> {
        val result = mutableListOf<FilePriority>()
        for (i in 0 until totalPieces) {
            val pieceStart = metadata.getPieceOffset(i)
            val pieceEnd = pieceStart + metadata.getPieceLength(i)
            var maxPriority = FilePriority.DO_NOT_DOWNLOAD

            for (fIdx in metadata.files.indices) {
                val file = metadata.files[fIdx]
                if (file.overlaps(pieceStart, pieceEnd)) {
                    val p = filePriorities[fIdx]
                    if (p.weight > maxPriority.weight) {
                        maxPriority = p
                    }
                }
            }
            result.add(maxPriority)
        }
        return result
    }

    @Synchronized
    fun isEndGame(): Boolean {
        val hasPendingNewPieces = pieceStates.any { it == PieceState.MISSING || it == PieceState.FAILED }
        if (hasPendingNewPieces) return false
        val remainingBlocks = activeDownloaders.values.sumOf { it.remainingBlocksCount() }
        return remainingBlocks in 1..20
    }

    @Synchronized
    fun getPieceState(index: Int): PieceState {
        return pieceStates[index]
    }

    @Synchronized
    fun getPieceStatesList(): List<PieceState> = pieceStates.toList()

    @Synchronized
    fun isComplete(): Boolean {
        val piecePriorities = computePiecePriorities()
        for (i in 0 until totalPieces) {
            if (piecePriorities[i] != FilePriority.DO_NOT_DOWNLOAD && pieceStates[i] != PieceState.COMPLETE) {
                return false
            }
        }
        return true
    }

    @Synchronized
    fun hasPiece(index: Int): Boolean = index in 0 until totalPieces && ourBitfield.hasPiece(index)

    @Synchronized
    fun onPeerConnected(bitfield: PeerBitfield) {
        for (i in 0 until totalPieces) {
            if (bitfield.hasPiece(i)) {
                pieceAvailability[i]++
            }
        }
    }

    @Synchronized
    fun onPeerHave(pieceIndex: Int) {
        if (pieceIndex in 0 until totalPieces) {
            pieceAvailability[pieceIndex]++
        }
    }

    @Synchronized
    fun onPeerDisconnected(bitfield: PeerBitfield?, cancelledRequests: List<BlockRequest> = emptyList()) {
        if (bitfield != null) {
            for (i in 0 until totalPieces) {
                if (bitfield.hasPiece(i)) {
                    pieceAvailability[i] = maxOf(0, pieceAvailability[i] - 1)
                }
            }
        }
        for (req in cancelledRequests) {
            onBlockRequestFailed(req.pieceIndex, req.beginOffset)
        }
    }

    @Synchronized
    fun hasPiecesWeNeed(peerBitfield: PeerBitfield): Boolean {
        val piecePriorities = computePiecePriorities()
        for (i in 0 until totalPieces) {
            if (peerBitfield.hasPiece(i) && pieceStates[i] != PieceState.COMPLETE && piecePriorities[i] != FilePriority.DO_NOT_DOWNLOAD) {
                return true
            }
        }
        return false
    }

    @Synchronized
    fun requestNextBlockForPeer(peerBitfield: PeerBitfield): BlockRequest? {
        val inEndGame = isEndGame()
        // 1. Try to find a block in existing active downloaders that this peer has
        for ((pieceIndex, downloader) in activeDownloaders) {
            if (peerBitfield.hasPiece(pieceIndex) && !downloader.isAllBlocksDownloaded()) {
                val block = scheduler.selectNextBlock(downloader, isEndGame = inEndGame)
                if (block != null) return block
            }
        }

        // 2. Select a new piece to download using the scheduler
        val piecePriorities = computePiecePriorities()
        val nextPieceIndex = scheduler.selectNextPiece(peerBitfield, pieceStates.toList(), pieceAvailability, piecePriorities)
            ?: return null

        val pieceLength = metadata.getPieceLength(nextPieceIndex)
        val expectedHash = metadata.pieceHashes[nextPieceIndex]
        val downloader = PieceDownloader(nextPieceIndex, pieceLength, expectedHash)

        activeDownloaders[nextPieceIndex] = downloader
        pieceStates[nextPieceIndex] = PieceState.DOWNLOADING

        return scheduler.selectNextBlock(downloader, isEndGame = false)
    }

    @Synchronized
    fun requestNextBlockForAllowedFast(peerBitfield: PeerBitfield, allowedPieces: Set<Int>): BlockRequest? {
        if (allowedPieces.isEmpty()) return null
        val inEndGame = isEndGame()

        // 1. Check existing active downloaders for allowed fast pieces
        for (pieceIndex in allowedPieces) {
            val downloader = activeDownloaders[pieceIndex]
            if (downloader != null && peerBitfield.hasPiece(pieceIndex) && !downloader.isAllBlocksDownloaded()) {
                val block = scheduler.selectNextBlock(downloader, isEndGame = inEndGame)
                if (block != null) return block
            }
        }

        // 2. Pick a new piece from allowed pieces that this peer has and we still need
        val piecePriorities = computePiecePriorities()
        val candidate = allowedPieces
            .filter { idx ->
                idx in 0 until totalPieces &&
                    peerBitfield.hasPiece(idx) &&
                    (pieceStates[idx] == PieceState.MISSING || pieceStates[idx] == PieceState.FAILED) &&
                    piecePriorities[idx] != FilePriority.DO_NOT_DOWNLOAD
            }
            .minByOrNull { pieceAvailability[it] } ?: return null

        val pieceLength = metadata.getPieceLength(candidate)
        val expectedHash = metadata.pieceHashes[candidate]
        val downloader = PieceDownloader(candidate, pieceLength, expectedHash)

        activeDownloaders[candidate] = downloader
        pieceStates[candidate] = PieceState.DOWNLOADING

        return scheduler.selectNextBlock(downloader, isEndGame = false)
    }

    @Synchronized
    fun onBlockReceived(pieceIndex: Int, beginOffset: Int, blockData: ByteArray): VerifiedPieceResult? {
        val downloader = activeDownloaders[pieceIndex] ?: return null
        val added = downloader.addBlock(beginOffset, blockData)
        if (!added) return null

        if (downloader.isAllBlocksDownloaded()) {
            pieceStates[pieceIndex] = PieceState.VERIFYING
            val verifiedData = downloader.assembleAndVerify()
            if (verifiedData != null) {
                activeDownloaders.remove(pieceIndex)
                val globalOffset = metadata.getPieceOffset(pieceIndex)
                return VerifiedPieceResult(
                    pieceIndex = pieceIndex,
                    globalOffset = globalOffset,
                    verifiedData = verifiedData
                )
            } else {
                pieceStates[pieceIndex] = PieceState.FAILED
                activeDownloaders.remove(pieceIndex)
                return null
            }
        }
        return null
    }

    @Synchronized
    fun onPieceWritten(pieceIndex: Int, byteCount: Int) {
        if (pieceIndex in 0 until totalPieces && pieceStates[pieceIndex] != PieceState.COMPLETE) {
            pieceStates[pieceIndex] = PieceState.COMPLETE
            ourBitfield.setPiece(pieceIndex, true)
            completedPiecesCount++
            verifiedBytes += byteCount
        }
    }

    @Synchronized
    fun onBlockRequestFailed(pieceIndex: Int, offset: Int) {
        val downloader = activeDownloaders[pieceIndex]
        if (downloader != null) {
            downloader.markBlockRequestFailed(offset)
        }
    }

    @Synchronized
    fun markPieceCompleteFromStorage(pieceIndex: Int) {
        if (pieceIndex in 0 until totalPieces && pieceStates[pieceIndex] != PieceState.COMPLETE) {
            pieceStates[pieceIndex] = PieceState.COMPLETE
            ourBitfield.setPiece(pieceIndex, true)
            completedPiecesCount++
            verifiedBytes += metadata.getPieceLength(pieceIndex)
        }
    }

    @Synchronized
    fun resetAllPieces() {
        for (i in 0 until totalPieces) {
            pieceStates[i] = PieceState.MISSING
            ourBitfield.setPiece(i, false)
        }
        completedPiecesCount = 0
        verifiedBytes = 0L
        activeDownloaders.clear()
    }

    var isSequential: Boolean
        get() = (scheduler as? RarestFirstPieceScheduler)?.isSequential == true
        set(value) {
            (scheduler as? RarestFirstPieceScheduler)?.isSequential = value
        }

    /**
     * Returns the contiguous length in bytes verified from the start (offset 0) of the specified file.
     * Useful for streaming playback buffers and sequential media read-ahead verification.
     */
    @Synchronized
    fun availableVerifiedRange(fileIndex: Int): Long {
        if (fileIndex !in metadata.files.indices) return 0L
        val file = metadata.files[fileIndex]
        if (file.length == 0L) return 0L

        var currentOffset = file.startByte
        while (currentOffset < file.endByte) {
            val pieceIdx = (currentOffset / metadata.pieceLength).toInt().coerceIn(0, totalPieces - 1)
            if (!ourBitfield.hasPiece(pieceIdx)) {
                break
            }
            val pieceEnd = metadata.getPieceOffset(pieceIdx) + metadata.getPieceLength(pieceIdx)
            currentOffset = minOf(file.endByte, pieceEnd)
        }
        return currentOffset - file.startByte
    }
}
