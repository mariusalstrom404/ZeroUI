package com.example.myapplication.ipc

import com.example.myapplication.MyAccessibilityService
import com.example.myapplication.nlp.AppIntent
import kotlinx.coroutines.CompletableDeferred

/**
 * In-process channel between the conversational UI and the accessibility service.
 *
 * This replaces the previous fire-and-forget broadcast pair
 * (`com.example.myapplication.COMMAND` / `.REPLY`), which offered no delivery
 * guarantee, no acknowledgement, and an unnecessary exported attack surface.
 *
 * A bound `Messenger` on the service itself isn't possible — `AccessibilityService`
 * marks `onBind()` final. Since both components live in the same process, a direct
 * registration bridge gives us what binding would: the UI knows whether the service
 * is connected ([isServiceConnected]), [submit] returns success/failure synchronously,
 * and the service reports per-command lifecycle via [Status]. All callbacks are invoked
 * on the main thread (the service's coroutine scope is main-dispatched).
 */
object CommandBridge {

    enum class Status { STARTED, FINISHED }

    private var service: MyAccessibilityService? = null
    private var replyListener: ((String, List<String>) -> Unit)? = null
    private var statusListener: ((Status) -> Unit)? = null
    private var stopListener: (() -> Boolean)? = null
    private var awaitingListener: ((Boolean) -> Unit)? = null
    private var answer: CompletableDeferred<String>? = null

    val isServiceConnected: Boolean get() = service != null

    /** A running automation asked the user something and is waiting for the reply. */
    val isAwaitingAnswer: Boolean get() = answer != null

    // --- Service side ---

    fun attachService(s: MyAccessibilityService) { service = s }

    fun detachService(s: MyAccessibilityService) { if (service === s) service = null }

    /** Adds [text] to the chat. [choices] are answers shown as buttons under it. */
    fun postReply(text: String, choices: List<String> = emptyList()) { replyListener?.invoke(text, choices) }

    fun postStatus(status: Status) { statusListener?.invoke(status) }

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

    fun setClient(
        onReply: (String, List<String>) -> Unit,
        onStatus: (Status) -> Unit,
        onStop: () -> Boolean,
        onAwaitingAnswer: (Boolean) -> Unit = {}
    ) {
        replyListener = onReply
        statusListener = onStatus
        stopListener = onStop
        awaitingListener = onAwaitingAnswer
    }

    fun clearClient() {
        replyListener = null
        statusListener = null
        stopListener = null
        awaitingListener = null
    }

    /** Sends parsed commands to the service, in order. Returns false if the service isn't connected. */
    fun submit(commands: List<AppIntent>): Boolean {
        val s = service ?: return false
        s.submitCommands(commands)
        return true
    }

    /** Shows that an utterance is being understood; no-op if the service isn't connected. */
    fun showUnderstanding(utterance: String) { service?.showUnderstanding(utterance) }

    fun clearUnderstanding() { service?.clearUnderstanding() }
}
