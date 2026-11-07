package com.nutorrent.ui.home

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.res.painterResource
import com.nutorrent.R
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nutorrent.core.device.NuHaptics
import com.nutorrent.core.device.NuSoundFeedback
import com.nutorrent.ui.components.AnimatedNuLogo
import com.nutorrent.ui.components.NuAboutEngineDialog
import com.nutorrent.ui.components.BatchActionBar
import com.nutorrent.ui.components.NetworkSpeedGauge
import com.nutorrent.ui.theme.NuGlassCard
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import com.nutorrent.ui.components.EdgeCaseDetails
import com.nutorrent.ui.components.EdgeCaseTroubleshootDialog
import com.nutorrent.ui.components.TroubleshootingStep
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nutorrent.core.model.TorrentId
import com.nutorrent.core.model.TorrentState
import com.nutorrent.core.util.FormatUtils
import com.nutorrent.data.repository.TorrentRepository
import com.nutorrent.data.repository.TorrentSummaryItem
import com.nutorrent.torrent.metainfo.MagnetUri
import com.nutorrent.ui.components.MetricChip
import com.nutorrent.ui.components.PieceProgressBar
import com.nutorrent.ui.components.StatusBadge
import com.nutorrent.ui.theme.NuPrimary
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import com.nutorrent.ui.components.AdaptiveNavigationLayout
import com.nutorrent.ui.components.BatchOperationsSheet
import com.nutorrent.ui.components.CommandPaletteDialog
import com.nutorrent.ui.components.DestructiveActionDialog
import com.nutorrent.ui.components.NavDestination
import com.nutorrent.ui.components.NoActiveDownloadsEmptyView
import com.nutorrent.ui.components.NoCompletedEmptyView
import com.nutorrent.ui.components.NoSearchResultsEmptyView
import com.nutorrent.ui.components.NoTorrentsEmptyView
import com.nutorrent.ui.components.SmartFormValidator
import com.nutorrent.ui.components.TorrentCardSkeleton
import com.nutorrent.ui.components.TorrentTableView
import kotlinx.coroutines.launch

enum class TorrentCategory(val label: String) {
    ALL("All"),
    DOWNLOADING("Downloading"),
    SEEDING("Seeding"),
    PAUSED("Paused"),
    COMPLETED("Completed"),
    ERROR("Error")
}

