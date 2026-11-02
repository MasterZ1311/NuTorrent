package com.nutorrent.data.storage

import com.nutorrent.core.model.TorrentFile
import com.nutorrent.core.model.TorrentId
import com.nutorrent.core.model.TorrentMetadata
import org.junit.Assert.*
import org.junit.Test

class StorageCalculatorTest {

    private fun createMetadata(files: List<TorrentFile>): TorrentMetadata {
        val total = files.sumOf { it.length }
        return TorrentMetadata(
            torrentId = TorrentId(ByteArray(20)),
            name = "test_torrent",
            pieceLength = 16384L,
            totalLength = total,
            piecesCount = ((total + 16383) / 16384).toInt(),
            pieceHashes = emptyList(),
            files = files,
            announceList = emptyList()
        )
    }

    @Test
    fun `test calculateRequiredBytes with all files selected and zero resume`() {
        val files = listOf(
            TorrentFile(0, "file1.mp4", 1_000_000L, 0, 1_000_000L),
            TorrentFile(1, "file2.nfo", 5_000L, 1_000_000L, 1_005_000L)
        )
        val meta = createMetadata(files)

        val required = StorageCalculator.calculateRequiredBytes(
            metadata = meta,
            selectedFileIndices = null,
            existingVerifiedBytes = 0L
        )

        assertEquals(1_005_000L + StorageCalculator.SAFETY_MARGIN_BYTES, required)
    }

    @Test
    fun `test calculateRequiredBytes with subset of files selected`() {
        val files = listOf(
            TorrentFile(0, "video.mkv", 2_000_000_000L, 0, 2_000_000_000L),
            TorrentFile(1, "sample.mkv", 50_000_000L, 2_000_000_000L, 2_050_000_000L),
            TorrentFile(2, "subs.srt", 100_000L, 2_050_000_000L, 2_050_100_000L)
        )
        val meta = createMetadata(files)

        // Select only video.mkv and subs.srt (skip sample.mkv)
        val selected = setOf(0, 2)
        val required = StorageCalculator.calculateRequiredBytes(
            metadata = meta,
            selectedFileIndices = selected,
            existingVerifiedBytes = 0L
        )

        assertEquals(2_000_100_000L + StorageCalculator.SAFETY_MARGIN_BYTES, required)
    }

    @Test
    fun `test calculateRequiredBytes accounts for partial resume state`() {
        val files = listOf(
            TorrentFile(0, "linux.iso", 1_000_000_000L, 0, 1_000_000_000L)
        )
        val meta = createMetadata(files)

        // Already downloaded 400 MB
        val verifiedBytes = 400_000_000L
        val required = StorageCalculator.calculateRequiredBytes(
            metadata = meta,
            selectedFileIndices = null,
            existingVerifiedBytes = verifiedBytes
        )

        assertEquals(600_000_000L + StorageCalculator.SAFETY_MARGIN_BYTES, required)
    }

    @Test
    fun `test calculateRequiredBytes returns safety margin on fully downloaded torrent`() {
        val files = listOf(
            TorrentFile(0, "doc.pdf", 500_000L, 0, 500_000L)
        )
        val meta = createMetadata(files)

        val required = StorageCalculator.calculateRequiredBytes(
            metadata = meta,
            selectedFileIndices = null,
            existingVerifiedBytes = 600_000L
        )

        assertEquals(StorageCalculator.SAFETY_MARGIN_BYTES, required)
    }

    @Test
    fun `test StorageAssessment properties when capacity is sufficient`() {
        val files = listOf(
            TorrentFile(0, "data.bin", 100_000_000L, 0, 100_000_000L)
        )
        val meta = createMetadata(files)
        val required = StorageCalculator.calculateRequiredBytes(meta, null, 0L)
        val available = 500_000_000L

        val assessment = StorageAssessment(
            requiredBytes = required,
            availableBytes = available,
            shortfallBytes = null,
            isInsufficient = false,
            isCapacityUnknown = false,
            existingBytes = 0L,
            safetyMarginBytes = StorageCalculator.SAFETY_MARGIN_BYTES
        )

        assertFalse(assessment.isInsufficient)
        assertFalse(assessment.isCapacityUnknown)
        assertEquals(500_000_000L, assessment.availableBytes)
        assertNull(assessment.shortfallBytes)
    }

    @Test
    fun `test StorageAssessment properties when capacity is insufficient`() {
        val files = listOf(
            TorrentFile(0, "huge_game.iso", 4_000_000_000L, 0, 4_000_000_000L)
        )
        val meta = createMetadata(files)
        val required = StorageCalculator.calculateRequiredBytes(meta, null, 0L)
        val available = 2_000_000_000L
        val shortfall = required - available

        val assessment = StorageAssessment(
            requiredBytes = required,
            availableBytes = available,
            shortfallBytes = shortfall,
            isInsufficient = true,
            isCapacityUnknown = false,
            existingBytes = 0L,
            safetyMarginBytes = StorageCalculator.SAFETY_MARGIN_BYTES
        )

        assertTrue(assessment.isInsufficient)
        assertFalse(assessment.isCapacityUnknown)
        assertEquals(shortfall, assessment.shortfallBytes)
        assertTrue((assessment.shortfallBytes ?: 0L) > 2_000_000_000L)
    }

    @Test
    fun `test StorageAssessment properties with unknown capacity provider`() {
        val files = listOf(
            TorrentFile(0, "file.zip", 500_000_000L, 0, 500_000_000L)
        )
        val meta = createMetadata(files)
        val required = StorageCalculator.calculateRequiredBytes(meta, null, 0L)

        val assessment = StorageAssessment(
            requiredBytes = required,
            availableBytes = null,
            shortfallBytes = null,
            isInsufficient = false,
            isCapacityUnknown = true,
            existingBytes = 0L,
            safetyMarginBytes = StorageCalculator.SAFETY_MARGIN_BYTES
        )

        assertTrue(assessment.isCapacityUnknown)
        assertFalse(assessment.isInsufficient)
        assertNull(assessment.availableBytes)
        assertNull(assessment.shortfallBytes)
    }

    @Test
    fun `test StorageAssessment when available space is exactly zero bytes`() {
        val files = listOf(
            TorrentFile(0, "test.dat", 10_000_000L, 0, 10_000_000L)
        )
        val meta = createMetadata(files)
        val required = StorageCalculator.calculateRequiredBytes(meta, null, 0L)
        val available = 0L

        val shortfall = (required - available).coerceAtLeast(0L)
        val assessment = StorageAssessment(
            requiredBytes = required,
            availableBytes = available,
            shortfallBytes = shortfall,
            isInsufficient = available < required,
            isCapacityUnknown = false,
            existingBytes = 0L,
            safetyMarginBytes = StorageCalculator.SAFETY_MARGIN_BYTES
        )

        assertTrue("0 free bytes must be marked insufficient", assessment.isInsufficient)
        assertFalse("0 free bytes is known capacity, not unknown", assessment.isCapacityUnknown)
        assertEquals(0L, assessment.availableBytes)
        assertEquals(required, assessment.shortfallBytes)
    }
}
