package com.nutorrent.ui.states

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SearchOff
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
fun TorrentNotFoundScreen(
    infoHashHex: String = "",
    onBack: () -> Unit,
    onAddTorrentClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val details = EdgeCaseDetails(
        categoryBadge = "404 // NOT FOUND",
        badgeColor = NuWarning,
        simpleHeadline = "Torrent Not Found",
        simpleGlance = "We couldn't locate this torrent in your download list. It may have been removed, cleared from history, or opened from an expired link.",
        whatHappened = if (infoHashHex.isNotBlank()) {
            "NuTorrent searched your active database for swarm hash $infoHashHex, but no active or stored torrent matches this identifier."
        } else {
            "The requested torrent is not registered in your active database."
        },
        whyItHappened = listOf(
            "The torrent was deleted or removed from your library in a previous session.",
            "A shortcut, notification, or deep link contained a typo or truncated hash.",
            "The app's database was cleared or re-initialized."
        ),
        troubleshootingSteps = listOf(
            TroubleshootingStep(
                stepNumber = 1,
                title = "Check your library filter chips",
                description = "On the Home screen, switch the top filter chip to \"All\" to see if the torrent is completed or paused.",
                tip = "Filters like 'Downloading' hide completed torrents."
            ),
            TroubleshootingStep(
                stepNumber = 2,
                title = "Re-add the torrent file or magnet link",
                description = "Re-open the original .torrent file or re-paste the magnet link to re-register it in your library.",
                tip = "NuTorrent will verify existing data and pick up where you left off."
            ),
            TroubleshootingStep(
                stepNumber = 3,
                title = "Point to your existing files",
                description = "If you previously downloaded the files, select the exact same folder when re-adding. The engine will verify checksums at 60 FPS and mark completed pieces instantly."
            )
        ),
        technicalDetails = if (infoHashHex.isNotBlank()) {
            "InfoHash: $infoHashHex\nQuery Status: NULL_RECORD\nAction: Database query returned 0 rows in torrents table"
        } else null
    )

    EdgeCaseScaffold(
        iconVector = Icons.Default.SearchOff,
        iconTint = NuWarning,
        details = details,
        onBack = onBack,
        primaryButtonText = "Back to Library",
        onPrimaryAction = onBack,
        secondaryButtonText = if (onAddTorrentClick != null) "Add New Torrent" else null,
        onSecondaryAction = onAddTorrentClick,
        previewContent = if (infoHashHex.isNotBlank()) {
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
                        text = "Hash: $infoHashHex",
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
