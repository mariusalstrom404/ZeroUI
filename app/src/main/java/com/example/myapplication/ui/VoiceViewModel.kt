package com.example.myapplication.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import com.example.myapplication.Message
import com.example.myapplication.debug.Timing
import com.example.myapplication.ipc.CommandBridge
import com.example.myapplication.speech.Speaker
import com.example.myapplication.nlp.AppIntent
import com.example.myapplication.nlp.CommandConversation
import com.example.myapplication.nlp.ConversationClosing
import com.example.myapplication.nlp.SystemAction
import com.example.myapplication.nlp.OpenAiLlmClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import android.util.Log
import java.io.File
import java.util.concurrent.Executors

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

    /** The conversation on screen. Earlier ones are in [history]. */
    val messages: SnapshotStateList<Message> = mutableStateListOf()

    /** Earlier conversations, newest first. */
    val history: SnapshotStateList<ChatSession> = mutableStateListOf()

    /** When the conversation on screen started; it's saved under this id. */
    private var sessionId = System.currentTimeMillis()
    private val store = ChatHistoryStore(File(app.filesDir, "chat_history.json"))
    /** Writes happen one at a time, in order, off the main thread. */
    private val writer = Executors.newSingleThreadExecutor()

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
    var speak: (text: String, interrupt: Boolean) -> Unit = { text, interrupt -> Speaker.speak(text, interrupt) }

    /**
     * Bumped when the chat should open its mic for the user's next request, after ZeroUI has
     * answered the last one (see [CommandBridge.listenForMore]).
     */
    var listenRequests by mutableIntStateOf(0)
        private set

    /**
     * Set by the host Activity to get out of the way once a running automation has its answer.
     * The Activity resets it when destroyed, since this ViewModel outlives it.
     */
    var onAnswered: () -> Unit = {}

    init {
        warmUp()
        loadHistory()
        // Every launch starts with a clean screen; what was said before is in History.
        messages.add(welcome())
        // Receive replies and per-command lifecycle status directly from the service.
        CommandBridge.setClient(
            onReply = { text, choices -> onServiceReply(text, choices) },
            onStatus = { isProcessing = it == CommandBridge.Status.STARTED },
            onStop = { stopRequest() },
            onAwaitingAnswer = { awaitingAnswer = it },
            onListen = { listenRequests++ }
        )
    }

    /**
     * Saved after every message, so nothing is lost if the app is closed: the conversation on
     * screen (once the user has said something) and the earlier ones.
     */
    private fun saveHistory() {
        val sessions = buildList {
            current()?.let { add(it) }
            addAll(history)
        }.take(ChatSessions.MAX_SESSIONS)
        writer.execute {
            try {
                store.save(sessions)
            } catch (e: Exception) {
                Log.e("VoiceViewModel", "Couldn't save the chat history", e)
            }
        }
    }

    /** The conversation on screen as a session, or null while it's only the greeting. */
    private fun current(): ChatSession? =
        messages.takeIf { ChatSessions.worthKeeping(it) }
            ?.let { ChatSession(sessionId, it.takeLast(ChatSessions.MAX_MESSAGES_PER_SESSION)) }

    /** Everything saved becomes history, including the conversation that was on screen last time. */
    private fun loadHistory() {
        val saved = try {
            store.load()
        } catch (e: Exception) {
            Log.e("VoiceViewModel", "Couldn't read the chat history", e)
            emptyList()
        }
        history.addAll(saved.sortedByDescending { it.id })
        migrateOldHistory()
    }

    /** Older versions kept one chat in preferences; it becomes the oldest session. */
    private fun migrateOldHistory() {
        val prefs = getApplication<Application>().getSharedPreferences("voice_history", Context.MODE_PRIVATE)
        val old = prefs.getString("history", null) ?: return
        // Old messages have no times; the file's last save is the closest to when they were said.
        val prefsFile = File(getApplication<Application>().applicationInfo.dataDir, "shared_prefs/voice_history.xml")
        val savedAt = prefsFile.lastModified().takeIf { it > 0 }
            ?: history.lastOrNull()?.id?.minus(1)
            ?: System.currentTimeMillis()
        ChatHistoryCodec.decodeLegacy(old, savedAt)
            ?.takeIf { ChatSessions.worthKeeping(it.messages) }
            ?.let { history.add(it) }
        prefs.edit().remove("history").apply()
        saveHistory()
    }

    private fun welcome() = Message(
        if (history.isEmpty()) WELCOME else "$WELCOME Earlier conversations are in History.",
        false,
        EXAMPLES
    )

    /**
     * Moves the conversation on screen into [history] and starts a clean one. An unanswered
     * question is dropped with it; a running automation carries on and reports here.
     */
    fun newConversation() {
        current()?.let { session ->
            history.removeAll { it.id == session.id }
            history.add(0, session)
        }
        conversation.reset()
        sessionId = System.currentTimeMillis()
        messages.clear()
        messages.add(welcome())
        saveHistory()
    }

    /**
     * The dialog was opened. After [ChatSessions.IDLE_MS] without a message the screen starts a
     * new conversation, unless something is still going on in this one.
     */
    fun onDialogShown() {
        val busy = isProcessing || awaitingAnswer || conversation.isWaiting
        if (!busy && ChatSessions.worthKeeping(messages) &&
            ChatSessions.isIdle(messages.lastOrNull()?.time, System.currentTimeMillis())
        ) {
            newConversation()
        }
    }

    fun deleteSession(id: Long) {
        history.removeAll { it.id == id }
        saveHistory()
    }

    fun clearSavedHistory() {
        history.clear()
        saveHistory()
    }

    /** "Clear history" said to ZeroUI: earlier conversations and this one. */
    private fun clearHistory() {
        history.clear()
        sessionId = System.currentTimeMillis()
        messages.clear()
        saveHistory()
        addAssistant("I've cleared our conversation history.")
    }

    override fun onCleared() {
        super.onCleared()
        scope.cancel()
        writer.shutdown()
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
        // "No thanks", "that's all": the user is done, so ZeroUI stops listening rather than asking the LLM.
        if (!conversation.isWaiting && ConversationClosing.isClosing(userInput)) {
            addAssistant("Okay. Tap the mic whenever you need me.")
            CommandBridge.endConversation()
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
            when {
                // Possibly background noise: listening again could go round in circles. The mic is offered instead.
                !turn.understood -> CommandBridge.offerMic("I didn't catch that")
                // Nothing went to the service, so nothing will report back: listen for more now.
                turn.run.all { isLocal(it) } -> CommandBridge.listenForMore(turn.says.lastOrNull())
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

    private fun run(intents: List<AppIntent>) {
        val (local, remote) = intents.partition { isLocal(it) }
        local.forEach { runLocally(it) }
        if (remote.isEmpty()) {
            CommandBridge.clearUnderstanding()
        } else {
            // A task in the app already in front needs this dialog out of its way.
            if (remote.any { actsOnAppInFront(it) }) onAnswered()
            dispatchToService(remote)
        }
    }

    /** Commands about the app underneath: the dialog would be what they act on, or capture. */
    private fun actsOnAppInFront(intent: AppIntent): Boolean = when (intent) {
        is AppIntent.AppTask -> intent.app == null
        is AppIntent.SystemControl -> intent.action == SystemAction.BACK || intent.action == SystemAction.SPLIT_SCREEN
        AppIntent.TakeScreenshot -> true
        else -> false
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
