package com.nutorrent.data.repository

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

data class UserSettings(
    val defaultDownloadPath: String,
    val maxPeerConnections: Int = 50,
    val downloadSpeedLimitBps: Long = 0L, // 0 = unlimited
    val uploadSpeedLimitBps: Long = 0L,   // 0 = unlimited
    val maxActiveDownloads: Int = 3,
    val maxActiveSeeds: Int = 2,
    val wifiOnly: Boolean = false,
    val keepScreenAwake: Boolean = false,
    val pauseOnLowBattery: Boolean = false,
    val pauseOnBatterySaver: Boolean = false,
    val batteryThresholdPercent: Int = 15,
    val downloadOnlyWhileCharging: Boolean = false,
    val schedulerEnabled: Boolean = false,
    val schedulerStartHour: Int = 8,
    val schedulerEndHour: Int = 22,
    val schedulerSpeedLimitBps: Long = 512 * 1024L,
    val enableLpd: Boolean = true,
    val enableHaptics: Boolean = true,
    val enableSoundEffects: Boolean = true,
    val themeMode: String = "SYSTEM", // "SYSTEM", "LIGHT", "DARK"
    val uiMode: String = "SIMPLE", // "SIMPLE", "ADVANCED"
    val lifetimeDownloadedBytes: Long = 0L,
    val lifetimeUploadedBytes: Long = 0L,
    val completedTorrentsCount: Int = 0,
    val hasCompletedOnboarding: Boolean = false,
    val preferredLayoutMode: String = "AUTO" // "AUTO", "CARDS", "TABLE"
)

