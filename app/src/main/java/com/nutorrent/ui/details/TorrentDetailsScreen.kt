package com.nutorrent.ui.details

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import android.content.Intent
import kotlinx.coroutines.launch
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import com.nutorrent.core.model.AppError
import com.nutorrent.core.model.ErrorAction
import com.nutorrent.ui.states.TorrentNotFoundScreen
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nutorrent.core.device.NuHaptics
import com.nutorrent.core.device.NuSoundFeedback
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import com.nutorrent.core.model.FilePriority
import com.nutorrent.core.model.PeerStateInfo
import com.nutorrent.core.model.PieceState
import com.nutorrent.core.model.TorrentFile
import com.nutorrent.core.model.TorrentId
import com.nutorrent.core.model.TorrentProgress
import com.nutorrent.core.model.TorrentState
import com.nutorrent.core.model.TrackerInfo
import com.nutorrent.core.util.FormatUtils
import com.nutorrent.data.repository.TorrentRepository
import com.nutorrent.ui.components.PieceMapView
import com.nutorrent.ui.components.DestructiveActionDialog
import com.nutorrent.ui.components.PieceProgressBar
import com.nutorrent.ui.components.StatItem
import com.nutorrent.ui.components.StatusBadge
import com.nutorrent.ui.theme.NuError
import com.nutorrent.ui.theme.NuPrimary
import com.nutorrent.ui.theme.NuSuccess
import com.nutorrent.ui.theme.NuWarning

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TorrentDetailsScreen(
    infoHashHex: String,
    repository: TorrentRepository,
    onBack: () -> Unit,
    onPauseClick: (TorrentId) -> Unit,
    onResumeClick: (TorrentId) -> Unit,
    onDeleteClick: (TorrentId, Boolean) -> Unit,
    onForceRecheckClick: (TorrentId) -> Unit = {},
    onSequentialToggle: (TorrentId, Boolean) -> Unit = { _, _ -> },
    onCategoryChange: (TorrentId, String) -> Unit = { _, _ -> }
) {
    val torrentId = TorrentId.fromHex(infoHashHex)
    val torrents by repository.torrentsList.collectAsStateWithLifecycle()
    val torrentItem = torrents.firstOrNull { it.torrentId == torrentId }

    val session = repository.getSession(torrentId)
    val sessionState by (session?.state?.collectAsStateWithLifecycle() ?: remember { androidx.compose.runtime.mutableStateOf(torrentItem?.state ?: TorrentState.PAUSED) })
    val sessionProgress by (session?.progress?.collectAsStateWithLifecycle() ?: remember { androidx.compose.runtime.mutableStateOf(torrentItem?.progress ?: TorrentProgress()) })
    val sessionError by (session?.lastError?.collectAsStateWithLifecycle() ?: remember { androidx.compose.runtime.mutableStateOf(torrentItem?.error) })
    val sessionPauseReason by (session?.pauseReason?.collectAsStateWithLifecycle() ?: remember { androidx.compose.runtime.mutableStateOf(torrentItem?.pauseReason ?: com.nutorrent.core.model.PauseReason.NONE) })
    val peers by (session?.peers?.collectAsStateWithLifecycle() ?: remember { androidx.compose.runtime.mutableStateOf(emptyList<PeerStateInfo>()) })
    val trackers by (session?.trackers?.collectAsStateWithLifecycle() ?: remember { androidx.compose.runtime.mutableStateOf(emptyList<TrackerInfo>()) })

    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val appSettings by ((context.applicationContext as? com.nutorrent.NuTorrentApplication)?.settingsRepository?.settings?.collectAsStateWithLifecycle()
        ?: remember { androidx.compose.runtime.mutableStateOf(null) })
    val isSimpleMode = appSettings?.uiMode != "ADVANCED"
    val tabs = remember(isSimpleMode) {
        if (isSimpleMode) listOf("Overview", "Files") else listOf("Overview", "Files", "Peers", "Trackers", "Pieces")
    }

    var selectedTabIndex by remember { mutableIntStateOf(0) }
    if (selectedTabIndex >= tabs.size) {
        selectedTabIndex = 0
    }

    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var deleteFilesChecked by remember { mutableStateOf(false) }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try {
                context.contentResolver.takePersistableUriPermission(uri, takeFlags)
            } catch (e: Exception) {
                com.nutorrent.core.util.Lg.w(
                    com.nutorrent.core.util.LogCategory.STORAGE,
                    "Failed to persist URI permission: ${e.message}"
                )
            }
            scope.launch {
                repository.updateTorrentDestination(torrentId, uri.toString())
                onResumeClick(torrentId)
            }
        }
    }

    val dbFilesEntities by repository.getFilesForTorrentFlow(infoHashHex).collectAsStateWithLifecycle(initialValue = emptyList())
    val dbFiles = remember(dbFilesEntities) {
        var currentStart = 0L
        dbFilesEntities.map { entity ->
            val start = currentStart
            val end = start + entity.length
            currentStart = end
            TorrentFile(
                index = entity.fileIndex,
                relativePath = entity.relativePath,
                length = entity.length,
                startByte = start,
                endByte = end
            )
        }
    }
    val effectiveFiles = remember(session?.metadata?.files, dbFiles) {
        val metaFiles = session?.metadata?.files
        if (!metaFiles.isNullOrEmpty()) metaFiles else dbFiles
    }
    val isComplete = sessionState.isFinished || sessionProgress.progressFraction >= 1f ||
        (torrentItem?.state?.isFinished == true) || ((torrentItem?.progress?.progressFraction ?: 0f) >= 1f)

    LaunchedEffect(isComplete) {
        if (isComplete) {
            NuHaptics.celebrate(context)
            NuSoundFeedback.playCompletionChime(context)
        }
    }

    if (torrentItem == null) {
        TorrentNotFoundScreen(
            infoHashHex = infoHashHex,
            onBack = onBack,
            onAddTorrentClick = onBack
        )
        return
    }

    if (showDeleteConfirmDialog) {
        DestructiveActionDialog(
            itemCount = 1,
            singleItemName = torrentItem.name,
            totalBytes = torrentItem.totalLength,
            onConfirmDelete = { deleteFiles ->
                showDeleteConfirmDialog = false
                onDeleteClick(torrentId, deleteFiles)
            },
            onDismiss = { showDeleteConfirmDialog = false }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = torrentItem.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { onForceRecheckClick(torrentId) }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Force Recheck")
                    }
                    IconButton(onClick = { showDeleteConfirmDialog = true }) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = "Delete")
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
        ) {
            // Sticky Transfer Summary Card visible across all tabs without scrolling
            StickyTransferSummaryHeader(
                torrentItem = torrentItem,
                sessionState = sessionState,
                sessionProgress = sessionProgress,
                sessionError = sessionError,
                pauseReason = sessionPauseReason,
                onPauseClick = { onPauseClick(torrentId) },
                onResumeClick = { onResumeClick(torrentId) },
                onForceRecheckClick = { onForceRecheckClick(torrentId) },
                onDeleteClick = { showDeleteConfirmDialog = true },
                onOpenFileClick = {
                    com.nutorrent.core.util.FileOpener.openPrimaryTarget(
                        context = context,
                        destinationPath = torrentItem.destinationPath,
                        metadata = session?.metadata,
                        torrentName = torrentItem.name
                    )
                },
                onFilesTabClick = { selectedTabIndex = 1 },
                onErrorActionClick = { err ->
                    when (err.action) {
                        ErrorAction.CHOOSE_LOCATION -> {
                            folderPickerLauncher.launch(null)
                        }
                        ErrorAction.FORCE_RECHECK -> {
                            onForceRecheckClick(torrentId)
                        }
                        ErrorAction.REANNOUNCE -> {
                            session?.reannounce()
                        }
                        ErrorAction.RETRY,
                        ErrorAction.CHECK_NETWORK -> {
                            onResumeClick(torrentId)
                        }
                        else -> {
                            onResumeClick(torrentId)
                        }
                    }
                }
            )

            if (tabs.size > 3) {
                ScrollableTabRow(
                    selectedTabIndex = selectedTabIndex,
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary,
                    edgePadding = 8.dp
                ) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTabIndex == index,
                            onClick = { selectedTabIndex = index },
                            text = { Text(title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp) }
                        )
                    }
                }
            } else {
                TabRow(
                    selectedTabIndex = selectedTabIndex,
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary
                ) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTabIndex == index,
                            onClick = { selectedTabIndex = index },
                            text = { Text(title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp) }
                        )
                    }
                }
            }

            Box(modifier = Modifier.weight(1f)) {
                when (selectedTabIndex) {
                    0 -> OverviewTab(
                        item = torrentItem,
                        state = sessionState,
                        progress = sessionProgress,
                        onSequentialToggle = onSequentialToggle,
                        onCategoryChange = onCategoryChange,
                        onChangeLocationClick = { folderPickerLauncher.launch(null) },
                        onViewFilesClick = { selectedTabIndex = 1 }
                    )
                    1 -> FilesTab(
                        files = effectiveFiles,
                        session = session,
                        destinationPath = torrentItem.destinationPath,
                        isTorrentComplete = isComplete,
                        isFetchingMetadata = sessionState == TorrentState.FETCHING_METADATA
                    )
                    2 -> PeersTab(peers)
                    3 -> TrackersTab(trackers, session)
                    4 -> PiecesTab(session?.pieceManager?.getPieceStates() ?: emptyList())
                }
            }
        }
    }
}

