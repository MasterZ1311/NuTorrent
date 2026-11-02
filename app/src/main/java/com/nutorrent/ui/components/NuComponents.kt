package com.nutorrent.ui.components

import androidx.compose.animation.core.animateFloatAsState
import com.nutorrent.core.model.PieceState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nutorrent.core.model.TorrentState
import com.nutorrent.ui.theme.NuError
import com.nutorrent.ui.theme.NuPrimary
import com.nutorrent.ui.theme.NuSecondary
import com.nutorrent.ui.theme.NuSuccess
import com.nutorrent.ui.theme.NuWarning

@Composable
fun StatusBadge(state: TorrentState, modifier: Modifier = Modifier) {
    val (bgColor, textColor, text) = when (state) {
        TorrentState.FETCHING_METADATA -> Triple(NuPrimary.copy(alpha = 0.15f), NuPrimary, "Metadata")
        TorrentState.DOWNLOADING -> Triple(NuPrimary.copy(alpha = 0.15f), NuPrimary, "Downloading")
        TorrentState.DISCOVERING_PEERS -> Triple(NuPrimary.copy(alpha = 0.15f), NuPrimary, "Discovering")
        TorrentState.VERIFYING -> Triple(NuWarning.copy(alpha = 0.15f), NuWarning, "Verifying")
        TorrentState.PAUSED -> Triple(NuSecondary.copy(alpha = 0.15f), NuSecondary, "Paused")
        TorrentState.QUEUED -> Triple(NuSecondary.copy(alpha = 0.15f), NuSecondary, "Queued")
        TorrentState.SEEDING -> Triple(NuSuccess.copy(alpha = 0.15f), NuSuccess, "Completed • Seeding")
        TorrentState.COMPLETED -> Triple(NuSuccess.copy(alpha = 0.15f), NuSuccess, "Completed")
        TorrentState.ERROR -> Triple(NuError.copy(alpha = 0.15f), NuError, "Error")
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bgColor)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(textColor)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = text,
            color = textColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
fun PieceProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    color: Color = NuPrimary,
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant
) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        label = "progress"
    )

    Box(
        modifier = modifier
            .height(6.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(3.dp))
            .background(trackColor)
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(animatedProgress)
                .clip(RoundedCornerShape(3.dp))
                .background(color)
        )
    }
}

@Composable
fun MetricChip(
    icon: ImageVector,
    text: String,
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(13.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = text,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
fun StatItem(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = label.uppercase(),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 0.8.sp
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
fun PieceMapView(
    pieceStates: List<PieceState>,
    modifier: Modifier = Modifier,
    blockSizeDp: Float = 6f,
    spacingDp: Float = 2f
) {
    if (pieceStates.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(80.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "No pieces loaded",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    val missingColor = MaterialTheme.colorScheme.surfaceVariant
    val downloadingColor = NuPrimary
    val verifiedColor = NuSuccess
    val failedColor = NuError

    androidx.compose.foundation.Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(140.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(8.dp)
    ) {
        val density = this
        val blockSize = with(density) { blockSizeDp.dp.toPx() }
        val spacing = with(density) { spacingDp.dp.toPx() }
        val step = blockSize + spacing

        val cols = (size.width / step).toInt().coerceAtLeast(1)
        val totalPieces = pieceStates.size

        for (i in 0 until totalPieces) {
            val col = i % cols
            val row = i / cols
            val x = col * step
            val y = row * step

            if (y + blockSize > size.height) {
                break
            }

            val color = when (pieceStates[i]) {
                PieceState.COMPLETE -> verifiedColor
                PieceState.DOWNLOADING, PieceState.QUEUED, PieceState.VERIFYING -> downloadingColor
                PieceState.FAILED -> failedColor
                PieceState.MISSING -> missingColor
            }

            drawRect(
                color = color,
                topLeft = androidx.compose.ui.geometry.Offset(x, y),
                size = androidx.compose.ui.geometry.Size(blockSize, blockSize)
            )
        }
    }
}
