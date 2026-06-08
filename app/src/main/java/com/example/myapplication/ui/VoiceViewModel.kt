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
import com.example.myapplication.UIIntent
import com.example.myapplication.ipc.CommandBridge
import com.example.myapplication.nlp.AppIntent
import com.example.myapplication.nlp.IntentParser
import com.example.myapplication.nlp.ml.ParserFactory
import org.json.JSONArray
import org.json.JSONObject

/**
 * Holds the conversational state and decision logic that used to live in MainActivity.
 *
 * It parses each utterance with the shared [IntentParser] (same engine the accessibility
 * service uses), answers locally where it can (battery, pleasantries, multi-turn prompts),
 * and otherwise hands the command to the accessibility service for execution.
 */
class VoiceViewModel(app: Application) : AndroidViewModel(app) {

    val messages: SnapshotStateList<Message> = mutableStateListOf()

    private val parser: IntentParser = ParserFactory.create(app)
    private var currentIntent = UIIntent.NONE
    private var savedEntity: String? = null

    /** The raw command awaiting a yes/no confirmation (high-risk actions only). */
    private var pendingCommand: String? = null

    /** True while a command has been handed to the service and we're awaiting its reply. */
    var isProcessing by mutableStateOf(false)
        private set

    /** Set by the host Activity to route assistant text to text-to-speech. */
    var speak: (String) -> Unit = {}

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
            onStatus = { isProcessing = it == CommandBridge.Status.STARTED }
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
        CommandBridge.clearClient()
    }

    /** Reply received from the accessibility service. */
    fun onServiceReply(text: String) = addAssistant(text)

    private fun addAssistant(text: String) {
        val last = messages.lastOrNull()
        if (last != null && !last.isUser && last.text == text) return
        messages.add(Message(text, false))
        speak(text)
    }

    fun onUserCommand(userInput: String) {
        messages.add(Message(userInput, true))
        val text = userInput.lowercase().trim()

        // 1. Multi-command chains -> hand the whole utterance to the service.
        if (text.contains(" and ") || text.contains(" then ") || text.contains(" next ")) {
            addAssistant("Sure thing, I'll get started on those tasks for you.")
            dispatchToService(userInput)
            return
        }

        // 2. Social pleasantries.
        if (text.contains("how are you") || text.contains("how's your day")) {
            addAssistant(
                listOf(
                    "I'm doing great! Ready for your commands.",
                    "Fantastic, thank you for asking!",
                    "I'm having a great time helping you out."
                ).random()
            )
            return
        }

        // 3. Multi-turn continuation.
        when (currentIntent) {
            UIIntent.CONFIRM -> {
                currentIntent = UIIntent.NONE
                val command = pendingCommand
                pendingCommand = null
                // Default to NOT proceeding unless the user clearly confirms (safe for money).
                val affirmed = listOf("yes", "yeah", "yep", "confirm", "proceed", "go ahead", "do it", "sure")
                    .any { text.contains(it) }
                if (affirmed && command != null) {
                    addAssistant("Okay, proceeding.")
                    dispatchToService(command)
                } else {
                    addAssistant("Okay, I've cancelled that.")
                }
                return
            }
            UIIntent.NAVIGATE_DEST -> {
                savedEntity = userInput
                addAssistant("Got it. And how would you like to get to $userInput? Bus, walking, or driving?")
                currentIntent = UIIntent.NAVIGATE_MODE
                return
            }
            UIIntent.NAVIGATE_MODE -> {
                val dest = savedEntity ?: "there"
                addAssistant("Perfect. Setting up your route to $dest now.")
                dispatchToService("navigate to $dest by $text")
                currentIntent = UIIntent.NONE; savedEntity = null
                return
            }
            UIIntent.OPEN_APP -> {
                addAssistant("Opening $userInput for you.")
                dispatchToService("open $userInput")
                currentIntent = UIIntent.NONE
                return
            }
            UIIntent.NONE, UIIntent.BRIGHTNESS -> {}
        }

        // 4. Single intent.
        when (val intent = parser.parse(userInput)) {
            AppIntent.Greeting -> {
                addAssistant("Hi! I'm ZeroUI, your voice assistant. Here is what I can do for you:\n\n" +
                        "📱 **Device Control**: 'turn on flashlight', 'volume up', 'set brightness to 50%', or 'check battery'.\n" +
                        "📸 **Screenshot & Photo**: 'take a screenshot', 'open camera', or 'take a photo'.\n" +
                        "🗺️ **Navigation**: 'navigate to Central Park' or 'take me to the airport'.\n" +
                        "🏦 **Banking (NCCUbank)**: 'check my balance', 'transfer 50 to Bob', or 'show my transaction history'.\n" +
                        "🍕 **Food (FoodGorilla)**: 'search for pizza on FoodGorilla' or 'open my cart'.\n" +
                        "💬 **Social (Throats)**: 'post Hello World to Throats'.\n" +
                        "📜 **History**: 'save history' or 'clear history'.\n" +
                        "🔄 **Repeat**: 'repeat' or 'one more time'.\n\n" +
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
            is AppIntent.Navigate -> {
                val dest = intent.destination
                if (dest == null) {
                    addAssistant("I'd be happy to help. Where would you like to go?")
                    currentIntent = UIIntent.NAVIGATE_DEST
                } else {
                    savedEntity = dest
                    addAssistant("I can do that. How do you want to get to $dest?")
                    currentIntent = UIIntent.NAVIGATE_MODE
                }
            }
            is AppIntent.OpenApp -> {
                if (intent.name.isEmpty()) {
                    addAssistant("Sure! Which app should I open?")
                    currentIntent = UIIntent.OPEN_APP
                } else {
                    dispatchToService(userInput)
                }
            }
            else -> {
                // High-risk actions (money transfer, ordering, checkout) require an
                // explicit spoken confirmation before we dispatch them.
                val prompt = confirmationPrompt(intent)
                if (prompt != null) {
                    pendingCommand = userInput
                    currentIntent = UIIntent.CONFIRM
                    addAssistant(prompt)
                } else {
                    dispatchToService(userInput)
                }
            }
        }
    }

    /** Returns a confirmation question for actionable high-risk intents, else null. */
    private fun confirmationPrompt(intent: AppIntent): String? = when (intent) {
        is AppIntent.Transfer ->
            if (intent.amount != null && intent.recipient != null)
                "You're about to transfer ${intent.amount} to ${intent.recipient}. Say 'confirm' to proceed, or 'cancel' to stop."
            else null
        is AppIntent.FoodOrder ->
            if (intent.itemId != null || intent.query != null)
                "You're about to place an order on FoodGorilla. Say 'confirm' to proceed, or 'cancel' to stop."
            else null
        AppIntent.FoodCheckout ->
            "You're about to check out on FoodGorilla. Say 'confirm' to proceed, or 'cancel' to stop."
        else -> null
    }

    private fun dispatchToService(command: String) {
        // CommandBridge.submit returns false when the accessibility service isn't
        // connected, giving us reliable delivery feedback the old broadcast lacked.
        if (!CommandBridge.submit(command)) {
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
