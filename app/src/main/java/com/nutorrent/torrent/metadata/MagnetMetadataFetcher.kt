package com.nutorrent.torrent.metadata

import com.nutorrent.core.model.PeerBitfield
import com.nutorrent.core.model.PeerEndpoint
import com.nutorrent.core.model.TorrentId
import com.nutorrent.core.model.TorrentMetadata
import com.nutorrent.core.util.Lg
import com.nutorrent.core.util.LogCategory
import com.nutorrent.torrent.dht.DhtNode
import com.nutorrent.torrent.metainfo.MetainfoParser
import com.nutorrent.torrent.peer.ExtensionHandshake
import com.nutorrent.torrent.peer.MetadataExchange
import com.nutorrent.torrent.peer.MetadataMessage
import com.nutorrent.torrent.peer.PeerConnection
import com.nutorrent.torrent.peer.PeerConnectionListener
import com.nutorrent.torrent.tracker.DefaultTrackerClient
import com.nutorrent.torrent.tracker.PeerIdGenerator
import com.nutorrent.torrent.tracker.TrackerAnnounceRequest
import com.nutorrent.torrent.tracker.TrackerClient
import com.nutorrent.torrent.tracker.TrackerEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class MagnetMetadataFetcher(
    val torrentId: TorrentId,
    val initialTrackers: List<String>,
    private val trackerClient: TrackerClient = DefaultTrackerClient(),
    private val dhtNode: DhtNode? = null,
    private val scope: CoroutineScope,
    private val maxConnections: Int = 20,
    private val onResolved: suspend (TorrentMetadata, ByteArray) -> Unit
) {
    private val ourPeerId = PeerIdGenerator.generate()
    val metadataExchange = MetadataExchange(torrentId)

    private val discoveredPeers = ConcurrentHashMap.newKeySet<PeerEndpoint>()
    private val activeConnections = ConcurrentHashMap<PeerEndpoint, PeerConnection>()
    private val failedPeers = ConcurrentHashMap<PeerEndpoint, Long>()

    private val isRunning = AtomicBoolean(false)
    private val isResolved = AtomicBoolean(false)

    private var announceJob: Job? = null
    private var dhtJob: Job? = null

    val allTrackers: List<String> = (initialTrackers + FALLBACK_TRACKERS).distinct()

    val connectedPeersCount: Int get() = activeConnections.size
    val totalDiscoveredCount: Int get() = discoveredPeers.size
    val progress: Float get() = metadataExchange.getProgress()

    companion object {
        val FALLBACK_TRACKERS = listOf(
            "udp://tracker.opentrackr.org:1337/announce",
            "udp://open.stealth.si:80/announce",
            "udp://tracker.torrent.eu.org:451/announce",
            "udp://explodie.org:6969/announce",
            "udp://tracker.openbittorrent.com:6969/announce",
            "http://tracker.opentrackr.org:1337/announce"
        )
    }

    fun start() {
        if (!isRunning.compareAndSet(false, true)) return
        Lg.i(LogCategory.METAINFO, "Starting metadata fetch for magnet: ${torrentId.hex}")

        // 1. Announce to trackers
        announceJob = scope.launch(Dispatchers.IO) {
            announceAllTrackers()
            while (isActive && isRunning.get() && !isResolved.get()) {
                delay(30_000L)
                if (activeConnections.size < 5) {
                    announceAllTrackers()
                }
            }
        }

        // 2. Query DHT
        dhtJob = scope.launch(Dispatchers.IO) {
            dhtNode?.let { dht ->
                try {
                    dht.findPeers(torrentId.bytes).collect { endpoint ->
                        addPeer(endpoint)
                    }
                } catch (e: Exception) {
                    Lg.d(LogCategory.DHT, "DHT metadata peer discovery ended: ${e.message}")
                }
            }
        }
    }

    private suspend fun announceAllTrackers() {
        for (trackerUrl in allTrackers) {
            if (!isRunning.get() || isResolved.get()) break
            scope.launch(Dispatchers.IO) {
                try {
                    val req = TrackerAnnounceRequest(
                        trackerUrl = trackerUrl,
                        infoHash = torrentId.bytes,
                        peerId = ourPeerId,
                        uploaded = 0L,
                        downloaded = 0L,
                        left = 0L,
                        compact = true,
                        event = TrackerEvent.STARTED
                    )
                    val resp = trackerClient.announce(req)
                    Lg.i(LogCategory.TRACKER, "Metadata fetcher announce to $trackerUrl returned ${resp.peers.size} peers")
                    for (peer in resp.peers) {
                        addPeer(peer)
                    }
                } catch (e: Exception) {
                    Lg.w(LogCategory.TRACKER, "Metadata fetcher announce failed to $trackerUrl: ${e.message}")
                }
            }
        }
    }

    fun addPeer(endpoint: PeerEndpoint) {
        if (!isRunning.get() || isResolved.get()) return
        if (discoveredPeers.add(endpoint)) {
            triggerConnect()
        }
    }

    @Synchronized
    private fun triggerConnect() {
        if (!isRunning.get() || isResolved.get()) return
        val needed = maxConnections - activeConnections.size
        if (needed <= 0) return

        val now = System.currentTimeMillis()
        val candidates = discoveredPeers.filter { ep ->
            !activeConnections.containsKey(ep) &&
                (failedPeers[ep]?.let { now - it > 30_000 } ?: true)
        }.take(needed)

        for (ep in candidates) {
            connectToPeer(ep)
        }
    }

    private fun connectToPeer(endpoint: PeerEndpoint) {
        val listener = object : PeerConnectionListener {
            override fun onHandshakeCompleted(peer: PeerConnection) {
                Lg.d(LogCategory.PEER, "Metadata fetcher handshake complete with ${peer.endpoint}")
            }

            override fun onBitfield(peer: PeerConnection, bitfield: PeerBitfield) {}
            override fun onHave(peer: PeerConnection, pieceIndex: Int) {}
            override fun onPieceBlock(peer: PeerConnection, pieceIndex: Int, begin: Int, block: ByteArray) {}

            override fun onExtensionHandshake(peer: PeerConnection, handshake: ExtensionHandshake) {
                if (isResolved.get() || !isRunning.get()) return
                if (handshake.extensions.containsKey("ut_metadata")) {
                    if (handshake.metadataSize != null && handshake.metadataSize > 0) {
                        metadataExchange.setMetadataSize(handshake.metadataSize)
                    }
                    val nextPiece = metadataExchange.getNextNeededPiece() ?: 0
                    peer.sendMetadataRequest(nextPiece)
                }
            }

            override fun onMetadataMessage(peer: PeerConnection, message: MetadataMessage) {
                if (isResolved.get() || !isRunning.get()) return
                when (message) {
                    is MetadataMessage.Data -> {
                        val verifiedBytes = metadataExchange.onPieceReceived(message.piece, message.totalSize, message.data)
                        if (verifiedBytes != null) {
                            if (isResolved.compareAndSet(false, true)) {
                                Lg.i(LogCategory.METAINFO, "Metadata successfully resolved and verified for ${torrentId.hex}!")
                                scope.launch(Dispatchers.IO) {
                                    try {
                                        val metadata = MetainfoParser.parseFromInfoBytes(verifiedBytes, allTrackers)
                                        val fullTorrentBytes = MetainfoParser.createTorrentBytesFromInfoDict(verifiedBytes, allTrackers)
                                        stop()
                                        onResolved(metadata, fullTorrentBytes)
                                    } catch (e: Exception) {
                                        Lg.e(LogCategory.METAINFO, "Failed parsing resolved metadata for ${torrentId.hex}", e)
                                        isResolved.set(false)
                                    }
                                }
                            }
                        } else {
                            val nextPiece = metadataExchange.getNextNeededPiece()
                            if (nextPiece != null) {
                                peer.sendMetadataRequest(nextPiece)
                            }
                        }
                    }
                    is MetadataMessage.Reject -> {
                        Lg.w(LogCategory.METAINFO, "Peer ${peer.endpoint} rejected metadata piece ${message.piece}")
                    }
                    is MetadataMessage.Request -> {
                        peer.sendMetadataReject(message.piece)
                    }
                }
            }

            override fun onDisconnected(peer: PeerConnection, error: Throwable?) {
                activeConnections.remove(peer.endpoint)
                if (error != null) {
                    failedPeers[peer.endpoint] = System.currentTimeMillis()
                }
                if (!isResolved.get() && isRunning.get()) {
                    triggerConnect()
                }
            }
        }

        val conn = PeerConnection(
            endpoint = endpoint,
            infoHash = torrentId.bytes,
            ourPeerId = ourPeerId,
            totalPieces = 0,
            listener = listener,
            scope = scope,
            connectTimeoutMs = 4000
        )
        activeConnections[endpoint] = conn
        conn.start()
    }

    fun stop() {
        if (isRunning.compareAndSet(true, false)) {
            announceJob?.cancel()
            dhtJob?.cancel()
            for (conn in activeConnections.values) {
                conn.close()
            }
            activeConnections.clear()
        }
    }
}
