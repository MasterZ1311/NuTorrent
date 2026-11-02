package com.nutorrent.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import com.nutorrent.core.model.PauseReason
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
fun StatusBadge(
    state: TorrentState,
    modifier: Modifier = Modifier,
    pauseReason: PauseReason = PauseReason.NONE
) {
    val (bgColor, textColor, text) = when {
        state == TorrentState.PAUSED && pauseReason != PauseReason.NONE && pauseReason != PauseReason.USER_MANUAL ->
            Triple(NuWarning.copy(alpha = 0.15f), NuWarning, pauseReason.label)
        state == TorrentState.PAUSED -> Triple(NuSecondary.copy(alpha = 0.15f), NuSecondary, "Paused")
        state == TorrentState.QUEUED -> Triple(NuSecondary.copy(alpha = 0.15f), NuSecondary, "Queued")
        state == TorrentState.FETCHING_METADATA -> Triple(NuPrimary.copy(alpha = 0.15f), NuPrimary, "Metadata")
        state == TorrentState.DOWNLOADING -> Triple(NuPrimary.copy(alpha = 0.15f), NuPrimary, "Downloading")
        state == TorrentState.DISCOVERING_PEERS -> Triple(NuPrimary.copy(alpha = 0.15f), NuPrimary, "Discovering")
        state == TorrentState.VERIFYING -> Triple(NuWarning.copy(alpha = 0.15f), NuWarning, "Verifying")
        state == TorrentState.SEEDING -> Triple(NuSuccess.copy(alpha = 0.15f), NuSuccess, "Completed • Seeding")
        state == TorrentState.COMPLETED -> Triple(NuSuccess.copy(alpha = 0.15f), NuSuccess, "Completed")
        state == TorrentState.ERROR -> Triple(NuError.copy(alpha = 0.15f), NuError, "Error")
        else -> Triple(NuSecondary.copy(alpha = 0.15f), NuSecondary, state.name)
    }

    val isLiveActive = state == TorrentState.DOWNLOADING ||
            state == TorrentState.FETCHING_METADATA ||
            state == TorrentState.DISCOVERING_PEERS ||
            state == TorrentState.SEEDING

    val infiniteTransition = rememberInfiniteTransition(label = "badgePulse")
    val dotPulseScale by if (isLiveActive) {
        infiniteTransition.animateFloat(
            initialValue = 0.82f,
            targetValue = 1.25f,
            animationSpec = infiniteRepeatable(
                animation = tween(850),
                repeatMode = RepeatMode.Reverse
            ),
            label = "dotPulse"
        )
    } else {
        remember { mutableFloatStateOf(1.0f) }
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
                .graphicsLayer {
                    scaleX = dotPulseScale
                    scaleY = dotPulseScale
                }
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
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    downloadSpeedBps: Long = 0L,
    isActive: Boolean = false,
    isFinished: Boolean = false
) {
    val targetProgress = progress.coerceIn(0f, 1f)
    val animatedProgress by animateFloatAsState(
        targetValue = targetProgress,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "progress"
    )

    val targetColor = when {
        isFinished || targetProgress >= 1f -> NuSuccess
        else -> color
    }
    val animatedColor by animateColorAsState(
        targetValue = targetColor,
        animationSpec = tween(500),
        label = "progressColor"
    )

    // Throughput-coupled shimmer duration
    val durationMillis = remember(downloadSpeedBps, isActive) {
        if (!isActive) 3500
        else when {
            downloadSpeedBps > 10_000_000 -> 500   // High throughput (>10 MB/s)
            downloadSpeedBps > 2_000_000  -> 900   // Medium-high (>2 MB/s)
            downloadSpeedBps > 200_000    -> 1600  // Moderate (>200 KB/s)
            else                          -> 2800  // Trickle
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "shimmerTransition")
    val shimmerOffset by infiniteTransition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerOffset"
    )

    val sparkAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(650),
            repeatMode = RepeatMode.Reverse
        ),
        label = "sparkAlpha"
    )

    Box(
        modifier = modifier
            .height(7.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(3.5.dp))
            .background(trackColor)
    ) {
        if (animatedProgress > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(animatedProgress)
                    .clip(RoundedCornerShape(3.5.dp))
                    .background(
                        if (isActive && !isFinished && targetProgress < 1f) {
                            Brush.horizontalGradient(
                                colors = listOf(
                                    animatedColor.copy(alpha = 0.82f),
                                    Color.White.copy(alpha = 0.55f),
                                    animatedColor
                                ),
                                startX = shimmerOffset * 500f,
                                endX = (shimmerOffset + 1.2f) * 500f
                            )
                        } else {
                            Brush.horizontalGradient(
                                colors = listOf(
                                    animatedColor.copy(alpha = 0.92f),
                                    animatedColor
                                )
                            )
                        }
                    )
            )

            // Leading-edge spark / plasma head on active downloads
            if (isActive && !isFinished && animatedProgress in 0.02f..0.98f) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(animatedProgress)
                ) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .size(width = 6.dp, height = 7.dp)
                            .clip(RoundedCornerShape(3.5.dp))
                            .background(Color.White.copy(alpha = sparkAlpha * 0.85f))
                    )
                }
            }
        }
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
    val verifiedColor = NuSuccess
    val failedColor = NuError

    val infiniteTransition = rememberInfiniteTransition(label = "pieceMapPulse")
    val downloadingAlpha by infiniteTransition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(700),
            repeatMode = RepeatMode.Reverse
        ),
        label = "downloadingAlpha"
    )

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
                PieceState.DOWNLOADING, PieceState.QUEUED, PieceState.VERIFYING ->
                    NuPrimary.copy(alpha = downloadingAlpha)
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
