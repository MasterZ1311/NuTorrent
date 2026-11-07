package com.nutorrent.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nutorrent.core.model.TorrentId
import com.nutorrent.core.model.TorrentState
import com.nutorrent.core.util.FormatUtils
import com.nutorrent.data.repository.TorrentSummaryItem
import com.nutorrent.ui.home.TorrentSortOrder
import com.nutorrent.ui.theme.NuError
import com.nutorrent.ui.theme.NuPrimary
import com.nutorrent.ui.theme.NuSuccess

@Composable
fun TorrentTableView(
    torrents: List<TorrentSummaryItem>,
    selectedIds: Set<TorrentId>,
    isSelectionMode: Boolean,
    currentSortOrder: TorrentSortOrder,
    sortAscending: Boolean,
    onSortChange: (TorrentSortOrder) -> Unit,
    onTorrentClick: (TorrentId) -> Unit,
    onToggleSelect: (TorrentId) -> Unit,
    onPauseClick: (TorrentId) -> Unit,
    onResumeClick: (TorrentId) -> Unit,
    onDeleteClick: (TorrentSummaryItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val horizontalScrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(horizontalScrollState)
    ) {
        // Table Header Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isSelectionMode) {
                Spacer(modifier = Modifier.width(36.dp))
            }

            TableHeaderCell(
                text = "NAME",
                width = 240.dp,
                isSorted = currentSortOrder == TorrentSortOrder.NAME,
                ascending = sortAscending,
                onClick = { onSortChange(TorrentSortOrder.NAME) }
            )

            TableHeaderCell(
                text = "STATUS",
                width = 110.dp
            )

            TableHeaderCell(
                text = "PROGRESS",
                width = 140.dp,
                isSorted = currentSortOrder == TorrentSortOrder.PROGRESS,
                ascending = sortAscending,
                onClick = { onSortChange(TorrentSortOrder.PROGRESS) }
            )

            TableHeaderCell(
                text = "SIZE",
                width = 95.dp,
                isSorted = currentSortOrder == TorrentSortOrder.SIZE,
                ascending = sortAscending,
                onClick = { onSortChange(TorrentSortOrder.SIZE) }
            )

            TableHeaderCell(
                text = "DL SPEED",
                width = 105.dp,
                isSorted = currentSortOrder == TorrentSortOrder.SPEED,
                ascending = sortAscending,
                onClick = { onSortChange(TorrentSortOrder.SPEED) }
            )

            TableHeaderCell(
                text = "UL SPEED",
                width = 105.dp
            )

            TableHeaderCell(
                text = "PEERS",
                width = 80.dp
            )

            Text(
                text = "ACTIONS",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(90.dp)
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Table Body
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(torrents, key = { it.torrentId.hex }) { item ->
                val isSelected = selectedIds.contains(item.torrentId)
                val isDownloading = item.state == TorrentState.DOWNLOADING
                val isSeeding = item.state == TorrentState.SEEDING

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            if (isSelected) NuPrimary.copy(alpha = 0.12f)
                            else MaterialTheme.colorScheme.surface
                        )
                        .clickable {
                            if (isSelectionMode) onToggleSelect(item.torrentId)
                            else onTorrentClick(item.torrentId)
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isSelectionMode) {
                        Checkbox(
                            checked = isSelected,
                            onCheckedChange = { onToggleSelect(item.torrentId) },
                            modifier = Modifier.size(36.dp)
                        )
                    }

                    // Name Column
                    Column(modifier = Modifier.width(240.dp)) {
                        Text(
                            text = item.name,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Status Column
                    Box(modifier = Modifier.width(110.dp)) {
                        StatusBadge(state = item.state, pauseReason = item.pauseReason)
                    }

                    // Progress Column
                    Column(modifier = Modifier.width(140.dp)) {
                        PieceProgressBar(
                            progress = item.progress.progressFraction,
                            isActive = item.state.isActive,
                            isFinished = item.state.isFinished,
                            downloadSpeedBps = item.progress.downloadSpeedBps
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "${(item.progress.progressFraction * 100).toInt()}% • ${FormatUtils.formatBytes(item.progress.verifiedBytes)}",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Size Column
                    Text(
                        text = FormatUtils.formatBytes(item.totalLength),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.width(95.dp)
                    )

                    // DL Speed Column
                    Text(
                        text = if (isDownloading && item.progress.downloadSpeedBps > 0)
                            FormatUtils.formatSpeed(item.progress.downloadSpeedBps) else "—",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = if (isDownloading && item.progress.downloadSpeedBps > 0) NuPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(105.dp)
                    )

                    // UL Speed Column
                    Text(
                        text = if ((isDownloading || isSeeding) && item.progress.uploadSpeedBps > 0)
                            FormatUtils.formatSpeed(item.progress.uploadSpeedBps) else "—",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = if (item.progress.uploadSpeedBps > 0) NuSuccess else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(105.dp)
                    )

                    // Peers Column
                    Text(
                        text = "${item.progress.connectedPeers}",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.width(80.dp)
                    )

                    // Actions Column
                    Row(
                        modifier = Modifier.width(90.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        if (item.state == TorrentState.PAUSED || item.state == TorrentState.QUEUED || item.state == TorrentState.ERROR) {
                            IconButton(
                                onClick = { onResumeClick(item.torrentId) },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = "Resume",
                                    tint = NuPrimary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        } else {
                            IconButton(
                                onClick = { onPauseClick(item.torrentId) },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Pause,
                                    contentDescription = "Pause",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        IconButton(
                            onClick = { onDeleteClick(item) },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteOutline,
                                contentDescription = "Delete",
                                tint = NuError,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
            }
        }
    }
}

@Composable
private fun TableHeaderCell(
    text: String,
    width: androidx.compose.ui.unit.Dp,
    isSorted: Boolean = false,
    ascending: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .width(width)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = if (isSorted) NuPrimary else MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (isSorted) {
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = if (ascending) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                contentDescription = null,
                tint = NuPrimary,
                modifier = Modifier.size(12.dp)
            )
        }
    }
}
