package com.example.myapplication.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppMatcherTest {

    private val apps = listOf(
        InstalledApp("Google", "com.google.android.googlequicksearchbox"),
        InstalledApp("Drive", "com.google.android.apps.docs"),
        InstalledApp("WhatsApp", "com.whatsapp"),
        InstalledApp("YouTube", "com.google.android.youtube"),
        InstalledApp("Spotify", "com.spotify.music"),
        InstalledApp("Chrome", "com.android.chrome"),
        InstalledApp("Chrome Beta", "com.chrome.beta"),
        InstalledApp("地圖", "com.google.android.apps.maps"),
        InstalledApp("Booking.com", "com.booking"),
        InstalledApp("foodpanda", "com.global.foodpanda.android"),
        InstalledApp("NCCU Bank", "com.example.nccubank")
    )

    private fun pkg(name: String) = AppMatcher.best(name, apps)?.packageName

    @Test
    fun brandPrefixDropped() = assertEquals("com.google.android.apps.docs", pkg("Google Drive"))

    @Test
    fun brandAloneStillOpensBrandApp() = assertEquals("com.google.android.googlequicksearchbox", pkg("google"))

    @Test
    fun spacingIgnored() {
        assertEquals("com.whatsapp", pkg("whats app"))
        assertEquals("com.google.android.youtube", pkg("You Tube"))
    }

    @Test
    fun fillerWordsIgnored() = assertEquals("com.spotify.music", pkg("the Spotify app"))

    @Test
    fun speechTypoTolerated() = assertEquals("com.spotify.music", pkg("spottify"))

    @Test
    fun exactBeatsLonger() = assertEquals("com.android.chrome", pkg("chrome"))

    @Test
    fun localisedLabelFoundByPackage() = assertEquals("com.google.android.apps.maps", pkg("Google Maps"))

    @Test
    fun domainAndCase() {
        assertEquals("com.booking", pkg("booking.com"))
        assertEquals("com.booking", pkg("Booking"))
        assertEquals("com.global.foodpanda.android", pkg("Foodpanda"))
    }

    @Test
    fun ownBankApp() {
        assertEquals("com.example.nccubank", pkg("NCCUbank"))
        assertEquals("com.example.nccubank", pkg("nccu"))
    }

    @Test
    fun unknownAppNotGuessed() {
        assertNull(pkg("Deliveroo"))
        assertNull(pkg("Telegram"))
    }
}
