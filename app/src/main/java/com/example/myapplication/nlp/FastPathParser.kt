package com.example.myapplication.nlp

import java.util.Locale

/**
 * Fast-path local rule parser that converts high-frequency commands into structured [AppIntent] in <1ms.
 *
 * Strips polite prefixes ("幫我", "請", "please", "can you") and trailing punctuation so natural
 * speech — including the app's own built-in example prompts and Siri-like queries (weather, alarm,
 * countdown timer, current time) — executes immediately without a cloud LLM round-trip.
 */
object FastPathParser {

    private val TRAILING_PUNCT = charArrayOf('.', '!', '?', '。', '！', '？', ',', '，', '~', '～')

    private val LEADING_FILLER_ZH = Regex("^(?:請問|請|幫我|麻煩你|麻煩|可以幫我|能不能幫我|替我|我要|我想|給我|幫我把|把)\\s*")
    private val LEADING_FILLER_EN = Regex(
        "^(?:(?:hey|hi|ok|okay)\\s+zeroui[,\\s]+)?(?:(?:please|can you|could you|would you|will you|help me(?: to)?)\\s+)+",
        RegexOption.IGNORE_CASE
    )
    private val TRAILING_POLITE_EN = Regex("\\s+(?:please|for me|now)$", RegexOption.IGNORE_CASE)
    private val TRAILING_POLITE_ZH = Regex("(?:一下|好嗎|好不好|可以嗎|謝謝|吧)$")

    private val COMPLEX_KEYWORDS = listOf(" and ", " then ", "然後", "並且", "並且", "再幫我", "再", "順便")

    // Volume / Brightness with explicit level
    private val VOLUME_SET_EN = Regex("^(?:set\\s+)?(?:the\\s+)?volume\\s+(?:to\\s+)?(\\d{1,3})\\s*%?$")
    private val VOLUME_SET_ZH = Regex("^(?:音量|聲音)(?:調到|調成|設為|設定為|設成|到)\\s*(\\d{1,3})\\s*%?$")
    private val BRIGHTNESS_SET_EN = Regex("^(?:set\\s+)?(?:the\\s+)?(?:screen\\s+)?brightness\\s+(?:to\\s+)?(\\d{1,3})\\s*%?$")
    private val BRIGHTNESS_SET_ZH = Regex("^(?:螢幕)?亮度(?:調到|調成|設為|設定為|設成|到)\\s*(\\d{1,3})\\s*%?$")

    // Navigation
    private val NAV_EN = Regex("^(?:navigate|directions|take me|guide me|drive me)\\s+(?:to\\s+)?(.+)$", RegexOption.IGNORE_CASE)
    private val NAV_ZH = Regex("^(?:導航到|導航去|帶我去|怎麼去|路線到)\\s*(.+)$")

    // Click text
    private val CLICK_EN = Regex("^(?:tap|click|press)\\s+(?:on\\s+)?(.+)$", RegexOption.IGNORE_CASE)
    private val CLICK_ZH = Regex("^(?:點擊|點一下|按一下|按下|點)\\s*(.+)$")

    // Weather
    private val WEATHER_ZH = Regex("(天氣|氣溫|溫度|會不會下雨|下雨嗎)")
    private val WEATHER_EN = Regex("\\b(?:weather|temperature|forecast)\\b", RegexOption.IGNORE_CASE)

    // Alarm & Timer cues
    private val ALARM_ZH = Regex("(?:鬧鐘|闹钟)")
    private val ALARM_EN = Regex("\\balarms?\\b", RegexOption.IGNORE_CASE)
    private val TIMER_ZH = Regex("(?:倒計時|倒計时|倒數|倒数|計時器|计时器|計時|计时)")
    private val TIMER_EN = Regex("\\b(?:timer|countdown)\\b", RegexOption.IGNORE_CASE)

