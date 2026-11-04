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

        // Complex instruction with multiple steps should return null (fallback to LLM)
        val complex = FastPathParser.parse("open Foodpanda and search for pizza")
        assertNull(complex)
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
