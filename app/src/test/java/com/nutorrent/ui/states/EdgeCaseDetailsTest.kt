package com.nutorrent.ui.states

import com.nutorrent.ui.components.EdgeCaseDetails
import com.nutorrent.ui.components.TroubleshootingStep
import com.nutorrent.ui.navigation.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeCaseDetailsTest {

    @Test
    fun `test Screen sealed class includes all edge case variants`() {
        val notFoundScreen: Screen = Screen.TorrentNotFound("abcdef1234567890")
        val invalidMagnetScreen: Screen = Screen.InvalidMagnet("magnet:?bad", "Missing xt parameter")
        val invalidTorrentScreen: Screen = Screen.InvalidTorrentFile("corrupt.torrent", "Invalid bencode")

        assertTrue(notFoundScreen is Screen.TorrentNotFound)
        assertEquals("abcdef1234567890", (notFoundScreen as Screen.TorrentNotFound).infoHash)

        assertTrue(invalidMagnetScreen is Screen.InvalidMagnet)
        assertEquals("magnet:?bad", (invalidMagnetScreen as Screen.InvalidMagnet).rawUrl)
        assertEquals("Missing xt parameter", (invalidMagnetScreen as Screen.InvalidMagnet).errorReason)

        assertTrue(invalidTorrentScreen is Screen.InvalidTorrentFile)
        assertEquals("corrupt.torrent", (invalidTorrentScreen as Screen.InvalidTorrentFile).fileName)
        assertEquals("Invalid bencode", (invalidTorrentScreen as Screen.InvalidTorrentFile).errorReason)
    }

    @Test
    fun `test edge case details satisfy clarity and troubleshooting requirements`() {
        val testCases = listOf(
            EdgeCaseDetails(
                categoryBadge = "404 // NOT FOUND",
                simpleHeadline = "Torrent Not Found",
                simpleGlance = "We couldn't locate this torrent in your download list.",
                whatHappened = "NuTorrent searched your active database for swarm hash, but no active record matches.",
                whyItHappened = listOf(
                    "The torrent was deleted or removed from your library.",
                    "A link contained a typo or truncated hash."
                ),
                troubleshootingSteps = listOf(
                    TroubleshootingStep(1, "Check filters", "Switch filter to All"),
                    TroubleshootingStep(2, "Re-add torrent", "Re-open original file")
                )
            ),
            EdgeCaseDetails(
                categoryBadge = "MALFORMED // MAGNET URI",
                simpleHeadline = "Invalid Magnet Link",
                simpleGlance = "This link could not be recognized as a valid torrent magnet.",
                whatHappened = "The link does not follow the BitTorrent Magnet URI specification (BEP 9).",
                whyItHappened = listOf(
                    "The link was cut off when copying.",
                    "The link points to a normal webpage."
                ),
                troubleshootingSteps = listOf(
                    TroubleshootingStep(1, "Copy full address", "Long press and select Copy link address"),
                    TroubleshootingStep(2, "Verify format", "Must start with magnet:?xt=urn:btih:")
                )
            ),
            EdgeCaseDetails(
                categoryBadge = "CORRUPT // TORRENT METAINFO",
                simpleHeadline = "Corrupted Torrent File",
                simpleGlance = "This .torrent file cannot be read because internal data is damaged.",
                whatHappened = "The Bencode parser failed to decode the file structure.",
                whyItHappened = listOf(
                    "The download was interrupted.",
                    "The website served an HTML error page."
                ),
                troubleshootingSteps = listOf(
                    TroubleshootingStep(1, "Check file size", "Real .torrent is between 5 KB and 500 KB"),
                    TroubleshootingStep(2, "Re-download", "Download again from source")
                )
            )
        )

        for (details in testCases) {
            assertTrue("Headline must not be blank", details.simpleHeadline.isNotBlank())
            assertTrue("Glance summary must not be blank", details.simpleGlance.isNotBlank())
            assertTrue("What happened must be informative", details.whatHappened.length > 20)
            assertTrue("Must provide at least 2 potential causes", details.whyItHappened.size >= 2)
            assertTrue("Must provide at least 2 troubleshooting steps", details.troubleshootingSteps.size >= 2)

            details.troubleshootingSteps.forEachIndexed { index, step ->
                assertEquals("Step number should be 1-based sequential", index + 1, step.stepNumber)
                assertTrue("Step title must be non-blank", step.title.isNotBlank())
                assertTrue("Step description must be non-blank", step.description.isNotBlank())
            }
        }
    }
}
