package com.nutorrent.app

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.nutorrent.app.core.util.Lg
import com.nutorrent.app.core.util.LogCategory
import com.nutorrent.app.data.repository.SettingsRepository
import com.nutorrent.app.data.repository.TorrentRepository
import com.nutorrent.app.service.TorrentDownloadService
import com.nutorrent.app.torrent.engine.TorrentEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class NuTorrentApplication : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var settingsRepository: SettingsRepository
        private set

    lateinit var torrentEngine: TorrentEngine
        private set

    lateinit var torrentRepository: TorrentRepository
        private set

    lateinit var networkObserver: com.nutorrent.app.torrent.network.NetworkConnectivityObserver
        private set

    lateinit var batteryMonitor: com.nutorrent.app.core.device.BatteryMonitor
        private set

    override fun onCreate() {
        super.onCreate()

        settingsRepository = SettingsRepository(this)
        torrentEngine = TorrentEngine(
            defaultMaxPeerConnections = settingsRepository.settings.value.maxPeerConnections,
            context = this
        )
        torrentEngine.setWifiOnlyMode(settingsRepository.settings.value.wifiOnly)

        torrentRepository = TorrentRepository(this, torrentEngine, appScope)

        networkObserver = com.nutorrent.app.torrent.network.NetworkConnectivityObserver(this)
        networkObserver.start { status ->
            torrentEngine.onNetworkChanged(isWifi = status.isWifi, isConnected = status.isConnected)
        }

        batteryMonitor = com.nutorrent.app.core.device.BatteryMonitor(this)
        batteryMonitor.onBatteryStateChanged = { status ->
            val s = settingsRepository.settings.value
            torrentEngine.onBatteryChanged(status, s.pauseOnLowBattery, s.pauseOnBatterySaver)
        }
        batteryMonitor.start()

        observeSettingsChanges()
        observeActiveTransfersForService()
    }

    private fun observeSettingsChanges() {
        appScope.launch {
            settingsRepository.settings.collectLatest { s ->
                torrentEngine.setWifiOnlyMode(s.wifiOnly)
                torrentEngine.setLpdEnabled(s.enableLpd)
                torrentEngine.setGlobalUploadLimit(s.uploadSpeedLimitBps)
                torrentEngine.updateGovernorConfig(
                    com.nutorrent.app.core.model.ResourceGovernorConfig(
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
                torrentEngine.onBatteryChanged(currentBattery, s.pauseOnLowBattery, s.pauseOnBatterySaver)
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
