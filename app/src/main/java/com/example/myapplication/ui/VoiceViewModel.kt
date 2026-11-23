package com.example.myapplication.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.provider.AlarmClock
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import com.example.myapplication.Message
import com.example.myapplication.MessageKind
import com.example.myapplication.assistant.WeatherService
import com.example.myapplication.debug.Timing
import com.example.myapplication.history.OperationHistoryManager
import com.example.myapplication.history.OperationRecord
import com.example.myapplication.ipc.CommandBridge
import com.example.myapplication.nlp.AppIntent
import com.example.myapplication.nlp.CommandConversation
import com.example.myapplication.nlp.OpenAiLlmClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Owns the one [VoiceViewModel] for the whole process instead of each MainActivity. The dialog is
 * closed and reopened while automations run (to get out of the app's way, then to ask a question),
 * and an Activity-scoped ViewModel would lose the chat, a pending follow-up question and the
 * service's replies each time.
 */
object ProcessViewModels : ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
}

/**
 * Holds the chat messages, operation history, and connects the UI to [CommandConversation], which
 * understands each utterance. Intents that only concern the chat or a quick reading are answered
 * here; the rest are handed to the accessibility service for execution.
 */
class VoiceViewModel(app: Application) : AndroidViewModel(app) {

    val messages: SnapshotStateList<Message> = mutableStateListOf()
    val operationHistory: SnapshotStateList<OperationRecord> = mutableStateListOf()

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private val llm = OpenAiLlmClient(app.assets)
    private val conversation = CommandConversation(llm)
    private var requestJob: Job? = null
    private var saveHistoryJob: Job? = null
    private var lastUtterance: String = ""

    /** True while a command is being understood or run by the service. */
    var isProcessing by mutableStateOf(false)
        private set

    /** A running automation asked a question and the next reply answers it. */
    var awaitingAnswer by mutableStateOf(CommandBridge.isAwaitingAnswer)
        private set

    /**
     * Set by the host Activity to route assistant text to text-to-speech. With interrupt false it
     * waits for what is being said, so a turn's sentences are all heard.
     */
    var speak: (text: String, interrupt: Boolean) -> Unit = { _, _ -> }

    /**
     * Set by the host Activity to get out of the way once a running automation has its answer.
     * The Activity resets it when destroyed, since this ViewModel outlives it.
     */
    var onAnswered: () -> Unit = {}

    init {
        UserPreferences.init(app)
        warmUp()
        loadHistory()
        refreshOperationHistory()
        // Receive replies and per-command lifecycle status safely on the main thread.
        CommandBridge.setClient(
            onReply = { text, choices, kind -> scope.launch { onServiceReply(text, choices, kind) } },
            onStatus = { status -> scope.launch { isProcessing = status == CommandBridge.Status.STARTED } },
            onStop = { stopRequest() },
            onAwaitingAnswer = { awaiting -> scope.launch { awaitingAnswer = awaiting } },
            onHistoryChanged = { scope.launch { refreshOperationHistory() } }
        )
    }

    /** Saved off the main thread with short debouncing so rapid message bursts don't stall UI. */
    private fun saveHistory(immediate: Boolean = false) {
        val snapshot = messages.takeLast(MAX_SAVED_MESSAGES)
        saveHistoryJob?.cancel()
        saveHistoryJob = scope.launch(Dispatchers.IO) {
            if (!immediate) delay(250)
            val prefs = getApplication<Application>().getSharedPreferences("voice_history", Context.MODE_PRIVATE)
            val array = JSONArray()
            snapshot.forEach {
                val obj = JSONObject()
                obj.put("text", it.text)
                obj.put("isUser", it.isUser)
                obj.put("kind", it.kind.name)
                obj.put("timestamp", it.timestamp)
                if (it.choices.isNotEmpty()) obj.put("choices", JSONArray(it.choices))
                array.put(obj)
            }
            prefs.edit().putString("history", array.toString()).apply()
        }
    }