    /**
     * Attempts to parse [utterance] into a list of [LlmCommand] locally.
     * Returns null if no local rule matches, signaling that the query should be handled by LLM.
     */
    fun parse(utterance: String): List<LlmCommand>? {
        val raw = utterance.trim().trimEnd(*TRAILING_PUNCT).trim()
        if (raw.isEmpty()) return null
        val rawLower = raw.lowercase(Locale.ROOT)
        // Multi-action chained commands go to the LLM so they are split accurately.
        if (isChainedCommand(rawLower)) return null

        val cleaned = stripPolite(raw)
        val lower = cleaned.lowercase(Locale.ROOT)

        val intent: AppIntent? = when {
            // Screenshot
            matchAny(
                lower,
                "take a screenshot", "take screenshot", "screenshot", "capture screen",
                "截圖", "螢幕截圖", "屏幕截圖", "抓圖", "截個圖"
            ) -> AppIntent.TakeScreenshot

            // Flashlight
            matchAny(
                lower,
                "turn on flashlight", "turn on the flashlight", "flashlight on", "light on", "open flashlight",
                "打開手電筒", "開手電筒", "開啟手電筒", "開燈", "打開燈"
            ) -> AppIntent.Flashlight(true)

            matchAny(
                lower,
                "turn off flashlight", "turn off the flashlight", "flashlight off", "light off", "close flashlight",
                "關閉手電筒", "關手電筒", "關掉手電筒", "關燈", "關掉燈"
            ) -> AppIntent.Flashlight(false)

            // Battery
            matchAny(
                lower,
                "check battery", "check my battery", "check the battery", "battery", "battery level", "how much battery",
                "檢查電量", "剩餘電量", "電量", "看電量", "還有多少電", "手機電量", "電量多少"
            ) -> AppIntent.Battery

            // Current time & date (Siri-like instant query)
            matchAny(
                lower,
                "what time is it", "what's the time", "whats the time", "current time", "tell me the time",
                "what's the date", "what date is it", "what day is it", "today's date",
                "現在幾點", "現在幾點了", "幾點了", "現在時間", "今天幾號", "今天星期幾", "今天禮拜幾", "今天日期"
            ) -> AppIntent.CurrentTime

            // Volume
            matchAny(
                lower,
                "volume up", "turn up volume", "turn up the volume", "increase volume", "louder",
                "音量調高", "音量大一點", "大聲一點", "大聲點", "調高音量", "音量加", "聲音大一點"
            ) -> AppIntent.Volume(AppIntent.Change.UP)

            matchAny(
                lower,
                "volume down", "turn down volume", "turn down the volume", "decrease volume", "quieter",
                "音量調低", "音量小一點", "小聲一點", "小聲點", "調低音量", "音量減", "聲音小一點"
            ) -> AppIntent.Volume(AppIntent.Change.DOWN)

            // Brightness
            matchAny(
                lower,
                "brightness up", "increase brightness", "turn up brightness", "turn up the brightness", "brighter",
                "亮度調高", "亮一點", "亮點", "調高亮度", "螢幕亮一點"
            ) -> AppIntent.Brightness(AppIntent.Change.UP)

            matchAny(
                lower,
                "brightness down", "decrease brightness", "turn down brightness", "turn down the brightness", "dimmer",
                "亮度調低", "暗一點", "暗點", "調低亮度", "螢幕暗一點"
            ) -> AppIntent.Brightness(AppIntent.Change.DOWN)

            // Camera / Photo
            matchAny(lower, "take a photo", "take photo", "take a picture", "拍照", "照相") -> AppIntent.CameraAction(takePhoto = true)
            matchAny(lower, "open camera", "camera", "開啟相機", "打開相機", "開相機") -> AppIntent.CameraAction(takePhoto = false)

            // NCCUbank quick balance
            matchAny(
                lower,
                "check balance", "check my balance", "my balance", "wallet balance",
                "查餘額", "查詢餘額", "看餘額", "查看餘額", "餘額多少", "帳戶餘額"
            ) -> AppIntent.CheckBalance

            // Operation & Conversation History
            matchAny(
                lower,
                "show operation history", "operation history",
                "查看歷史操作", "操作紀錄", "歷史紀錄", "操作歷史"
            ) -> AppIntent.ViewOperationHistory
            matchAny(lower, "clear history", "清除歷史", "清除紀錄", "清空紀錄") -> AppIntent.ClearHistory
            matchAny(lower, "save history", "儲存歷史", "保存歷史") -> AppIntent.SaveHistory

            // Greeting & Intro
            matchAny(
                lower,
                "hi", "hello", "hey", "what can you do",
                "你好", "哈囉", "嗨", "你能做什麼", "你可以做什麼", "自我介紹"
            ) -> AppIntent.Greeting

            // Repeat
            matchAny(lower, "repeat", "repeat last", "one more time", "重做一次", "重複", "再做一次") -> AppIntent.RepeatLast

            else -> parseStructured(cleaned, lower)
        }

        return intent?.let { listOf(LlmCommand(it)) }
    }

