package com.example.myapplication.ui

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray

/**
 * Process-wide user preferences shared across MainActivity, SpeechManager, Speaker, and
 * MyAccessibilityService overlays. Backed by SharedPreferences with StateFlows so UI and
 * overlays stay in sync immediately.
 */
object UserPreferences {

    private const val PREF_NAME = "zeroui_user_prefs"
    private const val KEY_TTS_MUTED = "tts_muted"
    private const val KEY_SPEECH_RATE = "speech_rate"
    private const val KEY_SPEECH_LOCALE = "speech_locale"
    private const val KEY_FLOATING_VISIBLE = "floating_visible"
    private const val KEY_FLOATING_AUTO_DIM = "floating_auto_dim"
    private const val KEY_FLOATING_RIGHT = "floating_is_right"
    private const val KEY_FLOATING_Y = "floating_y_px"
    private const val KEY_FLOATING_SAVED = "floating_pos_saved"
    private const val KEY_PINNED_COMMANDS = "pinned_commands"
    private const val KEY_APP_MEMORY_ENABLED = "app_memory_enabled"
    private const val KEY_TAP_HIGHLIGHT_ENABLED = "tap_highlight_enabled"

    const val LOCALE_AUTO = "auto"
    const val LOCALE_EN_US = "en-US"
    const val LOCALE_ZH_TW = "zh-TW"

    private var prefs: SharedPreferences? = null

    private val _ttsMuted = MutableStateFlow(false)
    val ttsMuted: StateFlow<Boolean> = _ttsMuted

    private val _speechRate = MutableStateFlow(1.0f)
    val speechRate: StateFlow<Float> = _speechRate

    private val _speechLocaleTag = MutableStateFlow(LOCALE_AUTO)
    val speechLocaleTag: StateFlow<String> = _speechLocaleTag

    private val _floatingVisible = MutableStateFlow(true)
    val floatingVisible: StateFlow<Boolean> = _floatingVisible

    private val _floatingAutoDim = MutableStateFlow(true)
    val floatingAutoDim: StateFlow<Boolean> = _floatingAutoDim

    private val _pinnedCommands = MutableStateFlow<List<String>>(emptyList())
    val pinnedCommands: StateFlow<List<String>> = _pinnedCommands

    private val _appMemoryEnabled = MutableStateFlow(true)
    val appMemoryEnabled: StateFlow<Boolean> = _appMemoryEnabled

    private val _tapHighlightEnabled = MutableStateFlow(true)
    val tapHighlightEnabled: StateFlow<Boolean> = _tapHighlightEnabled

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs = p
        _ttsMuted.value = p.getBoolean(KEY_TTS_MUTED, false)
        _speechRate.value = p.getFloat(KEY_SPEECH_RATE, 1.0f)
        _speechLocaleTag.value = p.getString(KEY_SPEECH_LOCALE, LOCALE_AUTO) ?: LOCALE_AUTO
        _floatingVisible.value = p.getBoolean(KEY_FLOATING_VISIBLE, true)
        _floatingAutoDim.value = p.getBoolean(KEY_FLOATING_AUTO_DIM, true)
        _pinnedCommands.value = loadPinned(p)
        _appMemoryEnabled.value = p.getBoolean(KEY_APP_MEMORY_ENABLED, true)
        _tapHighlightEnabled.value = p.getBoolean(KEY_TAP_HIGHLIGHT_ENABLED, true)
    }

    fun setAppMemoryEnabled(enabled: Boolean) {
        _appMemoryEnabled.value = enabled
        prefs?.edit()?.putBoolean(KEY_APP_MEMORY_ENABLED, enabled)?.apply()
    }

    fun setTapHighlightEnabled(enabled: Boolean) {
        _tapHighlightEnabled.value = enabled
        prefs?.edit()?.putBoolean(KEY_TAP_HIGHLIGHT_ENABLED, enabled)?.apply()
    }

    fun setTtsMuted(muted: Boolean) {
        _ttsMuted.value = muted
        prefs?.edit()?.putBoolean(KEY_TTS_MUTED, muted)?.apply()
    }

    fun toggleTtsMuted(): Boolean {
        val next = !_ttsMuted.value
        setTtsMuted(next)
        return next
    }

    fun setSpeechRate(rate: Float) {
        val clamped = rate.coerceIn(0.7f, 1.5f)
        _speechRate.value = clamped
        prefs?.edit()?.putFloat(KEY_SPEECH_RATE, clamped)?.apply()
    }

    fun setSpeechLocaleTag(tag: String) {
        _speechLocaleTag.value = tag
        prefs?.edit()?.putString(KEY_SPEECH_LOCALE, tag)?.apply()
    }

    fun setFloatingVisible(visible: Boolean) {
        _floatingVisible.value = visible
        prefs?.edit()?.putBoolean(KEY_FLOATING_VISIBLE, visible)?.apply()
    }

    fun setFloatingAutoDim(enabled: Boolean) {
        _floatingAutoDim.value = enabled
        prefs?.edit()?.putBoolean(KEY_FLOATING_AUTO_DIM, enabled)?.apply()
    }

    fun saveFloatingPosition(isRightEdge: Boolean, yPx: Int) {
        prefs?.edit()
            ?.putBoolean(KEY_FLOATING_SAVED, true)
            ?.putBoolean(KEY_FLOATING_RIGHT, isRightEdge)
            ?.putInt(KEY_FLOATING_Y, yPx)
            ?.apply()
    }

    /** Returns saved `(isRightEdge, yPx)` or null if the user hasn't dragged the button yet. */
    fun getFloatingPosition(): Pair<Boolean, Int>? {
        val p = prefs ?: return null
        if (!p.getBoolean(KEY_FLOATING_SAVED, false)) return null
        return p.getBoolean(KEY_FLOATING_RIGHT, true) to p.getInt(KEY_FLOATING_Y, 0)
    }

    fun clearFloatingPosition() {
        prefs?.edit()
            ?.remove(KEY_FLOATING_SAVED)
            ?.remove(KEY_FLOATING_RIGHT)
            ?.remove(KEY_FLOATING_Y)
            ?.apply()
    }

    fun togglePinnedCommand(command: String) {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return
        val current = _pinnedCommands.value.toMutableList()
        if (current.any { it.equals(trimmed, ignoreCase = true) }) {
            current.removeAll { it.equals(trimmed, ignoreCase = true) }
        } else {
            current.add(0, trimmed)
            if (current.size > 10) current.subList(10, current.size).clear()
        }
        _pinnedCommands.value = current
        savePinned(current)
    }

    fun isPinned(command: String): Boolean =
        _pinnedCommands.value.any { it.equals(command.trim(), ignoreCase = true) }

    private fun savePinned(list: List<String>) {
        val array = JSONArray()
        list.forEach { array.put(it) }
        prefs?.edit()?.putString(KEY_PINNED_COMMANDS, array.toString())?.apply()
    }

    private fun loadPinned(p: SharedPreferences): List<String> {
        val raw = p.getString(KEY_PINNED_COMMANDS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            List(arr.length()) { arr.getString(it) }.filter { it.isNotBlank() }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