    private fun loadHistory() {
        val prefs = getApplication<Application>().getSharedPreferences("voice_history", Context.MODE_PRIVATE)
        val history = prefs.getString("history", null) ?: return
        try {
            val array = JSONArray(history)
            messages.clear()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val choices = obj.optJSONArray("choices")?.let { c -> List(c.length()) { c.getString(it) } } ?: emptyList()
                val kind = runCatching { MessageKind.valueOf(obj.optString("kind", MessageKind.NORMAL.name)) }
                    .getOrDefault(MessageKind.NORMAL)
                val timestamp = obj.optLong("timestamp", System.currentTimeMillis())
                val text = obj.getString("text")
                // Skip legacy initial welcome bubble so the rich empty state can shine when no user chat exists.
                if (array.length() == 1 && !obj.getBoolean("isUser") && text == LEGACY_WELCOME) continue
                messages.add(Message(text, obj.getBoolean("isUser"), choices, kind, timestamp))
            }
        } catch (e: Exception) {
            messages.clear()
        }
    }

    fun clearHistory(announce: Boolean = false) {
        messages.clear()
        val prefs = getApplication<Application>().getSharedPreferences("voice_history", Context.MODE_PRIVATE)
        prefs.edit().remove("history").apply()
        if (announce) {
            addAssistant("I've cleared our conversation history.")
        }
    }

    fun refreshOperationHistory() {
        val records = OperationHistoryManager.getRecords(getApplication())
        operationHistory.clear()
        operationHistory.addAll(records)
    }

    fun clearOperationHistory() {
        OperationHistoryManager.clearHistory(getApplication())
        operationHistory.clear()
    }

    fun togglePinCommand(command: String) {
        UserPreferences.togglePinnedCommand(command)
    }

    override fun onCleared() {
        super.onCleared()
        scope.cancel()
        CommandBridge.clearClient()
    }

    /** Reply received from the accessibility service, which reads aloud what should be heard itself. */
    fun onServiceReply(
        text: String,
        choices: List<String> = emptyList(),
        kind: MessageKind = MessageKind.NORMAL
    ) {
        val resolvedKind = if (kind == MessageKind.NORMAL && isConfirmChoices(choices)) {
            MessageKind.CONFIRM
        } else {
            kind
        }
        addAssistant(text, spoken = true, choices = choices, kind = resolvedKind)
    }

    /**
     * Adds an assistant message and reads it aloud, as [speech] when that's shorter than the text
     * shown. [interrupt] false waits for what's being said instead of cutting it off.
     */
    private fun addAssistant(
        text: String,
        spoken: Boolean = false,
        choices: List<String> = emptyList(),
        interrupt: Boolean = true,
        speech: String = text,
        kind: MessageKind = MessageKind.NORMAL
    ) {
        val last = messages.lastOrNull()
        if (last != null && !last.isUser && last.text == text) {
            // The same question asked again keeps its buttons.
            if (choices.isNotEmpty() && last.choices != choices) {
                messages[messages.lastIndex] = last.copy(choices = choices, kind = kind)
            }
            return
        }
        messages.add(Message(text, false, choices, kind = kind))
        saveHistory()
        if (!spoken) speak(speech, interrupt)
    }

    /** Opens the connection to OpenAI now, so the next request doesn't wait for it. */
    fun warmUp() {
        scope.launch { llm.warmUp() }
    }

    fun onUserCommand(input: String) {
        val userInput = input.trim()
        if (userInput.isEmpty()) return
        messages.add(Message(userInput, true))
        saveHistory()
        // A running automation asked a question; this is its answer, not a new command.
        if (CommandBridge.deliverAnswer(userInput)) {
            onAnswered()
            return
        }
        lastUtterance = userInput
        requestJob?.cancel()
        isProcessing = true
        CommandBridge.showUnderstanding(userInput)
        requestJob = scope.launch {
            val foregroundApp = CommandBridge.foregroundAppName()
            val turn = Timing.measure("Understanding the request") { conversation.handle(userInput, foregroundApp) }
            // The first sentence cuts off anything older; the rest wait their turn so all are heard.
            turn.says.forEachIndexed { i, say ->
                val choices = if (i == turn.says.lastIndex) turn.choices else emptyList()
                val kind = inferKind(say, choices)
                addAssistant(say, choices = choices, interrupt = i == 0, kind = kind)
            }
            run(turn.run, userInput)
            if (!CommandBridge.isServiceConnected || turn.run.all { isLocal(it) }) {
                isProcessing = false
            }
        }
    }

    /** The stop button in the chat: stops whatever is being understood or run. */
    fun stop() {
        if (CommandBridge.isServiceConnected) CommandBridge.requestStop()
        else if (stopRequest()) addAssistant("Okay, I stopped.")
    }

    /** Cancels a request still being understood; returns false if there wasn't one. */
    private fun stopRequest(): Boolean {
        if (requestJob?.isActive != true) return false
        requestJob?.cancel()
        isProcessing = false
        return true
    }

    private suspend fun run(intents: List<AppIntent>, utterance: String) {
        val (local, remote) = intents.partition { isLocal(it) }
        for (intent in local) {
            runLocally(intent, utterance)
        }
        if (remote.isEmpty()) {
            CommandBridge.clearUnderstanding()
        } else {
            // A task in the app already in front needs this dialog out of its way.
            if (remote.any { it is AppIntent.AppTask && it.app == null }) onAnswered()
            dispatchToService(remote, utterance)
        }
    }

    private fun isLocal(intent: AppIntent): Boolean =
        intent === AppIntent.Greeting || intent === AppIntent.SaveHistory ||
            intent === AppIntent.ClearHistory || intent === AppIntent.ViewOperationHistory ||
            intent === AppIntent.Battery || intent === AppIntent.CurrentTime ||
            intent is AppIntent.Weather || intent is AppIntent.SetAlarm || intent is AppIntent.SetTimer

    private suspend fun runLocally(intent: AppIntent, utterance: String) {
        val preferZh = utterance.any { it.code > 0x2E80 } ||
            UserPreferences.speechLocaleTag.value == UserPreferences.LOCALE_ZH_TW
        when (intent) {
            AppIntent.Greeting -> addAssistant(
                if (preferZh) {
                    "嗨！我是 ZeroUI 語音助理。我可以幫你查天氣、設鬧鐘與倒計時、控制手機、導航、查 NCCUbank、訂餐或訂房，也能跨 App 幫你完成操作。點選下方範例或直接說出需求吧！"
                } else {
                    "Hi! I'm ZeroUI, your voice assistant. I can check the weather, set alarms & timers, control your phone, navigate, check NCCUbank, order food, find stays on Booking.com and work in most other apps. Tap an example below, or just say what you need."
                },
                choices = EXAMPLES,
                speech = if (preferZh) "嗨！我是 ZeroUI，有什麼我可以幫你的？" else "Hi! I'm ZeroUI. Tap an example, or just tell me what you need."
            )
            AppIntent.SaveHistory -> {
                saveHistory(immediate = true)
                addAssistant(if (preferZh) "對話紀錄已儲存，平時也會自動保存。" else "Our conversation is saved. I also save it automatically as we go.")
            }
            AppIntent.ClearHistory -> {
                clearHistory(announce = true)
            }
            AppIntent.ViewOperationHistory -> {
                refreshOperationHistory()
                addAssistant(OperationHistoryManager.formatHistorySummary(getApplication()))
            }
            AppIntent.Battery -> {
                val bm = getApplication<Application>()
                    .getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                val reply = if (preferZh) {
                    "目前電量是 $level%。${if (level < 20) "電量偏低，記得充電喔！" else "電量還很充足。"}"
                } else {
                    "Your battery is at $level%. ${if (level < 20) "You should charge it soon!" else "You're good to go."}"
                }
                addAssistant(reply)
                recordLocalOperation(utterance, "Checked battery ($level%)", "SUCCESS")
            }
            AppIntent.CurrentTime -> {
                val now = Date()
                val reply = if (preferZh) {
                    val fmt = SimpleDateFormat("M月d日 EEEE a h點mm分", Locale.TRADITIONAL_CHINESE)
                    "現在時間是 ${fmt.format(now)}。"
                } else {
                    val fmt = SimpleDateFormat("EEEE, MMM d, h:mm a", Locale.US)
                    "It's currently ${fmt.format(now)}."
                }
                addAssistant(reply)
                recordLocalOperation(utterance, "Checked current time", "SUCCESS")
            }
            is AppIntent.Weather -> {
                val reply = WeatherService.queryWeather(intent.location, intent.dayOffset, preferZh)
                addAssistant(reply)
                recordLocalOperation(utterance, "Checked weather (${intent.location ?: "current location"})", "SUCCESS")
            }
            is AppIntent.SetAlarm -> {
                val hour = intent.hour ?: return
                val minute = intent.minute ?: 0
                val ctx = getApplication<Application>()
                val alarmIntent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(AlarmClock.EXTRA_HOUR, hour)
                    putExtra(AlarmClock.EXTRA_MINUTES, minute)
                    putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    intent.label?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
                }
                val timeStr = String.format(Locale.ROOT, "%02d:%02d", hour, minute)
                val started = runCatching { ctx.startActivity(alarmIntent); true }.getOrDefault(false)
                if (started) {
                    val reply = if (preferZh) {
                        "已幫你設定 $timeStr 的鬧鐘${intent.label?.let { "（$it）" } ?: ""}。"
                    } else {
                        "Alarm set for $timeStr${intent.label?.let { " ($it)" } ?: ""}."
                    }
                    addAssistant(reply)
                    recordLocalOperation(utterance, "Set alarm for $timeStr", "SUCCESS")
                } else {
                    addAssistant(
                        if (preferZh) "找不到系統時鐘應用程式，無法設定鬧鐘。" else "I couldn't find a Clock app to set the alarm.",
                        kind = MessageKind.ERROR
                    )
                    recordLocalOperation(utterance, "Set alarm for $timeStr", "FAILED")
                }
            }
            is AppIntent.SetTimer -> {
                val seconds = intent.durationSeconds ?: return
                val ctx = getApplication<Application>()
                val timerIntent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                    putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    intent.label?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
                }
                val durationLabel = formatDuration(seconds, preferZh)
                val started = runCatching { ctx.startActivity(timerIntent); true }.getOrDefault(false)
                if (started) {
                    val reply = if (preferZh) {
                        "已開始倒計時 $durationLabel${intent.label?.let { "（$it）" } ?: ""}。"
                    } else {
                        "Countdown timer started for $durationLabel${intent.label?.let { " ($it)" } ?: ""}."
                    }
                    addAssistant(reply)
                    recordLocalOperation(utterance, "Started timer ($durationLabel)", "SUCCESS")
                } else {
                    addAssistant(
                        if (preferZh) "找不到系統時鐘應用程式，無法啟動倒計時。" else "I couldn't find a Clock app to start the timer.",
                        kind = MessageKind.ERROR
                    )
                    recordLocalOperation(utterance, "Started timer ($durationLabel)", "FAILED")
                }
            }
            else -> {}
        }
    }

    private fun recordLocalOperation(utterance: String, description: String, status: String) {
        OperationHistoryManager.addRecord(getApplication(), utterance, description, status)
        refreshOperationHistory()
    }

    private fun formatDuration(totalSeconds: Int, zh: Boolean): String {
        val hours = totalSeconds / 3600
        val mins = (totalSeconds % 3600) / 60
        val secs = totalSeconds % 60
        return if (zh) {
            buildString {
                if (hours > 0) append("${hours} 小時")
                if (mins > 0) {
                    if (isNotEmpty()) append(" ")
                    append("${mins} 分鐘")
                }
                if (secs > 0) {
                    if (isNotEmpty()) append(" ")
                    append("${secs} 秒")
                }
            }
        } else {
            listOfNotNull(
                hours.takeIf { it > 0 }?.let { "$it ${if (it == 1) "hour" else "hours"}" },
                mins.takeIf { it > 0 }?.let { "$it ${if (it == 1) "minute" else "minutes"}" },
                secs.takeIf { it > 0 }?.let { "$it ${if (it == 1) "second" else "seconds"}" }
            ).joinToString(" ")
        }
    }

    private fun dispatchToService(commands: List<AppIntent>, utterance: String) {
        if (!CommandBridge.submit(commands, utterance)) {
            promptEnableAccessibilityService()
        }
    }

    private fun isConfirmChoices(choices: List<String>): Boolean =
        choices.size == 2 &&
            choices[0].equals("Confirm", ignoreCase = true) &&
            choices[1].equals("Cancel", ignoreCase = true)

    private fun inferKind(text: String, choices: List<String>): MessageKind = when {
        isConfirmChoices(choices) -> MessageKind.CONFIRM
        text.contains("can't reach my language service", ignoreCase = true) ||
            text.startsWith("Sorry, I didn't understand", ignoreCase = true) -> MessageKind.ERROR
        else -> MessageKind.NORMAL
    }

    private companion object {
        const val MAX_SAVED_MESSAGES = 100
        const val LEGACY_WELCOME = "Hi! I'm ZeroUI. Tell me what to do, or tap an example to try it."
        val EXAMPLES = listOf(
            "Check my battery",
            "Turn on the flashlight",
            "Take me to the airport",
            "Search for pizza on Foodpanda",
            "Find a hotel in Tokyo on Booking.com"
        )
    }

    private fun promptEnableAccessibilityService() {
        addAssistant(
            "I need the accessibility service enabled to do that. I'll open the settings for you — please turn on ZeroUI.",
            kind = MessageKind.ERROR
        )
        try {
            val ctx = getApplication<Application>()
            ctx.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) { /* settings unavailable; the spoken prompt still informs the user */ }
    }
}
