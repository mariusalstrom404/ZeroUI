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
import com.example.myapplication.Message
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
 * Holds the chat messages and connects the UI to [CommandConversation], which understands each
 * utterance. Intents that only concern the chat or a quick reading are answered here; the rest
 * are handed to the accessibility service for execution.
 */
class VoiceViewModel(app: Application) : AndroidViewModel(app) {

    val messages: SnapshotStateList<Message> = mutableStateListOf()

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private val conversation = CommandConversation(OpenAiLlmClient(app.assets))
    private var requestJob: Job? = null

    /** True while a command is being understood or run by the service. */
    var isProcessing by mutableStateOf(false)
        private set

    /** Set by the host Activity to route assistant text to text-to-speech. */
    var speak: (String) -> Unit = {}

    /** Set by the host Activity to get out of the way once a running automation has its answer. */
    var onAnswered: () -> Unit = {}

    init {
        loadHistory()
        if (messages.isEmpty()) {
            messages.add(
                Message(
                    "Hey! I'm your assistant. I can handle multiple tasks at once. What should we do?",
                    false
                )
            )
        }
        // Receive replies and per-command lifecycle status directly from the service.
        CommandBridge.setClient(
            onReply = { onServiceReply(it) },
            onStatus = { isProcessing = it == CommandBridge.Status.STARTED },
            onStop = { stopRequest() }
        )
    }

    private fun saveHistory() {
        val prefs = getApplication<Application>().getSharedPreferences("voice_history", Context.MODE_PRIVATE)
        val array = JSONArray()
        messages.forEach {
            val obj = JSONObject()
            obj.put("text", it.text)
            obj.put("isUser", it.isUser)
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
                messages.add(Message(obj.getString("text"), obj.getBoolean("isUser")))
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

    /** Reply received from the accessibility service, which has already read it aloud itself. */
    fun onServiceReply(text: String) = addAssistant(text, spoken = true)

    private fun addAssistant(text: String, spoken: Boolean = false) {
        val last = messages.lastOrNull()
        if (last != null && !last.isUser && last.text == text) return
        messages.add(Message(text, false))
        if (!spoken) speak(text)
    }

    fun onUserCommand(userInput: String) {
        messages.add(Message(userInput, true))
        // A running automation asked a question; this is its answer, not a new command.
        if (CommandBridge.deliverAnswer(userInput)) {
            onAnswered()
            return
        }
        requestJob?.cancel()
        isProcessing = true
        CommandBridge.showUnderstanding(userInput)
        requestJob = scope.launch {
            val turn = conversation.handle(userInput)
            isProcessing = false
            turn.says.forEach { addAssistant(it) }
            run(turn.run)
        }
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
            AppIntent.Greeting -> {
                addAssistant("Hi! I'm ZeroUI, your voice assistant. Here is what I can do for you:\n\n" +
                        " Device Control: 'turn on flashlight', 'volume up', 'set brightness to 50%', or 'check battery'.\n" +
                        " Screenshot & Photo: 'take a screenshot', 'open camera', or 'take a photo'.\n" +
                        " Navigation: 'navigate to Central Park' or 'take me to the airport'.\n" +
                        " Banking (NCCUbank): 'check my balance', 'transfer 50 to Bob', or 'show my transaction history'.\n" +
                        " Food (FoodGorilla): 'search for pizza on FoodGorilla' or 'open my cart'.\n" +
                        " Any app: 'search for pizza on Foodpanda' or 'type hello'.\n" +
                        " Delivery apps: 'order Hawaiian Pizza from Pizza Hut on Foodpanda' (adds it to your cart).\n" +
                        " Social (Throats): 'post Hello World to Throats'.\n" +
                        " History: 'save history' or 'clear history'.\n" +
                        " Repeat: 'repeat' or 'one more time'.\n\n" +
                        "How can I help you today?")
            }
            AppIntent.SaveHistory -> {
                saveHistory()
                addAssistant("Conversation history has been saved successfully.")
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
