package com.example.myapplication.nlp

import java.util.Locale

/**
 * Understands plain system & daily commands on the phone without waiting for the LLM (<1ms):
 * screenshots, battery, volume, brightness, switching settings on and off, system actions
 * (home, back, recents, lock…), Settings pages, built-in apps, Chinese/English device controls,
 * and Siri-like everyday queries (weather, alarms, countdown timers, current time).
 *
 * It's deliberately strict for chains: the whole utterance has to be understood, or [parse]
 * returns null and the LLM gets it.
 */
object FastPathParser {

    /** At most this many commands in one utterance; longer chains go to the LLM. */
    private const val MAX_COMMANDS = 4

    /** The commands in [utterance], in order, or null when it isn't entirely fast-path commands. */
    fun parse(utterance: String): List<AppIntent>? {
        val raw = utterance.trim().trimEnd(*TRAILING_PUNCT).trim()
        if (raw.isEmpty()) return null

        // Check raw Chinese/Siri structured patterns before English normalization strips colons/Chinese fillers.
        if (!hasChainConnector(raw)) {
            singleRaw(raw)?.let { return listOf(it) }
        }

        val text = normalize(utterance)
        if (text.isEmpty()) return null
        // Whole first, so names containing "and" ("date and time settings") aren't split up.
        singleNormalized(text, raw)?.let { return listOf(it) }

        val parts = text.split(SEPARATOR).map { stripFillers(it) }.filter { it.isNotEmpty() }
        if (parts.isEmpty() || parts.size > MAX_COMMANDS) return null
        val intents = mutableListOf<AppIntent>()
        for (part in parts) {
            val previous = intents.lastOrNull()
            intents += singleNormalized(part, part)
                // "turn on wifi and bluetooth": the second part borrows the first one's verb.
                ?: (previous as? AppIntent.SystemToggle)?.let { toggle ->
                    SystemSetting.find(part)?.let { AppIntent.SystemToggle(it, toggle.enable) }
                }
                ?: return null
        }
        return intents
    }

