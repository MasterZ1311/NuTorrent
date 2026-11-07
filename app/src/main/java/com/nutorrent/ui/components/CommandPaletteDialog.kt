package com.nutorrent.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.nutorrent.core.model.TorrentId
import com.nutorrent.core.util.FormatUtils
import com.nutorrent.data.repository.TorrentSummaryItem
import com.nutorrent.ui.theme.NuPrimary
import com.nutorrent.ui.theme.NuSecondary
import com.nutorrent.ui.theme.NuSuccess

data class CommandPaletteAction(
    val id: String,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val shortcut: String? = null,
    val onExecute: () -> Unit
)

@Composable
fun CommandPaletteDialog(
    torrents: List<TorrentSummaryItem>,
    onDismiss: () -> Unit,
    onSelectTorrent: (TorrentId) -> Unit,
    onAddFileClick: () -> Unit,
    onAddMagnetClick: () -> Unit,
    onPauseAllClick: () -> Unit,
    onResumeAllClick: () -> Unit,
    onFilterDownloading: () -> Unit,
    onFilterSeeding: () -> Unit,
    onSettingsClick: () -> Unit,
    onCommunityClick: () -> Unit,
    onClearCompletedClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var query by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    val actions = remember {
        listOf(
            CommandPaletteAction("add_file", "Add Torrent File", "Open .torrent file from storage", Icons.Default.Add, "Ctrl+N", onAddFileClick),
            CommandPaletteAction("add_magnet", "Add Magnet Link", "Paste and resolve magnet URI", Icons.Default.Link, "Ctrl+V", onAddMagnetClick),
            CommandPaletteAction("pause_all", "Pause All Transfers", "Suspend active swarm downloads", Icons.Default.Pause, null, onPauseAllClick),
            CommandPaletteAction("resume_all", "Resume All Transfers", "Start all queued or paused downloads", Icons.Default.PlayArrow, null, onResumeAllClick),
            CommandPaletteAction("filter_dl", "View Downloading", "Show active download queue", Icons.Default.FileDownload, null, onFilterDownloading),
            CommandPaletteAction("filter_seed", "View Seeding", "Show seeding torrents", Icons.Default.FileUpload, null, onFilterSeeding),
            CommandPaletteAction("settings", "Open Settings", "Configure bandwidth, storage and rules", Icons.Default.Settings, "Ctrl+,", onSettingsClick),
            CommandPaletteAction("community", "Community & Referrals", "Share NuTorrent & view network stats", Icons.Default.People, null, onCommunityClick),
            CommandPaletteAction("clean_comp", "Clear Completed", "Remove finished torrents from queue", Icons.Default.DeleteSweep, null, onClearCompletedClick)
        )
    }

    val filteredActions = remember(query) {
        if (query.isBlank()) actions
        else actions.filter { it.title.contains(query, ignoreCase = true) || it.subtitle.contains(query, ignoreCase = true) }
    }

    val matchingTorrents = remember(query, torrents) {
        if (query.isBlank()) emptyList()
        else torrents.filter { it.name.contains(query, ignoreCase = true) || it.torrentId.hex.contains(query, ignoreCase = true) }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 24.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Header & Search Box
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    placeholder = { Text("Type a command or search transfers...") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            tint = NuPrimary
                        )
                    },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear")
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(modifier = Modifier.height(12.dp))

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp)
                ) {
                    // Torrent matches section
                    if (matchingTorrents.isNotEmpty()) {
                        item {
                            Text(
                                text = "MATCHING TRANSFERS (${matchingTorrents.size})",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                            )
                        }
                        items(matchingTorrents) { item ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        onSelectTorrent(item.torrentId)
                                        onDismiss()
                                    }
                                    .padding(horizontal = 8.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(NuPrimary.copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.FileDownload,
                                        contentDescription = null,
                                        tint = NuPrimary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.name,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${FormatUtils.formatBytes(item.progress.verifiedBytes)} of ${FormatUtils.formatBytes(item.totalLength)} • ${item.state.name}",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(
                                    text = "${(item.progress.progressFraction * 100).toInt()}%",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (item.progress.progressFraction >= 1f) NuSuccess else NuPrimary
                                )
                            }
                        }
                    }

                    // Quick Actions Section
                    item {
                        Text(
                            text = "COMMANDS & ACTIONS",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                        )
                    }

                    items(filteredActions) { action ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    action.onExecute()
                                    onDismiss()
                                }
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = action.icon,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = action.title,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = action.subtitle,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (action.shortcut != null) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = action.shortcut,
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
