package com.nutorrent.app.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.res.painterResource
import com.nutorrent.app.R
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import com.nutorrent.app.core.model.TorrentId
import com.nutorrent.app.core.model.TorrentState
import com.nutorrent.app.core.util.FormatUtils
import com.nutorrent.app.data.repository.TorrentRepository
import com.nutorrent.app.data.repository.TorrentSummaryItem
import com.nutorrent.app.torrent.metainfo.MagnetUri
import com.nutorrent.app.ui.components.MetricChip
import com.nutorrent.app.ui.components.PieceProgressBar
import com.nutorrent.app.ui.components.StatusBadge
import com.nutorrent.app.ui.theme.NuPrimary
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
    networkStatus: kotlinx.coroutines.flow.StateFlow<com.nutorrent.app.torrent.network.NetworkStatus>? = null,
    onAddTorrentClick: () -> Unit,
    onTorrentClick: (TorrentId) -> Unit,
    onSettingsClick: () -> Unit,
    onPauseClick: (TorrentId) -> Unit,
    onResumeClick: (TorrentId) -> Unit,
    onDeleteClick: (TorrentId, Boolean) -> Unit
) {
    val torrents by repository.torrentsList.collectAsState()
    val currentNetworkStatus by (networkStatus?.collectAsState() ?: androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(com.nutorrent.app.torrent.network.NetworkStatus(true, isWifi = true, isCellular = false, isMetered = false))
    })
    val scope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val defaultDownloadPath = remember(context) {
        (context.applicationContext as? com.nutorrent.app.NuTorrentApplication)?.settingsRepository?.settings?.value?.defaultDownloadPath
            ?: java.io.File(context.getExternalFilesDir(null) ?: context.filesDir, "Downloads").absolutePath
    }

    var selectedCategory by remember { mutableStateOf(TorrentCategory.ALL) }
    var currentSortOrder by remember { mutableStateOf(TorrentSortOrder.DATE_ADDED) }
    var sortAscending by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }

    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }

    var deleteTorrentTarget by remember { mutableStateOf<TorrentSummaryItem?>(null) }
    var deleteFilesChecked by remember { mutableStateOf(false) }

    var showAddChooserDialog by remember { mutableStateOf(false) }
    var showMagnetInputDialog by remember { mutableStateOf(false) }
    var magnetInputText by remember { mutableStateOf("") }
    var magnetParseError by remember { mutableStateOf<String?>(null) }

    val downloadingCount = torrents.count { it.state.isActive && it.state != TorrentState.SEEDING }
    val seedingCount = torrents.count { it.state == TorrentState.SEEDING }
    val pausedCount = torrents.count { (it.state == TorrentState.PAUSED || it.state == TorrentState.QUEUED) && it.progress.progressFraction < 1f }
    val completedCount = torrents.count { it.state.isFinished || it.progress.progressFraction >= 1f }
    val errorCount = torrents.count { it.state == TorrentState.ERROR }

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

    val filteredTorrents = remember(torrents, selectedCategory, searchQuery, currentSortOrder, sortAscending) {
        val filtered = torrents.filter { item ->
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

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Image(
                                painter = painterResource(R.drawable.ic_app_logo),
                                contentDescription = "NuTorrent Logo",
                                modifier = Modifier.size(28.dp)
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
                        IconButton(onClick = { isSearchActive = !isSearchActive }) {
                            Icon(
                                imageVector = if (isSearchActive) Icons.Default.Clear else Icons.Default.Search,
                                contentDescription = "Search",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Box {
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
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    text = order.label,
                                                    fontWeight = if (currentSortOrder == order) FontWeight.Bold else FontWeight.Normal,
                                                    color = if (currentSortOrder == order) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                                )
                                                if (currentSortOrder == order) {
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text(if (sortAscending) "↑" else "↓", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                                                }
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
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
                        FilterChip(
                            selected = selectedCategory == category,
                            onClick = { selectedCategory = category },
                            label = { Text("${category.label} ($count)") }
                        )
                    }
                }

                // Clipboard Magnet Detection Banner
                androidx.compose.animation.AnimatedVisibility(visible = isMagnetInClipboard) {
                    androidx.compose.material3.Card(
                        colors = androidx.compose.material3.CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Link,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Magnet Link in Clipboard",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
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
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                            ) {
                                Text("Add", fontSize = 12.sp)
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            IconButton(
                                onClick = { dismissedClipboardMagnet = clipboardText },
                                modifier = Modifier.size(28.dp)
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
        if (torrents.isEmpty()) {
            EmptyHomeState(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                onAddTorrentClick = onAddTorrentClick,
                onAddMagnetClick = { showMagnetInputDialog = true }
            )
        } else if (filteredTorrents.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No torrents match the filter",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(filteredTorrents, key = { it.torrentId.hex }) { item ->
                    TorrentCard(
                        item = item,
                        onClick = { onTorrentClick(item.torrentId) },
                        onPauseClick = { onPauseClick(item.torrentId) },
                        onResumeClick = { onResumeClick(item.torrentId) },
                        onDeleteClick = {
                            deleteTorrentTarget = item
                            deleteFilesChecked = false
                        },
                        onMoveUp = { repository.moveUpInQueue(item.torrentId) },
                        onMoveDown = { repository.moveDownInQueue(item.torrentId) }
                    )
                }
            }
        }
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
                        Text(
                            text = magnetParseError ?: "",
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
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
                            if (mag.trackers.isNotEmpty()) {
                                Text(
                                    text = "Trackers: ${mag.trackers.size} found",
                                    fontSize = 11.sp
                                )
                            }
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
                                    destinationPath = defaultDownloadPath,
                                    autoStart = true
                                )
                            }
                            showMagnetInputDialog = false
                            magnetInputText = ""
                            magnetParseError = null
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
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    deleteTorrentTarget?.let { item ->
        AlertDialog(
            onDismissRequest = { deleteTorrentTarget = null },
            title = { Text("Remove Torrent") },
            text = {
                Column {
                    Text("Are you sure you want to remove \"${item.name}\"?")
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = deleteFilesChecked,
                            onCheckedChange = { deleteFilesChecked = it }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Also delete downloaded files from disk", fontSize = 14.sp)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteClick(item.torrentId, deleteFilesChecked)
                        deleteTorrentTarget = null
                    }
                ) {
                    Text("Remove")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTorrentTarget = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun EmptyHomeState(
    modifier: Modifier = Modifier,
    onAddTorrentClick: () -> Unit,
    onAddMagnetClick: () -> Unit
) {
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
            Icon(
                imageVector = Icons.Outlined.CloudDownload,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(64.dp)
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
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(onClick = onAddTorrentClick) {
                    Icon(Icons.Default.FileOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Open .torrent")
                }
                OutlinedButton(onClick = onAddMagnetClick) {
                    Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Paste Magnet")
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
                            "Files are saved to your device's Downloads directory and are accessible at any time via Settings.",
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

@Composable
fun TorrentCard(
    item: TorrentSummaryItem,
    onClick: () -> Unit,
    onPauseClick: () -> Unit,
    onResumeClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
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
                if (item.queuePosition > 0) {
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
            StatusBadge(state = item.state)
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Row 2: Progress Bar
        PieceProgressBar(progress = item.progress.progressFraction)

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

        Spacer(modifier = Modifier.height(12.dp))

        // Row 4: Metrics and Actions
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
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
                            val target = com.nutorrent.app.core.util.FileOpener.resolvePrimaryTarget(
                                item.destinationPath,
                                torrentName = item.name
                            )
                            com.nutorrent.app.core.util.FileOpener.openFile(context, target)
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.FileOpen,
                            contentDescription = "Open File",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                if (item.state == TorrentState.QUEUED) {
                    IconButton(onClick = onMoveUp, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move Up", modifier = Modifier.size(16.dp))
                    }
                    IconButton(onClick = onMoveDown, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move Down", modifier = Modifier.size(16.dp))
                    }
                }

                if (item.state.isActive) {
                    IconButton(onClick = onPauseClick, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Default.Pause,
                            contentDescription = "Pause",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                } else if (item.state == TorrentState.PAUSED || item.state == TorrentState.QUEUED) {
                    IconButton(onClick = onResumeClick, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Resume",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                IconButton(onClick = onDeleteClick, modifier = Modifier.size(32.dp)) {
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
}
