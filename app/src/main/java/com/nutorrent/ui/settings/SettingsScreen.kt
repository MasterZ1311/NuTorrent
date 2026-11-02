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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
    val settings by settingsRepository.settings.collectAsState()

    val folderPickerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val path = uri.path ?: uri.toString()
            settingsRepository.updateSettings(settings.copy(defaultDownloadPath = path))
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
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = settings.defaultDownloadPath,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    androidx.compose.material3.OutlinedButton(
                        onClick = { folderPickerLauncher.launch(null) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Change Download Folder", fontSize = 12.sp)
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
                        10 * 1024 * 1024L
                    )
                    val downIndex = downloadSpeedSteps.indexOfFirst { it >= settings.downloadSpeedLimitBps }.let { if (it < 0) 0 else it }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Max Download Speed", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(
                            text = if (settings.downloadSpeedLimitBps <= 0L) "Unlimited" else "${settings.downloadSpeedLimitBps / 1024} KB/s",
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
                        5 * 1024 * 1024L
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
                            text = if (settings.uploadSpeedLimitBps <= 0L) "Unlimited" else "${settings.uploadSpeedLimitBps / 1024} KB/s",
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
                        valueRange = 5f..100f,
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
                            Text("Pause on Low Battery (< 15%)", fontSize = 14.sp, fontWeight = FontWeight.Medium)
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

            // About / Legal section
            SettingsSection(title = "ABOUT") {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "NuTorrent",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Version 1.0.0 (Production Release)",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Fast. Minimal. Private. Transparent.\n\nA modern peer-to-peer torrent client for Android designed for lawful distribution of open-source software, Linux distributions, public datasets, and user-owned media.",
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Privacy Guarantee",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "• Zero third-party analytics or crash SDKs\n• Zero advertising trackers or hardware IDs\n• Ephemeral SecureRandom peer IDs\n• Fully local Room database",
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Open Source / Licenses: Apache 2.0 / MIT",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
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
                    appendLine("App Version: 1.0.0 (Release Candidate)")
                    appendLine("Android OS: Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
                    appendLine("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                    appendLine("Storage: ${settings.defaultDownloadPath}")
                    appendLine("Active Torrents: $activeCount / ${torrents.size}")
                    appendLine("Connected Swarm Peers: $totalPeers")
                    appendLine("Max Peer Connections: ${settings.maxPeerConnections}")
                    appendLine("Local Peer Discovery (BEP 14): ${if (settings.enableLpd) "Enabled" else "Disabled"}")
                    appendLine("Wi-Fi Only: ${settings.wifiOnly}")
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

            // About section with brand logo
            SettingsSection(title = "ABOUT") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_app_logo),
                        contentDescription = "NuTorrent Logo",
                        modifier = Modifier.size(52.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text("NuTorrent", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text(
                            text = "Version 1.0.0 Production Release",
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
