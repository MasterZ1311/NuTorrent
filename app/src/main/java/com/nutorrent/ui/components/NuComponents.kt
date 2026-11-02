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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nutorrent.core.device.NuHaptics
import com.nutorrent.core.model.PauseReason
import com.nutorrent.core.model.PieceState
import com.nutorrent.core.model.TorrentState
import com.nutorrent.core.util.FormatUtils
import com.nutorrent.ui.theme.NuError
import com.nutorrent.ui.theme.NuGlassBorder
import com.nutorrent.ui.theme.NuGlassCard
import com.nutorrent.ui.theme.NuGlowCyan
import com.nutorrent.ui.theme.NuGlowSuccess
import com.nutorrent.ui.theme.NuNeonPlasma
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
            .border(0.5.dp, textColor.copy(alpha = 0.25f), RoundedCornerShape(6.dp))
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

    // Throughput-coupled shimmer duration: fast on 5G/gigabit, gentle on trickle
    val durationMillis = remember(downloadSpeedBps, isActive) {
        if (!isActive) 3500
        else when {
            downloadSpeedBps > 10_000_000 -> 450   // High throughput (>10 MB/s)
            downloadSpeedBps > 2_000_000  -> 850   // Medium-high (>2 MB/s)
            downloadSpeedBps > 200_000    -> 1500  // Moderate (>200 KB/s)
            else                          -> 2600  // Trickle
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
        initialValue = 0.40f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(600),
            repeatMode = RepeatMode.Reverse
        ),
        label = "sparkAlpha"
    )

    Box(
        modifier = modifier
            .height(8.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(trackColor)
    ) {
        if (animatedProgress > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(animatedProgress)
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        if (isActive && !isFinished && targetProgress < 1f) {
                            Brush.horizontalGradient(
                                colors = listOf(
                                    animatedColor.copy(alpha = 0.85f),
                                    Color.White.copy(alpha = 0.65f),
                                    animatedColor
                                ),
                                startX = shimmerOffset * 500f,
                                endX = (shimmerOffset + 1.2f) * 500f
                            )
                        } else {
                            Brush.horizontalGradient(
                                colors = listOf(
                                    animatedColor.copy(alpha = 0.95f),
                                    animatedColor
                                )
                            )
                        }
                    )
            )

            // Leading-edge plasma head on active downloads
            if (isActive && !isFinished && animatedProgress in 0.02f..0.98f) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(animatedProgress)
                ) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .size(width = 8.dp, height = 8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color.White.copy(alpha = sparkAlpha * 0.9f))
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
            .border(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
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
    isActive: Boolean = false,
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

    val radarSweepX by if (isActive) {
        infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(2600, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "radarSweepX"
        )
    } else {
        remember { mutableFloatStateOf(-1f) }
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(140.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f), RoundedCornerShape(10.dp))
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

            drawRoundRect(
                color = color,
                topLeft = Offset(x, y),
                size = Size(blockSize, blockSize),
                cornerRadius = CornerRadius(2f, 2f)
            )
        }

        // Radar sweep laser line across active swarm piece maps
        if (isActive && radarSweepX in 0f..1f) {
            val sweepPx = radarSweepX * size.width
            drawLine(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.Transparent,
                        NuPrimary.copy(alpha = 0.75f),
                        NuPrimary.copy(alpha = 0.95f),
                        Color.Transparent
                    )
                ),
                start = Offset(sweepPx, 0f),
                end = Offset(sweepPx, size.height),
                strokeWidth = 2.dp.toPx()
            )
        }
    }
}

/**
 * Cyber-kinetic live network throughput meter with glowing neon plasma ring.
 */
@Composable
fun NetworkSpeedGauge(
    downloadSpeedBps: Long,
    uploadSpeedBps: Long,
    activeTransfersCount: Int,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "gaugePulse")
    val pulseGlow by infiniteTransition.animateFloat(
        initialValue = 0.7f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseGlow"
    )

    val isActive = downloadSpeedBps > 0 || uploadSpeedBps > 0

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        NuGlassCard,
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                    )
                )
            )
            .border(1.dp, if (isActive) NuGlassBorder.copy(alpha = 0.45f * pulseGlow) else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f), RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Download Speed Cluster
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(NuPrimary.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowDownward,
                        contentDescription = "Download",
                        tint = NuPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "DOWNLOAD",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        letterSpacing = 0.8.sp
                    )
                    Text(
                        text = FormatUtils.formatSpeed(downloadSpeedBps),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (downloadSpeedBps > 0) NuPrimary else MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            // Central Active Transfers Status Indicator
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (isActive) NuSuccess.copy(alpha = pulseGlow) else NuSecondary.copy(alpha = 0.5f))
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "$activeTransfersCount active",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Upload Speed Cluster
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "UPLOAD",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        letterSpacing = 0.8.sp
                    )
                    Text(
                        text = FormatUtils.formatSpeed(uploadSpeedBps),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (uploadSpeedBps > 0) NuSuccess else MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(NuSuccess.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowUpward,
                        contentDescription = "Upload",
                        tint = NuSuccess,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

/**
 * Animated Contextual Action Bar for Multi-Select Batch Operations.
 */
@Composable
fun BatchActionBar(
    selectedCount: Int,
    totalCount: Int,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onPauseSelected: () -> Unit,
    onResumeSelected: () -> Unit,
    onDeleteSelected: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, NuPrimary.copy(alpha = 0.4f), RoundedCornerShape(14.dp)),
        color = NuGlassCard,
        tonalElevation = 6.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = {
                        NuHaptics.tick(context)
                        onClearSelection()
                    }
                ) {
                    Icon(Icons.Default.Clear, contentDescription = "Cancel selection", tint = MaterialTheme.colorScheme.onSurface)
                }
                Text(
                    text = "$selectedCount selected",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = NuPrimary
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = {
                        NuHaptics.tick(context)
                        onSelectAll()
                    }
                ) {
                    Icon(Icons.Default.SelectAll, contentDescription = "Select All", tint = MaterialTheme.colorScheme.onSurface)
                }
                IconButton(
                    onClick = {
                        NuHaptics.click(context)
                        onPauseSelected()
                    }
                ) {
                    Icon(Icons.Default.Pause, contentDescription = "Pause selected", tint = NuWarning)
                }
                IconButton(
                    onClick = {
                        NuHaptics.click(context)
                        onResumeSelected()
                    }
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Resume selected", tint = NuPrimary)
                }
                IconButton(
                    onClick = {
                        NuHaptics.warning(context)
                        onDeleteSelected()
                    }
                ) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = "Delete selected", tint = NuError)
                }
            }
        }
    }
}
