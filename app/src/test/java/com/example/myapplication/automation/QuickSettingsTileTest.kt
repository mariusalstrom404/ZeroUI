package com.example.myapplication.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuickSettingsTileTest {

    private val wifi = listOf("wi fi", "wifi", "wlan")

    @Test
    fun matchesTileNameWithOrWithoutState() {
        assertEquals("wi fi", QuickSettingsTile.matchedName("Wi-Fi", wifi))
        assertEquals("wi fi", QuickSettingsTile.matchedName("Wi-Fi,On", wifi))
        assertEquals("bluetooth", QuickSettingsTile.matchedName("Bluetooth. Off.", listOf("bluetooth")))
    }

    @Test
    fun otherLabelsDontMatch() {
        assertNull(QuickSettingsTile.matchedName("Wireless debugging", wifi))
        assertNull(QuickSettingsTile.matchedName("Bluetoothless", listOf("bluetooth")))
        assertNull(QuickSettingsTile.matchedName(null, wifi))
    }

    @Test
    fun switchStateWins() {
        assertEquals(true, QuickSettingsTile.state(true, "wi fi", "Wi-Fi, Off"))
        assertEquals(false, QuickSettingsTile.state(false, "wi fi", "Wi-Fi"))
    }

    @Test
    fun stateFromWords() {
        assertEquals(true, QuickSettingsTile.state(null, "wi fi", "Wi-Fi,On"))
        assertEquals(false, QuickSettingsTile.state(null, "bluetooth", "Bluetooth", "Off"))
        // The "not" in the name isn't read as a state.
        assertEquals(false, QuickSettingsTile.state(null, "do not disturb", "Do not disturb, Off"))
    }

    @Test
    fun unclearState() {
        assertNull(QuickSettingsTile.state(null, "wi fi", "Wi-Fi", "HomeNet"))
        assertNull(QuickSettingsTile.state(null, "wi fi", "Wi-Fi, On", "Off"))
    }
}
