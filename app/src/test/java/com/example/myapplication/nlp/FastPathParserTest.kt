package com.example.myapplication.nlp

import com.example.myapplication.nlp.AppIntent.Change
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FastPathParserTest {

    private fun parse(text: String) = FastPathParser.parse(text)

    private fun assertOne(expected: AppIntent, vararg utterances: String) {
        for (utterance in utterances) assertEquals(utterance, listOf(expected), parse(utterance))
    }

    private fun assertLlm(vararg utterances: String) {
        for (utterance in utterances) assertNull(utterance, parse(utterance))
    }

    @Test
    fun screenshot() = assertOne(
        AppIntent.TakeScreenshot,
        "take a screenshot", "Screenshot", "Take a screenshot, please.", "capture the screen",
        "Can you take a screen shot for me?", "grab a screenshot of this page"
    )

    @Test
    fun battery() = assertOne(
        AppIntent.Battery,
        "Check my battery", "what's my battery level", "battery", "How much battery do I have left?"
    )

    @Test
    fun volume() {
        assertOne(AppIntent.Volume(Change.UP), "volume up", "turn up the volume", "louder", "increase the volume")
        assertOne(AppIntent.Volume(Change.DOWN), "turn the volume down", "quieter", "lower the volume")
        assertOne(AppIntent.Volume(Change.SET, 40), "set volume to 40%", "volume 40", "set the volume to 40 percent")
        assertOne(AppIntent.Volume(Change.SET, 0), "mute", "mute the sound")
        assertOne(AppIntent.Volume(Change.SET, 100), "max volume")
    }

    @Test
    fun brightness() {
        assertOne(AppIntent.Brightness(Change.UP), "brightness up", "make the screen brighter", "increase brightness")
        assertOne(AppIntent.Brightness(Change.DOWN), "dim the screen", "lower the brightness", "dimmer")
        assertOne(AppIntent.Brightness(Change.SET, 70), "set brightness to 70%", "screen brightness 70")
        assertOne(AppIntent.Brightness(Change.SET, 100), "full brightness")
    }

    @Test
    fun levelOutOfRangeGoesToLlm() = assertLlm("set volume to 150")

    @Test
    fun flashlight() {
        assertOne(AppIntent.Flashlight(true), "turn on the flashlight", "torch on", "switch the flashlight on")
        assertOne(AppIntent.Flashlight(false), "turn off the torch", "flashlight off")
        // No way to know the current state here, so let the LLM handle it.
        assertLlm("toggle the flashlight")
    }

    @Test
    fun toggles() {
        assertOne(AppIntent.SystemToggle(SystemSetting.WIFI, true), "turn on wifi", "Turn on Wi-Fi", "enable wi-fi", "wifi on")
        assertOne(AppIntent.SystemToggle(SystemSetting.WIFI, false), "turn the wifi off", "disable WiFi")
        assertOne(AppIntent.SystemToggle(SystemSetting.BLUETOOTH, null), "toggle bluetooth")
        assertOne(AppIntent.SystemToggle(SystemSetting.AIRPLANE_MODE, true), "turn on airplane mode", "enable flight mode")
        assertOne(AppIntent.SystemToggle(SystemSetting.DO_NOT_DISTURB, true), "turn on do not disturb", "DND on")
        assertOne(AppIntent.SystemToggle(SystemSetting.DARK_MODE, false), "turn off dark mode", "disable dark theme")
        assertOne(AppIntent.SystemToggle(SystemSetting.AUTO_ROTATE, true), "turn on auto-rotate", "enable screen rotation")
        assertOne(AppIntent.SystemToggle(SystemSetting.LOCATION, false), "turn off location", "disable GPS")
        assertOne(AppIntent.SystemToggle(SystemSetting.MOBILE_DATA, false), "turn off mobile data")
        assertOne(AppIntent.SystemToggle(SystemSetting.HOTSPOT, true), "turn on the hotspot")
        assertOne(AppIntent.SystemToggle(SystemSetting.BATTERY_SAVER, true), "turn on battery saver", "enable power saving mode")
    }

    @Test
    fun systemActions() {
        assertOne(AppIntent.SystemControl(SystemAction.HOME), "go home", "home screen", "go to the home screen")
        assertOne(AppIntent.SystemControl(SystemAction.BACK), "go back", "back")
        assertOne(AppIntent.SystemControl(SystemAction.RECENTS), "show recent apps", "recents")
        assertOne(AppIntent.SystemControl(SystemAction.NOTIFICATIONS), "show my notifications", "open the notification shade")
        assertOne(AppIntent.SystemControl(SystemAction.QUICK_SETTINGS), "open quick settings")
        assertOne(AppIntent.SystemControl(SystemAction.LOCK_SCREEN), "lock the phone", "lock screen", "turn off the screen")
        assertOne(AppIntent.SystemControl(SystemAction.POWER_MENU), "show the power menu")
        assertOne(AppIntent.SystemControl(SystemAction.SPLIT_SCREEN), "split screen")
    }

    @Test
    fun settingsPages() {
        assertOne(AppIntent.OpenSettings(SettingsPage.MAIN), "open settings", "settings", "go to settings")
        assertOne(AppIntent.OpenSettings(SettingsPage.WIFI), "open wifi settings", "Wi-Fi settings")
        assertOne(AppIntent.OpenSettings(SettingsPage.BLUETOOTH), "show bluetooth settings")
        assertOne(AppIntent.OpenSettings(SettingsPage.DISPLAY), "open display settings")
        assertOne(AppIntent.OpenSettings(SettingsPage.ABOUT_PHONE), "open about phone", "show the about phone page")
        assertOne(AppIntent.OpenSettings(SettingsPage.DATE_TIME), "open date and time settings")
        assertOne(AppIntent.OpenSettings(SettingsPage.DEVELOPER), "open developer options")
    }

    @Test
    fun systemApps() {
        assertOne(AppIntent.OpenSystemApp(SystemApp.CALCULATOR), "open the calculator", "launch calculator app")
        assertOne(AppIntent.OpenSystemApp(SystemApp.PHONE), "open the dialer", "open phone")
        assertOne(AppIntent.OpenSystemApp(SystemApp.CLOCK), "show my alarms", "open clock")
        assertOne(AppIntent.OpenSystemApp(SystemApp.GALLERY), "open the gallery", "show my photos")
        assertOne(AppIntent.OpenSystemApp(SystemApp.MESSAGES), "open messages")
        assertOne(AppIntent.OpenSystemApp(SystemApp.PLAY_STORE), "open the play store")
        assertOne(AppIntent.CameraAction(takePhoto = false), "open the camera")
        assertOne(AppIntent.CameraAction(takePhoto = true), "take a selfie")
    }

    @Test
    fun politeWrappersAreIgnored() = assertOne(
        AppIntent.SystemToggle(SystemSetting.BLUETOOTH, true),
        "Hey ZeroUI, can you please turn on bluetooth for me?", "please enable bluetooth now"
    )

    @Test
    fun chainOfSystemCommands() {
        assertEquals(
            listOf(AppIntent.SystemToggle(SystemSetting.WIFI, true), AppIntent.OpenSystemApp(SystemApp.CALCULATOR)),
            parse("turn on wifi and open the calculator")
        )
        assertEquals(
            listOf(AppIntent.OpenSettings(SettingsPage.MAIN), AppIntent.OpenSettings(SettingsPage.ABOUT_PHONE)),
            parse("open settings and show the about phone page")
        )
        assertEquals(
            listOf(AppIntent.TakeScreenshot, AppIntent.SystemControl(SystemAction.HOME)),
            parse("take a screenshot, then go home")
        )
    }

    @Test
    fun sharedVerbCarriesOver() = assertEquals(
        listOf(AppIntent.SystemToggle(SystemSetting.WIFI, false), AppIntent.SystemToggle(SystemSetting.BLUETOOTH, false)),
        parse("turn off wifi and bluetooth")
    )

    @Test
    fun anythingElseGoesToLlm() = assertLlm(
        "",
        "open youtube",
        "open settings and turn on developer mode for me somehow",
        "turn on wifi and message Bob",
        "transfer 50 to Bob",
        "search for pizza on foodpanda",
        "turn it up",
        "turn on the lights in the kitchen",
        "book a hotel in Taipei",
        "open the latest email",
        "what's the weather"
    )

    @Test
    fun tooLongChainGoesToLlm() =
        assertLlm("go home, go back, go home, go back, go home")
}
