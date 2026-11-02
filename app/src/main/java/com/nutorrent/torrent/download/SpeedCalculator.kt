package com.nutorrent.torrent.download

import java.util.ArrayDeque

/**
 * Calculates smoothed transfer speed using a sliding window of historical byte samples,
 * and estimates remaining time (ETA) gracefully.
 */
class SpeedCalculator(
    private val windowDurationMs: Long = 4000L
) {
    private data class Sample(val timestampMs: Long, val totalBytes: Long)

    private val samples = ArrayDeque<Sample>()

    @Synchronized
    fun addSample(totalBytes: Long, nowMs: Long = System.currentTimeMillis()): Long {
        samples.addLast(Sample(nowMs, totalBytes))

        // Evict samples older than windowDurationMs
        while (samples.size > 2 && nowMs - samples.first.timestampMs > windowDurationMs) {
            samples.removeFirst()
        }

        if (samples.size < 2) return 0L

        val oldest = samples.first
        val newest = samples.last
        val timeDeltaMs = newest.timestampMs - oldest.timestampMs

        if (timeDeltaMs <= 0) return 0L

        val byteDelta = newest.totalBytes - oldest.totalBytes
        if (byteDelta <= 0) return 0L

        return (byteDelta * 1000L) / timeDeltaMs
    }

    fun calculateEta(remainingBytes: Long, speedBps: Long): Long? {
        if (remainingBytes <= 0) return 0L
        if (speedBps <= 0) return null
        val seconds = remainingBytes / speedBps
        // Cap ETA to 100 days to avoid astronomical numbers
        return if (seconds in 0..(100L * 24 * 3600)) seconds else null
    }

    @Synchronized
    fun reset() {
        samples.clear()
    }
}
