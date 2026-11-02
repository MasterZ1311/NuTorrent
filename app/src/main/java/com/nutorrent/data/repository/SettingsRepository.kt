package com.nutorrent.data.repository

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

data class UserSettings(
    val defaultDownloadPath: String,
    val maxPeerConnections: Int = 30,
    val downloadSpeedLimitBps: Long = 0L, // 0 = unlimited
    val uploadSpeedLimitBps: Long = 0L,   // 0 = unlimited
    val maxActiveDownloads: Int = 3,
    val maxActiveSeeds: Int = 2,
    val wifiOnly: Boolean = false,
    val keepScreenAwake: Boolean = false,
    val pauseOnLowBattery: Boolean = false,
    val pauseOnBatterySaver: Boolean = false,
    val schedulerEnabled: Boolean = false,
    val schedulerStartHour: Int = 8,
    val schedulerEndHour: Int = 22,
    val schedulerSpeedLimitBps: Long = 512 * 1024L,
    val enableLpd: Boolean = true,
    val themeMode: String = "SYSTEM" // "SYSTEM", "LIGHT", "DARK"
)

class SettingsRepository(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("nutorrent_settings", Context.MODE_PRIVATE)

    private val defaultDownloadDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "Downloads").apply {
        if (!exists()) mkdirs()
    }.absolutePath

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<UserSettings> = _settings.asStateFlow()

    private fun loadSettings(): UserSettings {
        return UserSettings(
            defaultDownloadPath = prefs.getString("download_path", defaultDownloadDir) ?: defaultDownloadDir,
            maxPeerConnections = prefs.getInt("max_peers", 30).coerceIn(5, 100),
            downloadSpeedLimitBps = prefs.getLong("speed_limit", 0L),
            uploadSpeedLimitBps = prefs.getLong("upload_speed_limit", 0L),
            maxActiveDownloads = prefs.getInt("max_active_downloads", 3).coerceIn(1, 20),
            maxActiveSeeds = prefs.getInt("max_active_seeds", 2).coerceIn(1, 20),
            wifiOnly = prefs.getBoolean("wifi_only", false),
            keepScreenAwake = prefs.getBoolean("keep_awake", false),
            pauseOnLowBattery = prefs.getBoolean("pause_on_low_battery", false),
            pauseOnBatterySaver = prefs.getBoolean("pause_on_battery_saver", false),
            schedulerEnabled = prefs.getBoolean("scheduler_enabled", false),
            schedulerStartHour = prefs.getInt("scheduler_start_hour", 8).coerceIn(0, 23),
            schedulerEndHour = prefs.getInt("scheduler_end_hour", 22).coerceIn(0, 23),
            schedulerSpeedLimitBps = prefs.getLong("scheduler_speed_limit", 512 * 1024L),
            enableLpd = prefs.getBoolean("enable_lpd", true),
            themeMode = prefs.getString("theme_mode", "SYSTEM") ?: "SYSTEM"
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
            .putBoolean("scheduler_enabled", newSettings.schedulerEnabled)
            .putInt("scheduler_start_hour", newSettings.schedulerStartHour)
            .putInt("scheduler_end_hour", newSettings.schedulerEndHour)
            .putLong("scheduler_speed_limit", newSettings.schedulerSpeedLimitBps)
            .putBoolean("enable_lpd", newSettings.enableLpd)
            .putString("theme_mode", newSettings.themeMode)
            .apply()
        _settings.value = newSettings
    }
}