    private fun parseStructured(text: String, lower: String): AppIntent? {
        // Explicit volume/brightness levels
        VOLUME_SET_EN.matchEntire(lower)?.groupValues?.get(1)?.toIntOrNull()?.let {
            return AppIntent.Volume(AppIntent.Change.SET, it.coerceIn(0, 100))
        }
        VOLUME_SET_ZH.matchEntire(text)?.groupValues?.get(1)?.toIntOrNull()?.let {
            return AppIntent.Volume(AppIntent.Change.SET, it.coerceIn(0, 100))
        }
        BRIGHTNESS_SET_EN.matchEntire(lower)?.groupValues?.get(1)?.toIntOrNull()?.let {
            return AppIntent.Brightness(AppIntent.Change.SET, it.coerceIn(0, 100))
        }
        BRIGHTNESS_SET_ZH.matchEntire(text)?.groupValues?.get(1)?.toIntOrNull()?.let {
            return AppIntent.Brightness(AppIntent.Change.SET, it.coerceIn(0, 100))
        }

        // Siri-like Weather / Alarm / Countdown Timer
        parseWeather(text, lower)?.let { return it }
        parseTimer(text, lower)?.let { return it }
        parseAlarm(text, lower)?.let { return it }

        // Simple Navigation (e.g. "Take me to the airport", "導航到台北車站")
        parseNavigate(text, lower)?.let { return it }

        // Explicit ClickText (e.g. "tap Settings", "點擊確定")
        parseClickText(text, lower)?.let { return it }

        // Simple App Launching (e.g. "open YouTube", "打開 Foodpanda")
        return parseOpenApp(text, lower)
    }

    private fun parseWeather(text: String, lower: String): AppIntent.Weather? {
        if (!WEATHER_ZH.containsMatchIn(text) && !WEATHER_EN.containsMatchIn(lower)) return null
        // Avoid catching app-opening like "open weather app"
        if (lower.startsWith("open ") || text.startsWith("打開") || text.startsWith("開啟")) return null

        val dayOffset = when {
            text.contains("後天") || text.contains("后天") || lower.contains("day after tomorrow") -> 2
            text.contains("明天") || text.contains("明日") || lower.contains("tomorrow") -> 1
            else -> 0
        }

        val location = extractWeatherLocation(text, lower)
        return AppIntent.Weather(location = location, dayOffset = dayOffset)
    }

    private fun extractWeatherLocation(text: String, lower: String): String? {
        // English: "weather in Tokyo", "what's the weather in New York tomorrow"
        val inMatch = Regex("\\b(?:in|at|for)\\s+([a-zA-Z\\s]{2,30}?)(?:\\s+(?:today|tomorrow|now|right now))?$", RegexOption.IGNORE_CASE)
            .find(lower)
        if (inMatch != null) {
            val loc = inMatch.groupValues[1].trim()
            if (loc.isNotEmpty() && loc !in setOf("today", "tomorrow", "the", "my area")) return loc
        }

        // Chinese: strip time & weather question phrases, see if a city name remains (e.g. "明天台北天氣如何" -> "台北")
        val stripped = text
            .replace(Regex("(?:今天|今日|現在|目前|明天|明日|後天|后天)"), "")
            .replace(Regex("(?:的天氣|天氣|氣溫|溫度|會不會下雨|下雨嗎|如何|怎麼樣|怎樣|好不好|呢|嗎|查|查詢|看|看一下)"), "")
            .replace(Regex("[的在]"), "")
            .trim()
        return stripped.takeIf { it.isNotEmpty() && it.length <= 16 }
    }

