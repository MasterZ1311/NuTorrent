package com.nutorrent.app.engine

import com.nutorrent.app.core.device.BatteryStatus
import com.nutorrent.app.torrent.engine.DownloadScheduler
import com.nutorrent.app.torrent.engine.TorrentEngine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadSchedulerTest {

    @Test
    fun `test download scheduler same day window`() {
        // Window 8:00 to 22:00
        assertFalse(DownloadScheduler.isInsideWindow(currentHour = 7, startHour = 8, endHour = 22))
        assertTrue(DownloadScheduler.isInsideWindow(currentHour = 8, startHour = 8, endHour = 22))
        assertTrue(DownloadScheduler.isInsideWindow(currentHour = 14, startHour = 8, endHour = 22))
        assertTrue(DownloadScheduler.isInsideWindow(currentHour = 21, startHour = 8, endHour = 22))
        assertFalse(DownloadScheduler.isInsideWindow(currentHour = 22, startHour = 8, endHour = 22))
        assertFalse(DownloadScheduler.isInsideWindow(currentHour = 23, startHour = 8, endHour = 22))
    }

    @Test
    fun `test download scheduler overnight window wrapping midnight`() {
        // Window 22:00 to 06:00
        assertFalse(DownloadScheduler.isInsideWindow(currentHour = 21, startHour = 22, endHour = 6))
        assertTrue(DownloadScheduler.isInsideWindow(currentHour = 22, startHour = 22, endHour = 6))
        assertTrue(DownloadScheduler.isInsideWindow(currentHour = 23, startHour = 22, endHour = 6))
        assertTrue(DownloadScheduler.isInsideWindow(currentHour = 0, startHour = 22, endHour = 6))
        assertTrue(DownloadScheduler.isInsideWindow(currentHour = 3, startHour = 22, endHour = 6))
        assertTrue(DownloadScheduler.isInsideWindow(currentHour = 5, startHour = 22, endHour = 6))
        assertFalse(DownloadScheduler.isInsideWindow(currentHour = 6, startHour = 22, endHour = 6))
        assertFalse(DownloadScheduler.isInsideWindow(currentHour = 12, startHour = 22, endHour = 6))
    }

    @Test
    fun `test battery status properties and low battery threshold`() {
        val lowDischarging = BatteryStatus(batteryPct = 12, isCharging = false, isPowerSaveMode = false, isLowBattery = true)
        assertTrue(lowDischarging.isLowBattery)
        assertFalse(lowDischarging.isCharging)

        val lowCharging = BatteryStatus(batteryPct = 12, isCharging = true, isPowerSaveMode = false, isLowBattery = false)
        assertFalse(lowCharging.isLowBattery)
        assertTrue(lowCharging.isCharging)
    }

    @Test
    fun `test torrent engine scheduler bandwidth limiter`() = runTest {
        val engine = TorrentEngine()
        val defaultLimit = 1_000_000L // 1 MB/s
        val scheduledLimit = 250_000L // 250 KB/s

        // Scheduler disabled -> uses default
        engine.applyScheduler(
            enabled = false,
            startHour = 8,
            endHour = 22,
            limitBps = scheduledLimit,
            defaultLimitBps = defaultLimit,
            currentHour = 12
        )
        assertFalse(engine.schedulerThrottled)
        assertEquals(defaultLimit, engine.bandwidthManager.globalDownloadBucket.getRate())

        // Scheduler enabled and inside window (12 PM in 8..22) -> uses scheduled limit
        engine.applyScheduler(
            enabled = true,
            startHour = 8,
            endHour = 22,
            limitBps = scheduledLimit,
            defaultLimitBps = defaultLimit,
            currentHour = 12
        )
        assertTrue(engine.schedulerThrottled)
        assertEquals(scheduledLimit, engine.bandwidthManager.globalDownloadBucket.getRate())

        // Scheduler enabled but outside window (23:00 in 8..22) -> uses default limit
        engine.applyScheduler(
            enabled = true,
            startHour = 8,
            endHour = 22,
            limitBps = scheduledLimit,
            defaultLimitBps = defaultLimit,
            currentHour = 23
        )
        assertFalse(engine.schedulerThrottled)
        assertEquals(defaultLimit, engine.bandwidthManager.globalDownloadBucket.getRate())

        engine.shutdown()
    }

    @Test
    fun `test battery state changes update engine paused flag`() {
        val engine = TorrentEngine()

        val normalBattery = BatteryStatus(batteryPct = 80, isCharging = false, isPowerSaveMode = false, isLowBattery = false)
        engine.onBatteryChanged(normalBattery, pauseOnLow = true, pauseOnPowerSave = true)
        assertFalse(engine.batteryPaused)

        val lowBattery = BatteryStatus(batteryPct = 10, isCharging = false, isPowerSaveMode = false, isLowBattery = true)
        engine.onBatteryChanged(lowBattery, pauseOnLow = true, pauseOnPowerSave = true)
        assertTrue(engine.batteryPaused)

        // Restore battery
        engine.onBatteryChanged(normalBattery, pauseOnLow = true, pauseOnPowerSave = true)
        assertFalse(engine.batteryPaused)

        engine.shutdown()
    }
}