    /** Raw-string matchers that rely on punctuation like ":" (6:45 am) or Chinese characters. */
    private fun singleRaw(raw: String): AppIntent? {
        val cleaned = stripPoliteZh(raw)
        val lower = cleaned.lowercase(Locale.ROOT)

        // Exact Chinese / bilingual high-frequency intents
        exactZhOrQuick(cleaned, lower)?.let { return it }

        // Explicit Chinese volume/brightness levels
        VOLUME_SET_ZH.matchEntire(cleaned)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 0..100 }?.let {
            return AppIntent.Volume(AppIntent.Change.SET, it)
        }
        BRIGHTNESS_SET_ZH.matchEntire(cleaned)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 0..100 }?.let {
            return AppIntent.Brightness(AppIntent.Change.SET, it)
        }

        // Siri-like Weather, Timer, Alarm (checked before generic openApp)
        parseWeather(cleaned, lower)?.let { return it }
        parseTimer(cleaned, lower)?.let { return it }
        parseAlarm(cleaned, lower)?.let { return it }

        // Navigation & ClickText
        parseNavigate(cleaned, lower)?.let { return it }
        parseClickText(cleaned, lower)?.let { return it }

        // Chinese OpenApp ("打開 LINE", "開啟 YouTube")
        parseOpenAppZh(cleaned, lower)?.let { return it }

        return null
    }

    /** One command on normalized English text, or null when not confident. */
    private fun singleNormalized(part: String, raw: String): AppIntent? =
        screenshot(part)
            ?: battery(part)
            ?: currentTime(part)
            ?: volume(part)
            ?: brightness(part)
            ?: systemAction(part)
            ?: settingsPage(part)
            ?: camera(part)
            ?: systemApp(part)
            ?: toggle(part)
            ?: quickEnglishMeta(part)
            ?: openAppEn(part, raw)

    // --- Normalising ---

    /**
     * Lowercase, punctuation and apostrophes dropped ("what's" → "whats"), hyphens as spaces,
     * "%" as "percent", articles and "my" removed, and polite openings and closings stripped.
     * Commas are kept: they separate commands.
     */
    fun normalize(utterance: String): String {
        val words = utterance.lowercase(Locale.ROOT)
            .replace("%", " percent")
            .replace(Regex("['’]"), "")
            .replace(Regex("[-_/]"), " ")
            .replace(Regex("[^\\p{L}\\p{N}, ]"), " ")
            .replace(",", " , ")
            .split(' ')
            .filter { it.isNotBlank() && it !in DROPPED_WORDS }
        return stripFillers(words.joinToString(" ").replace(" ,", ","))
    }

    private fun stripFillers(text: String): String {
        var result = text.trim().trim(',').trim()
        var changed = true
        while (changed) {
            changed = false
            if (result in PREFIXES || result in SUFFIXES) return ""
            for (prefix in PREFIXES) {
                if (result.startsWith("$prefix ")) {
                    result = result.removePrefix("$prefix ").trim()
                    changed = true
                }
            }
            for (suffix in SUFFIXES) {
                if (result.endsWith(" $suffix")) {
                    result = result.removeSuffix(" $suffix").trim()
                    changed = true
                }
            }
        }
        return result
    }

    private fun stripPoliteZh(text: String): String {
        var s = text.trim()
        var prev: String
        do {
            prev = s
            s = s.replace(LEADING_FILLER_EN, "").replace(LEADING_FILLER_ZH, "").trim()
        } while (s != prev && s.isNotEmpty())
        s = s.replace(TRAILING_POLITE_EN, "").replace(TRAILING_POLITE_ZH, "").trim()
        return s.ifEmpty { text }
    }

    private fun hasChainConnector(raw: String): Boolean {
        val lower = raw.lowercase(Locale.ROOT)
        return listOf(" and ", " then ", ", ", "然後", "並且", "順便").any { lower.contains(it) }
    }

    // --- English Normalized Matchers ---

    private fun screenshot(p: String): AppIntent? = AppIntent.TakeScreenshot.takeIf {
        p.matches(Regex("(?:(?:take|grab|capture|snap|make|get|do) )?(?:screenshot|screen shot|screen capture|screengrab|screen grab)(?: of (?:this|screen|this screen|this page))?")) ||
            p.matches(Regex("(?:capture|screenshot) (?:screen|this|this screen|this page|what is on screen)")) ||
            p.matches(Regex("(?:take|snap) (?:picture|photo) of (?:this )?screen"))
    }

    private fun battery(p: String): AppIntent? = AppIntent.Battery.takeIf {
        p.matches(Regex("(?:(?:whats|what is|hows|how is|check|show|show me|tell me|get) )?battery(?: level| percentage| status| life| left| charge)?")) ||
            p.matches(Regex("how much (?:battery|charge)(?: do i have| is left| left| have i got| remaining)?(?: left)?"))
    }

    private fun currentTime(p: String): AppIntent? = AppIntent.CurrentTime.takeIf {
        p in setOf(
            "what time is it", "whats time", "what is time", "current time", "tell me time",
            "whats date", "what date is it", "what day is it", "todays date"
        )
    }

    private fun volume(p: String): AppIntent? {
        val noun = "(?:volume|sound|media volume)"
        level(p, noun)?.let { return AppIntent.Volume(AppIntent.Change.SET, it) }
        return when {
            p.matches(Regex("(?:mute|silence)(?: $noun| phone| media| audio)?")) ->
                AppIntent.Volume(AppIntent.Change.SET, 0)
            p.matches(Regex("(?:max|maximum|full) $noun|$noun (?:to )?(?:max|maximum|full)")) ->
                AppIntent.Volume(AppIntent.Change.SET, 100)
            p.matches(Regex("$noun up|(?:turn|crank) $noun up|turn up $noun|(?:increase|raise|boost) $noun|louder|make it louder")) ->
                AppIntent.Volume(AppIntent.Change.UP)
            p.matches(Regex("$noun down|turn $noun down|turn down $noun|(?:decrease|lower|reduce) $noun|quieter|softer|make it quieter")) ->
                AppIntent.Volume(AppIntent.Change.DOWN)
            else -> null
        }
    }

    private fun brightness(p: String): AppIntent? {
        val noun = "(?:brightness|screen brightness|display brightness)"
        level(p, noun)?.let { return AppIntent.Brightness(AppIntent.Change.SET, it) }
        return when {
            p.matches(Regex("(?:max|maximum|full) $noun|$noun (?:to )?(?:max|maximum|full)")) ->
                AppIntent.Brightness(AppIntent.Change.SET, 100)
            p.matches(Regex("(?:min|minimum|lowest) $noun|$noun (?:to )?(?:min|minimum|lowest)")) ->
                AppIntent.Brightness(AppIntent.Change.SET, 0)
            p.matches(Regex("$noun up|turn $noun up|turn up $noun|(?:increase|raise|boost) $noun|brighter|make (?:screen|it|display) brighter|brighten (?:screen|display)")) ->
                AppIntent.Brightness(AppIntent.Change.UP)
            p.matches(Regex("$noun down|turn $noun down|turn down $noun|(?:decrease|lower|reduce|dim) $noun|dimmer|dim (?:screen|display)|make (?:screen|it|display) (?:dimmer|darker)")) ->
                AppIntent.Brightness(AppIntent.Change.DOWN)
            else -> null
        }
    }

    /** "set volume to 40 percent", "volume 40": the level, when it's 0–100. */
    private fun level(p: String, noun: String): Int? =
        Regex("(?:(?:set|turn|change|put) )?$noun(?: level)? (?:to |at )?(\\d{1,3})(?: percent)?")
            .matchEntire(p)?.groupValues?.get(1)?.toInt()?.takeIf { it in 0..100 }

    private fun systemAction(p: String): AppIntent? {
        val action = when {
            p.matches(Regex("(?:(?:go|go to|take me|take me to|return to|back to|show|open) )?(?:home|home screen|homescreen|launcher)")) ->
                SystemAction.HOME
            p.matches(Regex("(?:go )?back|(?:go|go to|return to) previous (?:screen|page)|press back(?: button)?")) ->
                SystemAction.BACK
            p.matches(Regex("(?:(?:show|show me|open|view|go to) )?(?:recent apps|recents|recent applications|app switcher|overview|multitasking)")) ->
                SystemAction.RECENTS
            p.matches(Regex("(?:(?:show|show me|open|check|pull down|view|expand|see) )?(?:notifications|notification shade|notification panel|notification bar)")) ->
                SystemAction.NOTIFICATIONS
            p.matches(Regex("(?:(?:show|open|pull down|expand) )?(?:quick settings|quick settings panel|control center|control centre)")) ->
                SystemAction.QUICK_SETTINGS
            p.matches(Regex("lock(?: screen| phone| device)?|(?:turn|switch) off screen|(?:put )?(?:phone|screen) to sleep")) ->
                SystemAction.LOCK_SCREEN
            p.matches(Regex("(?:(?:show|open) )?(?:power menu|power options|power dialog|shutdown menu|power button menu)")) ->
                SystemAction.POWER_MENU
            p.matches(Regex("(?:(?:toggle|enter|start|open|exit|use) )?split screen(?: mode)?")) ->
                SystemAction.SPLIT_SCREEN
            else -> return null
        }
        return AppIntent.SystemControl(action)
    }

    private fun settingsPage(p: String): AppIntent? {
        val open = "(?:(?:open|show|show me|go to|launch|take me to|bring up|start) )?"
        if (p.matches(Regex("${open}(?:settings|settings app|system settings|phone settings)"))) {
            return AppIntent.OpenSettings(SettingsPage.MAIN)
        }
        Regex("$open(.+?) (?:settings|setting|options|preferences)(?: page| screen| menu)?").matchEntire(p)?.let { m ->
            SettingsPage.find(m.groupValues[1])?.let { return AppIntent.OpenSettings(it) }
        }
        // Pages people name without saying "settings": "open about phone".
        Regex("(?:open|show|show me|go to|take me to|bring up) (.+?)(?: page| screen| menu)?").matchEntire(p)?.let { m ->
            SettingsPage.find(m.groupValues[1])
                ?.takeIf { it == SettingsPage.ABOUT_PHONE || it == SettingsPage.DEVELOPER }
                ?.let { return AppIntent.OpenSettings(it) }
        }
        return null
    }

    private fun camera(p: String): AppIntent? = when {
        p.matches(Regex("(?:open|launch|start|show) camera(?: app)?|camera")) -> AppIntent.CameraAction(takePhoto = false)
        p.matches(Regex("(?:take|snap) (?:photo|picture|pic|selfie)")) -> AppIntent.CameraAction(takePhoto = true)
        else -> null
    }

    private fun systemApp(p: String): AppIntent? {
        val m = Regex("(?:open|launch|start|go to|show|show me|run|bring up|take me to) (.+?)(?: app| application)?").matchEntire(p)
            ?: return null
        return SystemApp.find(m.groupValues[1])?.let { AppIntent.OpenSystemApp(it) }
    }

    /** "turn on wifi", "bluetooth off", "enable airplane mode", "toggle dark mode". */
    private fun toggle(p: String): AppIntent? {
        val (target, enable) = Regex("(?:turn|switch|set|put) (on|off) (.+)").matchEntire(p)?.let { it.groupValues[2] to (it.groupValues[1] == "on") }
            ?: Regex("(?:turn|switch|set|put) (.+) (on|off)").matchEntire(p)?.let { it.groupValues[1] to (it.groupValues[2] == "on") }
            ?: Regex("(?:enable|activate|start|use) (.+)").matchEntire(p)?.let { it.groupValues[1] to true }
            ?: Regex("(?:disable|deactivate|stop) (.+)").matchEntire(p)?.let { it.groupValues[1] to false }
            ?: Regex("toggle (.+)").matchEntire(p)?.let { it.groupValues[1] to null }
            ?: Regex("(.+) (on|off)").matchEntire(p)?.let { it.groupValues[1] to (it.groupValues[2] == "on") }
            ?: return null
        if (target in FLASHLIGHT) return enable?.let { AppIntent.Flashlight(it) }
        return SystemSetting.find(target)?.let { AppIntent.SystemToggle(it, enable) }
    }

    private fun quickEnglishMeta(p: String): AppIntent? = when (p) {
        "check balance", "balance", "wallet balance" -> AppIntent.CheckBalance
        "show operation history", "operation history" -> AppIntent.ViewOperationHistory
        "clear history" -> AppIntent.ClearHistory
        "save history" -> AppIntent.SaveHistory
        "hi", "hello", "what can you do" -> AppIntent.Greeting
        "repeat", "repeat last", "one more time" -> AppIntent.RepeatLast
        else -> null
    }

    /** Fast-path simple English app launch ("open YouTube") while letting ambiguous phrases ("open the latest email") go to LLM. */
    private fun openAppEn(normalized: String, raw: String): AppIntent? {
        val m = Regex("^(?:open|launch|start)\\s+(.+)$", RegexOption.IGNORE_CASE).matchEntire(stripPoliteZh(raw))
            ?: return null
        val appName = m.groupValues[1].trim()
        val lowerName = appName.lowercase(Locale.ROOT)
        if (appName.isEmpty() || appName.length > 28) return null
        if (lowerName.split(' ').size > 3) return null
        if (NON_APP_WORDS.any { Regex("\\b$it\\b").containsMatchIn(lowerName) }) return null
        if (isComplexAppInstruction(normalized)) return null
        return AppIntent.OpenApp(appName)
    }

    // --- Chinese & Siri-like Structured Matchers ---

    private fun exactZhOrQuick(text: String, lower: String): AppIntent? = when {
        matchAny(lower, "截圖", "螢幕截圖", "屏幕截圖", "抓圖", "截個圖") -> AppIntent.TakeScreenshot
        matchAny(lower, "打開手電筒", "開手電筒", "開啟手電筒", "開燈", "打開燈") -> AppIntent.Flashlight(true)
        matchAny(lower, "關閉手電筒", "關手電筒", "關掉手電筒", "關燈", "關掉燈") -> AppIntent.Flashlight(false)
        matchAny(lower, "檢查電量", "剩餘電量", "電量", "看電量", "還有多少電", "手機電量", "電量多少") -> AppIntent.Battery
        matchAny(lower, "現在幾點", "現在幾點了", "幾點了", "現在時間", "今天幾號", "今天星期幾", "今天禮拜幾", "今天日期") -> AppIntent.CurrentTime
        matchAny(lower, "音量調高", "音量大一點", "大聲一點", "大聲點", "調高音量", "音量加", "聲音大一點") -> AppIntent.Volume(AppIntent.Change.UP)
        matchAny(lower, "音量調低", "音量小一點", "小聲一點", "小聲點", "調低音量", "音量減", "聲音小一點") -> AppIntent.Volume(AppIntent.Change.DOWN)
        matchAny(lower, "亮度調高", "亮一點", "亮點", "調高亮度", "螢幕亮一點") -> AppIntent.Brightness(AppIntent.Change.UP)
        matchAny(lower, "亮度調低", "暗一點", "暗點", "調低亮度", "螢幕暗一點") -> AppIntent.Brightness(AppIntent.Change.DOWN)
        matchAny(lower, "拍照", "照相") -> AppIntent.CameraAction(takePhoto = true)
        matchAny(lower, "開啟相機", "打開相機", "開相機") -> AppIntent.CameraAction(takePhoto = false)
        matchAny(lower, "查餘額", "查詢餘額", "看餘額", "查看餘額", "餘額多少", "帳戶餘額") -> AppIntent.CheckBalance
        matchAny(lower, "查看歷史操作", "操作紀錄", "歷史紀錄", "操作歷史") -> AppIntent.ViewOperationHistory
        matchAny(lower, "清除歷史", "清除紀錄", "清空紀錄") -> AppIntent.ClearHistory
        matchAny(lower, "儲存歷史", "保存歷史") -> AppIntent.SaveHistory
        matchAny(lower, "你好", "哈囉", "嗨", "你能做什麼", "你可以做什麼", "自我介紹") -> AppIntent.Greeting
        matchAny(lower, "重做一次", "重複", "再做一次") -> AppIntent.RepeatLast
        matchAny(lower, "回首頁", "回到桌面", "回桌面", "主畫面") -> AppIntent.SystemControl(SystemAction.HOME)
        matchAny(lower, "返回", "回上一頁", "上一頁") -> AppIntent.SystemControl(SystemAction.BACK)
        matchAny(lower, "鎖定螢幕", "鎖屏", "關螢幕") -> AppIntent.SystemControl(SystemAction.LOCK_SCREEN)
        else -> null
    }

    private fun parseWeather(text: String, lower: String): AppIntent.Weather? {
        if (!WEATHER_ZH.containsMatchIn(text) && !WEATHER_EN.containsMatchIn(lower)) return null
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
        val inMatch = Regex("\\b(?:in|at|for)\\s+([a-zA-Z\\s]{2,30}?)(?:\\s+(?:today|tomorrow|now|right now))?$", RegexOption.IGNORE_CASE)
            .find(lower)
        if (inMatch != null) {
            val loc = inMatch.groupValues[1].trim()
            if (loc.isNotEmpty() && loc !in setOf("today", "tomorrow", "the", "my area")) return loc
        }
        val stripped = text
            .replace(Regex("(?:今天|今日|現在|目前|明天|明日|後天|后天)"), "")
            .replace(Regex("(?:的天氣|天氣|氣溫|溫度|會不會下雨|下雨嗎|如何|怎麼樣|怎樣|好不好|呢|嗎|查|查詢|看|看一下)"), "")
            .replace(Regex("[的在]"), "")
            .trim()
        return stripped.takeIf { it.isNotEmpty() && it.length <= 16 && it.any { ch -> ch.code > 0x2E80 } }
    }

    private fun parseTimer(text: String, lower: String): AppIntent.SetTimer? {
        if (!TIMER_ZH.containsMatchIn(text) && !TIMER_EN.containsMatchIn(lower)) return null
        if (lower.startsWith("open ") || lower.startsWith("show ")) return null
        val seconds = FollowUpRules.parseDurationSeconds(text)
        return AppIntent.SetTimer(durationSeconds = seconds)
    }

    private fun parseAlarm(text: String, lower: String): AppIntent.SetAlarm? {
        if (!ALARM_ZH.containsMatchIn(text) && !ALARM_EN.containsMatchIn(lower)) return null
        // Let "open clock" / "show my alarms" be handled by OpenSystemApp(CLOCK).
        if (lower.startsWith("open ") || lower.startsWith("show ")) return null
        val parsedTime = FollowUpRules.parseAlarmTime(text)
        return AppIntent.SetAlarm(hour = parsedTime?.first, minute = parsedTime?.second ?: 0)
    }

    private fun parseNavigate(text: String, lower: String): AppIntent.Navigate? {
        // Don't hijack "take me to settings/home/about phone/gallery/..."
        if (lower.startsWith("take me to ")) {
            val tail = lower.removePrefix("take me to ").trim()
            if (tail in setOf("home", "home screen", "settings") ||
                tail.endsWith(" settings") ||
                SettingsPage.find(tail) != null ||
                SystemApp.find(tail) != null
            ) return null
        }
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
        // Don't hijack "press back"
        if (lower == "press back" || lower == "press back button") return null
        val target = CLICK_EN.matchEntire(text)?.groupValues?.get(1)
            ?: CLICK_ZH.matchEntire(text)?.groupValues?.get(1)
            ?: return null
        val clean = target.trim().trim('"', '“', '”', '「', '」')
        if (clean.isEmpty() || clean.length > 30 || isComplexAppInstruction(lower)) return null
        return AppIntent.ClickText(clean)
    }

    private fun parseOpenAppZh(text: String, lower: String): AppIntent? {
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

    private fun isComplexAppInstruction(lower: String): Boolean {
        val complexKeywords = listOf(" and ", "然後", "並且", "搜尋", "搜索", "查", "幫我", " in ", " on ", "並", "找", "訂")
        return complexKeywords.any { lower.contains(it) }
    }

    private fun matchAny(text: String, vararg keywords: String): Boolean =
        keywords.any { text == it || text.replace(" ", "") == it.replace(" ", "") }

    // --- Constants ---

    private val TRAILING_PUNCT = charArrayOf('.', '!', '?', '。', '！', '？', ',', '，', '~', '～')
    private val SEPARATOR = Regex("\\s*,\\s*(?:(?:and then|and also|then|and|also)\\s+)?|\\s+(?:and then|and also|then|and|also)\\s+")
    private val DROPPED_WORDS = setOf("the", "my", "a", "an", "your")
    private val FLASHLIGHT = setOf("flashlight", "flash light", "torch", "flash")
    private val NON_APP_WORDS = setOf(
        "latest", "last", "first", "next", "new", "my", "the", "this", "that",
        "email", "mail", "message", "file", "document", "link", "page", "tab", "video", "song", "photo"
    )

    private val LEADING_FILLER_ZH = Regex("^(?:請問|請|幫我|麻煩你|麻煩|可以幫我|能不能幫我|替我|我要|我想|給我|幫我把|把)\\s*")
    private val LEADING_FILLER_EN = Regex(
        "^(?:(?:hey|hi|ok|okay)\\s+zeroui[,\\s]+)?(?:(?:please|can you|could you|would you|will you|help me(?: to)?)\\s+)+",
        RegexOption.IGNORE_CASE
    )
    private val TRAILING_POLITE_EN = Regex("\\s+(?:please|for me|now|right now|thanks|thank you)$", RegexOption.IGNORE_CASE)
    private val TRAILING_POLITE_ZH = Regex("(?:一下|好嗎|好不好|可以嗎|謝謝|吧)$")

    private val VOLUME_SET_ZH = Regex("^(?:音量|聲音)(?:調到|調成|設為|設定為|設成|到)\\s*(\\d{1,3})\\s*%?$")
    private val BRIGHTNESS_SET_ZH = Regex("^(?:螢幕)?亮度(?:調到|調成|設為|設定為|設成|到)\\s*(\\d{1,3})\\s*%?$")
    private val NAV_EN = Regex("^(?:navigate|directions|take me|guide me|drive me)\\s+(?:to\\s+)?(.+)$", RegexOption.IGNORE_CASE)
    private val NAV_ZH = Regex("^(?:導航到|導航去|帶我去|怎麼去|路線到)\\s*(.+)$")
    private val CLICK_EN = Regex("^(?:tap|click|press)\\s+(?:on\\s+)?(.+)$", RegexOption.IGNORE_CASE)
    private val CLICK_ZH = Regex("^(?:點擊|點一下|按一下|按下|點)\\s*(.+)$")
    private val WEATHER_ZH = Regex("(天氣|氣溫|溫度|會不會下雨|下雨嗎)")
    private val WEATHER_EN = Regex("\\b(?:weather|temperature|forecast)\\b", RegexOption.IGNORE_CASE)
    private val ALARM_ZH = Regex("(?:鬧鐘|闹钟)")
    private val ALARM_EN = Regex("\\balarms?\\b", RegexOption.IGNORE_CASE)
    private val TIMER_ZH = Regex("(?:倒計時|倒計时|倒數|倒数|計時器|计时器|計時|计时)")
    private val TIMER_EN = Regex("\\b(?:timer|countdown)\\b", RegexOption.IGNORE_CASE)

    /** Longest first, so "can you please" goes before "can you". */
    private val PREFIXES = listOf(
        "hey zeroui", "hi zeroui", "ok zeroui", "okay zeroui", "zeroui",
        "can you please", "could you please", "would you please", "will you please",
        "can you", "could you", "would you", "will you", "can u", "could u",
        "i want to", "i wanna", "i would like to", "id like to", "i need to", "i need you to",
        "please", "help me", "lets", "just", "quickly", "now", "ok", "okay", "hey"
    ).sortedByDescending { it.length }

    private val SUFFIXES = listOf(
        "please", "for me", "now", "right now", "thanks", "thank you", "real quick", "quickly"
    ).sortedByDescending { it.length }
}
