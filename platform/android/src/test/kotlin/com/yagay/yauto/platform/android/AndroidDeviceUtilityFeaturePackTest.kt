package com.yagay.yauto.platform.android

import android.os.BatteryManager
import android.os.PowerManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidDeviceUtilityFeaturePackTest {
    @Test fun `charging source mapping is stable`() {
        assertEquals("ac", chargingSourceName(BatteryManager.BATTERY_PLUGGED_AC))
        assertEquals("usb", chargingSourceName(BatteryManager.BATTERY_PLUGGED_USB))
        assertEquals("wireless", chargingSourceName(BatteryManager.BATTERY_PLUGGED_WIRELESS))
        assertEquals("none", chargingSourceName(0))
        assertTrue(matchesChargingSource("any_charging", BatteryManager.BATTERY_PLUGGED_USB))
        assertFalse(matchesChargingSource("wireless", BatteryManager.BATTERY_PLUGGED_USB))
        assertTrue(matchesChargingSource("none", 0))
    }

    @Test fun `battery status and health names use stable machine values`() {
        assertEquals("charging", batteryStatusName(BatteryManager.BATTERY_STATUS_CHARGING))
        assertEquals("full", batteryStatusName(BatteryManager.BATTERY_STATUS_FULL))
        assertEquals("good", batteryHealthName(BatteryManager.BATTERY_HEALTH_GOOD))
        assertEquals("overheat", batteryHealthName(BatteryManager.BATTERY_HEALTH_OVERHEAT))
    }

    @Test fun `thermal status names are stable`() {
        assertEquals("none", thermalStatusName(PowerManager.THERMAL_STATUS_NONE))
        assertEquals("moderate", thermalStatusName(PowerManager.THERMAL_STATUS_MODERATE))
        assertEquals("critical", thermalStatusName(PowerManager.THERMAL_STATUS_CRITICAL))
    }
}