enum class TorrentSortOrder(val label: String) {
    DATE_ADDED("Date Added"),
    NAME("Name"),
    PROGRESS("Progress"),
    SPEED("Speed"),
    SIZE("Size")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    repository: TorrentRepository,
    networkStatus: kotlinx.coroutines.flow.StateFlow<com.nutorrent.torrent.network.NetworkStatus>? = null,
    onAddTorrentClick: () -> Unit,
    onTorrentClick: (TorrentId) -> Unit,
    onSettingsClick: () -> Unit,
    onPauseClick: (TorrentId) -> Unit,
    onResumeClick: (TorrentId) -> Unit,
    onDeleteClick: (TorrentId, Boolean) -> Unit,
    onCommunityClick: () -> Unit = {}
) {
    val torrents by repository.torrentsList.collectAsStateWithLifecycle()
    val currentNetworkStatus by (networkStatus?.collectAsState() ?: androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(com.nutorrent.torrent.network.NetworkStatus(true, isWifi = true, isCellular = false, isMetered = false))
    })
    val scope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val appSettings by ((context.applicationContext as? com.nutorrent.NuTorrentApplication)?.settingsRepository?.settings?.collectAsStateWithLifecycle()
        ?: remember { androidx.compose.runtime.mutableStateOf(null) })
    val defaultDownloadPath = appSettings?.defaultDownloadPath
        ?: com.nutorrent.data.repository.SettingsRepository.getDefaultDownloadDir(context)

    var magnetDownloadPath by remember(defaultDownloadPath) {
        mutableStateOf(defaultDownloadPath)
    }

    val magnetFolderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try {
                context.contentResolver.takePersistableUriPermission(uri, takeFlags)
            } catch (e: Exception) {
                com.nutorrent.core.util.Lg.w(
                    com.nutorrent.core.util.LogCategory.STORAGE,
                    "Failed to persist URI permission for magnet: ${e.message}"
                )
            }
            magnetDownloadPath = uri.toString()
        }
    }

    var errorTargetTorrentId by remember { mutableStateOf<TorrentId?>(null) }
    val errorFolderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        val targetId = errorTargetTorrentId
        if (uri != null && targetId != null) {
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try {
                context.contentResolver.takePersistableUriPermission(uri, takeFlags)
            } catch (e: Exception) {
                com.nutorrent.core.util.Lg.w(
                    com.nutorrent.core.util.LogCategory.STORAGE,
                    "Failed to persist URI permission for error fix: ${e.message}"
                )
            }
            scope.launch {
                repository.updateTorrentDestination(targetId, uri.toString())
                repository.resumeTorrent(targetId)
            }
        }
        errorTargetTorrentId = null
    }

    var selectedCategory by remember { mutableStateOf(TorrentCategory.ALL) }
    var currentSortOrder by remember { mutableStateOf(TorrentSortOrder.DATE_ADDED) }
    var sortAscending by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }

    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }

    var deleteTorrentTarget by remember { mutableStateOf<TorrentSummaryItem?>(null) }
    var deleteFilesChecked by remember { mutableStateOf(false) }

    var showEngineStatusDialog by remember { mutableStateOf(false) }
    var showAddChooserDialog by remember { mutableStateOf(false) }
    var showMagnetInputDialog by remember { mutableStateOf(false) }
    var magnetInputText by remember { mutableStateOf("") }
    var magnetParseError by remember { mutableStateOf<String?>(null) }
    var showMagnetTroubleshoot by remember { mutableStateOf(false) }

    // Optimistic UI updates
    val optimisticStates = remember { androidx.compose.runtime.mutableStateMapOf<TorrentId, TorrentState>() }
    LaunchedEffect(torrents) {
        torrents.forEach { item ->
            if (optimisticStates[item.torrentId] == item.state) {
                optimisticStates.remove(item.torrentId)
            }
        }
    }

    val handlePause: (TorrentId) -> Unit = { id ->
        optimisticStates[id] = TorrentState.PAUSED
        onPauseClick(id)
    }

    val handleResume: (TorrentId) -> Unit = { id ->
        optimisticStates[id] = TorrentState.DOWNLOADING
        onResumeClick(id)
    }

    // Selection & Bulk actions
    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(emptySet<TorrentId>()) }
    var showBatchDeleteDialog by remember { mutableStateOf(false) }
    var batchDeleteFilesChecked by remember { mutableStateOf(false) }

    // Command palette & layout view mode
    var showCommandPalette by remember { mutableStateOf(false) }
    var isTableView by remember(appSettings?.preferredLayoutMode) {
        mutableStateOf(appSettings?.preferredLayoutMode == "TABLE")
    }

    var isInitialLoading by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(300)
        isInitialLoading = false
    }

    val freeSpaceFormatted = remember(defaultDownloadPath) {
        try {
            val f = java.io.File(defaultDownloadPath)
            val free = f.usableSpace
            if (free > 0L) FormatUtils.formatBytes(free) else null
        } catch (e: Exception) {
            null
        }
    }


    val downloadingCount = torrents.count { it.state.isActive && it.state != TorrentState.SEEDING }
    val seedingCount = torrents.count { it.state == TorrentState.SEEDING }
    val pausedCount = torrents.count { (it.state == TorrentState.PAUSED || it.state == TorrentState.QUEUED) && it.progress.progressFraction < 1f }
    val completedCount = torrents.count { it.state.isFinished || it.progress.progressFraction >= 1f }
    val errorCount = torrents.count { it.state == TorrentState.ERROR }

    val app = context.applicationContext as? com.nutorrent.NuTorrentApplication
    val totalDownSpeed by (app?.torrentEngine?.totalDownloadSpeedBps?.collectAsStateWithLifecycle() ?: remember { mutableLongStateOf(0L) })
    val totalUpSpeed by (app?.torrentEngine?.totalUploadSpeedBps?.collectAsStateWithLifecycle() ?: remember { mutableLongStateOf(0L) })


    val clipboardText = remember(torrents) {
        try {
            clipboardManager.getText()?.text?.trim()
        } catch (e: Exception) {
            null
        }
    }
    var dismissedClipboardMagnet by remember { mutableStateOf<String?>(null) }
    val isMagnetInClipboard = clipboardText != null &&
            clipboardText.startsWith("magnet:", ignoreCase = true) &&
            clipboardText != dismissedClipboardMagnet &&
            torrents.none { clipboardText.contains(it.torrentId.hex, ignoreCase = true) }

    val filteredTorrents = remember(torrents, optimisticStates.toMap(), selectedCategory, searchQuery, currentSortOrder, sortAscending) {
        val mapped = torrents.map { item ->
            val opt = optimisticStates[item.torrentId]
            if (opt != null) item.copy(state = opt) else item
        }
        val filtered = mapped.filter { item ->
            val matchesCategory = when (selectedCategory) {
                TorrentCategory.ALL -> true
                TorrentCategory.DOWNLOADING -> item.state.isActive && item.state != TorrentState.SEEDING
                TorrentCategory.SEEDING -> item.state == TorrentState.SEEDING
                TorrentCategory.PAUSED -> item.state == TorrentState.PAUSED || item.state == TorrentState.QUEUED
                TorrentCategory.COMPLETED -> item.state.isFinished || item.progress.progressFraction >= 1f
                TorrentCategory.ERROR -> item.state == TorrentState.ERROR
            }
            val matchesSearch = searchQuery.isBlank() ||
                    item.name.contains(searchQuery, ignoreCase = true) ||
                    item.torrentId.hex.contains(searchQuery, ignoreCase = true) ||
                    item.category.contains(searchQuery, ignoreCase = true) ||
                    (repository.getSession(item.torrentId)?.metadata?.files?.any { it.relativePath.contains(searchQuery, ignoreCase = true) } == true)
            matchesCategory && matchesSearch
        }

        when (currentSortOrder) {
            TorrentSortOrder.NAME -> if (sortAscending) filtered.sortedBy { it.name.lowercase() } else filtered.sortedByDescending { it.name.lowercase() }
            TorrentSortOrder.PROGRESS -> if (sortAscending) filtered.sortedBy { it.progress.progressFraction } else filtered.sortedByDescending { it.progress.progressFraction }
            TorrentSortOrder.SPEED -> if (sortAscending) filtered.sortedBy { it.progress.downloadSpeedBps + it.progress.uploadSpeedBps } else filtered.sortedByDescending { it.progress.downloadSpeedBps + it.progress.uploadSpeedBps }
            TorrentSortOrder.SIZE -> if (sortAscending) filtered.sortedBy { it.totalLength } else filtered.sortedByDescending { it.totalLength }
            TorrentSortOrder.DATE_ADDED -> if (sortAscending) filtered.sortedBy { it.addedDate } else filtered.sortedByDescending { it.addedDate }
        }
    }

    val selectedNavDestination = when (selectedCategory) {
        TorrentCategory.ALL -> NavDestination.ALL
        TorrentCategory.DOWNLOADING -> NavDestination.DOWNLOADING
        TorrentCategory.SEEDING -> NavDestination.SEEDING
        TorrentCategory.COMPLETED -> NavDestination.COMPLETED
        else -> NavDestination.ALL
    }

    AdaptiveNavigationLayout(
        selectedDestination = selectedNavDestination,
        onSelectDestination = { dest ->
            when (dest) {
                NavDestination.ALL -> selectedCategory = TorrentCategory.ALL
                NavDestination.DOWNLOADING -> selectedCategory = TorrentCategory.DOWNLOADING
                NavDestination.SEEDING -> selectedCategory = TorrentCategory.SEEDING
                NavDestination.COMPLETED -> selectedCategory = TorrentCategory.COMPLETED
                NavDestination.COMMUNITY -> onCommunityClick()
                NavDestination.SETTINGS -> onSettingsClick()
            }
        },
        onAddTransferClick = { showAddChooserDialog = true },
        downloadingCount = downloadingCount,
        seedingCount = seedingCount,
        freeDiskSpaceFormatted = freeSpaceFormatted
    ) { isWideScreen ->
    Scaffold(
        modifier = Modifier.onKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown) {
                if (event.isCtrlPressed && event.key == Key.K) {
                    showCommandPalette = true
                    true
                } else if (event.isCtrlPressed && event.key == Key.N) {
                    onAddTorrentClick()
                    true
                } else if (event.isCtrlPressed && event.key == Key.A) {
                    isSelectionMode = true
                    selectedIds = filteredTorrents.map { it.torrentId }.toSet()
                    true
                } else if (event.key == Key.Escape) {
                    if (isSelectionMode) {
                        isSelectionMode = false
                        selectedIds = emptySet()
                        true
                    } else if (showCommandPalette) {
                        showCommandPalette = false
                        true
                    } else false
                } else false
            } else false
        },
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AnimatedNuLogo(
                                size = 32.dp,
                                isTransferActive = downloadingCount > 0 || seedingCount > 0,
                                interactive = true,
                                onClick = { showEngineStatusDialog = true }
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "NuTorrent",
                                fontWeight = FontWeight.Bold,
                                letterSpacing = (-0.5).sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { showCommandPalette = true }) {
                            Icon(
                                imageVector = Icons.Default.Terminal,
                                contentDescription = "Command Palette (Ctrl+K)",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = {
                            isTableView = !isTableView
                            appSettings?.let { current ->
                                (context.applicationContext as? com.nutorrent.NuTorrentApplication)?.settingsRepository?.updateSettings(
                                    current.copy(preferredLayoutMode = if (isTableView) "TABLE" else "CARDS")
                                )
                            }
                        }) {
                            Icon(
                                imageVector = if (isTableView) Icons.Default.ViewAgenda else Icons.Default.TableChart,
                                contentDescription = if (isTableView) "Switch to Cards" else "Switch to Table View",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = {
                            isSelectionMode = !isSelectionMode
                            if (!isSelectionMode) selectedIds = emptySet()
                        }) {
                            Icon(
                                imageVector = Icons.Default.SelectAll,
                                contentDescription = if (isSelectionMode) "Cancel Selection" else "Select Multiple",
                                tint = if (isSelectionMode) NuPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { isSearchActive = !isSearchActive }) {
                            Icon(
                                imageVector = if (isSearchActive) Icons.Default.Clear else Icons.Default.Search,
                                contentDescription = "Search",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Box(contentAlignment = Alignment.Center) {
                            IconButton(onClick = { showSortMenu = true }) {
                                Icon(
                                    imageVector = Icons.Default.FilterList,
                                    contentDescription = "Sort",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            DropdownMenu(
                                expanded = showSortMenu,
                                onDismissRequest = { showSortMenu = false }
                            ) {
                                TorrentSortOrder.values().forEach { order ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = order.label,
                                                fontWeight = if (currentSortOrder == order) FontWeight.Bold else FontWeight.Normal,
                                                color = if (currentSortOrder == order) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                            )
                                        },
                                        trailingIcon = {
                                            if (currentSortOrder == order) {
                                                Icon(
                                                    imageVector = if (sortAscending) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                                                    contentDescription = if (sortAscending) "Ascending" else "Descending",
                                                    modifier = Modifier.size(16.dp),
                                                    tint = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        },
                                        onClick = {
                                            if (currentSortOrder == order) {
                                                sortAscending = !sortAscending
                                            } else {
                                                currentSortOrder = order
                                                sortAscending = false
                                            }
                                            showSortMenu = false
                                        }
                                    )
                                }
                            }
                        }
                        IconButton(onClick = onSettingsClick) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "Settings",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    )
                )

                if (isSearchActive) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        placeholder = { Text("Search by name or hash...") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        leadingIcon = {
                            Icon(Icons.Default.Search, contentDescription = null)
                        },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Clear")
                                }
                            }
                        }
                    )
                }

                // Category Filter Chips
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    items(TorrentCategory.values()) { category ->
                        val count = when (category) {
                            TorrentCategory.ALL -> torrents.size
                            TorrentCategory.DOWNLOADING -> downloadingCount
                            TorrentCategory.SEEDING -> seedingCount
                            TorrentCategory.PAUSED -> pausedCount
                            TorrentCategory.COMPLETED -> completedCount
                            TorrentCategory.ERROR -> errorCount
                        }
                        if (category != TorrentCategory.ALL && count == 0 && selectedCategory != category) return@items
                        FilterChip(
                            selected = selectedCategory == category,
                            onClick = { selectedCategory = category },
                            label = { Text("${category.label} ($count)") }
                        )
                    }
                }

                // Clipboard Magnet Detection Banner with Magnetic Flux & Pulse
                val magnetTransition = rememberInfiniteTransition(label = "magnetFlux")
                val magnetGlowAlpha by magnetTransition.animateFloat(
                    initialValue = 0.35f,
                    targetValue = 0.85f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1100),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "magnetGlow"
                )
                val magnetRingScale by magnetTransition.animateFloat(
                    initialValue = 0.85f,
                    targetValue = 1.35f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1500, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart
                    ),
                    label = "magnetRingScale"
                )
                val magnetRingAlpha by magnetTransition.animateFloat(
                    initialValue = 0.8f,
                    targetValue = 0.0f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1500, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart
                    ),
                    label = "magnetRingAlpha"
                )

                AnimatedVisibility(
                    visible = isMagnetInClipboard,
                    enter = fadeIn(spring(dampingRatio = 0.75f)) + expandVertically(spring(dampingRatio = 0.75f)),
                    exit = fadeOut(tween(150)) + shrinkVertically(tween(150))
                ) {
                    androidx.compose.material3.Card(
                        colors = androidx.compose.material3.CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                            .border(
                                1.5.dp,
                                NuPrimary.copy(alpha = magnetGlowAlpha),
                                RoundedCornerShape(12.dp)
                            )
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier.size(36.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                // Magnetic expanding flux ring
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .graphicsLayer {
                                            scaleX = magnetRingScale
                                            scaleY = magnetRingScale
                                            alpha = magnetRingAlpha
                                        }
                                        .clip(CircleShape)
                                        .border(1.5.dp, NuPrimary, CircleShape)
                                )
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(NuPrimary.copy(alpha = 0.2f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Default.Link,
                                        contentDescription = "Magnet Link",
                                        tint = NuPrimary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "Magnet Link Detected",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(NuPrimary)
                                    )
                                }
                                Text(
                                    clipboardText ?: "",
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    magnetInputText = clipboardText ?: ""
                                    showMagnetInputDialog = true
                                },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                modifier = Modifier.height(32.dp),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Attract ⚡", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            IconButton(
                                onClick = { dismissedClipboardMagnet = clipboardText },
                                modifier = Modifier
                                    .size(32.dp)
                                    .minimumInteractiveComponentSize()
                            ) {
                                Icon(
                                    Icons.Default.Clear,
                                    contentDescription = "Dismiss",
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }

                // Offline Indicator Banner
                androidx.compose.animation.AnimatedVisibility(visible = !currentNetworkStatus.isConnected) {
                    androidx.compose.material3.Surface(
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.95f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = androidx.compose.material.icons.Icons.Default.Clear,
                                contentDescription = "No Network Connection",
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "No network connection",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Text(
                                    text = "Your torrents are preserved. Transfers will resume when connectivity returns.",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddChooserDialog = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Torrent")
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        if (isInitialLoading && torrents.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                repeat(4) {
                    TorrentCardSkeleton()
                }
            }
        } else if (torrents.isEmpty()) {
            NoTorrentsEmptyView(
                onAddTorrentFile = onAddTorrentClick,
                onAddMagnet = { showMagnetInputDialog = true },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            )
        } else if (filteredTorrents.isEmpty()) {
            NoSearchResultsEmptyView(
                query = searchQuery,
                onClearSearch = { searchQuery = ""; selectedCategory = TorrentCategory.ALL },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            )
        } else if (isTableView) {
            TorrentTableView(
                torrents = filteredTorrents,
                selectedIds = selectedIds,
                isSelectionMode = isSelectionMode,
                currentSortOrder = currentSortOrder,
                sortAscending = sortAscending,
                onSortChange = { order ->
                    if (currentSortOrder == order) sortAscending = !sortAscending
                    else {
                        currentSortOrder = order
                        sortAscending = false
                    }
                },
                onTorrentClick = { onTorrentClick(it) },
                onToggleSelect = { id ->
                    selectedIds = if (selectedIds.contains(id)) selectedIds - id else selectedIds + id
                },
                onPauseClick = handlePause,
                onResumeClick = handleResume,
                onDeleteClick = { deleteTorrentTarget = it },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 88.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (isSelectionMode) {
                    item(key = "batchActionBar") {
                        BatchActionBar(
                            selectedCount = selectedIds.size,
                            totalCount = filteredTorrents.size,
                            onSelectAll = {
                                selectedIds = filteredTorrents.map { it.torrentId }.toSet()
                            },
                            onClearSelection = {
                                selectedIds = emptySet()
                                isSelectionMode = false
                            },
                            onPauseSelected = {
                                val toPause = selectedIds.toList()
                                selectedIds = emptySet()
                                isSelectionMode = false
                                for (id in toPause) handlePause(id)
                            },
                            onResumeSelected = {
                                val toResume = selectedIds.toList()
                                selectedIds = emptySet()
                                isSelectionMode = false
                                for (id in toResume) handleResume(id)
                            },
                            onDeleteSelected = {
                                showBatchDeleteDialog = true
                            }
                        )
                    }
                } else {
                    if (downloadingCount > 0 || seedingCount > 0 || totalDownSpeed > 0L || totalUpSpeed > 0L) {
                        item(key = "networkSpeedGauge") {
                            NetworkSpeedGauge(
                                downloadSpeedBps = totalDownSpeed,
                                uploadSpeedBps = totalUpSpeed,
                                activeTransfersCount = downloadingCount + seedingCount
                            )
                        }
                    }
                }

                items(filteredTorrents, key = { it.torrentId.hex }) { item ->
                    TorrentCard(
                        item = item,
                        modifier = Modifier.animateItem(),
                        isSelectionMode = isSelectionMode,
                        isSelected = selectedIds.contains(item.torrentId),
                        onToggleSelect = {
                            NuHaptics.tick(context)
                            selectedIds = if (selectedIds.contains(item.torrentId)) {
                                val next = selectedIds - item.torrentId
                                if (next.isEmpty()) isSelectionMode = false
                                next
                            } else {
                                selectedIds + item.torrentId
                            }
                        },
                        onLongClick = {
                            NuHaptics.heavyClick(context)
                            isSelectionMode = true
                            selectedIds = selectedIds + item.torrentId
                        },
                        onClick = {
                            if (isSelectionMode) {
                                NuHaptics.tick(context)
                                selectedIds = if (selectedIds.contains(item.torrentId)) {
                                    val next = selectedIds - item.torrentId
                                    if (next.isEmpty()) isSelectionMode = false
                                    next
                                } else {
                                    selectedIds + item.torrentId
                                }
                            } else {
                                NuHaptics.click(context)
                                onTorrentClick(item.torrentId)
                            }
                        },
                        onPauseClick = { handlePause(item.torrentId) },
                        onResumeClick = { handleResume(item.torrentId) },
                        onDeleteClick = {
                            deleteTorrentTarget = item
                            deleteFilesChecked = false
                        },
                        onMoveUp = { repository.moveUpInQueue(item.torrentId) },
                        onMoveDown = { repository.moveDownInQueue(item.torrentId) },
                        isAdvancedMode = appSettings?.uiMode == "ADVANCED",
                        onErrorLocationClick = {
                            errorTargetTorrentId = item.torrentId
                            errorFolderPickerLauncher.launch(null)
                        }
                    )
                }
            }
        }
    }
    }

    if (showEngineStatusDialog) {
        NuAboutEngineDialog(onDismiss = { showEngineStatusDialog = false })
    }

    if (showBatchDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showBatchDeleteDialog = false },
            title = { Text("Delete ${selectedIds.size} Torrents?") },
            text = {
                Column {
                    Text("Are you sure you want to remove the ${selectedIds.size} selected torrent transfers?")
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { batchDeleteFilesChecked = !batchDeleteFilesChecked }
                    ) {
                        Checkbox(
                            checked = batchDeleteFilesChecked,
                            onCheckedChange = { batchDeleteFilesChecked = it }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Also delete downloaded data from disk", fontSize = 13.sp)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        NuHaptics.warning(context)
                        val toDelete = selectedIds.toList()
                        selectedIds = emptySet()
                        isSelectionMode = false
                        showBatchDeleteDialog = false
                        for (id in toDelete) {
                            onDeleteClick(id, batchDeleteFilesChecked)
                        }
                    },
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showBatchDeleteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Add Options Chooser Dialog
    if (showAddChooserDialog) {
        AlertDialog(
            onDismissRequest = { showAddChooserDialog = false },
            title = { Text("Add Torrent") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton(
                        onClick = {
                            showAddChooserDialog = false
                            onAddTorrentClick()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.FileOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Open .torrent File")
                    }

                    OutlinedButton(
                        onClick = {
                            showAddChooserDialog = false
                            showMagnetInputDialog = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Paste Magnet Link")
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showAddChooserDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Magnet Link Input Dialog
    if (showMagnetInputDialog) {
        var parsedMagnet by remember(magnetInputText) {
            mutableStateOf<MagnetUri?>(null)
        }

        AlertDialog(
            onDismissRequest = {
                showMagnetInputDialog = false
                magnetInputText = ""
                magnetParseError = null
            },
            title = { Text("Add Magnet Link") },
            text = {
                Column {
                    OutlinedTextField(
                        value = magnetInputText,
                        onValueChange = { text ->
                            magnetInputText = text
                            try {
                                if (text.isNotBlank()) {
                                    parsedMagnet = MagnetUri.parse(text.trim())
                                    magnetParseError = null
                                } else {
                                    parsedMagnet = null
                                    magnetParseError = null
                                }
                            } catch (e: Exception) {
                                parsedMagnet = null
                                magnetParseError = e.message ?: "Invalid magnet URI"
                            }
                        },
                        label = { Text("Magnet URI") },
                        placeholder = { Text("magnet:?xt=urn:btih:...") },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 4,
                        isError = magnetParseError != null
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            onClick = {
                                val clip = clipboardManager.getText()?.text
                                if (clip != null) {
                                    magnetInputText = clip
                                    try {
                                        parsedMagnet = MagnetUri.parse(clip.trim())
                                        magnetParseError = null
                                    } catch (e: Exception) {
                                        parsedMagnet = null
                                        magnetParseError = e.message ?: "Invalid magnet URI"
                                    }
                                }
                            }
                        ) {
                            Text("Paste from Clipboard")
                        }
                    }

                    if (magnetParseError != null) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = magnetParseError ?: "Invalid magnet link",
                                    color = MaterialTheme.colorScheme.error,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(NuPrimary.copy(alpha = 0.12f))
                                    .clickable { showMagnetTroubleshoot = true }
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Info,
                                    contentDescription = "Troubleshooting Info",
                                    tint = NuPrimary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = "Learn why & how to troubleshoot",
                                    fontSize = 11.5.sp,
                                    color = NuPrimary,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }

                    parsedMagnet?.let { mag ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(8.dp)
                        ) {
                            Text(
                                text = "Name: ${mag.displayName ?: "Unknown (Fetching metadata)"}",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 12.sp
                            )
                            Text(
                                text = "Hash: ${mag.torrentId.hex.take(16)}...",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            if (mag.exactLength != null && mag.exactLength > 0L) {
                                Text(
                                    text = "Size: ${FormatUtils.formatBytes(mag.exactLength)}",
                                    fontSize = 11.sp
                                )
                            }
                            if (mag.trackers.isNotEmpty()) {
                                Text(
                                    text = "Trackers: ${mag.trackers.size} found",
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Download Location",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(
                                text = FormatUtils.formatStoragePath(magnetDownloadPath),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        OutlinedButton(
                            onClick = { magnetFolderPickerLauncher.launch(null) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text("Change", fontSize = 11.sp)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val mag = parsedMagnet
                        if (mag != null) {
                            scope.launch {
                                repository.addMagnetTorrent(
                                    magnet = mag,
                                    destinationPath = magnetDownloadPath,
                                    autoStart = true
                                )
                            }
                            showMagnetInputDialog = false
                            magnetInputText = ""
                            magnetParseError = null
                            magnetDownloadPath = defaultDownloadPath
                        }
                    },
                    enabled = parsedMagnet != null
                ) {
                    Text("Add")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showMagnetInputDialog = false
                        magnetInputText = ""
                        magnetParseError = null
                        magnetDownloadPath = defaultDownloadPath
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showMagnetTroubleshoot) {
        EdgeCaseTroubleshootDialog(
            details = EdgeCaseDetails(
                categoryBadge = "MALFORMED // MAGNET URI",
                badgeColor = com.nutorrent.ui.theme.NuError,
                simpleHeadline = "Invalid Magnet Link",
                simpleGlance = "The link you pasted is missing required swarm parameters or was truncated when copied.",
                whatHappened = "NuTorrent could not extract the exact-topic hash (xt=urn:btih:) from the provided text. BitTorrent clients require this 40-character hex or 32-character Base32 string to find peers in the swarm.",
                whyItHappened = listOf(
                    "The link was only partially highlighted before copying.",
                    "The text is a regular webpage URL (http://) instead of a magnet: link.",
                    "The source website uses an unsupported or proprietary URI format."
                ),
                troubleshootingSteps = listOf(
                    TroubleshootingStep(
                        stepNumber = 1,
                        title = "Copy the full link address",
                        description = "In your browser, long-press the magnet icon or download button, then select \"Copy link address\". Avoid highlighting the text manually.",
                        tip = "Manual text selection often misses the end of long URLs."
                    ),
                    TroubleshootingStep(
                        stepNumber = 2,
                        title = "Check the link prefix",
                        description = "A valid magnet link starts with \"magnet:?xt=urn:btih:\" followed by the torrent's unique hash.",
                        tip = "Example: magnet:?xt=urn:btih:1234567890abcdef..."
                    ),
                    TroubleshootingStep(
                        stepNumber = 3,
                        title = "Open a .torrent file instead",
                        description = "If the website offers a .torrent file, download it and open it in NuTorrent instead."
                    )
                ),
                technicalDetails = "Pasted Input: $magnetInputText\nError: ${magnetParseError ?: "Unknown parsing error"}"
            ),
            onDismiss = { showMagnetTroubleshoot = false }
        )
    }

    // Single Destructive Delete Dialog
    deleteTorrentTarget?.let { item ->
        DestructiveActionDialog(
            itemCount = 1,
            singleItemName = item.name,
            totalBytes = item.totalLength,
            onConfirmDelete = { deleteFiles ->
                onDeleteClick(item.torrentId, deleteFiles)
                deleteTorrentTarget = null
            },
            onDismiss = { deleteTorrentTarget = null }
        )
    }

    // Batch Destructive Delete Dialog
    if (showBatchDeleteDialog) {
        val totalBatchBytes = torrents.filter { it.torrentId in selectedIds }.sumOf { it.totalLength }
        DestructiveActionDialog(
            itemCount = selectedIds.size,
            totalBytes = totalBatchBytes,
            onConfirmDelete = { deleteFiles ->
                selectedIds.forEach { id ->
                    onDeleteClick(id, deleteFiles)
                }
                selectedIds = emptySet()
                isSelectionMode = false
                showBatchDeleteDialog = false
            },
            onDismiss = { showBatchDeleteDialog = false }
        )
    }

    // Batch Operations Bottom Sheet
    if (isSelectionMode && selectedIds.isNotEmpty()) {
        BatchOperationsSheet(
            selectedCount = selectedIds.size,
            totalCount = filteredTorrents.size,
            onPauseSelected = {
                selectedIds.forEach { handlePause(it) }
            },
            onResumeSelected = {
                selectedIds.forEach { handleResume(it) }
            },
            onMoveUpSelected = {
                selectedIds.forEach { repository.moveUpInQueue(it) }
            },
            onMoveDownSelected = {
                selectedIds.forEach { repository.moveDownInQueue(it) }
            },
            onDeleteSelected = {
                showBatchDeleteDialog = true
            },
            onSelectAll = {
                if (selectedIds.size == filteredTorrents.size) {
                    selectedIds = emptySet()
                } else {
                    selectedIds = filteredTorrents.map { it.torrentId }.toSet()
                }
            },
            onDismiss = {
                isSelectionMode = false
                selectedIds = emptySet()
            }
        )
    }

    // Command Palette Dialog
    if (showCommandPalette) {
        CommandPaletteDialog(
            torrents = torrents,
            onDismiss = { showCommandPalette = false },
            onSelectTorrent = { onTorrentClick(it) },
            onAddFileClick = onAddTorrentClick,
            onAddMagnetClick = { showMagnetInputDialog = true },
            onPauseAllClick = {
                torrents.filter { it.state.isActive }.forEach { handlePause(it.torrentId) }
            },
            onResumeAllClick = {
                torrents.filter { it.state == TorrentState.PAUSED || it.state == TorrentState.QUEUED }.forEach { handleResume(it.torrentId) }
            },
            onFilterDownloading = { selectedCategory = TorrentCategory.DOWNLOADING },
            onFilterSeeding = { selectedCategory = TorrentCategory.SEEDING },
            onSettingsClick = onSettingsClick,
            onCommunityClick = onCommunityClick,
            onClearCompletedClick = {
                torrents.filter { it.state.isFinished || it.progress.progressFraction >= 1f }.forEach {
                    onDeleteClick(it.torrentId, false)
                }
            }
        )
    }

    if (showMagnetTroubleshoot) {
        EdgeCaseTroubleshootDialog(
            details = EdgeCaseDetails(
                categoryBadge = "MALFORMED // MAGNET URI",
                badgeColor = com.nutorrent.ui.theme.NuError,
                simpleHeadline = "Invalid Magnet Link",
                simpleGlance = "The link you pasted is missing required swarm parameters or was truncated when copied.",
                whatHappened = "NuTorrent could not extract the exact-topic hash (xt=urn:btih:) from the provided text. BitTorrent clients require this 40-character hex or 32-character Base32 string to find peers in the swarm.",
                whyItHappened = listOf(
                    "The link was only partially highlighted before copying.",
                    "The text is a regular webpage URL (http://) instead of a magnet: link.",
                    "The source website uses an unsupported or proprietary URI format."
                ),
                troubleshootingSteps = listOf(
                    TroubleshootingStep(
                        stepNumber = 1,
                        title = "Copy the full link address",
                        description = "In your browser, long-press the magnet icon or download button, then select \"Copy link address\". Avoid highlighting the text manually.",
                        tip = "Manual text selection often misses the end of long URLs."
                    ),
                    TroubleshootingStep(
                        stepNumber = 2,
                        title = "Check the link prefix",
                        description = "A valid magnet link starts with \"magnet:?xt=urn:btih:\" followed by the torrent's unique hash.",
                        tip = "Example: magnet:?xt=urn:btih:1234567890abcdef..."
                    ),
                    TroubleshootingStep(
                        stepNumber = 3,
                        title = "Open a .torrent file instead",
                        description = "If the website offers a .torrent file, download it and open it in NuTorrent instead."
                    )
                ),
                technicalDetails = "Pasted Input: $magnetInputText\nError: ${magnetParseError ?: "Unknown parsing error"}"
            ),
            onDismiss = { showMagnetTroubleshoot = false }
        )
    }
}

@Composable
fun EmptyHomeState(
    modifier: Modifier = Modifier,
    onAddTorrentClick: () -> Unit,
    onAddMagnetClick: () -> Unit
) {
    val emptyTransition = rememberInfiniteTransition(label = "emptyFloating")
    val floatOffset by emptyTransition.animateFloat(
        initialValue = -5f,
        targetValue = 5f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "floatOffset"
    )

    val orbitAngle by emptyTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "orbit"
    )

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .padding(24.dp)
                .verticalScroll(rememberScrollState())
        ) {
            AnimatedNuLogo(
                size = 76.dp,
                isTransferActive = false,
                interactive = true
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Welcome to NuTorrent",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Fast • Minimal • Private • Transparent",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "A modern BitTorrent client designed for open-source software, Linux ISOs, and lawful peer-to-peer distribution.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 18.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )

            Spacer(modifier = Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FilledTonalButton(
                    modifier = Modifier.weight(1f),
                    onClick = onAddTorrentClick
                ) {
                    Icon(Icons.Default.FileOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Open .torrent", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = onAddMagnetClick
                ) {
                    Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Paste Magnet", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Informational Onboarding Cards
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text("Local Storage", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text(
                            "Files are saved to your device's internal storage in the 'NuTorrent' directory and are accessible at any time via Settings.",
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text("Background Transfers", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text(
                            "Transfers continue in the background using an efficient Android Foreground Service with low-battery pausing.",
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text("Private & Transparent", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text(
                            "Zero analytics, zero tracking SDKs, and zero advertisements. Pure peer-to-peer BitTorrent networking.",
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TorrentCard(
    item: TorrentSummaryItem,
    onClick: () -> Unit,
    onPauseClick: () -> Unit,
    onResumeClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    modifier: Modifier = Modifier,
    isAdvancedMode: Boolean = false,
    onErrorLocationClick: () -> Unit = {},
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    onToggleSelect: () -> Unit = {},
    onLongClick: () -> Unit = {}
) {
    val isActiveTransfer = item.state.isActive && item.state != TorrentState.PAUSED && item.progress.progressFraction < 1f
    val isFinished = item.state.isFinished || item.progress.progressFraction >= 1f

    val infiniteTransition = rememberInfiniteTransition(label = "activeCardGlow")
    val activeBorderAlpha by if (isActiveTransfer) {
        infiniteTransition.animateFloat(
            initialValue = 0.25f,
            targetValue = 0.75f,
            animationSpec = infiniteRepeatable(
                animation = tween(1100),
                repeatMode = RepeatMode.Reverse
            ),
            label = "activeBorderAlpha"
        )
    } else {
        remember { mutableFloatStateOf(0f) }
    }

    var dragTiltX by remember { mutableFloatStateOf(0f) }
    var dragTiltY by remember { mutableFloatStateOf(0f) }

    val rotX by animateFloatAsState(
        targetValue = -dragTiltY * 6f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "rotX"
    )
    val rotY by animateFloatAsState(
        targetValue = dragTiltX * 6f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "rotY"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectDragGestures(
                    onDrag = { change, dragAmount ->
                        dragTiltX = (dragTiltX + dragAmount.x / 400f).coerceIn(-1f, 1f)
                        dragTiltY = (dragTiltY + dragAmount.y / 400f).coerceIn(-1f, 1f)
                    },
                    onDragEnd = {
                        dragTiltX = 0f
                        dragTiltY = 0f
                    },
                    onDragCancel = {
                        dragTiltX = 0f
                        dragTiltY = 0f
                    }
                )
            }
            .graphicsLayer {
                rotationX = rotX
                rotationY = rotY
                cameraDistance = 14f * density
                shadowElevation = 4f + (kotlin.math.abs(rotX) + kotlin.math.abs(rotY)) * 2f
            }
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (isSelected) NuPrimary.copy(alpha = 0.08f)
                else MaterialTheme.colorScheme.surface
            )
            .border(
                if (isSelected) 1.5.dp else 1.dp,
                if (isSelected) NuPrimary else if (isActiveTransfer) NuPrimary.copy(alpha = activeBorderAlpha) else MaterialTheme.colorScheme.outline,
                RoundedCornerShape(12.dp)
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )

    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
        // Row 1: Name and Badges
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f).padding(end = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isSelectionMode) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { onToggleSelect() },
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                if (isAdvancedMode && item.queuePosition > 0) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "#${item.queuePosition}",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                }

                Text(
                    text = item.name,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            StatusBadge(state = item.state, pauseReason = item.pauseReason)
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Row 2: Progress Bar (Animated Speed-Coupled Shimmer)
        PieceProgressBar(
            progress = item.progress.progressFraction,
            downloadSpeedBps = item.progress.downloadSpeedBps,
            isActive = isActiveTransfer,
            isFinished = isFinished
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Row 3: Progress text and percentage
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${FormatUtils.formatBytes(item.progress.verifiedBytes)} / ${FormatUtils.formatBytes(item.totalLength)}",
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = FormatUtils.formatPercent(item.progress.progressFraction),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary
            )
        }

        // Download Doctor mini-banner on error cards
        if (item.state == TorrentState.ERROR) {
            Spacer(modifier = Modifier.height(10.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f))
                    .border(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                    .padding(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = item.error?.title ?: "Download Interrupted",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                val errMsg = item.error?.userMessage
                if (!errMsg.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = errMsg,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    FilledTonalButton(
                        onClick = {
                            if (item.error?.action == com.nutorrent.core.model.ErrorAction.CHOOSE_LOCATION) {
                                onErrorLocationClick()
                            } else {
                                onResumeClick()
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Text(item.error?.actionText ?: "Retry", fontSize = 11.sp)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Row 4: Metrics and Actions
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .horizontalScroll(rememberScrollState())
            ) {
                if (item.progress.downloadSpeedBps > 0) {
                    MetricChip(
                        icon = Icons.Default.ArrowDownward,
                        text = FormatUtils.formatSpeed(item.progress.downloadSpeedBps)
                    )
                }
                if (item.progress.uploadSpeedBps > 0) {
                    MetricChip(
                        icon = Icons.Default.ArrowUpward,
                        text = FormatUtils.formatSpeed(item.progress.uploadSpeedBps)
                    )
                }
                MetricChip(
                    icon = Icons.Default.Group,
                    text = "${item.progress.connectedPeers}"
                )
                if (item.state == TorrentState.DOWNLOADING && item.progress.etaSeconds != null) {
                    MetricChip(
                        icon = Icons.Default.Schedule,
                        text = FormatUtils.formatDuration(item.progress.etaSeconds)
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                val context = androidx.compose.ui.platform.LocalContext.current
                if (item.state.isFinished || item.progress.progressFraction >= 1f) {
                    IconButton(
                        onClick = {
                            com.nutorrent.core.util.FileOpener.openPrimaryTarget(
                                context = context,
                                destinationPath = item.destinationPath,
                                torrentName = item.name
                            )
                        },
                        modifier = Modifier
                            .size(36.dp)
                            .minimumInteractiveComponentSize()
                    ) {
                        Icon(
                            imageVector = Icons.Default.FileOpen,
                            contentDescription = "Open File",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                // Locate / Open Folder button
                IconButton(
                    onClick = {
                        com.nutorrent.core.util.FileOpener.openFolder(context, item.destinationPath)
                    },
                    modifier = Modifier
                        .size(36.dp)
                        .minimumInteractiveComponentSize()
                ) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = "Locate Folder",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }

                if (isAdvancedMode && item.state == TorrentState.QUEUED) {
                    IconButton(
                        onClick = onMoveUp,
                        modifier = Modifier
                            .size(36.dp)
                            .minimumInteractiveComponentSize()
                    ) {
                        Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move Up", modifier = Modifier.size(18.dp))
                    }
                    IconButton(
                        onClick = onMoveDown,
                        modifier = Modifier
                            .size(36.dp)
                            .minimumInteractiveComponentSize()
                    ) {
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move Down", modifier = Modifier.size(18.dp))
                    }
                }

                if (item.state.isActive) {
                    IconButton(
                        onClick = onPauseClick,
                        modifier = Modifier
                            .size(36.dp)
                            .minimumInteractiveComponentSize()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Pause,
                            contentDescription = "Pause",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                } else if (item.state == TorrentState.PAUSED || item.state == TorrentState.QUEUED) {
                    IconButton(
                        onClick = onResumeClick,
                        modifier = Modifier
                            .size(36.dp)
                            .minimumInteractiveComponentSize()
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Resume",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                } else if (item.state == TorrentState.ERROR) {
                    IconButton(
                        onClick = onResumeClick,
                        modifier = Modifier
                            .size(36.dp)
                            .minimumInteractiveComponentSize()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Retry",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                IconButton(
                    onClick = onDeleteClick,
                    modifier = Modifier
                        .size(36.dp)
                        .minimumInteractiveComponentSize()
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }

        // Specular highlight overlay shifts dynamically with 3D tilt
        if (dragTiltX != 0f || dragTiltY != 0f) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.08f + kotlin.math.abs(dragTiltX).coerceIn(0f, 0.08f)),
                                Color.Transparent
                            ),
                            center = androidx.compose.ui.geometry.Offset(
                                x = 120f - dragTiltX * 80f,
                                y = 80f - dragTiltY * 60f
                            ),
                            radius = 350f
                        )
                    )
            )
        }
    }
}

@Composable
fun NoSearchResultsState(
    searchQuery: String,
    selectedCategory: TorrentCategory,
    onClearSearch: () -> Unit,
    onResetCategory: () -> Unit,
    modifier: Modifier = Modifier
) {
    val searchTransition = rememberInfiniteTransition(label = "searchFloating")
    val floatOffset by searchTransition.animateFloat(
        initialValue = -4f,
        targetValue = 4f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600),
            repeatMode = RepeatMode.Reverse
        ),
        label = "searchFloat"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier.padding(32.dp)
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier
                    .size(36.dp)
                    .graphicsLayer { translationY = floatOffset }
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        if (searchQuery.isNotBlank()) {
            Text(
                text = "No results for \"$searchQuery\"",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Try a different torrent name or hash prefix",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))
            FilledTonalButton(onClick = onClearSearch) {
                Text("Clear Search")
            }
        } else {
            Text(
                text = "No ${selectedCategory.label} torrents",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Torrents matching this category will appear here",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (selectedCategory != TorrentCategory.ALL) {
                Spacer(modifier = Modifier.height(16.dp))
                FilledTonalButton(onClick = onResetCategory) {
                    Text("Show All Torrents")
                }
            }
        }
    }
}
