package com.nutorrent.torrent.download

import com.nutorrent.core.model.TorrentMetadata
import com.nutorrent.core.util.Lg
import com.nutorrent.core.util.LogCategory
import com.nutorrent.data.storage.TorrentStorage
import com.nutorrent.torrent.peer.PeerConnection
import com.nutorrent.torrent.piece.PieceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * Manages uploading pieces to peers, bandwidth accounting, and BitTorrent choking/unchoking.
 */
class UploadManager(
    val metadata: TorrentMetadata,
    var storage: TorrentStorage,
    val pieceManager: PieceManager,
    val maxUnchokeSlots: Int = 8,
    val bandwidthManager: com.nutorrent.torrent.network.BandwidthManager? = null
) {
    val totalUploadedBytes = AtomicLong(0L)
    private var optimisticUnchokedPeer: PeerConnection? = null
    private var lastOptimisticRotation = 0L

    /**
     * Handles an incoming request from a peer for a sub-piece block.
     * Serves data from storage only if the piece has been verified.
     */
    suspend fun handleBlockRequest(
        peer: PeerConnection,
        pieceIndex: Int,
        beginOffset: Int,
        length: Int
    ): Boolean = withContext(Dispatchers.IO) {
        // Enforce maximum block request limit (128 KiB safety bound)
        if (length <= 0 || length > 131072) {
            Lg.w(LogCategory.PEER, "Peer ${peer.endpoint} requested invalid block length: $length")
            return@withContext false
        }

        // Verify we actually have and verified this piece
        if (!pieceManager.hasPiece(pieceIndex)) {
            Lg.w(LogCategory.PEER, "Peer ${peer.endpoint} requested piece #$pieceIndex which is not completed")
            return@withContext false
        }

        val pieceLength = metadata.getPieceLength(pieceIndex)
        if (beginOffset + length > pieceLength) {
            Lg.w(LogCategory.PEER, "Block request exceeds piece length: $beginOffset + $length > $pieceLength")
            return@withContext false
        }

        try {
            val globalOffset = metadata.getPieceOffset(pieceIndex) + beginOffset
            val blockData = storage.readBytes(globalOffset, length)

            bandwidthManager?.acquireUpload(metadata.torrentId.hex, blockData.size)

            val sent = peer.sendPiece(pieceIndex, beginOffset, blockData)
            if (sent) {
                totalUploadedBytes.addAndGet(blockData.size.toLong())
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Lg.e(LogCategory.STORAGE, "Failed to read block from storage for peer ${peer.endpoint}", e)
            false
        }
    }

    /**
     * Updates choking and unchoking state across all active connections.
     * In downloading mode, prioritizes peers offering highest download rate (tit-for-tat).
     * In seeding mode, rotates or prioritizes peers with highest upload capacity.
     */
    fun updateChoking(activePeers: List<PeerConnection>, isSeeding: Boolean) {
        if (activePeers.isEmpty()) return

        val now = System.currentTimeMillis()
        val interestedPeers = activePeers.filter { it.isInterestedInUs }

        // Sort peers for regular unchoke slots
        val sortedPeers = if (isSeeding) {
            // In seeding mode: prefer peers to whom we uploaded fastest or recently active
            interestedPeers.sortedByDescending { it.uploadedBytes.get() }
        } else {
            // In downloading mode (tit-for-tat): prefer peers from whom we downloaded fastest
            interestedPeers.sortedByDescending { it.downloadedBytes.get() }
        }

        val regularUnchoke = sortedPeers.take(maxUnchokeSlots).toSet()

        // Rotate optimistic unchoke slot every 30 seconds
        val candidatesForOptimistic = interestedPeers.filter { it !in regularUnchoke }
        if (now - lastOptimisticRotation > 30_000L || optimisticUnchokedPeer == null || optimisticUnchokedPeer !in candidatesForOptimistic) {
            optimisticUnchokedPeer = if (candidatesForOptimistic.isNotEmpty()) {
                candidatesForOptimistic[Random.nextInt(candidatesForOptimistic.size)]
            } else null
            lastOptimisticRotation = now
        }

        // Apply unchoke / choke to all peers
        for (peer in activePeers) {
            val shouldUnchoke = peer in regularUnchoke || peer == optimisticUnchokedPeer
            if (shouldUnchoke && peer.weAreChoking) {
                peer.sendChoke(false)
            } else if (!shouldUnchoke && !peer.weAreChoking) {
                peer.sendChoke(true)
            }
        }
    }
}
