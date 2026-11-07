package com.nutorrent.torrent.download

import com.nutorrent.core.model.AppError
import com.nutorrent.core.model.BlockRequest
import com.nutorrent.core.model.PauseReason
import com.nutorrent.core.model.PeerBitfield
import com.nutorrent.core.model.PeerStateInfo
import com.nutorrent.core.model.TorrentMetadata
import com.nutorrent.core.model.TorrentProgress
import com.nutorrent.core.model.TorrentState
import com.nutorrent.core.model.TrackerInfo
import com.nutorrent.core.util.Lg
import com.nutorrent.core.util.LogCategory
import com.nutorrent.data.storage.FastResumeData
import com.nutorrent.data.storage.FastResumeManager
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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

class TorrentSession(
    val metadata: TorrentMetadata,
    var storage: TorrentStorage,
    val trackerClient: TrackerClient = DefaultTrackerClient(),
    private val scope: CoroutineScope,
    maxPeerConnections: Int = 60,
    val bandwidthManager: com.nutorrent.torrent.network.BandwidthManager? = null,
    val dhtNode: com.nutorrent.torrent.dht.DhtNode? = null,
    val listenPort: Int = 6881,
    val initialPeers: Collection<com.nutorrent.core.model.PeerEndpoint> = emptyList()
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

    private val _pauseReason = MutableStateFlow(PauseReason.NONE)
    val pauseReason: StateFlow<PauseReason> = _pauseReason.asStateFlow()

    var onStateChanged: ((TorrentState) -> Unit)? = null

    private fun setState(newState: TorrentState) {
        val changed = synchronized(this) {
            if ((_state.value == TorrentState.PAUSED || _state.value == TorrentState.QUEUED || _state.value == TorrentState.ERROR || !_state.value.isActive) &&
                newState != TorrentState.PAUSED && newState != TorrentState.ERROR) {
                return
            }
            if (_state.value != newState) {
                Lg.i(LogCategory.ENGINE, "[TORRENT_STATE] ${metadata.name} -> $newState")
                _state.value = newState
                true
            } else {
                false
            }
        }
        if (changed) {
            onStateChanged?.invoke(newState)
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
        (metadata.announceList + com.nutorrent.torrent.metadata.MagnetMetadataFetcher.FALLBACK_TRACKERS + com.nutorrent.torrent.tracker.CommunityTrackersSync.getCachedTrackers()).distinct().map { TrackerInfo(url = it) }
    )
    val trackers: StateFlow<List<TrackerInfo>> = _trackers.asStateFlow()

    private val _verificationProgress = MutableStateFlow(0f)
    val verificationProgress: StateFlow<Float> = _verificationProgress.asStateFlow()

    private val _lastError = MutableStateFlow<AppError?>(null)
    val lastError: StateFlow<AppError?> = _lastError.asStateFlow()

    internal var trackerReportedSeeds: Int? = null

    fun setLastError(error: AppError?) {
        _lastError.value = error
    }

    fun setError(error: AppError) {
        val changed = synchronized(this) {
            activeJob?.cancel()
            statsJob?.cancel()
            announceJob?.cancel()
            dhtJob?.cancel()
            peerManager.disconnectAll()
            storage.close()
            _lastError.value = error
            _pauseReason.value = PauseReason.NONE
            val stateChanged = (_state.value != TorrentState.ERROR)
            _state.value = TorrentState.ERROR
            _progress.value = _progress.value.copy(downloadSpeedBps = 0L, etaSeconds = null, connectedPeers = 0)
            stateChanged
        }
        if (changed) {
            onStateChanged?.invoke(TorrentState.ERROR)
        }
    }

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
                        } else if (pieceManager.completedPiecesCount % 10 == 0) {
                            FastResumeManager.save(
                                FastResumeData(
                                    infoHash = metadata.torrentId.hex,
                                    isComplete = false,
                                    completedPieces = pieceManager.completedPiecesCount,
                                    totalPieces = metadata.piecesCount,
                                    verifiedBytes = pieceManager.verifiedBytes,
                                    totalBytes = metadata.totalLength,
                                    bitfield = pieceManager.ourBitfield.bytes.clone()
                                )
                            )
                        }
                    } catch (e: AppError) {
                        pieceManager.onPieceWriteFailed(verifiedResult.pieceIndex)
                        Lg.e(LogCategory.STORAGE, "Storage error writing piece ${verifiedResult.pieceIndex}: ${e.userMessage}", e)
                        setError(e)
                    } catch (e: Throwable) {
                        pieceManager.onPieceWriteFailed(verifiedResult.pieceIndex)
                        Lg.e(LogCategory.STORAGE, "Failed writing piece ${verifiedResult.pieceIndex}", e)
                        val appError = AppError.fromThrowable(e, storage.getAvailableSpace(), metadata.totalLength)
                        setError(appError)
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
        if (initialPeers.isNotEmpty()) {
            peerManager.addPeers(initialPeers.toList())
        }
    }

    /**
     * Instantly sets the session to completed/seeding state from cached data without disk verification.
     */
    @Synchronized
    fun fastResumeSeeding() {
        pieceManager.markAllPiecesComplete()
        _verificationProgress.value = 1f
        _progress.value = _progress.value.copy(
            verifiedBytes = metadata.totalLength,
            completedPieces = metadata.piecesCount,
            downloadSpeedBps = 0L,
            etaSeconds = null
        )
        _state.value = TorrentState.SEEDING
    }

    /**
     * Restores state from cached fast-resume data.
     */
    @Synchronized
    fun restoreFastResume(resumeData: FastResumeData) {
        if (resumeData.isComplete) {
            fastResumeSeeding()
        } else {
            pieceManager.restoreFromBitfield(resumeData.bitfield)
            _verificationProgress.value = 1f
            _progress.value = _progress.value.copy(
                verifiedBytes = pieceManager.verifiedBytes,
                completedPieces = pieceManager.completedPiecesCount
            )
        }
    }

    fun start() {
        data class StartPlan(
            val targetState: TorrentState,
            val cachedResume: FastResumeData?,
            val isAlreadyComplete: Boolean
        )

        val plan: StartPlan? = synchronized(this) {
            if (_state.value.isActive) return
            _pauseReason.value = PauseReason.NONE
            _lastError.value = null
            activeJob?.cancel()

            // Synchronously determine and set the active state so the UI and engine immediately reflect it without delay
            val cachedResume = FastResumeManager.load(metadata.torrentId.hex)
            val isAlreadyComplete = cachedResume?.isComplete == true || pieceManager.isComplete() || _state.value == TorrentState.SEEDING || _state.value == TorrentState.COMPLETED

            val targetState = if (isAlreadyComplete) {
                fastResumeSeeding()
                TorrentState.SEEDING
            } else if (cachedResume != null && !cachedResume.isComplete && (cachedResume.completedPieces > 0 || pieceManager.completedPiecesCount > 0)) {
                if (pieceManager.completedPiecesCount == 0 && cachedResume.bitfield.isNotEmpty()) {
                    pieceManager.restoreFromBitfield(cachedResume.bitfield)
                }
                _verificationProgress.value = 1f
                _progress.value = _progress.value.copy(
                    verifiedBytes = pieceManager.verifiedBytes,
                    completedPieces = pieceManager.completedPiecesCount
                )
                TorrentState.DISCOVERING_PEERS
            } else {
                // New torrent or uncached: start directly in DISCOVERING_PEERS without flashing VERIFYING
                _verificationProgress.value = 1f
                TorrentState.DISCOVERING_PEERS
            }

            _state.value = targetState
            StartPlan(targetState, cachedResume, isAlreadyComplete)
        }

        if (plan == null) return

        Lg.i(LogCategory.ENGINE, "[TORRENT_STATE] ${metadata.name} -> ${plan.targetState}")
        onStateChanged?.invoke(plan.targetState)

        val cachedResume = plan.cachedResume
        val isAlreadyComplete = plan.isAlreadyComplete

        val job = synchronized(this) {
            if (_state.value != plan.targetState) return
            val launched = scope.launch(Dispatchers.IO) {
            try {
                storage.prepare()
                ensureActive()

                if (isAlreadyComplete) {
                    Lg.i(LogCategory.STORAGE, "Instant fast-resuming completed torrent ${metadata.name} without verification!")
                    FastResumeManager.save(
                        FastResumeData(
                            infoHash = metadata.torrentId.hex,
                            isComplete = true,
                            completedPieces = metadata.piecesCount,
                            totalPieces = metadata.piecesCount,
                            verifiedBytes = metadata.totalLength,
                            totalBytes = metadata.totalLength,
                            bitfield = pieceManager.ourBitfield.bytes.clone()
                        )
                    )
                } else if (cachedResume != null && !cachedResume.isComplete && pieceManager.completedPiecesCount > 0) {
                    Lg.i(LogCategory.STORAGE, "Fast-resuming partial torrent ${metadata.name} from cached bitfield")
                    _verificationProgress.value = 1f
                } else if (storage.areFilesComplete()) {
                    Lg.i(LogCategory.STORAGE, "Files are complete on disk for ${metadata.name}. Fast-resuming to seeding!")
                    pieceManager.markAllPiecesComplete()
                    _verificationProgress.value = 1f
                    _progress.value = _progress.value.copy(
                        verifiedBytes = metadata.totalLength,
                        completedPieces = metadata.piecesCount,
                        downloadSpeedBps = 0L,
                        etaSeconds = null
                    )
                    FastResumeManager.save(
                        FastResumeData(
                            infoHash = metadata.torrentId.hex,
                            isComplete = true,
                            completedPieces = metadata.piecesCount,
                            totalPieces = metadata.piecesCount,
                            verifiedBytes = metadata.totalLength,
                            totalBytes = metadata.totalLength,
                            bitfield = pieceManager.ourBitfield.bytes.clone()
                        )
                    )
                    setState(TorrentState.SEEDING)
                } else if (!storage.hasExistingDiskData()) {
                    Lg.i(LogCategory.STORAGE, "New/empty torrent ${metadata.name}: beginning download immediately without disk scan")
                    _verificationProgress.value = 1f
                } else {
                    // Only for un-cached pre-existing partial data on disk do we verify
                    _verificationProgress.value = 0f
                    setState(TorrentState.VERIFYING)

                    storage.verifyStoredPieces(
                        onProgress = { _, _, pct ->
                            _verificationProgress.value = pct / 100f
                        },
                        onPieceResult = { index, isComplete ->
                            if (isComplete) {
                                pieceManager.markPieceCompleteFromStorage(index)
                            } else {
                                pieceManager.markPieceIncomplete(index)
                            }
                        }
                    )
                    ensureActive()
                    if (!isActive || _state.value == TorrentState.PAUSED || _state.value == TorrentState.QUEUED) return@launch

                    _verificationProgress.value = 1f
                    updateProgressSnapshot()

                    // Save fast-resume immediately so subsequent startups are instant
                    FastResumeManager.save(
                        FastResumeData(
                            infoHash = metadata.torrentId.hex,
                            isComplete = pieceManager.isComplete(),
                            completedPieces = pieceManager.completedPiecesCount,
                            totalPieces = metadata.piecesCount,
                            verifiedBytes = pieceManager.verifiedBytes,
                            totalBytes = metadata.totalLength,
                            bitfield = pieceManager.ourBitfield.bytes.clone()
                        )
                    )
                }

                ensureActive()
                if (!isActive || _state.value == TorrentState.PAUSED || _state.value == TorrentState.QUEUED || _state.value == TorrentState.ERROR) return@launch

                if (pieceManager.isComplete()) {
                    setState(TorrentState.SEEDING)
                } else if (_state.value == TorrentState.DISCOVERING_PEERS || _state.value == TorrentState.VERIFYING) {
                    setState(TorrentState.DOWNLOADING)
                }

                ensureActive()
                if (!isActive || _state.value == TorrentState.PAUSED || _state.value == TorrentState.QUEUED || _state.value == TorrentState.ERROR) return@launch

                startStatsTicker()
                launch { announceToTrackers(TrackerEvent.STARTED) }
                queryDhtForPeers()
                peerManager.reconnectIfNeeded()
                for (peer in peerManager.getActiveConnections()) {
                    requestWorkForPeer(peer)
                }

                // Periodic maintenance loop: choking, peer reconnects, stalled request recovery, peer cycling, PEX
                var loopCounter = 0
                while (isActive && (_state.value == TorrentState.DOWNLOADING || _state.value == TorrentState.SEEDING || _state.value == TorrentState.DISCOVERING_PEERS)) {
                    val isLowPeers = peerManager.activeCount < 8
                    val delayMs = if (isLowPeers) 2500L else 5000L
                    delay(delayMs)
                    loopCounter++
                    uploadManager.updateChoking(peerManager.getActiveConnections(), isSeeding = _state.value == TorrentState.SEEDING)
                    
                    peerManager.reconnectIfNeeded()

                    // Stalled Request Recovery:
                    // Sweep requests that have stalled (> 7s) so blocks can be reassigned immediately to fast seeders
                    val now = System.currentTimeMillis()
                    val active = peerManager.getActiveConnections()
                    for (p in active) {
                        val stalled = p.sweepStalledRequests(7_000L)
                        if (stalled.isNotEmpty()) {
                            for (req in stalled) {
                                pieceManager.onBlockRequestFailed(req.pieceIndex, req.beginOffset)
                            }
                        }
                    }

                    // Feed any peers with spare pipeline capacity or newly freed blocks
                    if (_state.value == TorrentState.DOWNLOADING || _state.value == TorrentState.DISCOVERING_PEERS) {
                        for (p in active) {
                            if (!p.isChokingUs || p.allowedFastPieces.isNotEmpty()) {
                                requestWorkForPeer(p)
                            }
                        }
                    }

                    // Aggressive Peer Cycling:
                    // Disconnect peers that have kept us choked for > 25s with no in-flight requests,
                    // so that fresh peer candidates can be rotated in.
                    val hasQueuedCandidates = peerManager.totalKnownCount > peerManager.activeCount
                    for (peer in active) {
                        if (peer.isChokingUs &&
                            peer.pendingRequests.isEmpty() &&
                            peer.chokeReceivedAt > 0L &&
                            (now - peer.chokeReceivedAt) > 25_000L &&
                            peer.allowedFastPieces.isEmpty() &&
                            hasQueuedCandidates
                        ) {
                            Lg.d(LogCategory.PEER, "[CYCLE] Cycling choked peer ${peer.endpoint} (choked for ${(now - peer.chokeReceivedAt)/1000}s)")
                            peer.disconnect()
                        }
                    }

                    val isDownloading = _state.value == TorrentState.DOWNLOADING || _state.value == TorrentState.DISCOVERING_PEERS
                    val isStalledOrLow = isDownloading && (_progress.value.downloadSpeedBps == 0L || peerManager.activeCount < 8)

                    // Re-announce: if low peers, every 15s (6 cycles of 2.5s); otherwise every ~30s-60s
                    val announceCadence = if (isLowPeers) 6 else 12
                    if (isStalledOrLow && loopCounter % announceCadence == 0) {
                        launch { announceToTrackers(TrackerEvent.REGULAR) }
                        queryDhtForPeers()
                    } else if (loopCounter % 12 == 0) {
                        if (peerManager.activeCount < 5) {
                            launch { announceToTrackers(TrackerEvent.REGULAR) }
                            queryDhtForPeers()
                        }
                    }
                    if (loopCounter % 20 == 0) {
                        peerManager.broadcastPex(PexMessage(added = peerManager.getConnectedEndpoints()))
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (isActive) {
                    Lg.e(LogCategory.ENGINE, "Session error", e)
                    val appError = if (e is AppError) e else AppError.fromThrowable(e, storage.getAvailableSpace(), metadata.totalLength)
                    setError(appError)
                }
            }
        }

            activeJob = launched
            launched
        }
    }

    private fun calculateOptimalPipeline(peer: PeerConnection): Int {
        val success = peer.successfulBlocksCount
        return when {
            peer.currentDownloadRateBps > 30_000_000L || success > 500 -> 384 // ~6.0 MB in-flight for 5G/Gigabit Wi-Fi
            peer.currentDownloadRateBps > 15_000_000L || success > 250 -> 256 // ~4.0 MB in-flight
            peer.currentDownloadRateBps > 5_000_000L || success > 100 -> 128 // ~2.0 MB in-flight
            peer.currentDownloadRateBps > 1_000_000L || success > 30 -> 64 // ~1.0 MB in-flight
            else -> 32          // ~512 KB base pipeline
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
                val targetAllowedFast = when {
                    peer.successfulBlocksCount > 100 -> 64
                    peer.successfulBlocksCount > 30 -> 32
                    else -> 16
                }
                val toRequest = mutableListOf<BlockRequest>()
                while (peer.pendingRequests.size + toRequest.size < targetAllowedFast) {
                    val req = pieceManager.requestNextBlockForAllowedFast(peer.peerBitfield, peer.allowedFastPieces) ?: break
                    if (peer.pendingRequests.containsKey(req) || toRequest.contains(req)) break
                    toRequest.add(req)
                }
                if (toRequest.isNotEmpty()) {
                    val sentCount = peer.sendRequests(toRequest)
                    if (sentCount < toRequest.size) {
                        for (i in sentCount until toRequest.size) {
                            val failed = toRequest[i]
                            pieceManager.onBlockRequestFailed(failed.pieceIndex, failed.beginOffset)
                        }
                    }
                }
            }
            return
        }

        // Active unchoked peer: dynamically scale request pipeline to fully saturate available bandwidth
        val targetPipeline = calculateOptimalPipeline(peer)
        val toRequest = mutableListOf<BlockRequest>()
        while (peer.pendingRequests.size + toRequest.size < targetPipeline) {
            val req = pieceManager.requestNextBlockForPeer(peer.peerBitfield) ?: break
            if (peer.pendingRequests.containsKey(req) || toRequest.contains(req)) break
            toRequest.add(req)
        }
        if (toRequest.isNotEmpty()) {
            val sentCount = peer.sendRequests(toRequest)
            if (sentCount < toRequest.size) {
                for (i in sentCount until toRequest.size) {
                    val failed = toRequest[i]
                    pieceManager.onBlockRequestFailed(failed.pieceIndex, failed.beginOffset)
                }
            }
        }
    }

    internal suspend fun announceToTrackers(event: TrackerEvent) {
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
                            if (resp.seeders != null && resp.seeders >= 0) {
                                trackerReportedSeeds = resp.seeders
                            }
                            synchronized(updatedTrackers) {
                                updatedTrackers[idx] = trackerInfo.copy(
                                    status = "Online",
                                    peersCount = resp.peers.size,
                                    seedsCount = resp.seeders,
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

                val isSeeding = _state.value == TorrentState.SEEDING
                val speed = if (isSeeding) 0L else speedCalculator.addSample(totalRawDownloadedBytes.get())
                val uploadSpeed = uploadSpeedCalculator.addSample(uploadManager.totalUploadedBytes.get())
                val remaining = (metadata.totalLength - pieceManager.verifiedBytes).coerceAtLeast(0L)
                val eta = if (isSeeding || remaining == 0L) null else speedCalculator.calculateEta(remaining, speed)

                val connSeeds = peerManager.connectedSeedsCount
                val availSeeds = trackerReportedSeeds ?: if (connSeeds > 0) connSeeds else null

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
                    totalPeers = peerManager.totalKnownCount,
                    connectedSeeds = connSeeds,
                    availableSeeds = availSeeds
                )

                _peers.value = peerManager.getPeerStateInfos()
            }
        }
    }

    private suspend fun onDownloadCompleted() {
        setState(TorrentState.SEEDING)
        _lastError.value = null
        _progress.value = _progress.value.copy(
            verifiedBytes = metadata.totalLength,
            completedPieces = metadata.piecesCount,
            downloadSpeedBps = 0L,
            etaSeconds = null
        )
        Lg.i(LogCategory.ENGINE, "Torrent ${metadata.name} completed download. Now SEEDING to peers!")
        FastResumeManager.save(
            FastResumeData(
                infoHash = metadata.torrentId.hex,
                isComplete = true,
                completedPieces = metadata.piecesCount,
                totalPieces = metadata.piecesCount,
                verifiedBytes = metadata.totalLength,
                totalBytes = metadata.totalLength,
                bitfield = pieceManager.ourBitfield.bytes.clone()
            )
        )
        announceToTrackers(TrackerEvent.COMPLETED)
    }

    fun pause(reason: PauseReason = PauseReason.USER_MANUAL) {
        val changed = synchronized(this) {
            if (_state.value == TorrentState.PAUSED && _pauseReason.value == reason) return
            activeJob?.cancel()
            statsJob?.cancel()
            announceJob?.cancel()
            dhtJob?.cancel()
            peerManager.disconnectAll()
            storage.close()
            _pauseReason.value = reason
            val wasNotPaused = (_state.value != TorrentState.PAUSED)
            _state.value = TorrentState.PAUSED
            _progress.value = _progress.value.copy(downloadSpeedBps = 0L, etaSeconds = null, connectedPeers = 0)
            wasNotPaused
        }
        if (changed) {
            onStateChanged?.invoke(TorrentState.PAUSED)
        }
    }

    fun setQueued() {
        val changed = synchronized(this) {
            if (_state.value == TorrentState.QUEUED) return
            activeJob?.cancel()
            statsJob?.cancel()
            announceJob?.cancel()
            dhtJob?.cancel()
            peerManager.disconnectAll()
            storage.close()
            _pauseReason.value = PauseReason.QUEUED
            _state.value = TorrentState.QUEUED
            _progress.value = _progress.value.copy(downloadSpeedBps = 0L, etaSeconds = null, connectedPeers = 0)
            true
        }
        if (changed) {
            onStateChanged?.invoke(TorrentState.QUEUED)
        }
    }

    fun resume() {
        if (_state.value == TorrentState.PAUSED || _state.value == TorrentState.ERROR || _state.value == TorrentState.QUEUED) {
            _lastError.value = null
            _pauseReason.value = PauseReason.NONE
            start()
        }
    }

    fun forceRecheck(): Job {
        val wasActive = synchronized(this) {
            val active = _state.value.isActive
            activeJob?.cancel()
            statsJob?.cancel()
            announceJob?.cancel()
            dhtJob?.cancel()
            peerManager.disconnectAll()
            FastResumeManager.remove(metadata.torrentId.hex)
            _state.value = TorrentState.VERIFYING
            _verificationProgress.value = 0f
            pieceManager.resetAllPieces()
            active
        }
        onStateChanged?.invoke(TorrentState.VERIFYING)

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
                        } else {
                            pieceManager.markPieceIncomplete(index)
                        }
                    }
                )
                _verificationProgress.value = 1f
                _progress.value = _progress.value.copy(
                    verifiedBytes = pieceManager.verifiedBytes,
                    completedPieces = pieceManager.completedPiecesCount
                )

                if (pieceManager.isComplete()) {
                    FastResumeManager.save(
                        FastResumeData(
                            infoHash = metadata.torrentId.hex,
                            isComplete = true,
                            completedPieces = pieceManager.completedPiecesCount,
                            totalPieces = metadata.piecesCount,
                            verifiedBytes = pieceManager.verifiedBytes,
                            totalBytes = metadata.totalLength,
                            bitfield = pieceManager.ourBitfield.bytes.clone()
                        )
                    )
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
                val appError = if (e is AppError) e else AppError.fromThrowable(e, storage.getAvailableSpace(), metadata.totalLength)
                setError(appError)
            }
        }
        synchronized(this) {
            activeJob = job
        }
        return job
    }

    fun stop() {
        val changed = synchronized(this) {
            if (pieceManager.completedPiecesCount > 0) {
                FastResumeManager.save(
                    FastResumeData(
                        infoHash = metadata.torrentId.hex,
                        isComplete = pieceManager.isComplete(),
                        completedPieces = pieceManager.completedPiecesCount,
                        totalPieces = metadata.piecesCount,
                        verifiedBytes = pieceManager.verifiedBytes,
                        totalBytes = metadata.totalLength,
                        bitfield = pieceManager.ourBitfield.bytes.clone()
                    )
                )
            }
            activeJob?.cancel()
            statsJob?.cancel()
            announceJob?.cancel()
            dhtJob?.cancel()
            peerManager.disconnectAll()
            storage.close()
            _pauseReason.value = PauseReason.NONE
            val wasNotQueued = (_state.value != TorrentState.QUEUED)
            _state.value = TorrentState.QUEUED
            wasNotQueued
        }
        if (changed) {
            onStateChanged?.invoke(TorrentState.QUEUED)
        }
    }

    fun updateMaxPeerConnections(limit: Int) {
        peerManager.updateMaxConnections(limit)
    }
}
