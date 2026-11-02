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
    val defaultMaxPeerConnections: Int = 30,
    private val trackerClient: TrackerClient? = null,
    val governor: ResourceGovernor = ResourceGovernor(),
    val bandwidthManager: com.nutorrent.torrent.network.BandwidthManager = com.nutorrent.torrent.network.BandwidthManager(),
    val context: android.content.Context? = null
) {
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sessions = ConcurrentHashMap<TorrentId, TorrentSession>()
    private val queueOrder = CopyOnWriteArrayList<TorrentId>()

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
        storage: TorrentStorage
    ): TorrentSession {
        val baseLimit = if (isWifiConnected) maxOf(defaultMaxPeerConnections, 50) else defaultMaxPeerConnections
        val allocatedPeerLimit = governor.allocatePeerLimit(baseLimit, sessions.size + 1)
        val port = if (incomingPeerServer.boundPort > 0) incomingPeerServer.boundPort else 6881
        val session = if (trackerClient != null) {
            TorrentSession(metadata, storage, trackerClient, engineScope, allocatedPeerLimit, bandwidthManager, dhtNode = dhtNode, listenPort = port)
        } else {
            TorrentSession(metadata, storage, scope = engineScope, maxPeerConnections = allocatedPeerLimit, bandwidthManager = bandwidthManager, dhtNode = dhtNode, listenPort = port)
        }

        val id = metadata.torrentId
        sessions[id] = session
        if (!queueOrder.contains(id)) {
            queueOrder.add(id)
        }

        incomingPeerServer.registerSession(id.bytes) { socket, handshake ->
            session.peerManager.acceptIncomingPeer(socket, handshake)
        }

        observeSessionState(session)
        syncState()
        engineScope.launch {
            lpdSubsystem.announce(metadata.torrentId.hex)
        }
        return session
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

    @Synchronized
    fun processQueue() {
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
    }

    fun startOrQueue(torrentId: TorrentId) {
        val session = sessions[torrentId] ?: return
        val activeDownloads = sessions.values.count {
            val s = it.state.value
            s.isActive && s != TorrentState.SEEDING
        }
        if (governor.canStartDownload(activeDownloads)) {
            session.start()
        }
    }

    fun getSession(torrentId: TorrentId): TorrentSession? = sessions[torrentId]

    fun pauseSession(torrentId: TorrentId) {
        sessions[torrentId]?.pause()
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

    var schedulerThrottled: Boolean = false
        private set

    fun onBatteryChanged(status: com.nutorrent.core.device.BatteryStatus, pauseOnLow: Boolean, pauseOnPowerSave: Boolean) {
        val shouldPause = (pauseOnLow && status.isLowBattery) || (pauseOnPowerSave && status.isPowerSaveMode)
        if (shouldPause != batteryPaused) {
            batteryPaused = shouldPause
            Lg.i(LogCategory.ENGINE, "Battery status updated: shouldPause=$shouldPause (level=${status.batteryPct}%, powerSave=${status.isPowerSaveMode})")
            if (shouldPause) {
                for (session in sessions.values) {
                    if (session.state.value.isActive) {
                        session.pause()
                    }
                }
            } else {
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
        val targetPeerLimit = if (isWifi) maxOf(defaultMaxPeerConnections, 50) else defaultMaxPeerConnections
        for (session in sessions.values) {
            session.updateMaxPeerConnections(targetPeerLimit)
        }

        if (!isConnected || (isWifiOnly && !isWifi)) {
            // Pause active downloading sessions
            for (session in sessions.values) {
                if (session.state.value.isActive) {
                    session.pause()
                }
            }
        } else {
            // Auto-resume paused sessions if network recovered
            processQueue()
            for (session in sessions.values) {
                if (session.state.value.isActive) {
                    session.peerManager.reconnectIfNeeded()
                }
            }
        }
    }

    fun setWifiOnlyMode(enabled: Boolean) {
        isWifiOnly = enabled
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
        onResolved: suspend (TorrentMetadata, ByteArray) -> Unit
    ): com.nutorrent.torrent.metadata.MagnetMetadataFetcher {
        metadataFetchers[torrentId]?.stop()
        val fetcher = com.nutorrent.torrent.metadata.MagnetMetadataFetcher(
            torrentId = torrentId,
            initialTrackers = trackers,
            trackerClient = trackerClient ?: com.nutorrent.torrent.tracker.DefaultTrackerClient(),
            dhtNode = dhtNode,
            scope = engineScope,
            onResolved = { meta, raw ->
                metadataFetchers.remove(torrentId)
                _metadataFetchersFlow.value = metadataFetchers.toMap()
                onResolved(meta, raw)
            }
        )
        metadataFetchers[torrentId] = fetcher
        _metadataFetchersFlow.value = metadataFetchers.toMap()
        fetcher.start()
        return fetcher
    }

    fun cancelMetadataFetch(torrentId: TorrentId) {
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
