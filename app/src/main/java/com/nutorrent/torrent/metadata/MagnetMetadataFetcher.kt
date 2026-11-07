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
import com.nutorrent.torrent.peer.PeerHandshake
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
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class MagnetMetadataFetcher(
    val torrentId: TorrentId,
    val initialTrackers: List<String>,
    private val trackerClient: TrackerClient = DefaultTrackerClient(),
    private val dhtNode: DhtNode? = null,
    private val scope: CoroutineScope,
    private val maxConnections: Int = 40,
    val peerHints: List<String> = emptyList(),
    val exactSources: List<String> = emptyList(),
    val listenPort: Int = 0,
    private val onResolved: suspend (TorrentMetadata, ByteArray, Set<PeerEndpoint>) -> Unit
) {
    // Overloaded constructor for 2-parameter onResolved (backwards compatibility)
    constructor(
        torrentId: TorrentId,
        initialTrackers: List<String>,
        trackerClient: TrackerClient = DefaultTrackerClient(),
        dhtNode: DhtNode? = null,
        scope: CoroutineScope,
        maxConnections: Int = 40,
        peerHints: List<String> = emptyList(),
        exactSources: List<String> = emptyList(),
        listenPort: Int = 0,
        onResolvedSimple: suspend (TorrentMetadata, ByteArray) -> Unit
    ) : this(
        torrentId = torrentId,
        initialTrackers = initialTrackers,
        trackerClient = trackerClient,
        dhtNode = dhtNode,
        scope = scope,
        maxConnections = maxConnections,
        peerHints = peerHints,
        exactSources = exactSources,
        listenPort = listenPort,
        onResolved = { meta, raw, _ -> onResolvedSimple(meta, raw) }
    )

    private val ourPeerId = PeerIdGenerator.generate()
    val metadataExchange = MetadataExchange(torrentId)

    private val discoveredPeers = ConcurrentHashMap.newKeySet<PeerEndpoint>()
    private val pendingConnections = ConcurrentHashMap<PeerEndpoint, PeerConnection>()
    private val activeConnections = ConcurrentHashMap<PeerEndpoint, PeerConnection>()
    private val failedPeers = ConcurrentHashMap<PeerEndpoint, Long>()

    private val isRunning = AtomicBoolean(false)
    private val isResolved = AtomicBoolean(false)

    private var announceJob: Job? = null
    private var dhtJob: Job? = null
    private var httpFetchJob: Job? = null
    private var watchdogJob: Job? = null

    val allTrackers: List<String> = (initialTrackers + FALLBACK_TRACKERS + com.nutorrent.torrent.tracker.CommunityTrackersSync.getCachedTrackers()).distinct()

    val connectedPeersCount: Int get() = activeConnections.size
    val totalDiscoveredCount: Int get() = discoveredPeers.size
    val progress: Float get() = metadataExchange.getProgress()

    companion object {
        const val MAX_EXACT_SOURCE_BYTES = 5 * 1024 * 1024 // 5 MiB limit for remote .torrent files
        const val MAX_HTTP_REDIRECTS = 3

        val FALLBACK_TRACKERS = listOf(
            "udp://tracker.opentrackr.org:1337/announce",
            "udp://open.stealth.si:80/announce",
            "udp://tracker.torrent.eu.org:451/announce",
            "udp://explodie.org:6969/announce",
            "udp://tracker.openbittorrent.com:6969/announce",
            "udp://tracker.moeking.me:6969/announce",
            "udp://opentor.org:2710/announce",
            "http://tracker.opentrackr.org:1337/announce"
        )

        /**
         * Reads an InputStream into a ByteArray with a strict byte limit enforced incrementally during streaming.
         * Throws IOException if the stream exceeds maxBytes, preventing unbounded memory allocation.
         */
        @Throws(java.io.IOException::class)
        fun readBoundedStream(inputStream: java.io.InputStream, maxBytes: Int): ByteArray {
            val buffer = ByteArray(8192)
            val outputStream = java.io.ByteArrayOutputStream()
            var totalRead = 0
            while (true) {
                val bytesRead = inputStream.read(buffer)
                if (bytesRead == -1) break
                totalRead += bytesRead
                if (totalRead > maxBytes) {
                    throw java.io.IOException("Network stream exceeded maximum permitted size of $maxBytes bytes")
                }
                outputStream.write(buffer, 0, bytesRead)
            }
            return outputStream.toByteArray()
        }
    }

    fun start() {
        if (!isRunning.compareAndSet(false, true)) return
        Lg.i(LogCategory.METAINFO, "Starting high-speed pipelined metadata fetch for magnet: ${torrentId.hex}")

        // 1. Ingest peer hints directly from magnet URI (x.pe=)
        for (hint in peerHints) {
            try {
                val parts = hint.split(':')
                if (parts.size == 2) {
                    val ip = parts[0].trim().removePrefix("[").removeSuffix("]")
                    val port = parts[1].trim().toInt()
                    addPeer(PeerEndpoint(ip, port))
                }
            } catch (_: Exception) {}
        }

        // 2. Fetch directly from exact source (.torrent URL) if available (xs=)
        if (exactSources.isNotEmpty()) {
            httpFetchJob = scope.launch(Dispatchers.IO) {
                for (sourceUrl in exactSources) {
                    if (!isRunning.get() || isResolved.get()) break
                    try {
                        val bytes = fetchExactSource(sourceUrl) ?: continue
                        val meta = MetainfoParser.parse(bytes)
                        if (meta.torrentId == torrentId) {
                            if (isResolved.compareAndSet(false, true)) {
                                Lg.i(LogCategory.METAINFO, "Resolved metadata directly from exact source: $sourceUrl")
                                val peersSnapshot = discoveredPeers.toSet()
                                stop()
                                onResolved(meta, bytes, peersSnapshot)
                                return@launch
                            }
                        }
                    } catch (e: Exception) {
                        Lg.d(LogCategory.METAINFO, "Could not parse metadata from exact source $sourceUrl: ${e.message}")
                    }
                }
            }
        }

        // 3. Announce to trackers with our actual listening port
        announceJob = scope.launch(Dispatchers.IO) {
            announceAllTrackers()
            while (isActive && isRunning.get() && !isResolved.get()) {
                delay(20_000L)
                if (activeConnections.size < 8) {
                    announceAllTrackers()
                }
            }
        }

        // 4. Query DHT
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

        // 5. Watchdog for connection maintenance and stalled piece recovery
        watchdogJob = scope.launch(Dispatchers.IO) {
            while (isActive && isRunning.get() && !isResolved.get()) {
                delay(1000L)
                triggerConnect()
                dispatchWorkToIdlePeers()
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
                        port = if (listenPort > 0) listenPort else 6881,
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

    /**
     * Accepts an incoming peer connection dispatched from [IncomingPeerServer].
     * Immediately checks info hash and transitions peer into metadata exchange.
     */
    fun acceptIncomingPeer(socket: Socket, handshake: PeerHandshake): Boolean {
        if (!isRunning.get() || isResolved.get()) {
            try { socket.close() } catch (_: Exception) {}
            return false
        }
        val remoteIp = socket.inetAddress?.hostAddress ?: return false
        val remotePort = socket.port
        val endpoint = PeerEndpoint(remoteIp, remotePort)

        if (activeConnections.containsKey(endpoint) || pendingConnections.containsKey(endpoint)) {
            try { socket.close() } catch (_: Exception) {}
            return false
        }
        if (activeConnections.size >= maxConnections) {
            try { socket.close() } catch (_: Exception) {}
            return false
        }

        discoveredPeers.add(endpoint)
        val listener = createPeerListener(endpoint)
        val conn = PeerConnection(
            endpoint = endpoint,
            infoHash = torrentId.bytes,
            ourPeerId = ourPeerId,
            totalPieces = 0,
            listener = listener,
            scope = scope,
            connectTimeoutMs = 5000,
            incomingSocket = socket,
            incomingHandshake = handshake
        )
        activeConnections[endpoint] = conn
        conn.start()
        Lg.i(LogCategory.METAINFO, "Accepted incoming swarm peer for metadata: $endpoint")
        return true
    }

    @Synchronized
    private fun triggerConnect() {
        if (!isRunning.get() || isResolved.get()) return
        val availableActiveSlots = maxConnections - activeConnections.size
        if (availableActiveSlots <= 0) return

        val maxPendingDials = 30
        val pendingSlots = maxPendingDials - pendingConnections.size
        if (pendingSlots <= 0) return

        val toDialCount = minOf(availableActiveSlots, pendingSlots)
        val now = System.currentTimeMillis()
        val candidates = discoveredPeers.filter { ep ->
            !activeConnections.containsKey(ep) &&
            !pendingConnections.containsKey(ep) &&
            (failedPeers[ep]?.let { now - it > 20_000 } ?: true)
        }.take(toDialCount)

        for (ep in candidates) {
            connectToPeer(ep)
        }
    }

    private fun connectToPeer(endpoint: PeerEndpoint) {
        val listener = createPeerListener(endpoint)
        val conn = PeerConnection(
            endpoint = endpoint,
            infoHash = torrentId.bytes,
            ourPeerId = ourPeerId,
            totalPieces = 0,
            listener = listener,
            scope = scope,
            connectTimeoutMs = 2000 // Fast 2-second dial timeout for rapid swarm traversal
        )
        pendingConnections[endpoint] = conn
        conn.start()
    }

    private fun createPeerListener(endpoint: PeerEndpoint): PeerConnectionListener = object : PeerConnectionListener {
        override fun onHandshakeCompleted(peer: PeerConnection) {
            pendingConnections.remove(peer.endpoint)
            activeConnections[peer.endpoint] = peer
            Lg.d(LogCategory.PEER, "Metadata fetcher handshake complete with ${peer.endpoint}")
            if (!peer.peerSupportsExtensionProtocol) {
                peer.disconnect()
            } else {
                scope.launch(Dispatchers.IO) {
                    delay(3000L)
                    if (!isResolved.get() && isRunning.get() && activeConnections.containsKey(peer.endpoint)) {
                        if (!peer.peerExtensions.containsKey("ut_metadata")) {
                            peer.disconnect()
                        }
                    }
                }
            }
            triggerConnect()
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
                dispatchNextMetadataRequest(peer)
                dispatchWorkToIdlePeers()
            } else {
                peer.disconnect()
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
                                    val peersSnapshot = discoveredPeers.toSet()
                                    stop()
                                    onResolved(metadata, fullTorrentBytes, peersSnapshot)
                                } catch (e: Exception) {
                                    Lg.e(LogCategory.METAINFO, "Failed parsing resolved metadata for ${torrentId.hex}", e)
                                    isResolved.set(false)
                                }
                            }
                        }
                    } else {
                        dispatchNextMetadataRequest(peer)
                        dispatchWorkToIdlePeers()
                    }
                }
                is MetadataMessage.Reject -> {
                    Lg.w(LogCategory.METAINFO, "Peer ${peer.endpoint} rejected metadata piece ${message.piece}")
                    metadataExchange.onPieceFailed(message.piece)
                    dispatchWorkToIdlePeers()
                }
                is MetadataMessage.Request -> {
                    peer.sendMetadataReject(message.piece)
                }
            }
        }

        override fun onDisconnected(peer: PeerConnection, error: Throwable?) {
            pendingConnections.remove(peer.endpoint)
            activeConnections.remove(peer.endpoint)
            if (error != null) {
                failedPeers[peer.endpoint] = System.currentTimeMillis()
            }
            if (!isResolved.get() && isRunning.get()) {
                triggerConnect()
                dispatchWorkToIdlePeers()
            }
        }
    }

    private fun dispatchNextMetadataRequest(peer: PeerConnection) {
        if (!peer.peerExtensions.containsKey("ut_metadata")) return
        val nextPiece = metadataExchange.getNextNeededPiece(excludeInFlight = true)
            ?: metadataExchange.getNextNeededPiece(excludeInFlight = false)
            ?: return

        metadataExchange.markPieceRequested(nextPiece)
        peer.sendMetadataRequest(nextPiece)
        Lg.d(LogCategory.METAINFO, "Requested metadata piece $nextPiece from ${peer.endpoint}")
    }

    private fun dispatchWorkToIdlePeers() {
        if (isResolved.get() || !isRunning.get()) return
        for (peer in activeConnections.values) {
            if (peer.peerExtensions.containsKey("ut_metadata")) {
                val next = metadataExchange.getNextNeededPiece(excludeInFlight = true) ?: break
                metadataExchange.markPieceRequested(next)
                peer.sendMetadataRequest(next)
                Lg.d(LogCategory.METAINFO, "Pipelined metadata piece $next to peer ${peer.endpoint}")
            }
        }
    }

    fun stop() {
        if (isRunning.compareAndSet(true, false)) {
            watchdogJob?.cancel()
            announceJob?.cancel()
            dhtJob?.cancel()
            httpFetchJob?.cancel()
            for (conn in pendingConnections.values) {
                conn.close()
            }
            pendingConnections.clear()
            for (conn in activeConnections.values) {
                conn.close()
            }
            activeConnections.clear()
            metadataExchange.clearInFlight()
        }
    }

    private fun fetchExactSource(initialUrl: String): ByteArray? {
        var currentUrl = initialUrl
        var redirects = 0
        while (redirects <= MAX_HTTP_REDIRECTS) {
            if (!isRunning.get() || isResolved.get()) return null
            if (!currentUrl.startsWith("http://", ignoreCase = true) && !currentUrl.startsWith("https://", ignoreCase = true)) {
                return null
            }
            var conn: HttpURLConnection? = null
            try {
                val url = URL(currentUrl)
                conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 6000
                    readTimeout = 10000
                    instanceFollowRedirects = false // Controlled redirect hops with bounds checking
                    setRequestProperty("User-Agent", "NuTorrent/1.0.0")
                }

                val responseCode = conn.responseCode
                if (responseCode in 300..399) {
                    val location = conn.getHeaderField("Location")
                    if (location.isNullOrBlank()) {
                        return null
                    }
                    currentUrl = if (location.startsWith("http://", ignoreCase = true) || location.startsWith("https://", ignoreCase = true)) {
                        location
                    } else {
                        URL(url, location).toString()
                    }
                    redirects++
                    continue
                }

                if (responseCode in 200..299) {
                    val declaredLen = conn.contentLengthLong
                    if (declaredLen > MAX_EXACT_SOURCE_BYTES) {
                        Lg.w(LogCategory.METAINFO, "Exact source $currentUrl declared length $declaredLen exceeds limit $MAX_EXACT_SOURCE_BYTES")
                        return null
                    }
                    return conn.inputStream.use { stream ->
                        readBoundedStream(stream, MAX_EXACT_SOURCE_BYTES)
                    }
                } else {
                    Lg.d(LogCategory.METAINFO, "Exact source $currentUrl returned HTTP $responseCode")
                    return null
                }
            } catch (e: Exception) {
                Lg.d(LogCategory.METAINFO, "Could not fetch exact source $currentUrl: ${e.message}")
                return null
            } finally {
                try {
                    conn?.disconnect()
                } catch (_: Exception) {}
            }
        }
        Lg.w(LogCategory.METAINFO, "Exact source $initialUrl exceeded redirect limit of $MAX_HTTP_REDIRECTS")
        return null
    }


}
