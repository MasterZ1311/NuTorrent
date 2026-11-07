package com.nutorrent.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.res.painterResource
import com.nutorrent.R
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nutorrent.core.device.NuHaptics
import com.nutorrent.core.device.NuSoundFeedback
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nutorrent.data.repository.SettingsRepository
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settingsRepository: SettingsRepository,
    torrentRepository: com.nutorrent.data.repository.TorrentRepository? = null,
    onBack: () -> Unit
) {
    val settings by settingsRepository.settings.collectAsStateWithLifecycle()

    val context = androidx.compose.ui.platform.LocalContext.current
    val folderPickerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val takeFlags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try {
                context.contentResolver.takePersistableUriPermission(uri, takeFlags)
            } catch (e: Exception) {
                com.nutorrent.core.util.Lg.w(
                    com.nutorrent.core.util.LogCategory.STORAGE,
                    "takePersistableUriPermission failed for $uri: ${e.message}"
                )
            }
            settingsRepository.updateSettings(settings.copy(defaultDownloadPath = uri.toString()))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Downloads section
            SettingsSection(title = "DOWNLOADS") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text("Default Download Location", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = com.nutorrent.core.util.FormatUtils.formatStoragePath(settings.defaultDownloadPath),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    val defaultPath = com.nutorrent.data.repository.SettingsRepository.getDefaultDownloadDir(context)
                    val isDefault = settings.defaultDownloadPath == defaultPath
                    val isSAF = settings.defaultDownloadPath.startsWith("content://")
                    if (isDefault) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Default Internal Storage: Files are saved to the 'NuTorrent' directory on internal storage.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 15.sp
                        )
                    } else if (isSAF) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Custom SAF Storage: Files are saved directly to your chosen device storage and are accessible by any file manager or media app.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 15.sp
                        )
                    } else {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Custom Storage Location: Files are saved to a custom directory.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 15.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        androidx.compose.material3.Button(
                            onClick = { folderPickerLauncher.launch(null) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                Icons.Default.Folder,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Change Folder (SAF)", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, softWrap = false)
                        }
                        androidx.compose.material3.OutlinedButton(
                            onClick = {
                                com.nutorrent.core.util.FileOpener.openFolder(context, settings.defaultDownloadPath)
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                Icons.Default.Folder,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Open Folder", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, softWrap = false)
                        }
                    }
                    if (!isDefault) {
                        Spacer(modifier = Modifier.height(6.dp))
                        androidx.compose.material3.TextButton(
                            onClick = {
                                val defStoragePath = com.nutorrent.data.repository.SettingsRepository.getDefaultDownloadDir(context)
                                settingsRepository.updateSettings(settings.copy(defaultDownloadPath = defStoragePath))
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Reset to Default Storage (NuTorrent)", fontSize = 12.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Active Downloads Limit", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(
                            text = "${settings.maxActiveDownloads}",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Slider(
                        value = settings.maxActiveDownloads.toFloat(),
                        onValueChange = {
                            settingsRepository.updateSettings(
                                settings.copy(maxActiveDownloads = it.roundToInt())
                            )
                        },
                        valueRange = 1f..10f,
                        steps = 8
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Active Seeds Limit", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(
                            text = "${settings.maxActiveSeeds}",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Slider(
                        value = settings.maxActiveSeeds.toFloat(),
                        onValueChange = {
                            settingsRepository.updateSettings(
                                settings.copy(maxActiveSeeds = it.roundToInt())
                            )
                        },
                        valueRange = 1f..10f,
                        steps = 8
                    )
                }
            }

            // Network section
            SettingsSection(title = "NETWORK") {
                Column(modifier = Modifier.padding(16.dp)) {
                    val downloadSpeedSteps = listOf(
                        0L,
                        256 * 1024L,
                        512 * 1024L,
                        1024 * 1024L,
                        2 * 1024 * 1024L,
                        5 * 1024 * 1024L,
                        10 * 1024 * 1024L,
                        25 * 1024 * 1024L,
                        50 * 1024 * 1024L,
                        100 * 1024 * 1024L
                    )
                    val downIndex = downloadSpeedSteps.indexOfFirst { it >= settings.downloadSpeedLimitBps }.let { if (it < 0) 0 else it }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Max Download Speed", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(
                            text = when {
                                settings.downloadSpeedLimitBps <= 0L -> "Unlimited"
                                settings.downloadSpeedLimitBps >= 1024 * 1024L -> "${settings.downloadSpeedLimitBps / (1024 * 1024)} MB/s"
                                else -> "${settings.downloadSpeedLimitBps / 1024} KB/s"
                            },
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Slider(
                        value = downIndex.toFloat(),
                        onValueChange = {
                            val chosenSpeed = downloadSpeedSteps[it.roundToInt().coerceIn(0, downloadSpeedSteps.size - 1)]
                            settingsRepository.updateSettings(settings.copy(downloadSpeedLimitBps = chosenSpeed))
                        },
                        valueRange = 0f..(downloadSpeedSteps.size - 1).toFloat(),
                        steps = downloadSpeedSteps.size - 2
                    )

                    val uploadSpeedSteps = listOf(
                        0L,
                        128 * 1024L,
                        256 * 1024L,
                        512 * 1024L,
                        1024 * 1024L,
                        2 * 1024 * 1024L,
                        5 * 1024 * 1024L,
                        10 * 1024 * 1024L,
                        25 * 1024 * 1024L
                    )
                    val upIndex = uploadSpeedSteps.indexOfFirst { it >= settings.uploadSpeedLimitBps }.let { if (it < 0) 0 else it }

                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Max Upload Speed", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(
                            text = when {
                                settings.uploadSpeedLimitBps <= 0L -> "Unlimited"
                                settings.uploadSpeedLimitBps >= 1024 * 1024L -> "${settings.uploadSpeedLimitBps / (1024 * 1024)} MB/s"
                                else -> "${settings.uploadSpeedLimitBps / 1024} KB/s"
                            },
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Slider(
                        value = upIndex.toFloat(),
                        onValueChange = {
                            val chosenSpeed = uploadSpeedSteps[it.roundToInt().coerceIn(0, uploadSpeedSteps.size - 1)]
                            settingsRepository.updateSettings(settings.copy(uploadSpeedLimitBps = chosenSpeed))
                        },
                        valueRange = 0f..(uploadSpeedSteps.size - 1).toFloat(),
                        steps = uploadSpeedSteps.size - 2
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Maximum Peer Connections", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(
                            text = "${settings.maxPeerConnections}",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Slider(
                        value = settings.maxPeerConnections.toFloat(),
                        onValueChange = {
                            settingsRepository.updateSettings(
                                settings.copy(maxPeerConnections = it.roundToInt())
                            )
                        },
                        valueRange = 10f..200f,
                        steps = 18
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Local Peer Discovery (BEP 14)", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text(
                                "Discover high-speed peers on the same local Wi-Fi / LAN",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = settings.enableLpd,
                            onCheckedChange = {
                                settingsRepository.updateSettings(settings.copy(enableLpd = it))
                            }
                        )
                    }
                }
            }

            // Battery and Power section
            SettingsSection(title = "BATTERY & DATA") {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Download Over Wi-Fi Only", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text(
                                "Pause transfers when cellular data is active",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = settings.wifiOnly,
                            onCheckedChange = {
                                settingsRepository.updateSettings(settings.copy(wifiOnly = it))
                            }
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Pause on Battery Saver", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text(
                                "Automatically pause swarms when Android Power Save mode is enabled",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = settings.pauseOnBatterySaver,
                            onCheckedChange = {
                                settingsRepository.updateSettings(settings.copy(pauseOnBatterySaver = it))
                            }
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Pause on Low Battery", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text(
                                "Conserve remaining battery until plugged into power",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = settings.pauseOnLowBattery,
                            onCheckedChange = {
                                settingsRepository.updateSettings(settings.copy(pauseOnLowBattery = it))
                            }
                        )
                    }

                    if (settings.pauseOnLowBattery) {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Low Battery Threshold", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    text = "${settings.batteryThresholdPercent}%",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Slider(
                                value = settings.batteryThresholdPercent.toFloat(),
                                onValueChange = {
                                    settingsRepository.updateSettings(
                                        settings.copy(batteryThresholdPercent = it.roundToInt())
                                    )
                                },
                                valueRange = 5f..50f,
                                steps = 8
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Download Only While Charging", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text(
                                "Pause all transfers whenever device is running on battery",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = settings.downloadOnlyWhileCharging,
                            onCheckedChange = {
                                settingsRepository.updateSettings(settings.copy(downloadOnlyWhileCharging = it))
                            }
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Keep Screen Awake", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text(
                                "Prevent display from sleeping during downloads",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = settings.keepScreenAwake,
                            onCheckedChange = {
                                settingsRepository.updateSettings(settings.copy(keepScreenAwake = it))
                            }
                        )
                    }
                }
            }

            // Interface Mode section
            SettingsSection(title = "INTERFACE MODE") {
                Column(modifier = Modifier.padding(12.dp)) {
                    val modes = listOf(
                        "SIMPLE" to ("Simple (Standard)" to "Minimal view with Overview and Files. Hides raw swarm internals, piece maps, and queue reordering."),
                        "ADVANCED" to ("Advanced (Power User)" to "Full view with live connected peers, tracker logs, piece bitmap visualizer, and manual queue reordering.")
                    )
                    modes.forEach { (mode, pair) ->
                        val (title, description) = pair
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    settingsRepository.updateSettings(settings.copy(uiMode = mode))
                                }
                                .padding(8.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            RadioButton(
                                selected = settings.uiMode == mode,
                                onClick = {
                                    settingsRepository.updateSettings(settings.copy(uiMode = mode))
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(description, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }

            // Appearance section
            SettingsSection(title = "APPEARANCE") {
                Column(modifier = Modifier.padding(8.dp)) {
                    val themes = listOf("SYSTEM" to "System Default", "DARK" to "Dark Mode", "LIGHT" to "Light Mode")
                    themes.forEach { (mode, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    settingsRepository.updateSettings(settings.copy(themeMode = mode))
                                }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = settings.themeMode == mode,
                                onClick = {
                                    settingsRepository.updateSettings(settings.copy(themeMode = mode))
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(label, fontSize = 14.sp)
                        }
                    }
                }
            }

            // Haptics & Audio Feedback section
            SettingsSection(title = "HAPTICS & AUDIO FEEDBACK") {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                            Text("Tactile Haptics", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text(
                                text = "Rich physical micro-vibrations for clicks, transitions, and alerts",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = settings.enableHaptics,
                            onCheckedChange = { checked ->
                                settingsRepository.updateSettings(settings.copy(enableHaptics = checked))
                                if (checked) NuHaptics.click(context)
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                            Text("Synthesized Audio Chimes", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text(
                                text = "Futuristic audio chime on torrent download completion and start",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = settings.enableSoundEffects,
                            onCheckedChange = { checked ->
                                settingsRepository.updateSettings(settings.copy(enableSoundEffects = checked))
                                if (checked) NuSoundFeedback.playCompletionChime(context)
                            }
                        )
                    }
                }
            }

            // Performance Insights section
            SettingsSection(title = "PERFORMANCE INSIGHTS") {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val uptimeMs = torrentRepository?.engine?.getSessionUptimeMs() ?: 0L
                    val uptimeSec = uptimeMs / 1000L
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Session Uptime", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            text = com.nutorrent.core.util.FormatUtils.formatDuration(uptimeSec),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Lifetime Downloaded", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            text = com.nutorrent.core.util.FormatUtils.formatBytes(settings.lifetimeDownloadedBytes),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Lifetime Uploaded", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            text = com.nutorrent.core.util.FormatUtils.formatBytes(settings.lifetimeUploadedBytes),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = com.nutorrent.ui.theme.NuSuccess
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Completed Downloads", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            text = "${settings.completedTorrentsCount}",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            // Privacy Transparency section
            SettingsSection(title = "PRIVACY TRANSPARENCY") {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Local Storage & Analytics",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "• 100% Local Database: Torrent records and settings reside exclusively in local SQLite on your device.\n• Zero Telemetry: No Firebase, Crashlytics, or analytics SDKs.\n• No User Accounts: No sign-in, email, advertising IDs, or tracking cookies.",
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "BitTorrent Swarm Transparency",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "• Swarm IP Broadcast: As required by the BitTorrent protocol, your IP address and listening port are shared with connected peers in the swarm and public trackers to coordinate file transfers.\n• Ephemeral Peer IDs: A new cryptographically random peer ID is generated on each session to eliminate long-term swarm tracking.",
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Diagnostics section
            SettingsSection(title = "DIAGNOSTICS") {
                val torrents by (torrentRepository?.torrentsList?.collectAsState() ?: androidx.compose.runtime.remember {
                    androidx.compose.runtime.mutableStateOf(emptyList())
                })
                val activeCount = torrents.count { it.state.isActive }
                val totalPeers = torrents.sumOf { it.progress.connectedPeers }
                val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
                var copiedNotification by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }

                val diagText = buildString {
                    appendLine("NuTorrent Diagnostics")
                    appendLine("App Version: 1.0.1 (Production Release)")
                    appendLine("Android OS: Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
                    appendLine("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                    appendLine("Storage: ${settings.defaultDownloadPath}")
                    appendLine("UI Mode: ${settings.uiMode}")
                    appendLine("Active Torrents: $activeCount / ${torrents.size}")
                    appendLine("Connected Swarm Peers: $totalPeers")
                    appendLine("Max Peer Connections: ${settings.maxPeerConnections}")
                    appendLine("Local Peer Discovery (BEP 14): ${if (settings.enableLpd) "Enabled" else "Disabled"}")
                    appendLine("Wi-Fi Only: ${settings.wifiOnly}")
                    appendLine("Battery Threshold: ${settings.batteryThresholdPercent}%")
                    appendLine("Only While Charging: ${settings.downloadOnlyWhileCharging}")
                    appendLine("Theme Mode: ${settings.themeMode}")
                }

                Column(modifier = Modifier.padding(16.dp)) {
                    Text("System & Engine Status", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}) • $activeCount active • $totalPeers peers",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    androidx.compose.material3.OutlinedButton(
                        onClick = {
                            clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(diagText))
                            copiedNotification = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = if (copiedNotification) "Copied Diagnostics to Clipboard!" else "Copy Diagnostics to Clipboard",
                            fontSize = 12.sp
                        )
                    }
                }
            }

            // About section
            SettingsSection(title = "ABOUT") {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Image(
                            painter = painterResource(R.drawable.ic_app_logo),
                            contentDescription = "NuTorrent Logo",
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text("NuTorrent", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Text(
                                text = "Version 1.0.1 Production Release",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Fast. Minimal. Private. Transparent.",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "A modern peer-to-peer BitTorrent client for Android designed for lawful distribution of open-source software, Linux distributions, public datasets, and user-owned media.",
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Open Source / Licenses: Apache 2.0 / MIT",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun SettingsSection(
    title: String,
    content: @Composable () -> Unit
) {
    Column {
        Text(
            text = title,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 0.8.sp,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
        ) {
            content()
        }
    }
}
