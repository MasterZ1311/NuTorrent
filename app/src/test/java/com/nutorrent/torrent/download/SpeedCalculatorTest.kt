package com.nutorrent.torrent.download

import org.junit.Assert.*
import org.junit.Test

class SpeedCalculatorTest {

    @Test
    fun `test smoothed speed calculation`() {
        val calc = SpeedCalculator(windowDurationMs = 5000L)

        // Initial sample at t=0
        val s0 = calc.addSample(totalBytes = 0L, nowMs = 1000L)
        assertEquals(0L, s0)

        // Sample at t=1000ms: downloaded 1,000,000 bytes in 1s = 1,000,000 B/s
        val s1 = calc.addSample(totalBytes = 1_000_000L, nowMs = 2000L)
        assertEquals(1_000_000L, s1)

        // Sample at t=2000ms: downloaded another 1,000,000 bytes (total 2,000,000)
        val s2 = calc.addSample(totalBytes = 2_000_000L, nowMs = 3000L)
        assertEquals(1_000_000L, s2)
    }

    @Test
    fun `test eta calculation handles edge cases gracefully`() {
        val calc = SpeedCalculator()

        // 10 MB remaining at 1 MB/s -> 10 seconds
        val eta1 = calc.calculateEta(remainingBytes = 10_000_000L, speedBps = 1_000_000L)
        assertEquals(10L, eta1)

        // Zero speed must produce null (not infinity or crash!)
        val etaZeroSpeed = calc.calculateEta(remainingBytes = 10_000_000L, speedBps = 0L)
        assertNull(etaZeroSpeed)

        // Negative speed must produce null
        val etaNegSpeed = calc.calculateEta(remainingBytes = 10_000_000L, speedBps = -500L)
        assertNull(etaNegSpeed)

        // Zero remaining bytes must produce 0s
        val etaFinished = calc.calculateEta(remainingBytes = 0L, speedBps = 1_000_000L)
        assertEquals(0L, etaFinished)
    }
}
