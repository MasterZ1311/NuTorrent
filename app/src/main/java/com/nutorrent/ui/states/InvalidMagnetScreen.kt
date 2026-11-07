package com.nutorrent.ui.states

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nutorrent.ui.components.EdgeCaseDetails
import com.nutorrent.ui.components.EdgeCaseScaffold
import com.nutorrent.ui.components.TroubleshootingStep
import com.nutorrent.ui.theme.NuError

@Composable
fun InvalidMagnetScreen(
    rawUrl: String = "",
    errorReason: String = "",
    onBack: () -> Unit,
    onPasteAgain: ((String) -> Unit)? = null,
    onPickTorrentFile: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    val details = EdgeCaseDetails(
        categoryBadge = "MALFORMED // MAGNET URI",
        badgeColor = NuError,
        simpleHeadline = "Invalid Magnet Link",
        simpleGlance = "This link could not be recognized as a valid torrent magnet. Key download parameters like the swarm hash are missing or broken.",
        whatHappened = "The link does not follow the BitTorrent Magnet URI specification (BEP 9). NuTorrent needs an exact-topic identifier (xt=urn:btih:) to discover peers in the DHT network.",
        whyItHappened = listOf(
            "The link was cut off or only partially selected when copying from the browser.",
            "The link points to a normal webpage (http:// or https://) rather than a magnet: URI.",
            "The swarm hash is missing or contains invalid characters (must be 40 hex or 32 Base32 characters)."
        ),
        troubleshootingSteps = listOf(
            TroubleshootingStep(
                stepNumber = 1,
                title = "Copy the full link address",
                description = "In your browser, long-press the magnet icon or download button, then choose \"Copy link address\". Avoid highlighting the text manually.",
                tip = "Manual text selection often misses the end of long URLs."
            ),
            TroubleshootingStep(
                stepNumber = 2,
                title = "Verify the magnet format",
                description = "Valid magnet links start with \"magnet:?xt=urn:btih:\" followed by the unique torrent hash.",
                tip = "Example: magnet:?xt=urn:btih:1234567890abcdef..."
            ),
            TroubleshootingStep(
                stepNumber = 3,
                title = "Open a .torrent file instead",
                description = "If the website offers a .torrent file, download it and open it directly in NuTorrent. It contains the exact same swarm data."
            )
        ),
        technicalDetails = buildString {
            if (rawUrl.isNotBlank()) {
                append("Raw Input: $rawUrl\n")
            }
            if (errorReason.isNotBlank()) {
                append("Parser Reason: $errorReason\n")
            }
            append("Standard: BEP 9 (BitTorrent Extension for Magnet URIs)")
        }
    )

    EdgeCaseScaffold(
        iconVector = Icons.Default.LinkOff,
        iconTint = NuError,
        details = details,
        onBack = onBack,
        primaryButtonText = if (onPasteAgain != null) "Paste from Clipboard & Retry" else "Go Back",
        onPrimaryAction = {
            if (onPasteAgain != null) {
                val clipText = clipboardManager.getText()?.text?.trim()
                if (!clipText.isNullOrBlank()) {
                    onPasteAgain(clipText)
                } else {
                    Toast.makeText(context, "Clipboard is empty", Toast.LENGTH_SHORT).show()
                }
            } else {
                onBack()
            }
        },
        secondaryButtonText = if (onPickTorrentFile != null) "Open .torrent File" else "Return to Library",
        onSecondaryAction = onPickTorrentFile ?: onBack,
        previewContent = if (rawUrl.isNotBlank()) {
            {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                        .padding(10.dp)
                ) {
                    Text(
                        text = "Received Link Snippet:",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = rawUrl,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        } else null,
        modifier = modifier
    )
}
