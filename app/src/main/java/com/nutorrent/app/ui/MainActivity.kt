package com.nutorrent.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.nutorrent.app.NuTorrentApplication
import com.nutorrent.app.ui.addtorrent.AddTorrentScreen
import com.nutorrent.app.ui.details.TorrentDetailsScreen
import com.nutorrent.app.ui.home.HomeScreen
import com.nutorrent.app.ui.navigation.Screen
import com.nutorrent.app.ui.settings.SettingsScreen
import com.nutorrent.app.ui.theme.NuTorrentTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val incomingIntentData = mutableStateOf<android.net.Uri?>(null)

    private fun extractIncomingUri(intent: android.content.Intent?): android.net.Uri? {
        if (intent == null) return null
        if (intent.data != null) return intent.data
        if (intent.action == android.content.Intent.ACTION_SEND) {
            val text = intent.getStringExtra(android.content.Intent.EXTRA_TEXT)
            if (!text.isNullOrBlank() && text.trim().startsWith("magnet:", ignoreCase = true)) {
                return android.net.Uri.parse(text.trim())
            }
            val stream = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(android.content.Intent.EXTRA_STREAM, android.net.Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(android.content.Intent.EXTRA_STREAM)
            }
            if (stream != null) return stream
        }
        return null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        incomingIntentData.value = extractIncomingUri(intent)

        val app = application as NuTorrentApplication
        val repository = app.torrentRepository
        val settingsRepo = app.settingsRepository

        setContent {
            val userSettings by settingsRepo.settings.collectAsState()
            val isDarkTheme = when (userSettings.themeMode) {
                "DARK" -> true
                "LIGHT" -> false
                else -> isSystemInDarkTheme()
            }

            // Screen awake flag
            LaunchedEffect(userSettings.keepScreenAwake) {
                if (userSettings.keepScreenAwake) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }

            // Notification permission for Android 13+
            val notificationPermissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission()
            ) { /* no-op or handle */ }

            LaunchedEffect(Unit) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    if (ContextCompat.checkSelfPermission(
                            this@MainActivity,
                            Manifest.permission.POST_NOTIFICATIONS
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            }

            NuTorrentTheme(darkTheme = isDarkTheme) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    NuTorrentAppNavigation(
                        app = app,
                        incomingData = incomingIntentData.value,
                        onOpenTorrentPicker = { launcher ->
                            launcher.launch(arrayOf("*/*", "application/x-bittorrent"))
                        }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        incomingIntentData.value = extractIncomingUri(intent)
    }
}

@Composable
fun NuTorrentAppNavigation(
    app: NuTorrentApplication,
    incomingData: android.net.Uri? = null,
    onOpenTorrentPicker: (androidx.activity.result.ActivityResultLauncher<Array<String>>) -> Unit
) {
    val repository = app.torrentRepository
    val settingsRepo = app.settingsRepository
    val scope = rememberCoroutineScope()

    var currentScreen by remember { mutableStateOf<Screen>(Screen.Home) }

    LaunchedEffect(incomingData) {
        val uri = incomingData ?: return@LaunchedEffect
        if (uri.scheme.equals("magnet", ignoreCase = true)) {
            try {
                val magnet = com.nutorrent.app.torrent.metainfo.MagnetUri.parse(uri.toString())
                scope.launch {
                    repository.addMagnetTorrent(
                        magnet = magnet,
                        destinationPath = settingsRepo.settings.value.defaultDownloadPath,
                        autoStart = true
                    )
                }
            } catch (e: Exception) {
                com.nutorrent.app.core.util.Lg.e(com.nutorrent.app.core.util.LogCategory.ENGINE, "Failed parsing magnet link from intent: $uri", e)
            }
        } else {
            currentScreen = Screen.AddTorrent(uri)
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            currentScreen = Screen.AddTorrent(uri)
        }
    }

    when (val screen = currentScreen) {
        is Screen.Home -> {
            HomeScreen(
                repository = repository,
                networkStatus = app.networkObserver.networkStatus,
                onAddTorrentClick = {
                    onOpenTorrentPicker(filePickerLauncher)
                },
                onTorrentClick = { torrentId ->
                    currentScreen = Screen.Details(torrentId.hex)
                },
                onSettingsClick = {
                    currentScreen = Screen.Settings
                },
                onPauseClick = { torrentId ->
                    scope.launch { repository.pauseTorrent(torrentId) }
                },
                onResumeClick = { torrentId ->
                    scope.launch { repository.resumeTorrent(torrentId) }
                },
                onDeleteClick = { torrentId, deleteFiles ->
                    scope.launch { repository.deleteTorrent(torrentId, deleteFiles) }
                }
            )
        }
        is Screen.AddTorrent -> {
            BackHandler { currentScreen = Screen.Home }
            AddTorrentScreen(
                fileUri = screen.fileUri,
                repository = repository,
                settingsRepository = settingsRepo,
                onStartDownload = {
                    currentScreen = Screen.Home
                },
                onCancel = {
                    currentScreen = Screen.Home
                }
            )
        }
        is Screen.Details -> {
            BackHandler { currentScreen = Screen.Home }
            TorrentDetailsScreen(
                infoHashHex = screen.infoHash,
                repository = repository,
                onBack = { currentScreen = Screen.Home },
                onPauseClick = { torrentId ->
                    scope.launch { repository.pauseTorrent(torrentId) }
                },
                onResumeClick = { torrentId ->
                    scope.launch { repository.resumeTorrent(torrentId) }
                },
                onDeleteClick = { torrentId, deleteFiles ->
                    scope.launch {
                        repository.deleteTorrent(torrentId, deleteFiles = deleteFiles)
                        currentScreen = Screen.Home
                    }
                },
                onForceRecheckClick = { torrentId ->
                    scope.launch {
                        repository.forceRecheck(torrentId)
                    }
                },
                onSequentialToggle = { torrentId, enabled ->
                    scope.launch {
                        repository.setSequentialDownload(torrentId, enabled)
                    }
                },
                onCategoryChange = { torrentId, category ->
                    scope.launch {
                        repository.setCategory(torrentId, category)
                    }
                }
            )
        }
        is Screen.Settings -> {
            BackHandler { currentScreen = Screen.Home }
            SettingsScreen(
                settingsRepository = settingsRepo,
                torrentRepository = repository,
                onBack = { currentScreen = Screen.Home }
            )
        }
    }
}
