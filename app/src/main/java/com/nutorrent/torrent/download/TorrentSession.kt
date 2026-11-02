package com.nutorrent.torrent.download

import com.nutorrent.core.model.AppError
import com.nutorrent.core.model.BlockRequest
import com.nutorrent.core.model.PeerBitfield
import com.nutorrent.core.model.PeerStateInfo
import com.nutorrent.core.model.TorrentMetadata
import com.nutorrent.core.model.TorrentProgress
import com.nutorrent.core.model.TorrentState
import com.nutorrent.core.model.TrackerInfo
import com.nutorrent.core.util.Lg
import com.nutorrent.core.util.LogCategory
import com.nutorrent.data.storage.TorrentStorage
import com.nutorrent.torrent.peer.PeerConnection
import com.nutorrent.torrent.peer.PeerConnectionListener
import com.nutorrent.torrent.peer.PeerConnectionManager
import com.nutorrent.torrent.peer.PexMessage
import com.nutorrent.torrent.piece.PieceManager
import com.nutorrent.torrent.tracker.DefaultTrackerClient
import com.nutorrent.torrent.tracker.PeerIdGenerator
import com.nutorrent.torrent.tracker.TrackerAnnounceRequest
import com.nutorrent.torrent.tracker.TrackerClient
import com.nutorrent.torrent.tracker.TrackerEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

