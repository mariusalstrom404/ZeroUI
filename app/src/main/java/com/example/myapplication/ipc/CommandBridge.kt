package com.example.myapplication.ipc

import com.example.myapplication.MessageKind
import com.example.myapplication.MyAccessibilityService
import com.example.myapplication.nlp.AppIntent
import kotlinx.coroutines.CompletableDeferred

/**
 * In-process channel between the conversational UI and the accessibility service.
 *
 * Since both components live in the same process, a direct registration bridge gives us
 * synchronous delivery feedback, per-command lifecycle status, typed replies ([MessageKind]),
 * continuous follow-up listening coordination, live operation history updates, and instant
 * overlay preference sync.
 */
object CommandBridge {

    enum class Status { STARTED, FINISHED }

    @Volatile private var service: MyAccessibilityService? = null
    @Volatile private var replyListener: ((String, List<String>, MessageKind) -> Unit)? = null
    @Volatile private var statusListener: ((Status) -> Unit)? = null
    @Volatile private var stopListener: (() -> Boolean)? = null
    @Volatile private var awaitingListener: ((Boolean) -> Unit)? = null
    @Volatile private var pausedTaskListener: ((String?) -> Unit)? = null
    @Volatile private var listenListener: (() -> Unit)? = null
    @Volatile private var historyListener: (() -> Unit)? = null
    @Volatile private var answer: CompletableDeferred<String>? = null
    @Volatile private var pausedTaskSummary: String? = null

    val isServiceConnected: Boolean get() = service != null

    /** ZeroUI's chat is on screen (between its onStart and onStop). */
    @Volatile
    var isDialogVisible: Boolean = false
        private set

    /** A running automation asked the user something and is waiting for the reply. */
    val isAwaitingAnswer: Boolean get() = answer != null

    /** Non-null when an agent task is paused for manual user action and can be resumed. */
    val pausedTaskReason: String? get() = pausedTaskSummary
    val hasPausedTask: Boolean get() = pausedTaskSummary != null

    /** Label or package of the app currently underneath ZeroUI, or null when unavailable. */
    fun foregroundAppName(): String? = service?.foregroundAppLabel()

    // --- Service side ---

    fun attachService(s: MyAccessibilityService) { service = s }

    fun detachService(s: MyAccessibilityService) { if (service === s) service = null }

    /** Adds [text] to the chat. [choices] are answers shown as buttons under it. */
    fun postReply(
        text: String,
        choices: List<String> = emptyList(),
        kind: MessageKind = MessageKind.NORMAL
    ) {
        replyListener?.invoke(text, choices, kind)
    }

    fun postStatus(status: Status) { statusListener?.invoke(status) }

    /** Notifies the UI that [OperationHistoryManager] recorded a new entry. */
    fun notifyHistoryChanged() { historyListener?.invoke() }

    /** Updates whether a task is paused waiting for the user to finish a manual step. */
    fun postPausedTask(reason: String?) {
        pausedTaskSummary = reason
        pausedTaskListener?.invoke(reason)
    }

    /** The user pressed stop. Returns true if the client had a request in progress to cancel. */
    fun postStop(): Boolean = stopListener?.invoke() ?: false

    /** Starts waiting for the user's next utterance, which then answers the service, not the chat. */
    fun expectAnswer(): CompletableDeferred<String> =
        CompletableDeferred<String>().also {
            answer = it
            awaitingListener?.invoke(true)
        }

    fun clearAnswer(waiting: CompletableDeferred<String>) {
        if (answer !== waiting) return
        answer = null
        awaitingListener?.invoke(false)
    }

    // --- Client side ---

    /** Gives [text] to a running automation that asked for it. Returns false if none is waiting. */
    fun deliverAnswer(text: String): Boolean {
        val waiting = answer ?: return false
        answer = null
        awaitingListener?.invoke(false)
        waiting.complete(text)
        return true
    }

    /** Stop pressed in the chat: stops a running command, or a request still being understood. */
    fun requestStop() {
        val s = service
        // The service's stop also cancels a request being understood, through postStop.
        if (s != null) s.stopCurrentCommand() else stopListener?.invoke()
    }

    /** The voice dialog's mic opened or closed; shown on the floating button. */
    fun reportListening(listening: Boolean) { service?.setListening(listening) }

    /** The chat came on screen or left it. While it's on screen, it does the listening, not the pill. */
    fun reportDialogVisible(visible: Boolean) {
        isDialogVisible = visible
        if (visible) service?.cancelFollowUp()
    }

    /**
     * A request has been dealt with: listen for the user's next one instead of going quiet. Once
     * ZeroUI has finished speaking, the chat listens if it's on screen ([listenInDialog]);
     * otherwise the status pill does, over whatever app is in front.
     */
    fun listenForMore(prompt: String? = null) {
        val s = service
        if (s != null) s.startFollowUp(prompt) else listenInDialog()
    }

    /** Shows the status pill with its mic button and [title], without listening, when the chat isn't on screen. */
    fun offerMic(title: String) {
        if (!isDialogVisible) service?.offerMic(title)
    }

    /** The user said they're done: no more listening, and the pill goes away. */
    fun endConversation() { service?.endConversation() }

    /** Opens the chat's mic if the chat is on screen; returns false if it isn't. */
    fun listenInDialog(): Boolean {
        if (!isDialogVisible) return false
        listenListener?.invoke()
        return true
    }

    /** Applies updated floating-button visibility / auto-dim / position reset immediately. */
    fun refreshOverlayPreferences(resetPosition: Boolean = false) {
        service?.applyOverlayPreferences(resetPosition)
    }

    /** Resumes the paused [ScreenAgent] task from the current screen. Returns false if none is paused. */
    fun resumePausedTask(): Boolean = service?.resumePausedTask() ?: false

    /** Discards the paused task without resuming it. */
    fun clearPausedTask() {
        val s = service
        if (s != null) s.clearPausedTask() else postPausedTask(null)
    }

    fun setClient(
        onReply: (String, List<String>, MessageKind) -> Unit,
        onStatus: (Status) -> Unit,
        onStop: () -> Boolean,
        onAwaitingAnswer: (Boolean) -> Unit = {},
        onPausedTaskChanged: (String?) -> Unit = {},
        onListen: () -> Unit = {},
        onHistoryChanged: () -> Unit = {}
    ) {
        replyListener = onReply
        statusListener = onStatus
        stopListener = onStop
        awaitingListener = onAwaitingAnswer
        pausedTaskListener = onPausedTaskChanged
        listenListener = onListen
        historyListener = onHistoryChanged
    }

    fun clearClient() {
        replyListener = null
        statusListener = null
        stopListener = null
        awaitingListener = null
        pausedTaskListener = null
        listenListener = null
        historyListener = null
    }

    /** Sends parsed commands to the service, in order. Returns false if the service isn't connected. */
    fun submit(commands: List<AppIntent>, utterance: String = ""): Boolean {
        val s = service ?: return false
        s.submitCommands(commands, utterance)
        return true
    }

    /** Shows that an utterance is being understood; no-op if the service isn't connected. */
    fun showUnderstanding(utterance: String) { service?.showUnderstanding(utterance) }

    fun clearUnderstanding() { service?.clearUnderstanding() }
}
