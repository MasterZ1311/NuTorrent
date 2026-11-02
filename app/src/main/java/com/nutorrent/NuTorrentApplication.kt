package com.nutorrent

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.nutorrent.core.util.Lg
import com.nutorrent.core.util.LogCategory
import com.nutorrent.data.repository.SettingsRepository
import com.nutorrent.data.repository.TorrentRepository
import com.nutorrent.service.TorrentDownloadService
import com.nutorrent.torrent.engine.TorrentEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class NuTorrentApplication : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var settingsRepository: SettingsRepository
        private set

    lateinit var torrentEngine: TorrentEngine
        private set

    lateinit var torrentRepository: TorrentRepository
        private set

    lateinit var networkObserver: com.nutorrent.torrent.network.NetworkConnectivityObserver
        private set

    lateinit var batteryMonitor: com.nutorrent.core.device.BatteryMonitor
        private set

    override fun onCreate() {
        super.onCreate()

        settingsRepository = SettingsRepository(this)
        torrentEngine = TorrentEngine(
            defaultMaxPeerConnections = settingsRepository.settings.value.maxPeerConnections,
            context = this
        )
        torrentEngine.setWifiOnlyMode(settingsRepository.settings.value.wifiOnly)

        torrentRepository = TorrentRepository(this, torrentEngine, appScope, settingsRepository)

        networkObserver = com.nutorrent.torrent.network.NetworkConnectivityObserver(this)
        networkObserver.start { status ->
            torrentEngine.onNetworkChanged(isWifi = status.isWifi, isConnected = status.isConnected)
        }

        batteryMonitor = com.nutorrent.core.device.BatteryMonitor(this)
        batteryMonitor.onBatteryStateChanged = { status ->
            val s = settingsRepository.settings.value
            torrentEngine.onBatteryChanged(
                status,
                s.pauseOnLowBattery,
                s.pauseOnBatterySaver,
                s.batteryThresholdPercent,
                s.downloadOnlyWhileCharging
            )
        }
        batteryMonitor.start()

        observeSettingsChanges()
        observeActiveTransfersForService()
        observeLifetimeStats()

        appScope.launch(Dispatchers.IO) {
            try {
                com.nutorrent.torrent.tracker.CommunityTrackersSync.syncTrackers()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {}
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        for (session in torrentEngine.activeSessionsFlow.value) {
            if (session.pieceManager.completedPiecesCount > 0) {
                com.nutorrent.data.storage.FastResumeManager.save(
                    com.nutorrent.data.storage.FastResumeData(
                        infoHash = session.metadata.torrentId.hex,
                        isComplete = session.pieceManager.isComplete(),
                        completedPieces = session.pieceManager.completedPiecesCount,
                        totalPieces = session.metadata.piecesCount,
                        verifiedBytes = session.pieceManager.verifiedBytes,
                        totalBytes = session.metadata.totalLength,
                        bitfield = session.pieceManager.ourBitfield.bytes.clone()
                    )
                )
            }
        }
    }

    private fun observeLifetimeStats() {
        appScope.launch {
            var accumulatedDown = 0L
            var accumulatedUp = 0L
            var ticks = 0
            while (isActive) {
                delay(1000L)
                val downSpeed = torrentEngine.totalDownloadSpeedBps.value
                val upSpeed = torrentEngine.totalUploadSpeedBps.value
                if (downSpeed > 0) accumulatedDown += downSpeed
                if (upSpeed > 0) accumulatedUp += upSpeed
                ticks++

                if (ticks >= 30) {
                    if (accumulatedDown > 0) {
                        settingsRepository.addLifetimeDownloaded(accumulatedDown)
                        accumulatedDown = 0L
                    }
                    if (accumulatedUp > 0) {
                        settingsRepository.addLifetimeUploaded(accumulatedUp)
                        accumulatedUp = 0L
                    }
                    ticks = 0
                }
            }
        }
    }

    private fun observeSettingsChanges() {
        appScope.launch {
            settingsRepository.settings
                .distinctUntilChanged { old, new ->
                    old.wifiOnly == new.wifiOnly &&
                    old.enableLpd == new.enableLpd &&
                    old.uploadSpeedLimitBps == new.uploadSpeedLimitBps &&
                    old.downloadSpeedLimitBps == new.downloadSpeedLimitBps &&
                    old.maxActiveDownloads == new.maxActiveDownloads &&
                    old.maxActiveSeeds == new.maxActiveSeeds &&
                    old.maxPeerConnections == new.maxPeerConnections &&
                    old.schedulerEnabled == new.schedulerEnabled &&
                    old.schedulerStartHour == new.schedulerStartHour &&
                    old.schedulerEndHour == new.schedulerEndHour &&
                    old.schedulerSpeedLimitBps == new.schedulerSpeedLimitBps &&
                    old.pauseOnLowBattery == new.pauseOnLowBattery &&
                    old.pauseOnBatterySaver == new.pauseOnBatterySaver &&
                    old.batteryThresholdPercent == new.batteryThresholdPercent &&
                    old.downloadOnlyWhileCharging == new.downloadOnlyWhileCharging
                }
                .collectLatest { s ->
                torrentEngine.setWifiOnlyMode(s.wifiOnly)
                torrentEngine.setLpdEnabled(s.enableLpd)
                torrentEngine.setGlobalUploadLimit(s.uploadSpeedLimitBps)
                torrentEngine.updateGovernorConfig(
                    com.nutorrent.core.model.ResourceGovernorConfig(
                        maxActiveDownloads = s.maxActiveDownloads,
                        maxActiveSeeds = s.maxActiveSeeds,
                        maxTotalPeerConnections = (s.maxPeerConnections * s.maxActiveDownloads).coerceIn(10, 200)
                    )
                )
                torrentEngine.applyScheduler(
                    enabled = s.schedulerEnabled,
                    startHour = s.schedulerStartHour,
                    endHour = s.schedulerEndHour,
                    limitBps = s.schedulerSpeedLimitBps,
                    defaultLimitBps = s.downloadSpeedLimitBps
                )
                val currentBattery = batteryMonitor.getCurrentStatus()
                torrentEngine.onBatteryChanged(
                    currentBattery,
                    s.pauseOnLowBattery,
                    s.pauseOnBatterySaver,
                    s.batteryThresholdPercent,
                    s.downloadOnlyWhileCharging
                )
            }
        }
    }

    private fun observeActiveTransfersForService() {
        appScope.launch {
            torrentRepository.torrentsList.collectLatest { torrents ->
                val hasActive = torrents.any { it.state.isActive }
                if (hasActive) {
                    try {
                        TorrentDownloadService.start(this@NuTorrentApplication)
                    } catch (e: Exception) {
                        Lg.w(LogCategory.SERVICE, "Could not start Foreground Service: ${e.message}")
                    }
                }
            }
        }
    }
}
