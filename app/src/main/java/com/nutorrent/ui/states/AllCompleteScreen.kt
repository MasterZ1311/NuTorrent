package com.nutorrent.ui.states

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nutorrent.core.util.FormatUtils
import com.nutorrent.ui.theme.NuPrimary
import com.nutorrent.ui.theme.NuSuccess
import kotlin.random.Random

private data class ConfettiParticle(
    val initialX: Float,
    val initialY: Float,
    val velocityX: Float,
    val velocityY: Float,
    val color: Color,
    val size: Float,
    val rotationSpeed: Float
)

@Composable
fun AllCompleteScreen(
    totalSeededBytes: Long,
    overallRatio: Float,
    activePeersServed: Int,
    onAddMoreTorrents: () -> Unit,
    onBrowseFiles: () -> Unit,
    modifier: Modifier = Modifier
) {
    // One-shot confetti animation timer (0f -> 1f over 2.5s)
    val confettiProgress = remember { Animatable(0f) }

    val particles = remember {
        val colors = listOf(
            NuSuccess,
            NuPrimary,
            Color(0xFFFFD700), // Gold
            Color(0xFF00E5FF), // Cyan
            Color(0xFF69F0AE)  // Mint
        )
        List(48) {
            val angle = Random.nextDouble(Math.PI * 0.15, Math.PI * 0.85)
            val speed = Random.nextFloat() * 450f + 250f
            ConfettiParticle(
                initialX = 0.5f,
                initialY = 0.4f,
                velocityX = (kotlin.math.cos(angle) * speed).toFloat() * (if (Random.nextBoolean()) 1f else -1f),
                velocityY = -(kotlin.math.sin(angle) * speed).toFloat(),
                color = colors[Random.nextInt(colors.size)],
                size = Random.nextFloat() * 8f + 5f,
                rotationSpeed = Random.nextFloat() * 720f - 360f
            )
        }
    }

    LaunchedEffect(Unit) {
        confettiProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 2400, easing = LinearEasing)
        )
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Emerald Glowing Orb
            Box(
                modifier = Modifier
                    .size(108.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                NuSuccess.copy(alpha = 0.35f),
                                NuSuccess.copy(alpha = 0.08f),
                                Color.Transparent
                            )
                        )
                    )
                    .border(2.dp, NuSuccess.copy(alpha = 0.7f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = NuSuccess,
                    modifier = Modifier.size(54.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "You're Fully Seeded! 🌱",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "All torrents have reached 100% completion. You are now sharing bandwidth back to peer swarms.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                lineHeight = 19.sp,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Spacer(modifier = Modifier.height(28.dp))

            // Swarm Seeding Telemetry Card
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .border(1.dp, NuSuccess.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("DATA SEEDED", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        FormatUtils.formatBytes(totalSeededBytes),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = NuSuccess
                    )
                }

                Box(
                    modifier = Modifier
                        .height(36.dp)
                        .width(1.dp)
                        .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                )

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("GLOBAL RATIO", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        String.format("%.2f", overallRatio),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = if (overallRatio >= 1.0f) NuSuccess else NuPrimary
                    )
                }

                Box(
                    modifier = Modifier
                        .height(36.dp)
                        .width(1.dp)
                        .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                )

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("PEERS SERVED", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "$activePeersServed",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // CTAs
            Button(
                onClick = onAddMoreTorrents,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Add More Torrents", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedButton(
                onClick = onBrowseFiles,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Browse Downloaded Files", fontSize = 13.sp)
            }
        }

        // Confetti Canvas Overlay (rendered only during active burst)
        if (confettiProgress.value < 1f) {
            val t = confettiProgress.value
            val gravity = 550f * t * t
            val alpha = (1f - t).coerceIn(0f, 1f)

            Canvas(modifier = Modifier.fillMaxSize()) {
                val originX = size.width / 2f
                val originY = size.height * 0.35f

                particles.forEach { p ->
                    val x = originX + p.velocityX * t
                    val y = originY + p.velocityY * t + gravity
                    if (y <= size.height && x in 0f..size.width) {
                        drawRect(
                            color = p.color.copy(alpha = alpha),
                            topLeft = Offset(x, y),
                            size = Size(p.size, p.size * 0.6f)
                        )
                    }
                }
            }
        }
    }
}
