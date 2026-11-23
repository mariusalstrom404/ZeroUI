package com.example.myapplication.nlp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FastPathParserTest {

    @Test
    fun testSystemControlsChineseAndEnglish() {
        // Screenshot
        val screenshotEn = FastPathParser.parse("take a screenshot")
        assertNotNull(screenshotEn)
        assertEquals(AppIntent.TakeScreenshot, screenshotEn!![0].intent)

        val screenshotZh = FastPathParser.parse("螢幕截圖")
        assertNotNull(screenshotZh)
        assertEquals(AppIntent.TakeScreenshot, screenshotZh!![0].intent)

        // Flashlight
        val flashOn = FastPathParser.parse("打開手電筒")
        assertNotNull(flashOn)
        assertEquals(AppIntent.Flashlight(true), flashOn!![0].intent)

        val flashOff = FastPathParser.parse("turn off flashlight")
        assertNotNull(flashOff)
        assertEquals(AppIntent.Flashlight(false), flashOff!![0].intent)

        // Volume
        val volUp = FastPathParser.parse("大聲一點")
        assertNotNull(volUp)
        assertEquals(AppIntent.Volume(AppIntent.Change.UP), volUp!![0].intent)

        val volDown = FastPathParser.parse("volume down")
        assertNotNull(volDown)
        assertEquals(AppIntent.Volume(AppIntent.Change.DOWN), volDown!![0].intent)

        // Battery
        val battery = FastPathParser.parse("檢查電量")
        assertNotNull(battery)
        assertEquals(AppIntent.Battery, battery!![0].intent)
    }

    @Test
    fun testHistoryAndGreeting() {
        val history = FastPathParser.parse("查看歷史操作")
        assertNotNull(history)
        assertEquals(AppIntent.ViewOperationHistory, history!![0].intent)

        val greeting = FastPathParser.parse("你好")
        assertNotNull(greeting)
        assertEquals(AppIntent.Greeting, greeting!![0].intent)
    }

    @Test
    fun testOpenAppAndComplexFallback() {
        val openApp = FastPathParser.parse("open YouTube")
        assertNotNull(openApp)
        assertEquals(AppIntent.OpenApp("YouTube"), openApp!![0].intent)

        val politeOpenZh = FastPathParser.parse("幫我打開 LINE。")
        assertNotNull(politeOpenZh)
        assertEquals(AppIntent.OpenApp("LINE"), politeOpenZh!![0].intent)

        // Built-in examples should hit fast-path
        assertEquals(AppIntent.Battery, FastPathParser.parse("Check my battery")?.single()?.intent)
        assertEquals(AppIntent.Flashlight(true), FastPathParser.parse("Turn on the flashlight")?.single()?.intent)
        assertEquals(AppIntent.Navigate("airport", null), FastPathParser.parse("Take me to the airport")?.single()?.intent)

        // Complex instruction with multiple steps should return null (fallback to LLM)
        val complex = FastPathParser.parse("open Foodpanda and search for pizza")
        assertNull(complex)
    }

    @Test
    fun testSiriLikeWeatherAlarmTimerAndTime() {
        // Weather
        assertEquals(AppIntent.Weather(null, 0), FastPathParser.parse("今天天氣如何？")?.single()?.intent)
        assertEquals(AppIntent.Weather("台北", 0), FastPathParser.parse("台北今天天氣怎麼樣")?.single()?.intent)
        assertEquals(AppIntent.Weather("高雄", 1), FastPathParser.parse("明天高雄天氣如何")?.single()?.intent)
        assertEquals(AppIntent.Weather("tokyo", 0), FastPathParser.parse("What's the weather in Tokyo?")?.single()?.intent)

        // Alarm
        assertEquals(AppIntent.SetAlarm(null, 0), FastPathParser.parse("幫我設鬧鐘")?.single()?.intent)
        assertEquals(AppIntent.SetAlarm(7, 30), FastPathParser.parse("幫我設早上七點半的鬧鐘")?.single()?.intent)
        assertEquals(AppIntent.SetAlarm(20, 15), FastPathParser.parse("設晚上8點15分鬧鐘")?.single()?.intent)
        assertEquals(AppIntent.SetAlarm(6, 45), FastPathParser.parse("Set an alarm for 6:45 am")?.single()?.intent)

        // Countdown timer
        assertEquals(AppIntent.SetTimer(300), FastPathParser.parse("倒計時5分鐘")?.single()?.intent)
        assertEquals(AppIntent.SetTimer(900), FastPathParser.parse("倒數十五分鐘")?.single()?.intent)
        assertEquals(AppIntent.SetTimer(90), FastPathParser.parse("Set a timer for 1 minute 30 seconds")?.single()?.intent)
        assertEquals(AppIntent.SetTimer(null), FastPathParser.parse("幫我倒計時")?.single()?.intent)

        // Current time
        assertEquals(AppIntent.CurrentTime, FastPathParser.parse("現在幾點了？")?.single()?.intent)
        assertEquals(AppIntent.CurrentTime, FastPathParser.parse("What time is it?")?.single()?.intent)

        // Explicit volume/brightness levels
        assertEquals(AppIntent.Volume(AppIntent.Change.SET, 50), FastPathParser.parse("音量調到50%")?.single()?.intent)
        assertEquals(AppIntent.Brightness(AppIntent.Change.SET, 80), FastPathParser.parse("Set brightness to 80")?.single()?.intent)
    }

    @Test
    fun testExecutionSpeedSubMillisecond() {
        val start = System.nanoTime()
        repeat(100) {
            FastPathParser.parse("螢幕截圖")
            FastPathParser.parse("turn on flashlight")
            FastPathParser.parse("查看歷史操作")
        }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000.0
        // 300 parses should take well under 10ms total (sub-millisecond per parse)
        assertTrue("300 local parses took ${elapsedMs}ms", elapsedMs < 20.0)
    }
}
