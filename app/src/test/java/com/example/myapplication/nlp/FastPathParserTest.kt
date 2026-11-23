package com.example.myapplication.nlp

import com.example.myapplication.nlp.AppIntent.Change
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        "Can you take a screen shot for me?", "grab a screenshot of this page", "螢幕截圖"
    )

    @Test
    fun battery() = assertOne(
        AppIntent.Battery,
        "Check my battery", "what's my battery level", "battery", "How much battery do I have left?", "檢查電量"
    )

    @Test
    fun volume() {
        assertOne(AppIntent.Volume(Change.UP), "volume up", "turn up the volume", "louder", "increase the volume", "大聲一點")
        assertOne(AppIntent.Volume(Change.DOWN), "turn the volume down", "quieter", "lower the volume", "volume down")
        assertOne(AppIntent.Volume(Change.SET, 40), "set volume to 40%", "volume 40", "set the volume to 40 percent", "音量調到40%")
        assertOne(AppIntent.Volume(Change.SET, 0), "mute", "mute the sound")
        assertOne(AppIntent.Volume(Change.SET, 100), "max volume")
    }

    @Test
    fun brightness() {
        assertOne(AppIntent.Brightness(Change.UP), "brightness up", "make the screen brighter", "increase brightness")
        assertOne(AppIntent.Brightness(Change.DOWN), "dim the screen", "lower the brightness", "dimmer")
        assertOne(AppIntent.Brightness(Change.SET, 70), "set brightness to 70%", "screen brightness 70", "亮度調到70%")
        assertOne(AppIntent.Brightness(Change.SET, 100), "full brightness")
    }

    @Test
    fun levelOutOfRangeGoesToLlm() = assertLlm("set volume to 150")

    @Test
    fun flashlight() {
        assertOne(AppIntent.Flashlight(true), "turn on the flashlight", "torch on", "switch the flashlight on", "打開手電筒")
        assertOne(AppIntent.Flashlight(false), "turn off the torch", "flashlight off", "turn off flashlight")
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
    fun testHistoryGreetingOpenAppAndNavigate() {
        assertOne(AppIntent.ViewOperationHistory, "查看歷史操作")
        assertOne(AppIntent.Greeting, "你好")
        assertOne(AppIntent.OpenApp("YouTube"), "open YouTube")
        assertOne(AppIntent.OpenApp("LINE"), "幫我打開 LINE。")
        assertOne(AppIntent.Navigate("airport", null), "Take me to the airport")
    }

    @Test
    fun testSiriLikeWeatherAlarmTimerAndTime() {
        assertOne(AppIntent.Weather(null, 0), "今天天氣如何？", "what's the weather")
        assertOne(AppIntent.Weather("台北", 0), "台北今天天氣怎麼樣")
        assertOne(AppIntent.Weather("高雄", 1), "明天高雄天氣如何")
        assertOne(AppIntent.Weather("tokyo", 0), "What's the weather in Tokyo?")

        assertOne(AppIntent.SetAlarm(null, 0), "幫我設鬧鐘")
        assertOne(AppIntent.SetAlarm(7, 30), "幫我設早上七點半的鬧鐘")
        assertOne(AppIntent.SetAlarm(20, 15), "設晚上8點15分鬧鐘")
        assertOne(AppIntent.SetAlarm(6, 45), "Set an alarm for 6:45 am")

        assertOne(AppIntent.SetTimer(300), "倒計時5分鐘")
        assertOne(AppIntent.SetTimer(900), "倒數十五分鐘")
        assertOne(AppIntent.SetTimer(90), "Set a timer for 1 minute 30 seconds")
        assertOne(AppIntent.SetTimer(null), "幫我倒計時")

        assertOne(AppIntent.CurrentTime, "現在幾點了？", "What time is it?")
    }

    @Test
    fun anythingElseGoesToLlm() = assertLlm(
        "",
        "open settings and turn on developer mode for me somehow",
        "turn on wifi and message Bob",
        "open Foodpanda and search for pizza",
        "transfer 50 to Bob",
        "search for pizza on foodpanda",
        "turn it up",
        "turn on the lights in the kitchen",
        "book a hotel in Taipei",
        "open the latest email"
    )

    @Test
    fun tooLongChainGoesToLlm() =
        assertLlm("go home, go back, go home, go back, go home")

    @Test
    fun testExecutionSpeedSubMillisecond() {
        val start = System.nanoTime()
        repeat(100) {
            assertNotNull(FastPathParser.parse("螢幕截圖"))
            assertNotNull(FastPathParser.parse("turn on flashlight"))
            assertNotNull(FastPathParser.parse("查看歷史操作"))
        }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000.0
        assertTrue("300 local parses took ${elapsedMs}ms", elapsedMs < 35.0)
    }
}
