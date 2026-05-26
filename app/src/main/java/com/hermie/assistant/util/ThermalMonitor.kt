package com.hermie.assistant.util

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.util.Log

/**
 * Monitors device thermal state using battery temperature (all Android 9+)
 * and PowerManager thermal status (Android 10+).
 *
 * Battery temperature is reported in tenths of a degree Celsius by the kernel.
 * PowerManager thermal status is a coarser signal but reflects overall SoC throttling.
 *
 * Usage:
 *   val monitor = ThermalMonitor(context)
 *   if (monitor.isTooHot()) { // pause heavy work }
 *   monitor.coolDown(onProgress)  // suspends until safe to resume
 */
class ThermalMonitor(private val context: Context) {

    companion object {
        private const val TAG = "ThermalMonitor"

        /**
         * Battery temperature threshold in tenths of degrees Celsius.
         * 420 = 42.0C — phones typically throttle around 40-45C.
         * We pause early at 42C to prevent sustained thermal throttling.
         */
        const val BATTERY_TEMP_THRESHOLD = 420

        /**
         * Resume threshold — we wait until the phone cools below this.
         * 380 = 38.0C — decent headroom before hitting throttle again.
         */
        const val BATTERY_TEMP_RESUME = 380

        /**
         * PowerManager thermal status threshold.
         * THERMAL_STATUS_SEVERE (3) means the device is actively throttling.
         * We pause at MODERATE (2) to stay ahead of the throttle curve.
         */
        const val THERMAL_STATUS_THRESHOLD = 2 // THERMAL_STATUS_MODERATE

        /**
         * How long to wait between temperature checks while cooling down.
         */
        const val COOLDOWN_CHECK_INTERVAL_MS = 15_000L // 15 seconds
    }

    /**
     * Get current battery temperature in tenths of degrees Celsius.
     * Returns null if unavailable.
     */
    fun getBatteryTemperature(): Int? {
        return try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)?.takeIf { it > 0 }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read battery temperature", e)
            null
        }
    }

    /**
     * Get PowerManager thermal status (API 29+).
     * Returns -1 if unavailable (API < 29).
     *
     * Values: 0=NONE, 1=LIGHT, 2=MODERATE, 3=SEVERE, 4=CRITICAL, 5=EMERGENCY, 6=SHUTDOWN
     */
    fun getThermalStatus(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return -1
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.currentThermalStatus ?: -1
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read thermal status", e)
            -1
        }
    }

    /**
     * Check if the device is too hot and should pause heavy work.
     */
    fun isTooHot(): Boolean {
        val batteryTemp = getBatteryTemperature()
        val thermalStatus = getThermalStatus()

        val batteryHot = batteryTemp != null && batteryTemp >= BATTERY_TEMP_THRESHOLD
        val thermalHot = thermalStatus >= THERMAL_STATUS_THRESHOLD

        if (batteryHot || thermalHot) {
            Log.w(TAG, "Device too hot! battery=${batteryTemp?.let { "${it / 10.0}C" } ?: "?"}, thermal=$thermalStatus")
            return true
        }
        return false
    }

    /**
     * Check if the device has cooled down enough to resume work.
     */
    fun isCoolEnough(): Boolean {
        val batteryTemp = getBatteryTemperature()
        val thermalStatus = getThermalStatus()

        // Battery must be below resume threshold
        if (batteryTemp != null && batteryTemp > BATTERY_TEMP_RESUME) return false
        // Thermal status must be below moderate (if available)
        if (thermalStatus >= THERMAL_STATUS_THRESHOLD) return false

        return true
    }

    /**
     * Format the current thermal state for display.
     */
    fun formatStatus(): String {
        val batteryTemp = getBatteryTemperature()
        val thermalStatus = getThermalStatus()

        val tempStr = batteryTemp?.let { "${it / 10.0}\u00B0C" } ?: "unknown"
        val statusStr = when {
            thermalStatus < 0 -> ""
            thermalStatus == 0 -> " (nominal)"
            thermalStatus == 1 -> " (light)"
            thermalStatus == 2 -> " (moderate)"
            thermalStatus == 3 -> " (severe)"
            thermalStatus >= 4 -> " (critical!)"
            else -> ""
        }
        return "$tempStr$statusStr"
    }

    /**
     * Suspend until the device is cool enough to resume.
     * Logs progress and checks every [COOLDOWN_CHECK_INTERVAL_MS].
     *
     * @param onProgress callback for status messages
     * @return how long (ms) we waited, or 0 if no cooldown needed
     */
    suspend fun coolDown(onProgress: (String) -> Unit): Long {
        if (!isTooHot()) return 0

        val startTime = System.currentTimeMillis()
        onProgress("Device too hot (${formatStatus()}) — pausing to cool down...")

        var checks = 0
        while (!isCoolEnough()) {
            kotlinx.coroutines.delay(COOLDOWN_CHECK_INTERVAL_MS)
            checks++
            val elapsed = (System.currentTimeMillis() - startTime) / 1000
            onProgress("Still cooling (${formatStatus()}) — ${elapsed}s elapsed...")

            // Safety: after 5 minutes of cooling, warn but don't block forever
            if (checks >= 20) {
                onProgress("Warning: still hot after 5 minutes — resuming anyway")
                break
            }
        }

        val totalWait = System.currentTimeMillis() - startTime
        if (totalWait > 0) {
            onProgress("Cooled down (${formatStatus()}) — resuming after ${totalWait / 1000}s")
            Log.i(TAG, "Cooldown complete after ${totalWait / 1000}s")
        }
        return totalWait
    }
}
