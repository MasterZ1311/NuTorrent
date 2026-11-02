package com.nutorrent.torrent.engine

import com.nutorrent.core.device.BatteryStatus
import com.nutorrent.core.model.AppError
import com.nutorrent.core.model.PauseReason
import com.nutorrent.core.model.ResourceGovernor
import com.nutorrent.core.model.ResourceGovernorConfig
import com.nutorrent.core.model.TorrentFile
import com.nutorrent.core.model.TorrentId
import com.nutorrent.core.model.TorrentMetadata
import com.nutorrent.core.model.TorrentState
import com.nutorrent.data.repository.UserSettings
import com.nutorrent.data.storage.TorrentStorage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SmartRulesAndQueueTest {

    private fun createMockStorage(): TorrentStorage {
        return object : TorrentStorage {
            override suspend fun prepare() {}
            override suspend fun writePiece(globalOffset: Long, data: ByteArray) {}
            override suspend fun readBytes(globalOffset: Long, length: Int): ByteArray = ByteArray(length)
            override suspend fun verifyStoredPieces(onPieceResult: (Int, Boolean) -> Unit) {}
            override fun close() {}
        }
    }

    private fun createMetadata(idByte: Byte, name: String): TorrentMetadata {
        val hash = ByteArray(20) { idByte }
        return TorrentMetadata(
            torrentId = TorrentId(hash),
            name = name,
            pieceLength = 16384,
            piecesCount = 1,
            pieceHashes = listOf(ByteArray(20)),
            totalLength = 16384,
            files = listOf(TorrentFile(0, name, 16384, 0, 16384)),
            announceList = emptyList()
        )
    }

    @Test
    fun `test PauseReason labels and distinguishing user manual vs rules`() {
        assertEquals("Active", PauseReason.NONE.label)
        assertEquals("Paused", PauseReason.USER_MANUAL.label)
        assertEquals("Paused (Wi-Fi only)", PauseReason.RULE_WIFI_ONLY.label)
        assertEquals("Paused (Mobile data)", PauseReason.RULE_MOBILE_DATA.label)
        assertEquals("Paused (Low battery)", PauseReason.RULE_LOW_BATTERY.label)
        assertEquals("Paused (Not charging)", PauseReason.RULE_NOT_CHARGING.label)
        assertEquals("Paused (Battery saver)", PauseReason.RULE_BATTERY_SAVER.label)
        assertEquals("Queued", PauseReason.QUEUED.label)
    }

    @Test
    fun `test network rule pause never overrides manual pause upon Wi-Fi recovery`() = runBlocking {
        val engine = TorrentEngine()
        engine.onNetworkChanged(isWifi = true, isConnected = true)
        engine.setWifiOnlyMode(false)

        val metaAuto = createMetadata(1, "auto_download")
        val metaManual = createMetadata(2, "user_paused")

        val sAuto = engine.createSession(metaAuto, createMockStorage())
        val sManual = engine.createSession(metaManual, createMockStorage())

        // Start both
        engine.startOrQueue(metaAuto.torrentId)
        engine.startOrQueue(metaManual.torrentId)

        // User manually pauses sManual
        engine.pauseSession(metaManual.torrentId)
        assertEquals(TorrentState.PAUSED, sManual.state.value)
        assertEquals(PauseReason.USER_MANUAL, sManual.pauseReason.value)

        // Now Wi-Fi only mode is turned on and Wi-Fi disconnects
        engine.setWifiOnlyMode(true)
        engine.onNetworkChanged(isWifi = false, isConnected = true)

        // sAuto must be paused due to RULE_WIFI_ONLY
        assertEquals(TorrentState.PAUSED, sAuto.state.value)
        assertEquals(PauseReason.RULE_WIFI_ONLY, sAuto.pauseReason.value)

        // sManual must still be USER_MANUAL
        assertEquals(TorrentState.PAUSED, sManual.state.value)
        assertEquals(PauseReason.USER_MANUAL, sManual.pauseReason.value)

        // Wi-Fi reconnects!
        engine.onNetworkChanged(isWifi = true, isConnected = true)

        // sAuto must automatically recover and not be paused
        assertNotEquals(PauseReason.RULE_WIFI_ONLY, sAuto.pauseReason.value)

        // sManual MUST REMAIN PAUSED by the user!
        assertEquals(TorrentState.PAUSED, sManual.state.value)
        assertEquals(PauseReason.USER_MANUAL, sManual.pauseReason.value)

        engine.shutdown()
    }

    @Test
    fun `test battery rule pause never overrides manual pause upon power recovery`() = runBlocking {
        val engine = TorrentEngine()

        val metaAuto = createMetadata(10, "battery_affected")
        val metaManual = createMetadata(20, "manually_paused")

        val sAuto = engine.createSession(metaAuto, createMockStorage())
        val sManual = engine.createSession(metaManual, createMockStorage())

        engine.startOrQueue(metaAuto.torrentId)
        engine.startOrQueue(metaManual.torrentId)

        // User manually pauses sManual
        engine.pauseSession(metaManual.torrentId)
        assertEquals(PauseReason.USER_MANUAL, sManual.pauseReason.value)

        // Battery drops below threshold (e.g. 10% < 20%)
        val lowStatus = BatteryStatus(batteryPct = 10, isCharging = false, isPowerSaveMode = false, isLowBattery = true)
        engine.onBatteryChanged(lowStatus, pauseOnLow = true, pauseOnPowerSave = false, batteryThresholdPct = 20)

        assertTrue(engine.batteryPaused)
        assertEquals(PauseReason.RULE_LOW_BATTERY, sAuto.pauseReason.value)
        assertEquals(PauseReason.USER_MANUAL, sManual.pauseReason.value)

        // Device is plugged into charger
        val chargingStatus = BatteryStatus(batteryPct = 12, isCharging = true, isPowerSaveMode = false, isLowBattery = false)
        engine.onBatteryChanged(chargingStatus, pauseOnLow = true, pauseOnPowerSave = false, batteryThresholdPct = 20)

        assertFalse(engine.batteryPaused)
        // sAuto must resume
        assertNotEquals(PauseReason.RULE_LOW_BATTERY, sAuto.pauseReason.value)

        // sManual MUST STAY PAUSED!
        assertEquals(TorrentState.PAUSED, sManual.state.value)
        assertEquals(PauseReason.USER_MANUAL, sManual.pauseReason.value)

        engine.shutdown()
    }

    @Test
    fun `test download only while charging rule`() = runBlocking {
        val engine = TorrentEngine()

        val meta = createMetadata(30, "charging_test")
        val session = engine.createSession(meta, createMockStorage())
        engine.startOrQueue(meta.torrentId)

        // Device is running on battery with downloadOnlyWhileCharging = true
        val discharging = BatteryStatus(batteryPct = 80, isCharging = false, isPowerSaveMode = false)
        engine.onBatteryChanged(
            discharging,
            pauseOnLow = false,
            pauseOnPowerSave = false,
            batteryThresholdPct = 15,
            downloadOnlyWhileCharging = true
        )

        assertTrue(engine.batteryPaused)
        assertEquals(PauseReason.RULE_NOT_CHARGING, session.pauseReason.value)
        assertEquals(TorrentState.PAUSED, session.state.value)

        // Device is plugged into power
        val charging = BatteryStatus(batteryPct = 80, isCharging = true, isPowerSaveMode = false)
        engine.onBatteryChanged(
            charging,
            pauseOnLow = false,
            pauseOnPowerSave = false,
            batteryThresholdPct = 15,
            downloadOnlyWhileCharging = true
        )

        assertFalse(engine.batteryPaused)
        assertNotEquals(PauseReason.RULE_NOT_CHARGING, session.pauseReason.value)

        engine.shutdown()
    }

    @Test
    fun `test single torrent error does not block queue and auto-promotes queued download`() = runBlocking {
        val governor = ResourceGovernor(ResourceGovernorConfig(maxActiveDownloads = 1))
        val engine = TorrentEngine(governor = governor)

        val meta1 = createMetadata(41, "torrent_fail")
        val meta2 = createMetadata(42, "torrent_queued")

        val s1 = engine.createSession(meta1, createMockStorage())
        val s2 = engine.createSession(meta2, createMockStorage())

        engine.startOrQueue(meta1.torrentId)
        engine.startOrQueue(meta2.torrentId)

        // s1 is active, s2 is queued
        assertNotEquals(TorrentState.QUEUED, s1.state.value)
        assertEquals(TorrentState.QUEUED, s2.state.value)

        // s1 encounters an unexpected storage failure
        s1.setError(AppError.InsufficientStorage(requiredBytes = 5000L, availableBytes = 1000L))

        // Queue must immediately promote s2
        assertNotEquals(TorrentState.QUEUED, s2.state.value)

        engine.shutdown()
    }

    @Test
    fun `test UserSettings contains UI mode and battery threshold defaults`() {
        val settings = UserSettings(defaultDownloadPath = "/dummy")
        assertEquals("SIMPLE", settings.uiMode)
        assertEquals(15, settings.batteryThresholdPercent)
        assertFalse(settings.downloadOnlyWhileCharging)
        assertEquals(0L, settings.lifetimeDownloadedBytes)
        assertEquals(0L, settings.lifetimeUploadedBytes)
        assertEquals(0, settings.completedTorrentsCount)
    }
}