class TorrentSession(
    val metadata: TorrentMetadata,
    val storage: TorrentStorage,
    val trackerClient: TrackerClient = DefaultTrackerClient(),
    private val scope: CoroutineScope,
    maxPeerConnections: Int = 30,
    val bandwidthManager: com.nutorrent.torrent.network.BandwidthManager? = null,
    val dhtNode: com.nutorrent.torrent.dht.DhtNode? = null,
    val listenPort: Int = 6881
) {
    private val ourPeerId = PeerIdGenerator.generate()
    val pieceManager = PieceManager(metadata)
    val uploadManager = UploadManager(metadata, storage, pieceManager, bandwidthManager = bandwidthManager)
    private val speedCalculator = SpeedCalculator()
    private val uploadSpeedCalculator = SpeedCalculator()

    var isSequentialDownload: Boolean
        get() = pieceManager.isSequential
        set(value) {
            pieceManager.isSequential = value
        }

    fun availableVerifiedRange(fileIndex: Int): Long = pieceManager.availableVerifiedRange(fileIndex)

    private val _state = MutableStateFlow(TorrentState.QUEUED)
    val state: StateFlow<TorrentState> = _state.asStateFlow()

    private fun setState(newState: TorrentState) {
        if (_state.value != newState) {
            Lg.i(LogCategory.ENGINE, "[TORRENT_STATE] ${metadata.name} -> $newState")
            _state.value = newState
        }
    }

    private val _progress = MutableStateFlow(
        TorrentProgress(
            totalBytes = metadata.totalLength,
            totalPieces = metadata.piecesCount
        )
    )
    val progress: StateFlow<TorrentProgress> = _progress.asStateFlow()

    private val _peers = MutableStateFlow<List<PeerStateInfo>>(emptyList())
    val peers: StateFlow<List<PeerStateInfo>> = _peers.asStateFlow()

    private val _trackers = MutableStateFlow<List<TrackerInfo>>(
        (metadata.announceList + com.nutorrent.torrent.metadata.MagnetMetadataFetcher.FALLBACK_TRACKERS).distinct().map { TrackerInfo(url = it) }
    )
    val trackers: StateFlow<List<TrackerInfo>> = _trackers.asStateFlow()

    private val _verificationProgress = MutableStateFlow(0f)
    val verificationProgress: StateFlow<Float> = _verificationProgress.asStateFlow()

    private var activeJob: Job? = null
    private var statsJob: Job? = null
    private var announceJob: Job? = null
    private var dhtJob: Job? = null
    private val totalRawDownloadedBytes = AtomicLong(0L)

    private val peerListener = object : PeerConnectionListener {
        override fun onHandshakeCompleted(peer: PeerConnection) {
            Lg.d(LogCategory.PEER, "Handshake completed with peer ${peer.endpoint}")
            if (pieceManager.hasPiecesWeNeed(peer.peerBitfield) && !peer.weAreInterested) {
                peer.sendInterested()
            }
            requestWorkForPeer(peer)
        }

        override fun onBitfield(peer: PeerConnection, bitfield: PeerBitfield) {
            pieceManager.onPeerConnected(bitfield)
            if (pieceManager.hasPiecesWeNeed(bitfield) && !peer.weAreInterested) {
                peer.sendInterested()
            }
            requestWorkForPeer(peer)
        }

        override fun onHave(peer: PeerConnection, pieceIndex: Int) {
            pieceManager.onPeerHave(pieceIndex)
            if (pieceManager.hasPiecesWeNeed(peer.peerBitfield) && !peer.weAreInterested) {
                peer.sendInterested()
            }
            requestWorkForPeer(peer)
        }

        override fun onAllowedFast(peer: PeerConnection, pieceIndex: Int) {
            Lg.d(LogCategory.PEER, "Peer ${peer.endpoint} allowed fast piece $pieceIndex")
            if (pieceManager.hasPiecesWeNeed(peer.peerBitfield) && !peer.weAreInterested) {
                peer.sendInterested()
            }
            requestWorkForPeer(peer)
        }

        override fun onBlockRejected(peer: PeerConnection, request: BlockRequest) {
            Lg.d(LogCategory.PEER, "Peer ${peer.endpoint} rejected request $request")
            pieceManager.onBlockRequestFailed(request.pieceIndex, request.beginOffset)
            requestWorkForPeer(peer)
        }

        override fun onChokeStateChanged(peer: PeerConnection, isChokingUs: Boolean, cancelledRequests: List<BlockRequest>) {
            if (isChokingUs) {
                for (req in cancelledRequests) {
                    pieceManager.onBlockRequestFailed(req.pieceIndex, req.beginOffset)
                }
                if (peer.allowedFastPieces.isNotEmpty()) {
                    requestWorkForPeer(peer)
                }
            } else {
                if (pieceManager.hasPiecesWeNeed(peer.peerBitfield) && !peer.weAreInterested) {
                    peer.sendInterested()
                }
                requestWorkForPeer(peer)
            }
        }

        override fun onPieceBlock(peer: PeerConnection, pieceIndex: Int, begin: Int, block: ByteArray) {
            totalRawDownloadedBytes.addAndGet(block.size.toLong())
            val verifiedResult = pieceManager.onBlockReceived(pieceIndex, begin, block)

            if (verifiedResult != null) {
                // Write piece asynchronously to storage
                scope.launch(Dispatchers.IO) {
                    try {
                        storage.writePiece(verifiedResult.globalOffset, verifiedResult.verifiedData)
                        pieceManager.onPieceWritten(verifiedResult.pieceIndex, verifiedResult.verifiedData.size)
                        Lg.i(LogCategory.STORAGE, "[STORAGE] piece=${verifiedResult.pieceIndex} written")
                        updateProgressSnapshot()
                        peerManager.broadcastHave(verifiedResult.pieceIndex)

                        if (pieceManager.isComplete()) {
                            onDownloadCompleted()
                        }
                    } catch (e: com.nutorrent.core.model.AppError.StorageFull) {
                        Lg.e(LogCategory.STORAGE, "Disk space full (< 15MB available), pausing torrent ${metadata.name}", e)
                        setState(TorrentState.ERROR)
                        pause()
                    } catch (e: Exception) {
                        Lg.e(LogCategory.STORAGE, "Failed writing piece ${verifiedResult.pieceIndex}", e)
                    }
                }
            }

            requestWorkForPeer(peer)
        }

        override fun onPexMessage(peer: PeerConnection, payload: ByteArray) {
            val pex = PexMessage.parse(payload) ?: return
            if (pex.added.isNotEmpty()) {
                peerManager.addPeers(pex.added)
            }
        }

        override fun onUploadRequest(peer: PeerConnection, pieceIndex: Int, begin: Int, length: Int) {
            scope.launch(Dispatchers.IO) {
                uploadManager.handleBlockRequest(peer, pieceIndex, begin, length)
            }
        }

        override fun onDisconnected(peer: PeerConnection, error: Throwable?, cancelledRequests: List<BlockRequest>) {
            pieceManager.onPeerDisconnected(peer.peerBitfield, cancelledRequests)
        }
    }

    val peerManager: PeerConnectionManager

    init {
        peerManager = PeerConnectionManager(
            infoHash = metadata.torrentId.bytes,
            ourPeerId = ourPeerId,
            totalPieces = metadata.piecesCount,
            ourBitfield = pieceManager.ourBitfield,
            scope = scope,
            maxConnections = maxPeerConnections,
            listener = peerListener,
            bandwidthManager = bandwidthManager
        )
    }

    fun start() {
        if (_state.value.isActive) return
        activeJob?.cancel()

        setState(TorrentState.VERIFYING)
        activeJob = scope.launch(Dispatchers.IO) {
            try {
                _verificationProgress.value = 0f
                storage.prepare()

                // Check stored pieces on start with progress callback
                storage.verifyStoredPieces(
                    onProgress = { _, _, pct ->
                        _verificationProgress.value = pct / 100f
                    },
                    onPieceResult = { index, isComplete ->
                        if (isComplete) {
                            pieceManager.markPieceCompleteFromStorage(index)
                        }
                    }
                )
                _verificationProgress.value = 1f
                updateProgressSnapshot()

                if (pieceManager.isComplete()) {
                    setState(TorrentState.SEEDING)
                    startStatsTicker()
                    launch { announceToTrackers(TrackerEvent.STARTED) }
                    queryDhtForPeers()
                    peerManager.reconnectIfNeeded()
                } else {
                    setState(TorrentState.DISCOVERING_PEERS)
                    startStatsTicker()
                    launch { announceToTrackers(TrackerEvent.STARTED) }
                    queryDhtForPeers()
                    if (_state.value == TorrentState.DISCOVERING_PEERS) {
                        setState(TorrentState.DOWNLOADING)
                    }
                    peerManager.reconnectIfNeeded()
                    for (peer in peerManager.getActiveConnections()) {
                        requestWorkForPeer(peer)
                    }
                }

                // Periodic loop: choking (5s), tracker announce (20-30s), PEX (60s)
                var loopCounter = 0
                while (isActive && (_state.value == TorrentState.DOWNLOADING || _state.value == TorrentState.SEEDING)) {
                    delay(5_000L)
                    loopCounter++
                    uploadManager.updateChoking(peerManager.getActiveConnections(), isSeeding = _state.value == TorrentState.SEEDING)
                    
                    peerManager.reconnectIfNeeded()

                    // Aggressive Peer Cycling:
                    // Disconnect peers that have kept us choked for > 30s with no in-flight requests,
                    // so that fresh peer candidates can be rotated in.
                    val now = System.currentTimeMillis()
                    val active = peerManager.getActiveConnections()
                    val hasQueuedCandidates = peerManager.totalKnownCount > peerManager.activeCount
                    for (peer in active) {
                        if (peer.isChokingUs &&
                            peer.pendingRequests.isEmpty() &&
                            peer.chokeReceivedAt > 0L &&
                            (now - peer.chokeReceivedAt) > 30_000L &&
                            peer.allowedFastPieces.isEmpty() &&
                            hasQueuedCandidates
                        ) {
                            Lg.d(LogCategory.PEER, "[CYCLE] Cycling choked peer ${peer.endpoint} (choked for ${(now - peer.chokeReceivedAt)/1000}s)")
                            peer.disconnect()
                        }
                    }

                    val isDownloading = _state.value == TorrentState.DOWNLOADING
                    val isStalledOrLow = isDownloading && (_progress.value.downloadSpeedBps == 0L || peerManager.activeCount < 8)

                    if (isStalledOrLow && loopCounter % 4 == 0) { // Every 20s if stalled or low active peers
                        launch { announceToTrackers(TrackerEvent.REGULAR) }
                        queryDhtForPeers()
                    } else if (loopCounter % 6 == 0) { // 30s regular
                        if (peerManager.activeCount < 5) {
                            launch { announceToTrackers(TrackerEvent.REGULAR) }
                            queryDhtForPeers()
                        }
                    }
                    if (loopCounter % 12 == 0) { // 60s
                        peerManager.broadcastPex(PexMessage(added = peerManager.getConnectedEndpoints()))
                    }
                }
            } catch (e: Throwable) {
                if (isActive) {
                    Lg.e(LogCategory.ENGINE, "Session error", e)
                    setState(TorrentState.ERROR)
                }
            }
        }
    }

    private fun requestWorkForPeer(peer: PeerConnection) {
        if (_state.value != TorrentState.DOWNLOADING && _state.value != TorrentState.DISCOVERING_PEERS) return
        if (_state.value == TorrentState.DISCOVERING_PEERS) {
            setState(TorrentState.DOWNLOADING)
        }

        if (pieceManager.hasPiecesWeNeed(peer.peerBitfield) && !peer.weAreInterested) {
            peer.sendInterested()
        }

        if (peer.isChokingUs) {
            if (peer.allowedFastPieces.isNotEmpty()) {
                while (peer.pendingRequests.size < 4) {
                    val req = pieceManager.requestNextBlockForAllowedFast(peer.peerBitfield, peer.allowedFastPieces) ?: break
                    if (peer.pendingRequests.containsKey(req)) break
                    val sent = peer.sendRequest(req)
                    if (!sent) {
                        pieceManager.onBlockRequestFailed(req.pieceIndex, req.beginOffset)
                        break
                    }
                }
            }
            return
        }

        // Request blocks up to pipeline depth of 8
        while (peer.pendingRequests.size < 8) {
            val req = pieceManager.requestNextBlockForPeer(peer.peerBitfield) ?: break
            if (peer.pendingRequests.containsKey(req)) break
            val sent = peer.sendRequest(req)
            if (!sent) {
                pieceManager.onBlockRequestFailed(req.pieceIndex, req.beginOffset)
                break
            }
        }
    }

    private suspend fun announceToTrackers(event: TrackerEvent) {
        val currentTrackers = _trackers.value
        val updatedTrackers = currentTrackers.toMutableList()

        kotlinx.coroutines.coroutineScope {
            currentTrackers.forEachIndexed { idx, trackerInfo ->
                if (trackerInfo.url.startsWith("http://", ignoreCase = true) ||
                    trackerInfo.url.startsWith("https://", ignoreCase = true) ||
                    trackerInfo.url.startsWith("udp://", ignoreCase = true)
                ) {
                    launch(Dispatchers.IO) {
                        try {
                            val req = TrackerAnnounceRequest(
                                trackerUrl = trackerInfo.url,
                                infoHash = metadata.torrentId.bytes,
                                peerId = ourPeerId,
                                port = listenPort,
                                uploaded = uploadManager.totalUploadedBytes.get(),
                                downloaded = totalRawDownloadedBytes.get(),
                                left = (metadata.totalLength - pieceManager.verifiedBytes).coerceAtLeast(0L),
                                compact = true,
                                event = event
                            )
                            val resp = trackerClient.announce(req)
                            synchronized(updatedTrackers) {
                                updatedTrackers[idx] = trackerInfo.copy(
                                    status = "Online",
                                    peersCount = resp.peers.size,
                                    intervalSeconds = resp.intervalSeconds,
                                    lastError = null
                                )
                                _trackers.value = updatedTrackers.toList()
                            }
                            if (resp.peers.isNotEmpty()) {
                                Lg.i(LogCategory.TRACKER, "Tracker ${trackerInfo.url} returned ${resp.peers.size} peers for ${metadata.name}")
                                peerManager.addPeers(resp.peers)
                            }
                        } catch (e: Exception) {
                            Lg.w(LogCategory.TRACKER, "Announce failed to ${trackerInfo.url}: ${e.message}")
                            synchronized(updatedTrackers) {
                                updatedTrackers[idx] = trackerInfo.copy(
                                    status = "Failed",
                                    lastError = e.message
                                )
                                _trackers.value = updatedTrackers.toList()
                            }
                        }
                    }
                } else {
                    synchronized(updatedTrackers) {
                        updatedTrackers[idx] = trackerInfo.copy(
                            status = "Unsupported protocol",
                            lastError = "Unsupported tracker protocol scheme"
                        )
                    }
                }
            }
        }
        _trackers.value = updatedTrackers.toList()
    }

    fun reannounce() {
        scope.launch(Dispatchers.IO) {
            launch { announceToTrackers(TrackerEvent.REGULAR) }
            queryDhtForPeers()
            peerManager.reconnectIfNeeded()
        }
    }

    private fun queryDhtForPeers() {
        dhtNode?.let { dht ->
            scope.launch(Dispatchers.IO) {
                try {
                    Lg.d(LogCategory.DHT, "[DHT] querying for ${metadata.torrentId.hex}")
                    dht.findPeers(metadata.torrentId.bytes).collect { endpoint ->
                        Lg.d(LogCategory.DHT, "[DHT] peer found $endpoint")
                        peerManager.addPeers(listOf(endpoint))
                    }
                } catch (e: Exception) {
                    Lg.d(LogCategory.DHT, "[DHT] lookup failed for ${metadata.name}: ${e.message}")
                }
            }
        }
    }

    fun updateProgressSnapshot() {
        val currentProgress = _progress.value
        val remaining = (metadata.totalLength - pieceManager.verifiedBytes).coerceAtLeast(0L)
        val eta = speedCalculator.calculateEta(remaining, currentProgress.downloadSpeedBps)

        _progress.value = currentProgress.copy(
            downloadedBytes = totalRawDownloadedBytes.get(),
            uploadedBytes = uploadManager.totalUploadedBytes.get(),
            verifiedBytes = pieceManager.verifiedBytes,
            completedPieces = pieceManager.completedPiecesCount,
            etaSeconds = eta,
            connectedPeers = peerManager.activeCount,
            totalPeers = peerManager.totalKnownCount
        )
    }

    private fun startStatsTicker() {
        statsJob?.cancel()
        statsJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(1000L) // 1 second ticker

                val speed = speedCalculator.addSample(totalRawDownloadedBytes.get())
                val uploadSpeed = uploadSpeedCalculator.addSample(uploadManager.totalUploadedBytes.get())
                val remaining = metadata.totalLength - pieceManager.verifiedBytes
                val eta = speedCalculator.calculateEta(remaining, speed)

                _progress.value = TorrentProgress(
                    downloadedBytes = totalRawDownloadedBytes.get(),
                    uploadedBytes = uploadManager.totalUploadedBytes.get(),
                    verifiedBytes = pieceManager.verifiedBytes,
                    totalBytes = metadata.totalLength,
                    completedPieces = pieceManager.completedPiecesCount,
                    totalPieces = metadata.piecesCount,
                    downloadSpeedBps = speed,
                    uploadSpeedBps = uploadSpeed,
                    etaSeconds = eta,
                    connectedPeers = peerManager.activeCount,
                    totalPeers = peerManager.totalKnownCount
                )

                _peers.value = peerManager.getPeerStateInfos()
            }
        }
    }

    private suspend fun onDownloadCompleted() {
        setState(TorrentState.SEEDING)
        _progress.value = _progress.value.copy(
            verifiedBytes = metadata.totalLength,
            completedPieces = metadata.piecesCount,
            downloadSpeedBps = 0L,
            etaSeconds = 0L
        )
        Lg.i(LogCategory.ENGINE, "Torrent ${metadata.name} completed download. Now SEEDING to peers!")
        announceToTrackers(TrackerEvent.COMPLETED)
    }

    fun pause() {
        if (_state.value == TorrentState.PAUSED) return
        activeJob?.cancel()
        statsJob?.cancel()
        announceJob?.cancel()
        dhtJob?.cancel()
        peerManager.disconnectAll()
        storage.close()
        setState(TorrentState.PAUSED)
        _progress.value = _progress.value.copy(downloadSpeedBps = 0L, etaSeconds = null, connectedPeers = 0)
    }

    fun resume() {
        if (_state.value == TorrentState.PAUSED) {
            start()
        }
    }

    fun forceRecheck(): Job {
        val wasActive = _state.value.isActive
        activeJob?.cancel()
        statsJob?.cancel()
        announceJob?.cancel()
        dhtJob?.cancel()
        peerManager.disconnectAll()
        setState(TorrentState.VERIFYING)
        _verificationProgress.value = 0f
        pieceManager.resetAllPieces()

        val job = scope.launch(Dispatchers.IO) {
            try {
                storage.prepare()
                storage.verifyStoredPieces(
                    onProgress = { _, _, pct ->
                        _verificationProgress.value = pct / 100f
                    },
                    onPieceResult = { index, isComplete ->
                        if (isComplete) {
                            pieceManager.markPieceCompleteFromStorage(index)
                        }
                    }
                )
                _verificationProgress.value = 1f
                _progress.value = _progress.value.copy(
                    verifiedBytes = pieceManager.verifiedBytes,
                    completedPieces = pieceManager.completedPiecesCount
                )

                if (pieceManager.isComplete()) {
                    setState(TorrentState.SEEDING)
                    startStatsTicker()
                    announceJob = launch { announceToTrackers(TrackerEvent.STARTED) }
                    peerManager.reconnectIfNeeded()
                } else if (wasActive) {
                    setState(TorrentState.DOWNLOADING)
                    startStatsTicker()
                    announceJob = launch { announceToTrackers(TrackerEvent.STARTED) }
                    peerManager.reconnectIfNeeded()
                } else {
                    setState(TorrentState.PAUSED)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                setState(TorrentState.PAUSED)
                throw e
            } catch (e: Exception) {
                Lg.e(LogCategory.STORAGE, "Force recheck failed for ${metadata.name}", e)
                setState(TorrentState.ERROR)
            }
        }
        activeJob = job
        return job
    }

    fun stop() {
        activeJob?.cancel()
        statsJob?.cancel()
        announceJob?.cancel()
        dhtJob?.cancel()
        peerManager.disconnectAll()
        storage.close()
        setState(TorrentState.QUEUED)
    }

    fun updateMaxPeerConnections(limit: Int) {
        peerManager.updateMaxConnections(limit)
    }
}
