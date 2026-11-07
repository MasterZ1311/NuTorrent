package com.nutorrent.torrent.engine

import com.nutorrent.core.model.ResourceGovernor
import com.nutorrent.core.model.ResourceGovernorConfig
import com.nutorrent.core.model.TorrentId
import com.nutorrent.core.model.TorrentMetadata
import com.nutorrent.core.model.TorrentState
import com.nutorrent.core.util.Lg
import com.nutorrent.core.util.LogCategory
import com.nutorrent.data.storage.TorrentStorage
import com.nutorrent.torrent.download.TorrentSession
import com.nutorrent.torrent.tracker.TrackerClient
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class TorrentEngine(
    val defaultMaxPeerConnections: Int = 80,
    private val trackerClient: TrackerClient? = null,
    val governor: ResourceGovernor = ResourceGovernor(),
    val bandwidthManager: com.nutorrent.torrent.network.BandwidthManager = com.nutorrent.torrent.network.BandwidthManager(),
    val context: android.content.Context? = null
) {
    private val engineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Lg.e(LogCategory.ENGINE, "Uncaught engine coroutine exception: ${throwable.message}", throwable)
    }
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + engineExceptionHandler)
    private val sessions = ConcurrentHashMap<TorrentId, TorrentSession>()
    private val queueOrder = CopyOnWriteArrayList<TorrentId>()
    val engineStartTime: Long = System.currentTimeMillis()

    fun getSessionUptimeMs(): Long = System.currentTimeMillis() - engineStartTime

    val lpdSubsystem = com.nutorrent.torrent.lpd.LpdSubsystem(engineScope)
    val dhtNode = com.nutorrent.torrent.dht.DhtNode(scope = engineScope)
    val incomingPeerServer = com.nutorrent.torrent.network.IncomingPeerServer(engineScope)

    private val metadataFetchers = ConcurrentHashMap<TorrentId, com.nutorrent.torrent.metadata.MagnetMetadataFetcher>()
    private val _metadataFetchersFlow = MutableStateFlow<Map<TorrentId, com.nutorrent.torrent.metadata.MagnetMetadataFetcher>>(emptyMap())
    val metadataFetchersFlow: StateFlow<Map<TorrentId, com.nutorrent.torrent.metadata.MagnetMetadataFetcher>> = _metadataFetchersFlow.asStateFlow()

    private val _activeSessionsFlow = MutableStateFlow<List<TorrentSession>>(emptyList())
    val activeSessionsFlow: StateFlow<List<TorrentSession>> = _activeSessionsFlow.asStateFlow()

    private val _queueOrderFlow = MutableStateFlow<List<TorrentId>>(emptyList())
    val queueOrderFlow: StateFlow<List<TorrentId>> = _queueOrderFlow.asStateFlow()

    private val _totalDownloadSpeedBps = MutableStateFlow(0L)
    val totalDownloadSpeedBps: StateFlow<Long> = _totalDownloadSpeedBps.asStateFlow()

    private val _totalUploadSpeedBps = MutableStateFlow(0L)
    val totalUploadSpeedBps: StateFlow<Long> = _totalUploadSpeedBps.asStateFlow()

    private var statsJob: Job? = null

    var isWifiOnly: Boolean = false
        private set

    var isWifiConnected: Boolean = false
        private set

    var isNetworkConnected: Boolean = true
        private set

    init {
        lpdSubsystem.onPeerDiscovered = { infoHashHex, endpoint ->
            try {
                val id = TorrentId.fromHex(infoHashHex)
                sessions[id]?.peerManager?.addPeers(listOf(endpoint))
            } catch (e: Exception) {
                // Ignore invalid hex
            }
        }
        lpdSubsystem.start()
        dhtNode.start()

        val listenPort = incomingPeerServer.start()
        if (listenPort > 0) {
            engineScope.launch {
                com.nutorrent.torrent.network.UpnpClient.forwardPort(listenPort, context)
            }
        }

        startStatsTicker()
    }

    private fun startStatsTicker() {
        statsJob?.cancel()
        statsJob = engineScope.launch {
            while (isActive) {
                delay(1000L)
                var sumDown = 0L
                var sumUp = 0L
                for (session in sessions.values) {
                    val progress = session.progress.value
                    sumDown += progress.downloadSpeedBps
                    sumUp += progress.uploadSpeedBps
                }
                _totalDownloadSpeedBps.value = sumDown
                _totalUploadSpeedBps.value = sumUp
            }
        }
    }

    fun createSession(
        metadata: TorrentMetadata,
        storage: TorrentStorage,
        initialPeers: Collection<com.nutorrent.core.model.PeerEndpoint> = emptyList()
    ): TorrentSession {
        val baseLimit = if (isWifiConnected) maxOf(defaultMaxPeerConnections, 150) else defaultMaxPeerConnections
        val allocatedPeerLimit = governor.allocatePeerLimit(baseLimit, sessions.size + 1)
        val port = if (incomingPeerServer.boundPort > 0) incomingPeerServer.boundPort else 6881
        val session = if (trackerClient != null) {
            TorrentSession(metadata, storage, trackerClient, engineScope, allocatedPeerLimit, bandwidthManager, dhtNode = dhtNode, listenPort = port, initialPeers = initialPeers)
        } else {
            TorrentSession(metadata, storage, scope = engineScope, maxPeerConnections = allocatedPeerLimit, bandwidthManager = bandwidthManager, dhtNode = dhtNode, listenPort = port, initialPeers = initialPeers)
        }

        val id = metadata.torrentId
        sessions[id] = session
        if (!queueOrder.contains(id)) {
            queueOrder.add(id)
        }

        incomingPeerServer.registerSession(id.bytes) { socket, handshake ->
            session.peerManager.acceptIncomingPeer(socket, handshake)
        }

        session.onStateChanged = { _ ->
            processQueue()
        }

        observeSessionState(session)
        syncState()
        engineScope.launch {
            lpdSubsystem.announce(metadata.torrentId.hex)
        }
        return session
    }

    fun replaceSession(metadata: TorrentMetadata, storage: TorrentStorage): TorrentSession {
        val id = metadata.torrentId
        sessions[id]?.stop()
        return createSession(metadata, storage)
    }

    suspend fun setGlobalDownloadLimit(limitBps: Long) {
        bandwidthManager.setGlobalDownloadLimit(limitBps)
    }

    suspend fun setGlobalUploadLimit(limitBps: Long) {
        bandwidthManager.setGlobalUploadLimit(limitBps)
    }

    suspend fun setTorrentDownloadLimit(infoHash: String, limitBps: Long) {
        bandwidthManager.setTorrentDownloadLimit(infoHash, limitBps)
    }

    suspend fun setTorrentUploadLimit(infoHash: String, limitBps: Long) {
        bandwidthManager.setTorrentUploadLimit(infoHash, limitBps)
    }

    private fun observeSessionState(session: TorrentSession) {
        engineScope.launch {
            session.state.collectLatest { _ ->
                processQueue()
            }
        }
    }

    private var isProcessingQueue = false

    @Synchronized
    fun processQueue() {
        if (isProcessingQueue) return
        isProcessingQueue = true
        try {
            if (isWifiOnly && !isWifiConnected) return
            if (batteryPaused) return

            val activeDownloads = sessions.values.count {
                val s = it.state.value
                s.isActive && s != TorrentState.SEEDING
            }

            var availableSlots = (governor.config.maxActiveDownloads - activeDownloads).coerceAtLeast(0)

            for (id in queueOrder) {
                if (availableSlots <= 0) break
                val session = sessions[id] ?: continue
                if (session.state.value == TorrentState.QUEUED) {
                    session.start()
                    availableSlots--
                }
            }
        } finally {
            isProcessingQueue = false
        }
    }

    fun startOrQueue(torrentId: TorrentId) {
        val session = sessions[torrentId] ?: return
        if (isWifiOnly && !isWifiConnected) {
            session.pause(com.nutorrent.core.model.PauseReason.RULE_WIFI_ONLY)
            return
        }
        if (batteryPaused) {
            session.pause(currentBatteryPauseReason)
            return
        }
        val activeDownloads = sessions.values.count {
            val s = it.state.value
            s.isActive && s != TorrentState.SEEDING
        }
        if (governor.canStartDownload(activeDownloads)) {
            session.start()
        } else {
            session.setQueued()
        }
    }

    fun getSession(torrentId: TorrentId): TorrentSession? = sessions[torrentId]

    fun pauseSession(torrentId: TorrentId) {
        sessions[torrentId]?.pause(com.nutorrent.core.model.PauseReason.USER_MANUAL)
        processQueue()
    }

    fun resumeSession(torrentId: TorrentId) {
        startOrQueue(torrentId)
    }

    fun forceRecheck(torrentId: TorrentId): Job? = sessions[torrentId]?.forceRecheck()

    fun removeSession(torrentId: TorrentId) {
        sessions[torrentId]?.let { session ->
            incomingPeerServer.unregisterSession(session.metadata.torrentId.bytes)
            session.stop()
            sessions.remove(torrentId)
            queueOrder.remove(torrentId)
            syncState()
            processQueue()
        }
    }

    @Synchronized
    fun moveUpInQueue(torrentId: TorrentId) {
        val idx = queueOrder.indexOf(torrentId)
        if (idx > 0) {
            queueOrder.removeAt(idx)
            queueOrder.add(idx - 1, torrentId)
            syncState()
            processQueue()
        }
    }

    @Synchronized
    fun moveDownInQueue(torrentId: TorrentId) {
        val idx = queueOrder.indexOf(torrentId)
        if (idx >= 0 && idx < queueOrder.size - 1) {
            queueOrder.removeAt(idx)
            queueOrder.add(idx + 1, torrentId)
            syncState()
            processQueue()
        }
    }

    fun getQueuePosition(torrentId: TorrentId): Int {
        val idx = queueOrder.indexOf(torrentId)
        return if (idx >= 0) idx + 1 else -1
    }

    private fun syncState() {
        _activeSessionsFlow.value = sessions.values.toList()
        _queueOrderFlow.value = queueOrder.toList()
    }

    var batteryPaused: Boolean = false
        private set

    var currentBatteryPauseReason: com.nutorrent.core.model.PauseReason = com.nutorrent.core.model.PauseReason.NONE
        private set

    var schedulerThrottled: Boolean = false
        private set

    fun onBatteryChanged(
        status: com.nutorrent.core.device.BatteryStatus,
        pauseOnLow: Boolean,
        pauseOnPowerSave: Boolean,
        batteryThresholdPct: Int = 15,
        downloadOnlyWhileCharging: Boolean = false
    ) {
        val isLow = status.batteryPct < batteryThresholdPct && !status.isCharging
        val notCharging = downloadOnlyWhileCharging && !status.isCharging
        val powerSave = pauseOnPowerSave && status.isPowerSaveMode

        val shouldPause = (pauseOnLow && isLow) || powerSave || notCharging
        val reason = when {
            notCharging -> com.nutorrent.core.model.PauseReason.RULE_NOT_CHARGING
            pauseOnLow && isLow -> com.nutorrent.core.model.PauseReason.RULE_LOW_BATTERY
            powerSave -> com.nutorrent.core.model.PauseReason.RULE_BATTERY_SAVER
            else -> com.nutorrent.core.model.PauseReason.NONE
        }

        if (shouldPause != batteryPaused || (shouldPause && reason != currentBatteryPauseReason)) {
            batteryPaused = shouldPause
            currentBatteryPauseReason = reason
            Lg.i(LogCategory.ENGINE, "Battery status updated: shouldPause=$shouldPause, reason=$reason (level=${status.batteryPct}%, charging=${status.isCharging}, powerSave=${status.isPowerSaveMode})")
            if (shouldPause) {
                for (session in sessions.values) {
                    if (session.state.value.isActive && session.pauseReason.value != com.nutorrent.core.model.PauseReason.USER_MANUAL) {
                        session.pause(reason)
                    }
                }
            } else {
                // Resume ONLY sessions paused by battery rules; NEVER override manual pause!
                for (session in sessions.values) {
                    val pr = session.pauseReason.value
                    if (pr == com.nutorrent.core.model.PauseReason.RULE_LOW_BATTERY ||
                        pr == com.nutorrent.core.model.PauseReason.RULE_BATTERY_SAVER ||
                        pr == com.nutorrent.core.model.PauseReason.RULE_NOT_CHARGING
                    ) {
                        session.setQueued()
                    }
                }
                processQueue()
            }
        }
    }

    suspend fun applyScheduler(
        enabled: Boolean,
        startHour: Int,
        endHour: Int,
        limitBps: Long,
        defaultLimitBps: Long,
        currentHour: Int = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    ) {
        if (!enabled) {
            schedulerThrottled = false
            bandwidthManager.setGlobalDownloadLimit(defaultLimitBps)
            return
        }

        val inWindow = DownloadScheduler.isInsideWindow(currentHour, startHour, endHour)
        schedulerThrottled = inWindow
        if (inWindow) {
            bandwidthManager.setGlobalDownloadLimit(limitBps)
        } else {
            bandwidthManager.setGlobalDownloadLimit(defaultLimitBps)
        }
    }

    fun onNetworkChanged(isWifi: Boolean, isConnected: Boolean) {
        Lg.d(LogCategory.ENGINE, "Network changed: isWifi=$isWifi, isConnected=$isConnected")
        isWifiConnected = isWifi
        isNetworkConnected = isConnected
        val targetPeerLimit = if (isWifi) maxOf(defaultMaxPeerConnections, 50) else defaultMaxPeerConnections
        for (session in sessions.values) {
            session.updateMaxPeerConnections(targetPeerLimit)
        }

        val isBlocked = !isConnected || (isWifiOnly && !isWifi)
        val reason = if (isWifiOnly && !isWifi) {
            com.nutorrent.core.model.PauseReason.RULE_WIFI_ONLY
        } else {
            com.nutorrent.core.model.PauseReason.RULE_MOBILE_DATA
        }

        if (isBlocked) {
            // Pause active downloading sessions
            for (session in sessions.values) {
                if (session.state.value.isActive && session.pauseReason.value != com.nutorrent.core.model.PauseReason.USER_MANUAL) {
                    session.pause(reason)
                }
            }
        } else {
            // Auto-resume ONLY sessions paused by network rules; NEVER touch USER_MANUAL!
            for (session in sessions.values) {
                val pr = session.pauseReason.value
                if (pr == com.nutorrent.core.model.PauseReason.RULE_WIFI_ONLY ||
                    pr == com.nutorrent.core.model.PauseReason.RULE_MOBILE_DATA
                ) {
                    session.setQueued()
                }
            }
            processQueue()
            for (session in sessions.values) {
                if (session.state.value.isActive) {
                    session.peerManager.reconnectIfNeeded()
                }
            }
        }
    }

    fun setWifiOnlyMode(enabled: Boolean) {
        if (isWifiOnly != enabled) {
            isWifiOnly = enabled
            onNetworkChanged(isWifi = isWifiConnected, isConnected = isNetworkConnected)
        }
    }

    fun setLpdEnabled(enabled: Boolean) {
        if (enabled) {
            lpdSubsystem.start()
        } else {
            lpdSubsystem.stop()
        }
    }

    fun updateGovernorConfig(newConfig: ResourceGovernorConfig) {
        governor.updateConfig(newConfig)
        processQueue()
    }

    fun startMetadataFetch(
        torrentId: TorrentId,
        trackers: List<String>,
        peerHints: List<String> = emptyList(),
        exactSources: List<String> = emptyList(),
        onResolved: suspend (TorrentMetadata, ByteArray, Set<com.nutorrent.core.model.PeerEndpoint>) -> Unit
    ): com.nutorrent.torrent.metadata.MagnetMetadataFetcher {
        metadataFetchers[torrentId]?.stop()
        incomingPeerServer.unregisterSession(torrentId.bytes)
        val port = if (incomingPeerServer.boundPort > 0) incomingPeerServer.boundPort else 6881
        val fetcher = com.nutorrent.torrent.metadata.MagnetMetadataFetcher(
            torrentId = torrentId,
            initialTrackers = trackers,
            trackerClient = trackerClient ?: com.nutorrent.torrent.tracker.DefaultTrackerClient(),
            dhtNode = dhtNode,
            scope = engineScope,
            peerHints = peerHints,
            exactSources = exactSources,
            listenPort = port,
            onResolved = { meta, raw, discoveredPeers ->
                incomingPeerServer.unregisterSession(torrentId.bytes)
                metadataFetchers.remove(torrentId)
                _metadataFetchersFlow.value = metadataFetchers.toMap()
                onResolved(meta, raw, discoveredPeers)
            }
        )
        metadataFetchers[torrentId] = fetcher
        _metadataFetchersFlow.value = metadataFetchers.toMap()

        // Hook incoming peer connections for this torrent during metadata exchange
        incomingPeerServer.registerSession(torrentId.bytes) { socket, handshake ->
            fetcher.acceptIncomingPeer(socket, handshake)
        }

        fetcher.start()
        return fetcher
    }

    fun cancelMetadataFetch(torrentId: TorrentId) {
        incomingPeerServer.unregisterSession(torrentId.bytes)
        metadataFetchers.remove(torrentId)?.let {
            it.stop()
            _metadataFetchersFlow.value = metadataFetchers.toMap()
        }
    }

    fun getMetadataFetcher(torrentId: TorrentId): com.nutorrent.torrent.metadata.MagnetMetadataFetcher? = metadataFetchers[torrentId]

    fun shutdown() {
        statsJob?.cancel()
        lpdSubsystem.stop()
        dhtNode.stop()
        val portToRelease = incomingPeerServer.boundPort
        incomingPeerServer.stop()
        if (portToRelease > 0) {
            engineScope.launch {
                com.nutorrent.torrent.network.UpnpClient.deletePortMapping(portToRelease)
            }
        }
        for (fetcher in metadataFetchers.values) {
            fetcher.stop()
        }
        metadataFetchers.clear()
        _metadataFetchersFlow.value = emptyMap()
        for (session in sessions.values) {
            session.stop()
        }
        sessions.clear()
        queueOrder.clear()
        syncState()
    }
}