class SettingsRepository(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("nutorrent_settings", Context.MODE_PRIVATE)

    private val defaultDownloadDir: String get() = getDefaultDownloadDir(context)




    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<UserSettings> = _settings.asStateFlow()

    private fun loadSettings(): UserSettings {
        val savedPath = prefs.getString("download_path", null)
        val resolvedPath = if (savedPath.isNullOrBlank() || savedPath.endsWith("/Downloads") || savedPath.endsWith("\\Downloads") || !isPathWritable(savedPath, context)) {
            val def = getDefaultDownloadDir(context)
            prefs.edit().putString("download_path", def).apply()
            def
        } else {
            savedPath
        }
        return UserSettings(
            defaultDownloadPath = resolvedPath,
            maxPeerConnections = prefs.getInt("max_peers", 50).coerceIn(5, 200),
            downloadSpeedLimitBps = prefs.getLong("speed_limit", 0L),
            uploadSpeedLimitBps = prefs.getLong("upload_speed_limit", 0L),
            maxActiveDownloads = prefs.getInt("max_active_downloads", 3).coerceIn(1, 20),
            maxActiveSeeds = prefs.getInt("max_active_seeds", 2).coerceIn(1, 20),
            wifiOnly = prefs.getBoolean("wifi_only", false),
            keepScreenAwake = prefs.getBoolean("keep_awake", false),
            pauseOnLowBattery = prefs.getBoolean("pause_on_low_battery", false),
            pauseOnBatterySaver = prefs.getBoolean("pause_on_battery_saver", false),
            batteryThresholdPercent = prefs.getInt("battery_threshold", 15).coerceIn(5, 50),
            downloadOnlyWhileCharging = prefs.getBoolean("download_only_charging", false),
            schedulerEnabled = prefs.getBoolean("scheduler_enabled", false),
            schedulerStartHour = prefs.getInt("scheduler_start_hour", 8).coerceIn(0, 23),
            schedulerEndHour = prefs.getInt("scheduler_end_hour", 22).coerceIn(0, 23),
            schedulerSpeedLimitBps = prefs.getLong("scheduler_speed_limit", 512 * 1024L),
            enableLpd = prefs.getBoolean("enable_lpd", true),
            enableHaptics = prefs.getBoolean("enable_haptics", true),
            enableSoundEffects = prefs.getBoolean("enable_sound_effects", true),
            themeMode = prefs.getString("theme_mode", "SYSTEM") ?: "SYSTEM",
            uiMode = prefs.getString("ui_mode", "SIMPLE") ?: "SIMPLE",
            lifetimeDownloadedBytes = prefs.getLong("lifetime_downloaded", 0L),
            lifetimeUploadedBytes = prefs.getLong("lifetime_uploaded", 0L),
            completedTorrentsCount = prefs.getInt("completed_torrents_count", 0),
            hasCompletedOnboarding = prefs.getBoolean("has_completed_onboarding", false),
            preferredLayoutMode = prefs.getString("preferred_layout_mode", "AUTO") ?: "AUTO"
        )
    }

    fun updateSettings(newSettings: UserSettings) {
        prefs.edit()
            .putString("download_path", newSettings.defaultDownloadPath)
            .putInt("max_peers", newSettings.maxPeerConnections)
            .putLong("speed_limit", newSettings.downloadSpeedLimitBps)
            .putLong("upload_speed_limit", newSettings.uploadSpeedLimitBps)
            .putInt("max_active_downloads", newSettings.maxActiveDownloads)
            .putInt("max_active_seeds", newSettings.maxActiveSeeds)
            .putBoolean("wifi_only", newSettings.wifiOnly)
            .putBoolean("keep_awake", newSettings.keepScreenAwake)
            .putBoolean("pause_on_low_battery", newSettings.pauseOnLowBattery)
            .putBoolean("pause_on_battery_saver", newSettings.pauseOnBatterySaver)
            .putInt("battery_threshold", newSettings.batteryThresholdPercent)
            .putBoolean("download_only_charging", newSettings.downloadOnlyWhileCharging)
            .putBoolean("scheduler_enabled", newSettings.schedulerEnabled)
            .putInt("scheduler_start_hour", newSettings.schedulerStartHour)
            .putInt("scheduler_end_hour", newSettings.schedulerEndHour)
            .putLong("scheduler_speed_limit", newSettings.schedulerSpeedLimitBps)
            .putBoolean("enable_lpd", newSettings.enableLpd)
            .putBoolean("enable_haptics", newSettings.enableHaptics)
            .putBoolean("enable_sound_effects", newSettings.enableSoundEffects)
            .putString("theme_mode", newSettings.themeMode)
            .putString("ui_mode", newSettings.uiMode)
            .putLong("lifetime_downloaded", newSettings.lifetimeDownloadedBytes)
            .putLong("lifetime_uploaded", newSettings.lifetimeUploadedBytes)
            .putInt("completed_torrents_count", newSettings.completedTorrentsCount)
            .putBoolean("has_completed_onboarding", newSettings.hasCompletedOnboarding)
            .putString("preferred_layout_mode", newSettings.preferredLayoutMode)
            .apply()
        _settings.value = newSettings
    }

    fun addLifetimeDownloaded(bytes: Long) {
        if (bytes <= 0) return
        val updated = _settings.value.lifetimeDownloadedBytes + bytes
        prefs.edit().putLong("lifetime_downloaded", updated).apply()
        _settings.value = _settings.value.copy(lifetimeDownloadedBytes = updated)
    }

    fun addLifetimeUploaded(bytes: Long) {
        if (bytes <= 0) return
        val updated = _settings.value.lifetimeUploadedBytes + bytes
        prefs.edit().putLong("lifetime_uploaded", updated).apply()
        _settings.value = _settings.value.copy(lifetimeUploadedBytes = updated)
    }

    fun incrementCompletedCount() {
        val updated = _settings.value.completedTorrentsCount + 1
        prefs.edit().putInt("completed_torrents_count", updated).apply()
        _settings.value = _settings.value.copy(completedTorrentsCount = updated)
    }

    companion object {
        /**
         * Safely probes whether a directory can actually be created and written to
         * under Android Scoped Storage / SELinux policies using an atomic file write probe.
         */
        fun isPathWritable(path: String?, context: Context? = null): Boolean {
            if (path.isNullOrBlank()) return false
            if (path.startsWith("content://", ignoreCase = true)) {
                return if (context != null) {
                    try {
                        val uri = android.net.Uri.parse(path)
                        val doc = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, uri)
                        doc != null && doc.exists() && doc.canWrite()
                    } catch (_: Throwable) {
                        false
                    }
                } else {
                    true
                }
            }
            return try {
                val cleanPath = if (path.startsWith("file://", ignoreCase = true)) {
                    android.net.Uri.parse(path).path ?: path.removePrefix("file://")
                } else {
                    path
                }
                val dir = File(cleanPath)
                if (!dir.exists()) {
                    dir.mkdirs()
                }
                if (dir.exists() && dir.isDirectory) {
                    val probe = File(dir, ".probe_${System.currentTimeMillis()}")
                    if (probe.createNewFile()) {
                        probe.delete()
                        true
                    } else {
                        false
                    }
                } else {
                    false
                }
            } catch (_: Throwable) {
                false
            }
        }

        /**
         * Resolves and ensures the default download directory in storage.
         * Guarantees returning a directory that actually has write permissions.
         */
        fun getDefaultDownloadDir(context: Context): String {
            // 1. Public Downloads directory: /storage/emulated/0/Download/NuTorrent (if accessible/writable)
            try {
                val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                if (downloadsDir != null) {
                    val nuFolder = File(downloadsDir, "NuTorrent")
                    if (isPathWritable(nuFolder.absolutePath)) {
                        return nuFolder.absolutePath
                    }
                }
            } catch (_: Throwable) {}

            // 2. Guaranteed safe fallback: App-specific external storage (.../Android/data/com.nutorrent/files/NuTorrent)
            // Always writable on all Android versions without requiring broad permissions
            val appNuFolder = File(context.getExternalFilesDir(null) ?: context.filesDir, "NuTorrent").apply {
                if (!exists()) {
                    mkdirs()
                }
            }
            return appNuFolder.absolutePath
        }
    }
}
