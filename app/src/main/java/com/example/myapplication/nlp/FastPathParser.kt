package com.example.myapplication.nlp

/**
 * Understands plain system commands on the phone, without the LLM: screenshots, battery, volume,
 * brightness, switching settings on and off, system actions (home, back, recents, lock…),
 * Settings pages and built-in apps. Such commands then run in milliseconds instead of waiting
 * seconds for the language service, and still work offline.
 *
 * It's deliberately strict: the whole utterance has to be understood, or [parse] returns null
 * and the LLM gets it. "turn on wifi and open the calculator" is two fast commands, but
 * "turn on wifi and message Bob" goes to the LLM as a whole, so a chain is never half
 * understood. Anything ambiguous ("turn it up", "toggle the flashlight") also goes to the LLM.
 */
object FastPathParser {

    /** At most this many commands in one utterance; longer chains go to the LLM. */
    private const val MAX_COMMANDS = 4

    /** The commands in [utterance], in order, or null when it isn't entirely system commands. */
    fun parse(utterance: String): List<AppIntent>? {
        val text = normalize(utterance)
        if (text.isEmpty()) return null
        // Whole first, so names containing "and" ("date and time settings") aren't split up.
        single(text)?.let { return listOf(it) }
        val parts = text.split(SEPARATOR).map { stripFillers(it) }.filter { it.isNotEmpty() }
        if (parts.isEmpty() || parts.size > MAX_COMMANDS) return null
        val intents = mutableListOf<AppIntent>()
        for (part in parts) {
            val previous = intents.lastOrNull()
            intents += single(part)
                // "turn on wifi and bluetooth": the second part borrows the first one's verb.
                ?: (previous as? AppIntent.SystemToggle)?.let { toggle ->
                    SystemSetting.find(part)?.let { AppIntent.SystemToggle(it, toggle.enable) }
                }
                ?: return null
        }
        return intents
    }

    /** One command, or null when [part] isn't one this parser is sure about. */
    private fun single(part: String): AppIntent? =
        screenshot(part)
            ?: battery(part)
            ?: volume(part)
            ?: brightness(part)
            ?: systemAction(part)
            ?: settingsPage(part)
            ?: camera(part)
            ?: systemApp(part)
            ?: toggle(part)

    // --- Normalising ---

    /**
     * Lowercase, punctuation and apostrophes dropped ("what's" → "whats"), hyphens as spaces,
     * "%" as "percent", articles and "my" removed, and polite openings and closings stripped.
     * Commas are kept: they separate commands.
     */
    fun normalize(utterance: String): String {
        val words = utterance.lowercase()
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

    // --- Matchers ---

    private fun screenshot(p: String): AppIntent? = AppIntent.TakeScreenshot.takeIf {
        p.matches(Regex("(?:(?:take|grab|capture|snap|make|get|do) )?(?:screenshot|screen shot|screen capture|screengrab|screen grab)(?: of (?:this|screen|this screen|this page))?")) ||
            p.matches(Regex("(?:capture|screenshot) (?:screen|this|this screen|this page|what is on screen)")) ||
            p.matches(Regex("(?:take|snap) (?:picture|photo) of (?:this )?screen"))
    }

    private fun battery(p: String): AppIntent? = AppIntent.Battery.takeIf {
        p.matches(Regex("(?:(?:whats|what is|hows|how is|check|show|show me|tell me|get) )?battery(?: level| percentage| status| life| left| charge)?")) ||
            p.matches(Regex("how much (?:battery|charge)(?: do i have| is left| left| have i got| remaining)?(?: left)?"))
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

    private val SEPARATOR = Regex("\\s*,\\s*(?:(?:and then|and also|then|and|also)\\s+)?|\\s+(?:and then|and also|then|and|also)\\s+")

    private val DROPPED_WORDS = setOf("the", "my", "a", "an", "your")

    private val FLASHLIGHT = setOf("flashlight", "flash light", "torch", "flash")

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