@Composable
fun StickyTransferSummaryHeader(
    torrentItem: com.nutorrent.data.repository.TorrentSummaryItem,
    sessionState: TorrentState,
    sessionProgress: TorrentProgress,
    sessionError: AppError?,
    onPauseClick: () -> Unit,
    onResumeClick: () -> Unit,
    onForceRecheckClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onOpenFileClick: () -> Unit,
    onFilesTabClick: () -> Unit,
    onErrorActionClick: (AppError) -> Unit,
    pauseReason: com.nutorrent.core.model.PauseReason = torrentItem.pauseReason
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // State Badge & Category row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusBadge(state = sessionState, pauseReason = pauseReason)
            if (torrentItem.category.isNotBlank()) {
                Text(
                    text = torrentItem.category.uppercase(),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Speeds Row: Visible immediately without requiring scrolling
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "↓ ${FormatUtils.formatSpeed(sessionProgress.downloadSpeedBps)}",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = "↑ ${FormatUtils.formatSpeed(sessionProgress.uploadSpeedBps)}",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = NuSuccess
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Swarm Metrics: Connected peers vs total known, and Available seeds collapsed compactly
        val peersText = if (sessionProgress.totalPeers > 0) {
            "${sessionProgress.connectedPeers} connected (${sessionProgress.totalPeers} known)"
        } else {
            "${sessionProgress.connectedPeers} connected"
        }
        val seedsText = if (sessionProgress.availableSeeds != null) {
            "${sessionProgress.availableSeeds} available"
        } else if (sessionProgress.connectedSeeds > 0) {
            "${sessionProgress.connectedSeeds} connected"
        } else {
            "—"
        }
        Text(
            text = "Peers: $peersText   ·   Seeds: $seedsText",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Progress Bar
        PieceProgressBar(
            progress = sessionProgress.progressFraction,
            downloadSpeedBps = sessionProgress.downloadSpeedBps,
            isActive = sessionState.isActive && sessionState != TorrentState.PAUSED,
            isFinished = sessionState.isFinished || sessionProgress.progressFraction >= 1f
        )

        Spacer(modifier = Modifier.height(6.dp))

        // Progress details & ETA
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${FormatUtils.formatPercent(sessionProgress.progressFraction)}  •  ${FormatUtils.formatBytes(sessionProgress.verifiedBytes)} / ${FormatUtils.formatBytes(torrentItem.totalLength)}",
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium
            )

            val etaStr = if (sessionProgress.etaSeconds != null && sessionProgress.etaSeconds > 0) {
                "ETA: ${FormatUtils.formatDuration(sessionProgress.etaSeconds)}"
            } else {
                "ETA: —"
            }
            Text(
                text = etaStr,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Primary Action Buttons Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (sessionState.isActive) {
                Button(
                    onClick = onPauseClick,
                    modifier = Modifier.weight(1.3f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.Pause, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Pause", fontSize = 11.sp, maxLines = 1)
                }
            } else {
                Button(
                    onClick = onResumeClick,
                    modifier = Modifier.weight(1.3f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Resume", fontSize = 11.sp, maxLines = 1)
                }
            }

            val isComplete = sessionState.isFinished || sessionProgress.progressFraction >= 1f
            OutlinedButton(
                onClick = {
                    if (isComplete) {
                        onOpenFileClick()
                    } else {
                        onFilesTabClick()
                    }
                },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp)
            ) {
                Icon(
                    if (isComplete) Icons.Default.FileOpen else Icons.Default.Description,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(3.dp))
                Text(if (isComplete) "Open" else "Files", fontSize = 11.sp, maxLines = 1)
            }

            OutlinedButton(
                onClick = onForceRecheckClick,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(3.dp))
                Text("Recheck", fontSize = 11.sp, maxLines = 1)
            }

            IconButton(
                onClick = onDeleteClick,
                modifier = Modifier
                    .size(36.dp)
                    .minimumInteractiveComponentSize()
            ) {
                Icon(
                    Icons.Default.DeleteOutline,
                    contentDescription = "Delete",
                    tint = NuError,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // Actionable Error Card (answers: What happened? Why? What to do next?)
        if (sessionState == TorrentState.ERROR || sessionError != null) {
            val err = sessionError
            Spacer(modifier = Modifier.height(10.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(NuError.copy(alpha = 0.12f))
                    .border(1.dp, NuError.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                    .padding(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Error, contentDescription = null, tint = NuError, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = err?.title ?: "Download Error",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = NuError
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = err?.userMessage ?: "Download encountered an error and was paused.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    lineHeight = 16.sp
                )
                if (err?.actionText != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Button(
                            onClick = { onErrorActionClick(err) },
                            colors = ButtonDefaults.buttonColors(containerColor = NuError)
                        ) {
                            Text(err.actionText, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun OverviewTab(
    item: com.nutorrent.data.repository.TorrentSummaryItem,
    state: TorrentState,
    progress: TorrentProgress,
    onSequentialToggle: (TorrentId, Boolean) -> Unit = { _, _ -> },
    onCategoryChange: (TorrentId, String) -> Unit = { _, _ -> },
    onChangeLocationClick: () -> Unit = {},
    onViewFilesClick: () -> Unit = {}
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Main status card
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = FormatUtils.formatPercent(progress.progressFraction),
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary
                    )
                    StatusBadge(state = state, pauseReason = item.pauseReason)
                }

                Spacer(modifier = Modifier.height(12.dp))
                PieceProgressBar(
                    progress = progress.progressFraction,
                    downloadSpeedBps = progress.downloadSpeedBps,
                    isActive = state.isActive && state != TorrentState.PAUSED,
                    isFinished = state.isFinished || progress.progressFraction >= 1f
                )
                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "${FormatUtils.formatBytes(progress.verifiedBytes)} of ${FormatUtils.formatBytes(item.totalLength)} verified",
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (state.isFinished || progress.progressFraction >= 1f) {
            item {
                val context = androidx.compose.ui.platform.LocalContext.current
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(NuSuccess.copy(alpha = 0.12f))
                        .border(1.dp, NuSuccess.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                        .padding(16.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = NuSuccess,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Download 100% Complete!",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "The file is completely verified and stored on your device. The torrent is now seeding (uploading pieces to other peers in the swarm). You can view, play, or share your file below.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 17.sp
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                com.nutorrent.core.util.FileOpener.openPrimaryTarget(
                                    context = context,
                                    destinationPath = item.destinationPath,
                                    torrentName = item.name
                                )
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Open File")
                        }
                        OutlinedButton(
                            onClick = {
                                com.nutorrent.core.util.FileOpener.openFolder(context, item.destinationPath)
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Open Folder")
                        }
                    }
                }
            }

            item {
                com.nutorrent.ui.components.RatioWarningBanner(
                    ratio = progress.shareRatio.toFloat()
                )
            }
        }

        // Category selection card
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                    .padding(16.dp)
            ) {
                Text(
                    text = "CATEGORY / LABEL",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 0.8.sp
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val categoryOptions = listOf("ISO", "Media", "Docs", "Software")
                    categoryOptions.forEach { cat ->
                        FilterChip(
                            selected = item.category.equals(cat, ignoreCase = true),
                            onClick = {
                                onCategoryChange(item.torrentId, if (item.category.equals(cat, ignoreCase = true)) "" else cat)
                            },
                            label = { Text(cat, fontSize = 12.sp) }
                        )
                    }
                }
            }
        }

        // Sequential download control
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("Sequential Download", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text(
                        "Prioritize contiguous pieces from beginning of files for media playback",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = item.sequentialDownload,
                    onCheckedChange = { isChecked ->
                        onSequentialToggle(item.torrentId, isChecked)
                    }
                )
            }
        }

        // Transfer statistics
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                    .padding(16.dp)
            ) {
                Text(
                    text = "TRANSFER STATISTICS",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 0.8.sp
                )
                Spacer(modifier = Modifier.height(16.dp))

                Row(modifier = Modifier.fillMaxWidth()) {
                    StatItem(
                        label = "Download Speed",
                        value = FormatUtils.formatSpeed(progress.downloadSpeedBps),
                        modifier = Modifier.weight(1f)
                    )
                    StatItem(
                        label = "ETA",
                        value = FormatUtils.formatDuration(progress.etaSeconds),
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(modifier = Modifier.fillMaxWidth()) {
                    StatItem(
                        label = "Connected Peers",
                        value = FormatUtils.formatPeers(progress.connectedPeers, progress.totalPeers),
                        modifier = Modifier.weight(1f)
                    )
                    StatItem(
                        label = "Completed Pieces",
                        value = "${progress.completedPieces} / ${progress.totalPieces}",
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // Location & Info hash
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                    .padding(16.dp)
            ) {
                Text(
                    text = "STORAGE & IDENTITY",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 0.8.sp
                )
                Spacer(modifier = Modifier.height(12.dp))

                Text("Destination Folder", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    text = FormatUtils.formatStoragePath(item.destinationPath),
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium
                )

                val isCustomSAF = item.destinationPath.startsWith("content://")
                val isNuTorrentFolder = item.destinationPath.contains("NuTorrent")
                if (!isCustomSAF && !isNuTorrentFolder) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Note: Tap 'Change Location' below if you want files moved to a different directory or public folder.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 15.sp
                    )
                }

                val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
                val context = androidx.compose.ui.platform.LocalContext.current
                var copiedPath by remember { mutableStateOf(false) }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            com.nutorrent.core.util.FileOpener.openPrimaryTarget(
                                context = context,
                                destinationPath = item.destinationPath,
                                torrentName = item.name
                            )
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Open File", fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = {
                            com.nutorrent.core.util.FileOpener.openFolder(context, item.destinationPath)
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Open Folder", fontSize = 12.sp)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onChangeLocationClick,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Change Location", fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = onViewFilesClick,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Description, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("View Files", fontSize = 12.sp)
                    }
                }
                OutlinedButton(
                    onClick = {
                        clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(item.destinationPath))
                        copiedPath = true
                    },
                    modifier = Modifier.padding(top = 8.dp).fillMaxWidth()
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (copiedPath) "Path Copied to Clipboard!" else "Copy Storage Path", fontSize = 12.sp)
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text("SHA-1 Info Hash", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(item.torrentId.hex, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
                var copiedHash by remember { mutableStateOf(false) }
                androidx.compose.material3.OutlinedButton(
                    onClick = {
                        clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(item.torrentId.hex))
                        copiedHash = true
                    },
                    modifier = Modifier.padding(top = 6.dp)
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (copiedHash) "Hash Copied!" else "Copy Info Hash", fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
fun FilesTab(
    files: List<com.nutorrent.core.model.TorrentFile>,
    session: com.nutorrent.torrent.download.TorrentSession? = null,
    destinationPath: String = "",
    isTorrentComplete: Boolean = false,
    isFetchingMetadata: Boolean = false
) {
    if (files.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                if (isFetchingMetadata) {
                    CircularProgressIndicator(modifier = Modifier.size(36.dp), color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Downloading Metadata...",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Connecting to swarm peers to retrieve file names and sizes. Files will appear automatically.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                } else {
                    Icon(
                        Icons.Default.Description,
                        contentDescription = null,
                        modifier = Modifier.size(44.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "No files information available",
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        return
    }

    var prioritiesVersion by remember { mutableIntStateOf(0) }
    val priorities = remember(session, prioritiesVersion) {
        session?.pieceManager?.getFilePriorities() ?: emptyList()
    }

    // Group files by parent directory
    val folderGroups = remember(files) {
        val map = linkedMapOf<String, MutableList<com.nutorrent.core.model.TorrentFile>>()
        for (f in files) {
            val normalized = f.relativePath.replace('\\', '/')
            val slash = normalized.lastIndexOf('/')
            val folder = if (slash >= 0) normalized.substring(0, slash) else ""
            map.getOrPut(folder) { mutableListOf() }.add(f)
        }
        map.entries.map { Pair(it.key, it.value) }
    }

    val expandedFolders = remember {
        mutableStateMapOf<String, Boolean>().apply {
            folderGroups.forEach { this[it.first] = true }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        folderGroups.forEach { (folderName, groupFiles) ->
            if (folderName.isNotEmpty()) {
                val isExpanded = expandedFolders[folderName] != false
                val folderTotalBytes = groupFiles.sumOf { it.length }

                item(key = "folder_$folderName") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .clickable { expandedFolders[folderName] = !isExpanded }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(
                                if (isExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(
                                Icons.Default.Folder,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = folderName,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Text(
                            text = "${groupFiles.size} files • ${FormatUtils.formatBytes(folderTotalBytes)}",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (isExpanded) {
                    items(groupFiles, key = { "file_${it.index}" }) { file ->
                        FileItemRow(
                            file = file,
                            session = session,
                            priorities = priorities,
                            destinationPath = destinationPath,
                            isTorrentComplete = isTorrentComplete,
                            isNested = true,
                            onPriorityChanged = { prioritiesVersion++ }
                        )
                    }
                }
            } else {
                // Root files
                items(groupFiles, key = { "root_${it.index}" }) { file ->
                    FileItemRow(
                        file = file,
                        session = session,
                        priorities = priorities,
                        destinationPath = destinationPath,
                        isTorrentComplete = isTorrentComplete,
                        isNested = false,
                        onPriorityChanged = { prioritiesVersion++ }
                    )
                }
            }
        }
    }
}

@Composable
private fun FileItemRow(
    file: com.nutorrent.core.model.TorrentFile,
    session: com.nutorrent.torrent.download.TorrentSession?,
    priorities: List<FilePriority>,
    destinationPath: String = "",
    isTorrentComplete: Boolean = false,
    isNested: Boolean,
    onPriorityChanged: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val priority = if (file.index in priorities.indices) priorities[file.index] else FilePriority.NORMAL
    val verified = if (isTorrentComplete) file.length else (session?.availableVerifiedRange(file.index) ?: 0L)
    val pct = if (isTorrentComplete) 100 else if (file.length > 0) ((verified.toDouble() / file.length.toDouble()) * 100).toInt() else 0
    val leafName = file.relativePath.replace('\\', '/').substringAfterLast('/')
    val isComplete = (pct >= 100 || isTorrentComplete) && destinationPath.isNotBlank()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = if (isNested) 16.dp else 0.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable {
                if (isComplete) {
                    com.nutorrent.core.util.FileOpener.openFile(
                        context = context,
                        destinationPath = destinationPath,
                        relativePath = file.relativePath,
                        displayName = leafName
                    )
                } else {
                    android.widget.Toast.makeText(
                        context,
                        "File is still downloading ($pct% complete)",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.weight(1f).padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (isComplete) Icons.Default.PlayArrow else Icons.Default.Description,
                contentDescription = null,
                tint = if (isComplete) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = leafName,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${FormatUtils.formatBytes(file.length)} • $pct% verified${if (isComplete) " • Tap to open" else ""}",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = if (isComplete) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (isComplete) {
                IconButton(
                    onClick = {
                        com.nutorrent.core.util.FileOpener.openFile(
                            context = context,
                            destinationPath = destinationPath,
                            relativePath = file.relativePath,
                            displayName = leafName
                        )
                    },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.FileOpen,
                        contentDescription = "Open",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
                IconButton(
                    onClick = {
                        com.nutorrent.core.util.FileOpener.openFolder(
                            context = context,
                            destinationPath = destinationPath
                        )
                    },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = "Locate",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
                IconButton(
                    onClick = {
                        com.nutorrent.core.util.FileOpener.shareFile(
                            context = context,
                            destinationPath = destinationPath,
                            relativePath = file.relativePath
                        )
                    },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = "Share",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            val (pBg, pText, pLabel) = when (priority) {
                FilePriority.HIGH -> Triple(NuWarning.copy(alpha = 0.2f), NuWarning, "HIGH")
                FilePriority.NORMAL -> Triple(NuPrimary.copy(alpha = 0.15f), NuPrimary, "NORMAL")
                FilePriority.LOW -> Triple(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant, "LOW")
                FilePriority.DO_NOT_DOWNLOAD -> Triple(NuError.copy(alpha = 0.2f), NuError, "SKIP")
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(pBg)
                    .clickable(enabled = session != null) {
                        val nextPriority = when (priority) {
                            FilePriority.NORMAL -> FilePriority.HIGH
                            FilePriority.HIGH -> FilePriority.LOW
                            FilePriority.LOW -> FilePriority.DO_NOT_DOWNLOAD
                            FilePriority.DO_NOT_DOWNLOAD -> FilePriority.NORMAL
                        }
                        session?.pieceManager?.setFilePriority(file.index, nextPriority)
                        onPriorityChanged()
                    }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = pLabel,
                    color = pText,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun PiecesTab(
    pieceStates: List<com.nutorrent.core.model.PieceState>
) {
    val total = pieceStates.size
    val verified = pieceStates.count { it == com.nutorrent.core.model.PieceState.COMPLETE }
    val downloading = pieceStates.count { it == com.nutorrent.core.model.PieceState.DOWNLOADING || it == com.nutorrent.core.model.PieceState.QUEUED || it == com.nutorrent.core.model.PieceState.VERIFYING }
    val failed = pieceStates.count { it == com.nutorrent.core.model.PieceState.FAILED }
    val missing = pieceStates.count { it == com.nutorrent.core.model.PieceState.MISSING }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                    .padding(16.dp)
            ) {
                Text(
                    text = "PIECE AVAILABILITY ($verified / $total)",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 0.8.sp
                )
                Spacer(modifier = Modifier.height(12.dp))

                PieceMapView(
                    pieceStates = pieceStates,
                    isActive = downloading > 0,
                    modifier = Modifier.fillMaxWidth().height(180.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    LegendIndicator(color = NuSuccess, label = "Verified: $verified")
                    LegendIndicator(color = NuPrimary, label = "Active: $downloading")
                    LegendIndicator(color = NuError, label = "Failed: $failed")
                    LegendIndicator(color = MaterialTheme.colorScheme.surfaceVariant, label = "Missing: $missing")
                }
            }
        }
    }
}

@Composable
private fun LegendIndicator(color: androidx.compose.ui.graphics.Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(text = label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun PeersTab(peers: List<PeerStateInfo>) {
    if (peers.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Group, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(48.dp))
                Spacer(modifier = Modifier.height(12.dp))
                Text("Connecting to swarm peers...", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(peers) { peer ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = peer.endpoint.toString(),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    val statusText = when {
                        !peer.isHandshakeComplete -> "Connecting..."
                        !peer.isChokingUs -> "Unchoked • Pieces: ${peer.piecesCount}"
                        peer.piecesCount == 0 -> "Choked (Leecher) • Pieces: 0"
                        else -> "Choked • Pieces: ${peer.piecesCount}"
                    }
                    val statusColor = when {
                        !peer.isHandshakeComplete -> MaterialTheme.colorScheme.primary
                        !peer.isChokingUs -> NuSuccess
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Text(
                        text = statusText,
                        fontSize = 11.sp,
                        color = statusColor
                    )
                }

                Text(
                    text = FormatUtils.formatBytes(peer.downloadedBytes),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun TrackersTab(
    trackers: List<TrackerInfo>,
    session: com.nutorrent.torrent.download.TorrentSession? = null
) {
    if (trackers.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No trackers available", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (session != null) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${trackers.size} Trackers",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    androidx.compose.material3.OutlinedButton(
                        onClick = { session.reannounce() },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Reannounce",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Announce Now", fontSize = 12.sp)
                    }
                }
            }
        }
        items(trackers) { tracker ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = tracker.url,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(end = 8.dp)
                    )
                    Text(
                        text = tracker.status,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (tracker.status == "Online") NuSuccess else MaterialTheme.colorScheme.error
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Peers: ${tracker.peersCount}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Interval: ${tracker.intervalSeconds}s", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                tracker.lastError?.let { err ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = err,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
