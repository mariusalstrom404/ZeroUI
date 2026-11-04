package com.example.myapplication.nlp

import java.util.Locale

/**
 * Fast-path local rule parser that converts high-frequency commands into structured [AppIntent] in <1ms.
 */
object FastPathParser {

    /**
     * Attempts to parse [utterance] into a list of [LlmCommand] locally.
     * Returns null if no local rule matches, signaling that the query should be handled by LLM.
     */
    fun parse(utterance: String): List<LlmCommand>? {
        val text = utterance.trim()
        if (text.isEmpty()) return null
        val lower = text.lowercase(Locale.ROOT)

        val intent: AppIntent? = when {
            // Screenshot
            matchAny(lower, "take a screenshot", "screenshot", "截圖", "螢幕截圖", "抓圖") -> AppIntent.TakeScreenshot

            // Flashlight
            matchAny(lower, "turn on flashlight", "flashlight on", "打開手電筒", "開手電筒", "開啟手電筒", "開燈") -> AppIntent.Flashlight(true)
            matchAny(lower, "turn off flashlight", "flashlight off", "關閉手電筒", "關手電筒", "關燈") -> AppIntent.Flashlight(false)

            // Battery
            matchAny(lower, "check battery", "battery", "battery level", "檢查電量", "剩餘電量", "電量", "看電量") -> AppIntent.Battery

            // Volume
            matchAny(lower, "volume up", "turn up volume", "音量調高", "大聲一點", "大聲點", "調高音量", "音量加") -> AppIntent.Volume(AppIntent.Change.UP)
            matchAny(lower, "volume down", "turn down volume", "音量調低", "小聲一點", "小聲點", "調低音量", "音量減") -> AppIntent.Volume(AppIntent.Change.DOWN)

            // Brightness
            matchAny(lower, "brightness up", "increase brightness", "亮度調高", "亮一點", "亮點", "調高亮度") -> AppIntent.Brightness(AppIntent.Change.UP)
            matchAny(lower, "brightness down", "decrease brightness", "亮度調低", "暗一點", "暗點", "調低亮度") -> AppIntent.Brightness(AppIntent.Change.DOWN)

            // Camera / Photo
            matchAny(lower, "take a photo", "take photo", "拍照") -> AppIntent.CameraAction(takePhoto = true)
            matchAny(lower, "open camera", "camera", "開啟相機", "打開相機") -> AppIntent.CameraAction(takePhoto = false)

            // Operation & Conversation History
            matchAny(lower, "show operation history", "operation history", "查看歷史操作", "操作紀錄", "歷史紀錄", "操作歷史") -> AppIntent.ViewOperationHistory
            matchAny(lower, "clear history", "清除歷史", "清除紀錄") -> AppIntent.ClearHistory
            matchAny(lower, "save history", "儲存歷史", "保存歷史") -> AppIntent.SaveHistory

            // Greeting & Intro
            matchAny(lower, "hi", "hello", "hey", "你好", "嗨", "你能做什麼", "自我介紹", "what can you do") -> AppIntent.Greeting

            // Repeat
            matchAny(lower, "repeat", "repeat last", "one more time", "重做一次", "重複") -> AppIntent.RepeatLast

            // Simple App Launching (e.g. "open YouTube", "打開 Foodpanda")
            else -> parseOpenApp(text, lower)
        }

        return intent?.let { listOf(LlmCommand(it)) }
    }

    private fun parseOpenApp(text: String, lower: String): AppIntent? {
        val openPrefixesEn = listOf("open ", "launch ", "start ")
        for (prefix in openPrefixesEn) {
            if (lower.startsWith(prefix)) {
                val app = text.substring(prefix.length).trim()
                if (app.isNotEmpty() && !isComplexInstruction(lower)) {
                    return AppIntent.OpenApp(app)
                }
            }
        }
        val openPrefixesZh = listOf("開啟", "打開", "啟動")
        for (prefix in openPrefixesZh) {
            if (text.startsWith(prefix)) {
                val app = text.substring(prefix.length).trim()
                if (app.isNotEmpty() && !isComplexInstruction(lower)) {
                    return AppIntent.OpenApp(app)
                }
            }
        }
        return null
    }

    private fun isComplexInstruction(lower: String): Boolean {
        val complexKeywords = listOf(" and ", "然後", "並且", "搜尋", "搜索", "查", "幫我", " in ", " on ", "並")
        return complexKeywords.any { lower.contains(it) }
    }

    private fun matchAny(text: String, vararg keywords: String): Boolean =
        keywords.any { text == it || text.replace(" ", "") == it.replace(" ", "") }
}
