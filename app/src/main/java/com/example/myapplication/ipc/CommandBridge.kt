package com.example.myapplication.ipc

import com.example.myapplication.MyAccessibilityService

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
    private var replyListener: ((String) -> Unit)? = null
    private var statusListener: ((Status) -> Unit)? = null

    val isServiceConnected: Boolean get() = service != null

    // --- Service side ---

    fun attachService(s: MyAccessibilityService) { service = s }

    fun detachService(s: MyAccessibilityService) { if (service === s) service = null }

    fun postReply(text: String) { replyListener?.invoke(text) }

    fun postStatus(status: Status) { statusListener?.invoke(status) }

    // --- Client side ---

    fun setClient(onReply: (String) -> Unit, onStatus: (Status) -> Unit) {
        replyListener = onReply
        statusListener = onStatus
    }

    fun clearClient() {
        replyListener = null
        statusListener = null
    }

    /** Sends a command to the service. Returns false if the service isn't connected. */
    fun submit(command: String): Boolean {
        val s = service ?: return false
        s.submitCommand(command)
        return true
    }
}
