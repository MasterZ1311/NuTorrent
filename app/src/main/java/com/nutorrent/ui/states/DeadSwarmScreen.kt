package com.nutorrent.ui.states

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nutorrent.ui.components.EdgeCaseDetails
import com.nutorrent.ui.components.EdgeCaseScaffold
import com.nutorrent.ui.components.TroubleshootingStep
import com.nutorrent.ui.theme.NuWarning

@Composable
fun DeadSwarmScreen(
    torrentName: String = "",
    infoHashHex: String = "",
    onBack: () -> Unit,
    onKeepWaiting: () -> Unit,
    onCancelDownload: () -> Unit,
    modifier: Modifier = Modifier
) {
    val details = EdgeCaseDetails(
        categoryBadge = "TIMEOUT // ZERO SEEDERS",
        badgeColor = NuWarning,
        simpleHeadline = "No Seeders Available",
        simpleGlance = "NuTorrent reached out to the swarm and DHT network, but no active seeders or peers are currently sharing this torrent.",
        whatHappened = "The metadata resolution process (BEP 9) could not retrieve the file list. In the BitTorrent protocol, at least one seeder or peer with complete metadata must be online for the download to start.",
        whyItHappened = listOf(
            "The torrent is inactive or abandoned with 0 seeders online worldwide.",
            "The trackers embedded in the magnet link are dead, unreachable, or offline.",
            "Your Wi-Fi or cellular network is blocking P2P DHT/UDP ports."
        ),
        troubleshootingSteps = listOf(
            TroubleshootingStep(
                stepNumber = 1,
                title = "Check seeder status on the website",
                description = "Go back to the website where you found the link. Check the \"Seeders\" column. If it says 0, nobody is currently uploading the files.",
                tip = "A torrent requires at least 1 seeder with 100% of the file."
            ),
            TroubleshootingStep(
                stepNumber = 2,
                title = "Leave it waiting in the background",
                description = "NuTorrent uses non-blocking coroutines with very low battery usage. Leave it queued; the download will kick off automatically the second a seeder connects.",
                tip = "Many torrents have seeders that come online at specific hours."
            ),
            TroubleshootingStep(
                stepNumber = 3,
                title = "Switch connection or test a VPN",
                description = "Some public Wi-Fi networks (schools, cafes, hotels) throttle or block BitTorrent DHT packets. Try switching to mobile data or turning on a VPN."
            )
        ),
        technicalDetails = buildString {
            if (torrentName.isNotBlank()) {
                append("Name: $torrentName\n")
            }
            if (infoHashHex.isNotBlank()) {
                append("Hash: $infoHashHex\n")
            }
            append("Protocol: BEP 9 (ut_metadata extension) & BEP 5 (Mainline DHT)\nPeers Responded: 0")
        }
    )

    EdgeCaseScaffold(
        iconVector = Icons.Default.CloudOff,
        iconTint = NuWarning,
        details = details,
        onBack = onBack,
        primaryButtonText = "Keep Waiting in Background",
        onPrimaryAction = onKeepWaiting,
        secondaryButtonText = "Cancel Download",
        onSecondaryAction = onCancelDownload,
        previewContent = if (torrentName.isNotBlank() || infoHashHex.isNotBlank()) {
            {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = if (torrentName.isNotBlank()) torrentName else "Hash: $infoHashHex",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        } else null,
        modifier = modifier
    )
}
