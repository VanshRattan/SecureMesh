package com.capstone.chatapp.data.transport

import android.content.Context
import android.os.BatteryManager

/**
 * Battery-fraction + charging read for [TransportArbiter]'s energy-budget scoring. Plain
 * [BatteryManager] queries -- no permission needed, unlike the Bluetooth/location signals
 * the other tiers depend on.
 */
class EnergyMonitor(context: Context) {
    private val batteryManager =
        context.applicationContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager

    /** 0f (empty) to 1f (full); defaults to 1f (assume plenty) if the property can't be read. */
    fun batteryFraction(): Float {
        val pct = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
        return pct.coerceIn(0, 100) / 100f
    }

    fun isCharging(): Boolean = batteryManager?.isCharging ?: true
}
