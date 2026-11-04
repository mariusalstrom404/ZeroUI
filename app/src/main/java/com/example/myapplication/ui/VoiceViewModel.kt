package com.example.myapplication.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
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
import com.example.myapplication.debug.Timing
import com.example.myapplication.ipc.CommandBridge
import com.example.myapplication.nlp.AppIntent
import com.example.myapplication.nlp.CommandConversation
import com.example.myapplication.nlp.OpenAiLlmClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

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
 * Holds the chat messages and connects the UI to [CommandConversation], which understands each
 * utterance. Intents that only concern the chat or a quick reading are answered here; the rest
 * are handed to the accessibility service for execution.
 */
class VoiceViewModel(app: Application) : AndroidViewModel(app) {

    val messages: SnapshotStateList<Message> = mutableStateListOf()

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private val llm = OpenAiLlmClient(app.assets)
    private val conversation = CommandConversation(llm)
    private var requestJob: Job? = null

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
        warmUp()
        loadHistory()
        if (messages.isEmpty()) {
            messages.add(Message(WELCOME, false, EXAMPLES))
        }
        // Receive replies and per-command lifecycle status directly from the service.
        CommandBridge.setClient(
            onReply = { text, choices -> onServiceReply(text, choices) },
            onStatus = { isProcessing = it == CommandBridge.Status.STARTED },
            onStop = { stopRequest() },
            onAwaitingAnswer = { awaitingAnswer = it }
        )
    }

    /** Saved after every message, so the chat survives the app being closed. Only the latest are kept. */
    private fun saveHistory() {
        val prefs = getApplication<Application>().getSharedPreferences("voice_history", Context.MODE_PRIVATE)
        val array = JSONArray()
        messages.takeLast(MAX_SAVED_MESSAGES).forEach {
            val obj = JSONObject()
            obj.put("text", it.text)
            obj.put("isUser", it.isUser)
            if (it.choices.isNotEmpty()) obj.put("choices", JSONArray(it.choices))
            array.put(obj)
        }
        prefs.edit().putString("history", array.toString()).apply()
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
                messages.add(Message(obj.getString("text"), obj.getBoolean("isUser"), choices))
            }
        } catch (e: Exception) {
            messages.clear()
        }
    }

    private fun clearHistory() {
        messages.clear()
        val prefs = getApplication<Application>().getSharedPreferences("voice_history", Context.MODE_PRIVATE)
        prefs.edit().remove("history").apply()
        addAssistant("I've cleared our conversation history.")
    }

    override fun onCleared() {
        super.onCleared()
        scope.cancel()
        CommandBridge.clearClient()
    }

    /** Reply received from the accessibility service, which reads aloud what should be heard itself. */
    fun onServiceReply(text: String, choices: List<String> = emptyList()) =
        addAssistant(text, spoken = true, choices = choices)

    /**
     * Adds an assistant message and reads it aloud, as [speech] when that's shorter than the text
     * shown. [interrupt] false waits for what's being said instead of cutting it off.
     */
    private fun addAssistant(
        text: String,
        spoken: Boolean = false,
        choices: List<String> = emptyList(),
        interrupt: Boolean = true,
        speech: String = text
    ) {
        val last = messages.lastOrNull()
        if (last != null && !last.isUser && last.text == text) {
            // The same question asked again keeps its buttons.
            if (choices.isNotEmpty() && last.choices != choices) messages[messages.lastIndex] = last.copy(choices = choices)
            return
        }
        messages.add(Message(text, false, choices))
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
        requestJob?.cancel()
        isProcessing = true
        CommandBridge.showUnderstanding(userInput)
        requestJob = scope.launch {
            val turn = Timing.measure("Understanding the request") { conversation.handle(userInput) }
            isProcessing = false
            // The first sentence cuts off anything older; the rest wait their turn so all are heard.
            turn.says.forEachIndexed { i, say ->
                val choices = if (i == turn.says.lastIndex) turn.choices else emptyList()
                addAssistant(say, choices = choices, interrupt = i == 0)
            }
            run(turn.run)
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

    private fun run(intents: List<AppIntent>) {
        val (local, remote) = intents.partition { isLocal(it) }
        local.forEach { runLocally(it) }
        if (remote.isEmpty()) {
            CommandBridge.clearUnderstanding()
        } else {
            // A task in the app already in front needs this dialog out of its way.
            if (remote.any { it is AppIntent.AppTask && it.app == null }) onAnswered()
            dispatchToService(remote)
        }
    }

    private fun isLocal(intent: AppIntent): Boolean =
        intent === AppIntent.Greeting || intent === AppIntent.SaveHistory ||
            intent === AppIntent.ClearHistory || intent === AppIntent.Battery

    private fun runLocally(intent: AppIntent) {
        when (intent) {
            AppIntent.Greeting -> addAssistant(
                "Hi! I'm ZeroUI, your voice assistant. I can control your phone, navigate, check NCCUbank, " +
                    "order food, find stays on Booking.com and work in most other apps. " +
                    "Tap an example below, or just say what you need. You can ask for several things at once.",
                choices = EXAMPLES,
                // The examples are on screen; reading them all out takes too long.
                speech = "Hi! I'm ZeroUI. Tap an example, or just tell me what you need."
            )
            AppIntent.SaveHistory -> {
                saveHistory()
                addAssistant("Our conversation is saved. I also save it automatically as we go.")
            }
            AppIntent.ClearHistory -> {
                clearHistory()
            }
            AppIntent.Battery -> {
                val bm = getApplication<Application>()
                    .getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                addAssistant("Your battery is at $level%. ${if (level < 20) "You should charge it soon!" else "You're good to go."}")
            }
            else -> {}
        }
    }

    private fun dispatchToService(commands: List<AppIntent>) {
        // CommandBridge.submit returns false when the accessibility service isn't
        // connected, giving us reliable delivery feedback the old broadcast lacked.
        if (!CommandBridge.submit(commands)) {
            promptEnableAccessibilityService()
        }
    }

    private companion object {
        const val MAX_SAVED_MESSAGES = 100
        const val WELCOME = "Hi! I'm ZeroUI. Tell me what to do, or tap an example to try it."
        val EXAMPLES = listOf(
            "Check my battery",
            "Turn on the flashlight",
            "Take me to the airport",
            "Search for pizza on Foodpanda",
            "Find a hotel in Tokyo on Booking.com"
        )
    }

    private fun promptEnableAccessibilityService() {
        addAssistant("I need the accessibility service enabled to do that. I'll open the settings for you — please turn on ZeroUI.")
        try {
            val ctx = getApplication<Application>()
            ctx.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) { /* settings unavailable; the spoken prompt still informs the user */ }
    }
}
