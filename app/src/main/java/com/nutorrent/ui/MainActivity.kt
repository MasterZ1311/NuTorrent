package com.nutorrent.ui

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
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nutorrent.NuTorrentApplication
import com.nutorrent.core.device.NuHaptics
import com.nutorrent.core.device.NuSoundFeedback
import com.nutorrent.ui.addtorrent.AddTorrentScreen
import com.nutorrent.ui.details.TorrentDetailsScreen
import com.nutorrent.ui.home.HomeScreen
import com.nutorrent.ui.navigation.Screen
import com.nutorrent.ui.settings.SettingsScreen
import com.nutorrent.ui.theme.NuTorrentTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val incomingIntentData = mutableStateOf<android.net.Uri?>(null)

    private fun extractIncomingUri(intent: android.content.Intent?): android.net.Uri? {
        if (intent == null) return null
        if (intent.data != null) return intent.data
        if (intent.action == android.content.Intent.ACTION_SEND) {
            val text = intent.getStringExtra(android.content.Intent.EXTRA_TEXT)
            val decoded = if (!text.isNullOrBlank()) com.nutorrent.core.util.UniversalLinkDecoder.decode(text) else ""
            if (decoded.startsWith("magnet:", ignoreCase = true) || decoded.startsWith("thunder://", ignoreCase = true)) {
                return android.net.Uri.parse(decoded)
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
            val userSettings by settingsRepo.settings.collectAsStateWithLifecycle()
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
    val context = LocalContext.current

    var currentScreen by remember { mutableStateOf<Screen>(Screen.Home) }

    LaunchedEffect(incomingData) {
        val uri = incomingData ?: return@LaunchedEffect
        val uriStr = uri.toString()
        if (uri.scheme.equals("magnet", ignoreCase = true) || uri.scheme.equals("thunder", ignoreCase = true) || uriStr.startsWith("magnet:", ignoreCase = true) || uriStr.startsWith("thunder://", ignoreCase = true)) {
            try {
                val magnet = com.nutorrent.torrent.metainfo.MagnetUri.parse(uri.toString())
                scope.launch {
                    repository.addMagnetTorrent(
                        magnet = magnet,
                        destinationPath = settingsRepo.settings.value.defaultDownloadPath,
                        autoStart = true
                    )
                    NuHaptics.heavyClick(context)
                    NuSoundFeedback.playStartBeep(context)
                }
            } catch (e: Exception) {
                com.nutorrent.core.util.Lg.e(com.nutorrent.core.util.LogCategory.ENGINE, "Failed parsing magnet link from intent: $uri", e)
            }
        } else {
            currentScreen = Screen.AddTorrent(uri)
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            NuHaptics.click(context)
            currentScreen = Screen.AddTorrent(uri)
        }
    }

    AnimatedContent(
        targetState = currentScreen,
        transitionSpec = {
            if (targetState is Screen.Details || targetState is Screen.AddTorrent || targetState is Screen.Settings) {
                (slideInVertically(
                    initialOffsetY = { it / 5 },
                    animationSpec = spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow)
                ) + fadeIn(animationSpec = tween(240))) togetherWith
                (fadeOut(animationSpec = tween(150)) + scaleOut(targetScale = 0.96f, animationSpec = tween(200)))
            } else {
                (fadeIn(animationSpec = tween(240)) + scaleIn(initialScale = 0.96f, animationSpec = tween(200))) togetherWith
                (slideOutVertically(
                    targetOffsetY = { it / 5 },
                    animationSpec = tween(200)
                ) + fadeOut(animationSpec = tween(150)))
            }
        },
        label = "screenTransitions"
    ) { screen ->
        when (screen) {
            is Screen.Home -> {
                HomeScreen(
                    repository = repository,
                    networkStatus = app.networkObserver.networkStatus,
                    onAddTorrentClick = {
                        NuHaptics.click(context)
                        onOpenTorrentPicker(filePickerLauncher)
                    },
                    onTorrentClick = { torrentId ->
                        NuHaptics.click(context)
                        currentScreen = Screen.Details(torrentId.hex)
                    },
                    onSettingsClick = {
                        NuHaptics.tick(context)
                        currentScreen = Screen.Settings
                    },
                    onPauseClick = { torrentId ->
                        NuHaptics.click(context)
                        scope.launch { repository.pauseTorrent(torrentId) }
                    },
                    onResumeClick = { torrentId ->
                        NuHaptics.click(context)
                        NuSoundFeedback.playStartBeep(context)
                        scope.launch { repository.resumeTorrent(torrentId) }
                    },
                    onDeleteClick = { torrentId, deleteFiles ->
                        NuHaptics.warning(context)
                        scope.launch { repository.deleteTorrent(torrentId, deleteFiles) }
                    }
                )
            }
            is Screen.AddTorrent -> {
                BackHandler {
                    NuHaptics.tick(context)
                    currentScreen = Screen.Home
                }
                AddTorrentScreen(
                    fileUri = screen.fileUri,
                    repository = repository,
                    settingsRepository = settingsRepo,
                    onStartDownload = {
                        NuHaptics.heavyClick(context)
                        NuSoundFeedback.playStartBeep(context)
                        currentScreen = Screen.Home
                    },
                    onCancel = {
                        NuHaptics.tick(context)
                        currentScreen = Screen.Home
                    }
                )
            }
            is Screen.Details -> {
                BackHandler {
                    NuHaptics.tick(context)
                    currentScreen = Screen.Home
                }
                TorrentDetailsScreen(
                    infoHashHex = screen.infoHash,
                    repository = repository,
                    onBack = {
                        NuHaptics.tick(context)
                        currentScreen = Screen.Home
                    },
                    onPauseClick = { torrentId ->
                        NuHaptics.click(context)
                        scope.launch { repository.pauseTorrent(torrentId) }
                    },
                    onResumeClick = { torrentId ->
                        NuHaptics.click(context)
                        NuSoundFeedback.playStartBeep(context)
                        scope.launch { repository.resumeTorrent(torrentId) }
                    },
                    onDeleteClick = { torrentId, deleteFiles ->
                        NuHaptics.warning(context)
                        scope.launch {
                            repository.deleteTorrent(torrentId, deleteFiles = deleteFiles)
                            currentScreen = Screen.Home
                        }
                    },
                    onForceRecheckClick = { torrentId ->
                        NuHaptics.click(context)
                        scope.launch {
                            repository.forceRecheck(torrentId)
                        }
                    },
                    onSequentialToggle = { torrentId, enabled ->
                        NuHaptics.tick(context)
                        scope.launch {
                            repository.setSequentialDownload(torrentId, enabled)
                        }
                    },
                    onCategoryChange = { torrentId, category ->
                        NuHaptics.tick(context)
                        scope.launch {
                            repository.setCategory(torrentId, category)
                        }
                    }
                )
            }
            is Screen.Settings -> {
                BackHandler {
                    NuHaptics.tick(context)
                    currentScreen = Screen.Home
                }
                SettingsScreen(
                    settingsRepository = settingsRepo,
                    torrentRepository = repository,
                    onBack = {
                        NuHaptics.tick(context)
                        currentScreen = Screen.Home
                    }
                )
            }
        }
    }
}
