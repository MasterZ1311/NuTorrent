package com.nutorrent.core.device

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class BatteryStatus(
    val batteryPct: Int = 100,
    val isCharging: Boolean = false,
    val isPowerSaveMode: Boolean = false,
    val isLowBattery: Boolean = false // < 15% and discharging
)

class BatteryMonitor(
    private val context: Context,
    var onBatteryStateChanged: ((BatteryStatus) -> Unit)? = null
) {
    private val _status = MutableStateFlow(getCurrentStatus())
    val status: StateFlow<BatteryStatus> = _status.asStateFlow()

    private var receiver: BroadcastReceiver? = null

    fun start() {
        if (receiver != null) return

        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                val newStatus = getCurrentStatus()
                _status.value = newStatus
                onBatteryStateChanged?.invoke(newStatus)
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
        try {
            context.registerReceiver(r, filter)
            receiver = r
        } catch (e: Exception) {
            // Some testing environments do not allow registerReceiver
        }
    }

    fun stop() {
        receiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (e: Exception) {
                // Ignore unregister errors
            }
        }
        receiver = null
    }

    fun getCurrentStatus(): BatteryStatus {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val isPowerSave = powerManager?.isPowerSaveMode == true

        val iFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryIntent: Intent? = try {
            context.registerReceiver(null, iFilter)
        } catch (e: Exception) {
            null
        }

        val level: Int = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale: Int = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val pct: Int = if (level >= 0 && scale > 0) ((level.toFloat() / scale.toFloat()) * 100).toInt() else 100

        val rawStatus: Int = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging: Boolean = rawStatus == BatteryManager.BATTERY_STATUS_CHARGING ||
                rawStatus == BatteryManager.BATTERY_STATUS_FULL

        val isLow = pct < 15 && !isCharging

        return BatteryStatus(
            batteryPct = pct,
            isCharging = isCharging,
            isPowerSaveMode = isPowerSave,
            isLowBattery = isLow
        )
    }
}
