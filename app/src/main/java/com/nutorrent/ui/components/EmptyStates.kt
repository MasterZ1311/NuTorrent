package com.nutorrent.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.FileDownloadDone
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nutorrent.ui.theme.NuPrimary
import com.nutorrent.ui.theme.NuSecondary
import com.nutorrent.ui.theme.NuSuccess

@Composable
fun NuEmptyState(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    accentColor: Color = NuPrimary,
    primaryActionText: String? = null,
    onPrimaryAction: (() -> Unit)? = null,
    secondaryActionText: String? = null,
    onSecondaryAction: (() -> Unit)? = null
) {
    val transition = rememberInfiniteTransition(label = "emptyStatePulse")
    val pulseScale by transition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(76.dp)
                .graphicsLayer {
                    scaleX = pulseScale
                    scaleY = pulseScale
                }
                .clip(CircleShape)
                .background(accentColor.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(38.dp)
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = title,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = subtitle,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            lineHeight = 19.sp,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        if (primaryActionText != null && onPrimaryAction != null) {
            Spacer(modifier = Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (secondaryActionText != null && onSecondaryAction != null) {
                    OutlinedButton(onClick = onSecondaryAction) {
                        Text(secondaryActionText)
                    }
                }
                Button(onClick = onPrimaryAction) {
                    Text(primaryActionText)
                }
            }
        }
    }
}

@Composable
fun NoTorrentsEmptyView(
    onAddTorrentFile: () -> Unit,
    onAddMagnet: () -> Unit,
    modifier: Modifier = Modifier
) {
    NuEmptyState(
        icon = Icons.Default.CloudDownload,
        title = "Your Download Queue is Clean",
        subtitle = "NuTorrent transfers files directly from decentralized BitTorrent swarm peers without intermediaries.",
        primaryActionText = "Add File",
        onPrimaryAction = onAddTorrentFile,
        secondaryActionText = "Paste Magnet",
        onSecondaryAction = onAddMagnet,
        modifier = modifier
    )
}

@Composable
fun NoSearchResultsEmptyView(
    query: String,
    onClearSearch: () -> Unit,
    modifier: Modifier = Modifier
) {
    NuEmptyState(
        icon = Icons.Default.SearchOff,
        title = "No Matches Found",
        subtitle = "No transfers found matching \"$query\". Check for typos or try clearing your active filter.",
        primaryActionText = "Clear Search",
        onPrimaryAction = onClearSearch,
        accentColor = NuSecondary,
        modifier = modifier
    )
}

@Composable
fun NoActiveDownloadsEmptyView(
    onAddTorrent: () -> Unit,
    modifier: Modifier = Modifier
) {
    NuEmptyState(
        icon = Icons.Default.CloudDownload,
        title = "No Active Downloads",
        subtitle = "All current transfers are either completed, paused, or seeding.",
        primaryActionText = "Add New Transfer",
        onPrimaryAction = onAddTorrent,
        accentColor = NuPrimary,
        modifier = modifier
    )
}

@Composable
fun NoCompletedEmptyView(
    modifier: Modifier = Modifier
) {
    NuEmptyState(
        icon = Icons.Default.FileDownloadDone,
        title = "No Completed Transfers",
        subtitle = "When your downloads finish verifying, they will appear here ready to open and share.",
        accentColor = NuSuccess,
        modifier = modifier
    )
}

@Composable
fun NoPeersEmptyView(
    modifier: Modifier = Modifier
) {
    NuEmptyState(
        icon = Icons.Default.Group,
        title = "Discovering Swarm Peers",
        subtitle = "Announcing to trackers and querying the Mainline DHT network for active peers...",
        accentColor = NuPrimary,
        modifier = modifier
    )
}
