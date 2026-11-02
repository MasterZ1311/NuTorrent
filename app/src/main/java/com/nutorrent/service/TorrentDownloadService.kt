package com.nutorrent.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.nutorrent.NuTorrentApplication
import com.nutorrent.R
import com.nutorrent.core.model.TorrentState
import com.nutorrent.core.util.FormatUtils
import com.nutorrent.core.util.Lg
import com.nutorrent.core.util.LogCategory
import com.nutorrent.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class TorrentDownloadService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var observerJob: Job? = null
    private lateinit var notificationManager: NotificationManager

    companion object {
        const val CHANNEL_ID = "nutorrent_downloads"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.nutorrent.action.START"
        const val ACTION_PAUSE_ALL = "com.nutorrent.action.PAUSE_ALL"
        const val ACTION_RESUME_ALL = "com.nutorrent.action.RESUME_ALL"
        const val ACTION_STOP = "com.nutorrent.action.STOP"

        @Volatile
        var isServiceRunning = false
            private set

        fun start(context: Context) {
            if (isServiceRunning) return
            val intent = Intent(context, TorrentDownloadService::class.java).apply {
                action = ACTION_START
            }
            try {
                context.startForegroundService(intent)
            } catch (e: Exception) {
                Lg.w(LogCategory.SERVICE, "Could not start Foreground Service: ${e.message}")
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, TorrentDownloadService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
        startInForeground(createNotification("NuTorrent", "Preparing downloads...", 0, false))
        observeDownloads()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun startInForeground(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun observeDownloads() {
        val app = application as NuTorrentApplication
        val repository = app.torrentRepository

        observerJob?.cancel()
        observerJob = serviceScope.launch {
            repository.torrentsList.collectLatest { torrents ->
                val activeDownloads = torrents.filter { it.state.isActive }

                if (activeDownloads.isEmpty()) {
                    Lg.d(LogCategory.SERVICE, "No active torrent transfers. Stopping foreground service.")
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@collectLatest
                }

                var totalDownSpeed = 0L
                var totalUpSpeed = 0L
                var totalVerified = 0L
                var totalBytes = 0L

                for (t in activeDownloads) {
                    totalDownSpeed += t.progress.downloadSpeedBps
                    totalUpSpeed += t.progress.uploadSpeedBps
                    totalVerified += t.progress.verifiedBytes
                    totalBytes += t.totalLength
                }

                val speedSummary = "↓ ${FormatUtils.formatSpeed(totalDownSpeed)} • ↑ ${FormatUtils.formatSpeed(totalUpSpeed)}"
                val overallPercent = if (totalBytes > 0) ((totalVerified.toDouble() / totalBytes.toDouble()) * 100).toInt() else 0

                val title = if (activeDownloads.size == 1) {
                    activeDownloads.first().name
                } else {
                    "${activeDownloads.size} active transfers"
                }

                val singleEta = if (activeDownloads.size == 1 && activeDownloads.first().progress.etaSeconds != null) {
                    " • ETA ${FormatUtils.formatDuration(activeDownloads.first().progress.etaSeconds)}"
                } else ""

                val isFetchingMetadata = activeDownloads.size == 1 && activeDownloads.first().state == TorrentState.FETCHING_METADATA
                val content = if (isFetchingMetadata) {
                    "Fetching metadata from peers..."
                } else {
                    "$overallPercent% • $speedSummary$singleEta"
                }
                val notification = createNotification(title, content, overallPercent, indeterminate = isFetchingMetadata)
                notificationManager.notify(NOTIFICATION_ID, notification)
            }
        }
    }

    private fun createNotification(
        title: String,
        content: String,
        progressPercent: Int,
        indeterminate: Boolean
    ): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pauseAllIntent = Intent(this, TorrentDownloadService::class.java).apply {
            action = ACTION_PAUSE_ALL
        }
        val pausePendingIntent = PendingIntent.getService(
            this,
            1,
            pauseAllIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val resumeAllIntent = Intent(this, TorrentDownloadService::class.java).apply {
            action = ACTION_RESUME_ALL
        }
        val resumePendingIntent = PendingIntent.getService(
            this,
            2,
            resumeAllIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("NuTorrent • $title")
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, progressPercent, indeterminate)
            .setContentIntent(contentPendingIntent)
            .addAction(android.R.drawable.ic_media_pause, "Pause All", pausePendingIntent)
            .addAction(android.R.drawable.ic_media_play, "Resume All", resumePendingIntent)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        isServiceRunning = true
        if (intent?.action != ACTION_STOP) {
            startInForeground(createNotification("NuTorrent", "Managing transfers...", 0, false))
        }

        when (intent?.action) {
            ACTION_PAUSE_ALL -> {
                val app = application as NuTorrentApplication
                serviceScope.launch {
                    val torrents = app.torrentRepository.torrentsList.value
                    for (t in torrents) {
                        if (t.state.isActive) {
                            app.torrentRepository.pauseTorrent(t.torrentId)
                        }
                    }
                }
            }
            ACTION_RESUME_ALL -> {
                val app = application as NuTorrentApplication
                serviceScope.launch {
                    val torrents = app.torrentRepository.torrentsList.value
                    for (t in torrents) {
                        if (t.state == TorrentState.PAUSED) {
                            app.torrentRepository.resumeTorrent(t.torrentId)
                        }
                    }
                }
            }
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        serviceScope.cancel()
        Lg.d(LogCategory.SERVICE, "TorrentDownloadService destroyed cleanly.")
    }
}
