package com.creatorcam.app.recording

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import com.creatorcam.app.util.Logger

/**
 * Battery + thermal safety. Thermal status (API 29+) is polled during
 * recording; severe overheating stops the take safely instead of crashing.
 */
class BatteryThermalMonitor(private val context: Context) {

    fun batteryPercent(): Int {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val prop = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (prop in 1..100) return prop
        val intent: Intent? = context.registerReceiver(
            null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) (level * 100 / scale) else -1
    }

    fun isCharging(): Boolean {
        val intent: Intent? = context.registerReceiver(
            null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
    }

    /** 0 (none) .. 6 (shutdown). -1 if the API is unavailable. */
    fun thermalStatus(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return -1
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return runCatching { pm.currentThermalStatus }
            .getOrElse {
                Logger.w("Thermal", "Thermal query failed", it)
                -1
            }
    }

    fun thermalLabel(status: Int): String = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> "None"
        PowerManager.THERMAL_STATUS_LIGHT -> "Light"
        PowerManager.THERMAL_STATUS_MODERATE -> "Moderate"
        PowerManager.THERMAL_STATUS_SEVERE -> "Severe"
        PowerManager.THERMAL_STATUS_CRITICAL -> "Critical"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "Emergency"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "Shutdown"
        else -> "Unknown"
    }

    /** True when recording must stop now to protect the device and the file. */
    fun mustStop(status: Int): Boolean =
        status >= PowerManager.THERMAL_STATUS_SEVERE

    fun shouldWarn(status: Int): Boolean =
        status == PowerManager.THERMAL_STATUS_MODERATE
}