    private fun parseTimer(text: String, lower: String): AppIntent.SetTimer? {
        if (!TIMER_ZH.containsMatchIn(text) && !TIMER_EN.containsMatchIn(lower)) return null
        val seconds = FollowUpRules.parseDurationSeconds(text)
        return AppIntent.SetTimer(durationSeconds = seconds)
    }

    private fun parseAlarm(text: String, lower: String): AppIntent.SetAlarm? {
        if (!ALARM_ZH.containsMatchIn(text) && !ALARM_EN.containsMatchIn(lower)) return null
        // Avoid catching "open alarm/clock app"
        if (lower == "open alarm" || lower == "open clock") return null
        val parsedTime = FollowUpRules.parseAlarmTime(text)
        return AppIntent.SetAlarm(hour = parsedTime?.first, minute = parsedTime?.second ?: 0)
    }

    private fun parseNavigate(text: String, lower: String): AppIntent.Navigate? {
        val destRaw = NAV_EN.matchEntire(text)?.groupValues?.get(1)
            ?: NAV_ZH.matchEntire(text)?.groupValues?.get(1)
            ?: return null
        if (isComplexAppInstruction(lower)) return null
        val mode = FollowUpRules.travelMode(text)
        val cleanedDest = destRaw
            .replace(Regex("\\b(?:by|via)\\s+(?:bus|transit|walking|walk|driving|car)\\b", RegexOption.IGNORE_CASE), "")
            .replace(Regex("(?:搭公車|坐公車|走路|步行|開車)"), "")
            .replace(Regex("^the\\s+", RegexOption.IGNORE_CASE), "")
            .trim()
        if (cleanedDest.isEmpty() || cleanedDest.length > 50) return null
        return AppIntent.Navigate(destination = cleanedDest, mode = mode)
    }

    private fun parseClickText(text: String, lower: String): AppIntent.ClickText? {
        val target = CLICK_EN.matchEntire(text)?.groupValues?.get(1)
            ?: CLICK_ZH.matchEntire(text)?.groupValues?.get(1)
            ?: return null
        val clean = target.trim().trim('"', '“', '”', '「', '」')
        if (clean.isEmpty() || clean.length > 30 || isComplexAppInstruction(lower)) return null
        return AppIntent.ClickText(clean)
    }

    private fun parseOpenApp(text: String, lower: String): AppIntent? {
        val openPrefixesEn = listOf("open ", "launch ", "start ")
        for (prefix in openPrefixesEn) {
            if (lower.startsWith(prefix)) {
                val app = text.substring(prefix.length).trim()
                if (app.isNotEmpty() && !isComplexAppInstruction(lower)) {
                    return AppIntent.OpenApp(app)
                }
            }
        }
        val openPrefixesZh = listOf("開啟", "打開", "啟動", "開")
        for (prefix in openPrefixesZh) {
            if (text.startsWith(prefix)) {
                val app = text.substring(prefix.length).trim()
                if (app.isNotEmpty() && app.length <= 24 && !isComplexAppInstruction(lower)) {
                    return AppIntent.OpenApp(app)
                }
            }
        }
        return null
    }

    private fun stripPolite(text: String): String {
        var s = text.trim()
        var prev: String
        do {
            prev = s
            s = s.replace(LEADING_FILLER_EN, "").replace(LEADING_FILLER_ZH, "").trim()
        } while (s != prev && s.isNotEmpty())
        s = s.replace(TRAILING_POLITE_EN, "").replace(TRAILING_POLITE_ZH, "").trim()
        return s.ifEmpty { text }
    }

    private fun isChainedCommand(lower: String): Boolean =
        COMPLEX_KEYWORDS.any { lower.contains(it) }

    private fun isComplexAppInstruction(lower: String): Boolean {
        val complexKeywords = listOf(" and ", "然後", "並且", "搜尋", "搜索", "查", "幫我", " in ", " on ", "並", "找", "訂")
        return complexKeywords.any { lower.contains(it) }
    }

    private fun matchAny(text: String, vararg keywords: String): Boolean =
        keywords.any { text == it || text.replace(" ", "") == it.replace(" ", "") }
}
