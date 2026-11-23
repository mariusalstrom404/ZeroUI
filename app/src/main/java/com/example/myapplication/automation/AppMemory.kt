package com.example.myapplication.automation

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Privacy-scrubbed navigation insights learned from a completed [ScreenAgent] run in one app.
 * Typed text is never stored (always generalized to `<input>`), and sensitive/payment screens
 * are excluded before learning is recorded.
 */
data class AppLearning(
    val dismissLabels: List<String> = emptyList(),
    val searchEntries: List<String> = emptyList(),
    val playbook: String? = null,
    val userChoices: List<String> = emptyList()
)

data class AppHintEntry(
    val app: String,
    val dismissLabels: List<String> = emptyList(),
    val searchEntries: List<String> = emptyList(),
    val playbooks: List<String> = emptyList(),
    val choiceCounts: Map<String, Int> = emptyMap(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    /** User options selected at least twice in this app, surfaced as soft hints. */
    val frequentChoices: List<String>
        get() = choiceCounts.entries
            .filter { it.value >= AppMemoryStore.CHOICE_PROMOTION_THRESHOLD }
            .sortedByDescending { it.value }
            .map { it.key }
            .take(AppMemoryStore.MAX_PREFERENCES)
}

/**
 * Local, on-device store of lightweight per-app exploration hints (dismiss buttons for popups,
 * search entry points, generalized step playbooks, and repeated user option preferences).
 *
 * Designed so the core logic runs cleanly in JVM unit tests with or without a backing [file].
 */
class AppMemoryStore(
    private val file: File? = null,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private val lock = Any()
    private val entries = LinkedHashMap<String, AppHintEntry>()

    init {
        loadFromDisk()
    }

    fun record(app: String, learning: AppLearning) {
        val key = app.trim()
        if (key.isEmpty()) return

        val safeDismiss = learning.dismissLabels.mapNotNull { sanitizeLabel(it) }
        val safeSearch = learning.searchEntries.mapNotNull { sanitizeLabel(it) }
        val safePlaybook = learning.playbook?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_PLAYBOOK_CHARS }
        val safeChoices = learning.userChoices.mapNotNull { sanitizeLabel(it) }

        if (safeDismiss.isEmpty() && safeSearch.isEmpty() && safePlaybook == null && safeChoices.isEmpty()) return

        synchronized(lock) {
            val prev = entries[key] ?: AppHintEntry(key)
            val mergedDismiss = mergeDistinct(safeDismiss, prev.dismissLabels, MAX_DISMISS_LABELS)
            val mergedSearch = mergeDistinct(safeSearch, prev.searchEntries, MAX_SEARCH_ENTRIES)
            val mergedPlaybooks = mergeDistinct(listOfNotNull(safePlaybook), prev.playbooks, MAX_PLAYBOOKS)
            val mergedChoices = prev.choiceCounts.toMutableMap()
            for (choice in safeChoices) {
                mergedChoices[choice] = (mergedChoices[choice] ?: 0) + 1
            }
            // Keep top tracked choices bounded.
            val boundedChoices = mergedChoices.entries
                .sortedByDescending { it.value }
                .take(MAX_TRACKED_CHOICES)
                .associate { it.key to it.value }

            entries[key] = AppHintEntry(
                app = key,
                dismissLabels = mergedDismiss,
                searchEntries = mergedSearch,
                playbooks = mergedPlaybooks,
                choiceCounts = boundedChoices,
                updatedAt = clock()
            )
            trimAppsLocked()
            saveToDiskLocked()
        }
    }

    /** Formats concise hints for [app] to include in the LLM prompt, or null when nothing is learned yet. */
    fun hintsFor(app: String?): String? {
        val key = app?.trim().orEmpty()
        if (key.isEmpty()) return null
        val entry = synchronized(lock) { entries[key] } ?: return null
        val lines = mutableListOf<String>()
        if (entry.dismissLabels.isNotEmpty()) {
            lines += "Popup dismiss buttons seen here: ${entry.dismissLabels.joinToString(", ") { "\"$it\"" }}"
        }
        if (entry.searchEntries.isNotEmpty()) {
            lines += "Search entry controls seen here: ${entry.searchEntries.joinToString(", ") { "\"$it\"" }}"
        }
        if (entry.playbooks.isNotEmpty()) {
            lines += "Past successful navigation paths:"
            entry.playbooks.forEach { lines += "- $it" }
        }
        val frequent = entry.frequentChoices
        if (frequent.isNotEmpty()) {
            lines += "User often prefers: ${frequent.joinToString(", ") { "\"$it\"" }} (still ask if unsure or options differ)"
        }
        if (lines.isEmpty()) return null
        return lines.joinToString("\n").take(MAX_PROMPT_HINTS_CHARS)
    }

    fun entryFor(app: String): AppHintEntry? = synchronized(lock) { entries[app.trim()] }

    fun appCount(): Int = synchronized(lock) { entries.size }

    fun clear() {
        synchronized(lock) {
            entries.clear()
            try {
                file?.delete()
            } catch (_: Exception) {
            }
        }
    }

    private fun trimAppsLocked() {
        if (entries.size <= MAX_APPS) return
        val oldest = entries.values.sortedBy { it.updatedAt }.take(entries.size - MAX_APPS)
        oldest.forEach { entries.remove(it.app) }
    }

    private fun mergeDistinct(newest: List<String>, existing: List<String>, limit: Int): List<String> {
        val out = LinkedHashMap<String, String>()
        for (item in newest + existing) {
            val norm = item.lowercase()
            if (!out.containsKey(norm)) out[norm] = item
            if (out.size >= limit) break
        }
        return out.values.toList()
    }

    private fun loadFromDisk() {
        val f = file ?: return
        if (!f.exists()) return
        try {
            val root = JSONObject(f.readText())
            val arr = root.optJSONArray("apps") ?: return
            synchronized(lock) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val app = o.optString("app").trim()
                    if (app.isEmpty()) continue
                    val choicesObj = o.optJSONObject("choiceCounts")
                    val counts = mutableMapOf<String, Int>()
                    if (choicesObj != null) {
                        val keys = choicesObj.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            val v = choicesObj.optInt(k, 0)
                            if (v > 0) counts[k] = v
                        }
                    }
                    entries[app] = AppHintEntry(
                        app = app,
                        dismissLabels = o.optStringList("dismissLabels"),
                        searchEntries = o.optStringList("searchEntries"),
                        playbooks = o.optStringList("playbooks"),
                        choiceCounts = counts,
                        updatedAt = o.optLong("updatedAt", 0L)
                    )
                }
            }
        } catch (_: Exception) {
            // Corrupted cache can safely be rebuilt over time.
        }
    }

    private fun saveToDiskLocked() {
        val f = file ?: return
        try {
            val arr = JSONArray()
            for (e in entries.values) {
                val countsObj = JSONObject()
                e.choiceCounts.forEach { (k, v) -> countsObj.put(k, v) }
                arr.put(
                    JSONObject()
                        .put("app", e.app)
                        .put("dismissLabels", JSONArray(e.dismissLabels))
                        .put("searchEntries", JSONArray(e.searchEntries))
                        .put("playbooks", JSONArray(e.playbooks))
                        .put("choiceCounts", countsObj)
                        .put("updatedAt", e.updatedAt)
                )
            }
            val parent = f.parentFile
            if (parent != null && !parent.exists()) parent.mkdirs()
            val tmp = File(f.parentFile, "${f.name}.tmp")
            tmp.writeText(JSONObject().put("apps", arr).toString())
            if (!tmp.renameTo(f)) {
                f.writeText(JSONObject().put("apps", arr).toString())
                tmp.delete()
            }
        } catch (_: Exception) {
        }
    }

    private fun JSONObject.optStringList(key: String): List<String> {
        val arr = optJSONArray(key) ?: return emptyList()
        return List(arr.length()) { arr.optString(it).trim() }.filter { it.isNotEmpty() }
    }

    companion object {
        const val MAX_APPS = 25
        const val MAX_DISMISS_LABELS = 4
        const val MAX_SEARCH_ENTRIES = 3
        const val MAX_PLAYBOOKS = 4
        const val MAX_PREFERENCES = 4
        const val MAX_TRACKED_CHOICES = 12
        const val CHOICE_PROMOTION_THRESHOLD = 2
        const val MAX_PLAYBOOK_CHARS = 140
        const val MAX_PROMPT_HINTS_CHARS = 480
        private const val MAX_LABEL_CHARS = 32

        private val EMAIL_REGEX = Regex("\\S+@\\S+\\.\\S+")
        private val LONG_NUMBER_REGEX = Regex("\\d{4,}")

        /**
         * Returns a clean, non-sensitive UI label suitable for local navigation memory, or null if
         * the label looks like personal data (email, long numeric ID/phone/card, or private field).
         */
        fun sanitizeLabel(raw: String?): String? {
            val cleaned = raw?.replace(Regex("\\s+"), " ")?.trim() ?: return null
            if (cleaned.isEmpty() || cleaned.length > MAX_LABEL_CHARS) return null
            if (EMAIL_REGEX.containsMatchIn(cleaned)) return null
            if (LONG_NUMBER_REGEX.containsMatchIn(cleaned)) return null
            if (ActionGuard.isPrivateField(listOf(cleaned), password = false)) return null
            if (ActionGuard.forTap(listOf(cleaned)) == ActionGuard.Verdict.BLOCK) return null
            return cleaned
        }
    }
}

/**
 * Process-wide singleton for on-device app exploration memory, synchronized with Settings UI.
 */
object AppMemory {
    private const val FILE_NAME = "app_memory.json"

    @Volatile private var store: AppMemoryStore? = null
    private val _learnedAppCount = MutableStateFlow(0)
    val learnedAppCount: StateFlow<Int> = _learnedAppCount

    fun init(context: Context) {
        if (store != null) return
        val s = AppMemoryStore(File(context.applicationContext.filesDir, FILE_NAME))
        store = s
        _learnedAppCount.value = s.appCount()
    }

    fun hintsFor(app: String?, enabled: Boolean = true): String? {
        if (!enabled) return null
        return store?.hintsFor(app)
    }

    fun record(app: String, learning: AppLearning, enabled: Boolean = true) {
        if (!enabled) return
        val s = store ?: return
        s.record(app, learning)
        _learnedAppCount.value = s.appCount()
    }

    fun clear() {
        store?.clear()
        _learnedAppCount.value = 0
    }
}
