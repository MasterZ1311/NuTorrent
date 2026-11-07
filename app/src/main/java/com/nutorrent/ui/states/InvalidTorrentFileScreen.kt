package com.nutorrent.ui.states

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nutorrent.ui.components.EdgeCaseDetails
import com.nutorrent.ui.components.EdgeCaseScaffold
import com.nutorrent.ui.components.TroubleshootingStep
import com.nutorrent.ui.theme.NuError

@Composable
fun InvalidTorrentFileScreen(
    fileName: String = "",
    errorReason: String = "",
    onBack: () -> Unit,
    onChooseAnotherFile: () -> Unit,
    onPasteMagnetInstead: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val details = EdgeCaseDetails(
        categoryBadge = "CORRUPT // TORRENT METAINFO",
        badgeColor = NuError,
        simpleHeadline = "Corrupted Torrent File",
        simpleGlance = "This .torrent file cannot be read because its internal data is damaged, incomplete, or not a recognized BitTorrent file.",
        whatHappened = "The Bencode parser failed to decode the file structure. Genuine BitTorrent files contain a specific dictionary structure with 'info', 'pieces', and 'piece length' keys.",
        whyItHappened = listOf(
            "The download was interrupted, leaving a partial or zero-byte file on your device.",
            "The website served an HTML error, login prompt, or Cloudflare challenge page saved with a .torrent extension.",
            "The file is encrypted or was modified by another application on your phone."
        ),
        troubleshootingSteps = listOf(
            TroubleshootingStep(
                stepNumber = 1,
                title = "Check file size in your file manager",
                description = "Genuine .torrent files are normally between 5 KB and 500 KB. If the file is 1 KB or smaller, it is likely an HTML error page from the website.",
                tip = "Open the file with a text viewer: if it starts with <!DOCTYPE html>, it is an HTML page."
            ),
            TroubleshootingStep(
                stepNumber = 2,
                title = "Re-download the file",
                description = "Go back to the source website and download the .torrent file again. Make sure the download finishes 100% before opening.",
                tip = "Try using Chrome or your device's default browser."
            ),
            TroubleshootingStep(
                stepNumber = 3,
                title = "Use a magnet link instead",
                description = "Look for a magnet link on the download page. Magnet links connect straight to the swarm without needing to save a .torrent file to your phone."
            )
        ),
        technicalDetails = buildString {
            if (fileName.isNotBlank()) {
                append("File: $fileName\n")
            }
            if (errorReason.isNotBlank()) {
                append("Parser Error: $errorReason\n")
            }
            append("Bencode Protocol: BEP 3 (The BitTorrent Protocol Specification)")
        }
    )

    EdgeCaseScaffold(
        iconVector = Icons.Default.Description,
        iconTint = NuError,
        details = details,
        onBack = onBack,
        primaryButtonText = "Choose Another File",
        onPrimaryAction = onChooseAnotherFile,
        secondaryButtonText = if (onPasteMagnetInstead != null) "Paste Magnet Link Instead" else "Go Back",
        onSecondaryAction = onPasteMagnetInstead ?: onBack,
        previewContent = if (fileName.isNotBlank()) {
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
                        text = fileName,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        } else null,
        modifier = modifier
    )
}
