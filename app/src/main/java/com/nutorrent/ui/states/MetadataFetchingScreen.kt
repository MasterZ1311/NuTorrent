package com.nutorrent.ui.states

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nutorrent.ui.theme.NuPrimary
import com.nutorrent.ui.theme.NuSuccess

@Composable
fun MetadataFetchingScreen(
    infoHashHex: String,
    displayName: String? = null,
    peerCount: Int = 0,
    dhtNodeCount: Int = 0,
    onRunInBackground: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "sonarSweep")

    // Continuous 360-degree radar sweep angle
    val sweepAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "sweepAngle"
    )

    // Pulsing sonar waves
    val waveScale by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "waveScale"
    )

    val waveAlpha by infiniteTransition.animateFloat(
        initialValue = 0.7f,
        targetValue = 0.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "waveAlpha"
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // 3D Sonar Radar Display
        Box(
            modifier = Modifier.size(220.dp),
            contentAlignment = Alignment.Center
        ) {
            // Sonar Canvas (grid rings + rotating beam sweep + expanding echo wave)
            val primaryColor = NuPrimary
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .rotate(sweepAngle)
            ) {
                // Expanding ping wave
                drawCircle(
                    color = primaryColor.copy(alpha = waveAlpha * 0.5f),
                    radius = (size.minDimension / 2f) * waveScale,
                    style = Stroke(width = 2.dp.toPx())
                )

                // 90-degree sweeping sonar cone
                drawArc(
                    brush = Brush.sweepGradient(
                        0.0f to Color.Transparent,
                        0.75f to primaryColor.copy(alpha = 0.05f),
                        1.0f to primaryColor.copy(alpha = 0.45f)
                    ),
                    startAngle = 0f,
                    sweepAngle = 90f,
                    useCenter = true
                )
            }

            // Stationary Radar Concentric Range Rings & Crosshairs
            Canvas(modifier = Modifier.fillMaxSize()) {
                val r = size.minDimension / 2f
                // Concentric circles
                drawCircle(color = primaryColor.copy(alpha = 0.15f), radius = r, style = Stroke(width = 1.dp.toPx()))
                drawCircle(color = primaryColor.copy(alpha = 0.18f), radius = r * 0.68f, style = Stroke(width = 1.dp.toPx()))
                drawCircle(color = primaryColor.copy(alpha = 0.22f), radius = r * 0.36f, style = Stroke(width = 1.dp.toPx()))

                // Crosshairs
                drawLine(
                    color = primaryColor.copy(alpha = 0.15f),
                    start = androidx.compose.ui.geometry.Offset(center.x, 0f),
                    end = androidx.compose.ui.geometry.Offset(center.x, size.height),
                    strokeWidth = 1.dp.toPx()
                )
                drawLine(
                    color = primaryColor.copy(alpha = 0.15f),
                    start = androidx.compose.ui.geometry.Offset(0f, center.y),
                    end = androidx.compose.ui.geometry.Offset(size.width, center.y),
                    strokeWidth = 1.dp.toPx()
                )
            }

            // Center DHT Node / Swarm Orb
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .border(2.dp, NuPrimary, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Hub,
                    contentDescription = null,
                    tint = NuPrimary,
                    modifier = Modifier.size(26.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

        Text(
            text = displayName ?: "Resolving Torrent Swarm...",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = "Searching DHT nodes & trackers for torrent metainfo",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Live Swarm Telemetry Badges
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "Peers: $peerCount",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                    color = if (peerCount > 0) NuSuccess else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (dhtNodeCount > 0) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "DHT Nodes: $dhtNodeCount",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                        color = NuPrimary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = infoHashHex.take(16) + "...",
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Action Buttons
        Button(
            onClick = onRunInBackground,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
            Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Wait in Background", fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }

        Spacer(modifier = Modifier.height(10.dp))

        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
            shape = RoundedCornerShape(10.dp)
        ) {
            Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Cancel Attraction", fontSize = 13.sp)
        }
    }
}
