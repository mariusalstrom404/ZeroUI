package com.example.myapplication.automation

import com.example.myapplication.nlp.FollowUpRules
import com.example.myapplication.nlp.FollowUpSlot
import com.example.myapplication.nlp.LlmUnavailableException

/** One element on screen, as structured text only. The LLM refers to it by [id]. */
data class ScreenNode(
    val id: Int,
    val label: String?,
    val viewId: String? = null,
    val tappable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val enabled: Boolean = true,
    val password: Boolean = false,
    /** Everything a tap here would activate; checked by [ActionGuard]. */
    val tapLabels: List<String> = listOfNotNull(label),
    /** True/false for checkable switches, checkboxes, and radio buttons; null if not checkable. */
    val checked: Boolean? = null,
    /** True when a tab, filter chip, or option is currently selected. */
    val selected: Boolean = false,
    /** Short semantic role such as "switch", "checkbox", "radio", "tab", or null. */
    val role: String? = null,
    /** Coarse screen region hint for icon-only or navigation controls ("top-left", "top-right", "bottom-bar"). */
    val region: String? = null,
    /** Placeholder hint text on an editable field when distinct from [label]. */
    val hint: String? = null,
    /** Available scroll directions on a scrollable node (e.g. "down", "up", "left", "right"). */
    val scrollDirs: List<String> = emptyList()
)

/** What the accessibility tree shows right now. Never includes an image of the screen. */
data class Screen(
    val app: String?,
    val nodes: List<ScreenNode>,
    /** True when a modal dialog or bottom-sheet window sits above the main app window. */
    val isPopupOverlay: Boolean = false,
    /** Scrollability summary for the main list ("can_scroll_down", "can_scroll_up", "at_bottom"). */
    val scrollSummary: String? = null
) {

    fun describe(): String = buildString {
        append("App: ").append(app ?: "unknown").append('\n')
        if (isPopupOverlay) append("Overlay: dialog/popup\n")
        scrollSummary?.let { append("Scroll: ").append(it).append('\n') }
        for (node in nodes) {
            append('[').append(node.id).append(']')
            if (node.tappable) append(" tap")
            if (node.editable) append(" type")
            if (node.scrollable) append(" scroll")
            if (!node.enabled) append(" disabled")
            if (node.password) append(" password")
            node.role?.let { append(' ').append(it) }
            node.checked?.let { append(if (it) " checked=on" else " checked=off") }
            if (node.selected) append(" selected")
            node.region?.let { append(' ').append(it) }
            if (node.scrollDirs.isNotEmpty()) append(" dirs=").append(node.scrollDirs.joinToString(","))
            node.label?.let { append(" \"").append(it).append('"') }
            node.hint?.takeIf { it != node.label }?.let { append(" hint=\"").append(it).append('"') }
            node.viewId?.let { append(" id=").append(it) }
            append('\n')
        }
    }

    /** Equal for two reads of an unchanged screen; numbering is left out, state changes are included. */
    val fingerprint: String
        get() = buildString {
            append(app ?: "")
            if (isPopupOverlay) append("#popup")
            append('#')
            nodes.joinTo(this, "|") {
                "${it.label}:${it.viewId}:${it.enabled}:${it.editable}:${it.checked}:${it.selected}"
            }
        }

    fun node(id: Int?): ScreenNode? = nodes.firstOrNull { it.id == id }
}

/** One action chosen by the LLM. */
sealed class ScreenAction {
    data class Tap(val node: Int?) : ScreenAction()
    data class LongPress(val node: Int?) : ScreenAction()
    data class Type(val node: Int?, val text: String?, val submit: Boolean) : ScreenAction()
    data class ScrollDown(val node: Int? = null) : ScreenAction()
    data class ScrollUp(val node: Int? = null) : ScreenAction()
    data class ScrollLeft(val node: Int? = null) : ScreenAction()
    data class ScrollRight(val node: Int? = null) : ScreenAction()
    data object Back : ScreenAction()
    data object Wait : ScreenAction()
    /** [choices] are the options on screen, offered to the user as buttons to tap. */
    data class Ask(val question: String?, val choices: List<String> = emptyList()) : ScreenAction()
    /** Pauses automation so the user can finish a manual step (login, OTP, verification) then resume. */
    data class PauseForUser(val instruction: String?) : ScreenAction()
    data class Done(val summary: String?) : ScreenAction()
    data class GiveUp(val reason: String?) : ScreenAction()
}

/**
 * One reply of the LLM: [action], then the taps and typing it planned on the same screen. [then]
 * saves a round trip for each step the LLM doesn't need to see the result of first, such as
 * closing a popup and then tapping the search box.
 */
data class ScreenTurn(val action: ScreenAction, val then: List<ScreenAction> = emptyList())

fun interface ScreenLlm {
    @Throws(LlmUnavailableException::class)
    suspend fun nextScreenAction(goal: String, history: List<String>, screen: String): ScreenTurn

    @Throws(LlmUnavailableException::class)
    suspend fun nextScreenAction(
        goal: String,
        history: List<String>,
        screen: String,
        appHints: String?
    ): ScreenTurn = nextScreenAction(goal, history, screen)
}

/** Reads and acts on the real screen; a fake stands in for it in tests. */
interface ScreenDevice {
    /**
     * Waits for the screen to stop changing, then reads it. Ids stay valid until the next read.
     * [previous] is the screen before an action: if nothing seems to have changed, the read waits
     * a little longer for a slow app before reporting it unchanged.
     */
    suspend fun read(previous: Screen? = null): Screen
    suspend fun tap(id: Int): Boolean
    suspend fun longPress(id: Int): Boolean = tap(id)
    suspend fun type(id: Int, text: String, submit: Boolean): Boolean
    suspend fun scroll(down: Boolean): Boolean
    suspend fun scroll(down: Boolean, nodeId: Int?): Boolean = scroll(down)
    suspend fun scrollHorizontal(left: Boolean, nodeId: Int? = null): Boolean = false
    suspend fun back()
    suspend fun pause()
}

/** Asks the user something and returns their reply, or null if they didn't answer. */
fun interface AgentUser {
    /** [choices] are offered as buttons to tap; the user may still say or type anything. */
    suspend fun ask(question: String, choices: List<String>): String?
}

/**
 * Works towards a goal in any app, one validated action at a time: read the screen, get one action
 * from the LLM, check it in code, perform it, see whether the screen changed.
 *
 * Includes loop detection (tracks visited screen fingerprints and warns/stops on A↔B cycles),
 * context-aware [ActionGuard] checks, and resilient multi-step [ScreenTurn.then] element matching.
 */
class ScreenAgent(
    private val device: ScreenDevice,
    private val llm: ScreenLlm,
    private val user: AgentUser,
    private val onProgress: (String) -> Unit = {},
    private val hintsProvider: (String?) -> String? = { null },
    private val onLearned: (String, AppLearning) -> Unit = { _, _ -> }
) {

    sealed class Outcome {
        data class Done(val summary: String?) : Outcome()
        /** Reached something only the user may do; [what] names it. Nothing was tapped there. */
        data class HandedOver(val what: String) : Outcome()
        /** Paused for the user to finish a manual step (login, OTP, verification), then resume. */
        data class PausedForUser(val instruction: String) : Outcome()
        data object Declined : Outcome()
        data object NoAnswer : Outcome()
        data class GaveUp(val reason: String?) : Outcome()
        data object Unavailable : Outcome()
    }

    /** History snapshot from the most recent [run], used when resuming after a handover/pause. */
    var lastHistory: List<String> = emptyList()
        private set

    /** Foreground app package seen during the most recent [run]. */
    var lastApp: String? = null
        private set

    /** A step planned on an earlier screen, with the element it was meant for there. */
    private class Planned(val action: ScreenAction, val node: ScreenNode)

    suspend fun run(goal: String, initialHistory: List<String> = emptyList()): Outcome {
        val history = initialHistory.toMutableList()
        val planned = ArrayDeque<Planned>()
        val visitCounts = mutableMapOf<String, Int>()
        val triedOnScreen = mutableMapOf<String, MutableList<String>>()
        val learnedDismiss = mutableListOf<String>()
        val learnedSearch = mutableListOf<String>()
        val learnedPlaybook = mutableListOf<String>()
        val learnedChoices = mutableListOf<String>()
        var unchanged = 0
        var screen = device.read()
        var hintsApp: String? = screen.app
        var appHints: String? = hintsProvider(hintsApp)
        visitCounts[screen.fingerprint] = 1

        try {
            while (true) {
                if (screen.app != hintsApp) {
                    hintsApp = screen.app
                    appHints = hintsProvider(hintsApp)
                }
                val screenLabels = screen.nodes.mapNotNull { it.label }
                if (ActionGuard.isPaymentScreen(screenLabels)) {
                    return Outcome.HandedOver("the payment screen")
                }
                val action = nextPlanned(planned, screen, history)
                    ?: decide(goal, history, screen, planned, appHints)
                    ?: return Outcome.Unavailable

                val beforeFp = screen.fingerprint
                val beforePopup = screen.isPopupOverlay
                val beforeHasEditable = screen.nodes.any { it.editable }
                var actedLabel: String? = null
                var actedKind: String? = null

                val note: String = when (action) {
                    is ScreenAction.Done -> {
                        val pkg = screen.app
                        if (pkg != null && (learnedDismiss.isNotEmpty() || learnedSearch.isNotEmpty() ||
                                learnedPlaybook.isNotEmpty() || learnedChoices.isNotEmpty())
                        ) {
                            val playbook = learnedPlaybook.take(6).takeIf { it.size >= 2 }?.joinToString(" → ")
                            onLearned(
                                pkg,
                                AppLearning(
                                    dismissLabels = learnedDismiss.distinct(),
                                    searchEntries = learnedSearch.distinct(),
                                    playbook = playbook,
                                    userChoices = learnedChoices.toList()
                                )
                            )
                        }
                        return Outcome.Done(action.summary)
                    }
                    is ScreenAction.GiveUp -> return Outcome.GaveUp(action.reason)
                    is ScreenAction.PauseForUser -> {
                        val instruction = action.instruction?.takeIf { it.isNotBlank() }
                            ?: "Please complete this step on screen, then tap Resume or say continue."
                        history += "Paused for user: $instruction"
                        return Outcome.PausedForUser(instruction)
                    }
                    is ScreenAction.Ask -> {
                        val question = action.question
                        if (question.isNullOrBlank()) {
                            "ask_user needs a question."
                        } else {
                            val answer = user.ask(question, action.choices) ?: return Outcome.NoAnswer
                            if (FollowUpRules.read(FollowUpSlot.TEXT, answer) == FollowUpRules.Reading.Cancel) return Outcome.Declined
                            // Record only when the answer matches one of the explicit on-screen choices.
                            val matchedChoice = action.choices.firstOrNull { it.equals(answer.trim(), ignoreCase = true) }
                            if (matchedChoice != null) learnedChoices += matchedChoice
                            // A question changes nothing on screen, and that isn't being stuck.
                            history += "You asked: $question The user answered: $answer"
                            screen = device.read()
                            continue
                        }
                    }
                    is ScreenAction.Tap -> {
                        val node = screen.node(action.node)
                        if (node == null) {
                            "There is no element ${action.node}."
                        } else {
                            val guardOutcome = checkTapGuard(node, screenLabels)
                            if (guardOutcome != null) return guardOutcome
                            onProgress("Tapping “${node.label ?: "element"}”")
                            if (device.tap(node.id)) {
                                actedLabel = node.label
                                actedKind = "tap"
                                "Tapped [${node.id}] \"${node.label}\"."
                            } else "Tapping [${node.id}] failed."
                        }
                    }
                    is ScreenAction.LongPress -> {
                        val node = screen.node(action.node)
                        if (node == null) {
                            "There is no element ${action.node}."
                        } else {
                            val guardOutcome = checkTapGuard(node, screenLabels)
                            if (guardOutcome != null) return guardOutcome
                            onProgress("Long-pressing “${node.label ?: "element"}”")
                            if (device.longPress(node.id)) {
                                actedLabel = node.label
                                actedKind = "long_press"
                                "Long-pressed [${node.id}] \"${node.label}\"."
                            } else "Long-pressing [${node.id}] failed."
                        }
                    }
                    is ScreenAction.Type -> {
                        val node = screen.node(action.node)
                        val text = action.text
                        when {
                            node == null || !node.editable -> "Element ${action.node} isn't a text field."
                            text.isNullOrEmpty() -> "type needs text."
                            ActionGuard.isPrivateField(node.tapLabels, node.password) ->
                                return Outcome.HandedOver(node.label ?: "this private field")
                            else -> {
                                onProgress("Typing “$text”")
                                if (device.type(node.id, text, action.submit)) {
                                    actedKind = "type"
                                    "Typed \"$text\" into [${node.id}]."
                                } else "Typing into [${node.id}] failed."
                            }
                        }
                    }
                    is ScreenAction.ScrollDown -> {
                        val ok = if (action.node != null) device.scroll(true, action.node) else device.scroll(true)
                        if (ok) "Scrolled down." else "Couldn't scroll down further."
                    }
                    is ScreenAction.ScrollUp -> {
                        val ok = if (action.node != null) device.scroll(false, action.node) else device.scroll(false)
                        if (ok) "Scrolled up." else "Couldn't scroll up further."
                    }
                    is ScreenAction.ScrollLeft -> {
                        if (device.scrollHorizontal(left = true, nodeId = action.node)) "Scrolled left." else "Couldn't scroll left further."
                    }
                    is ScreenAction.ScrollRight -> {
                        if (device.scrollHorizontal(left = false, nodeId = action.node)) "Scrolled right." else "Couldn't scroll right further."
                    }
                    ScreenAction.Back -> {
                        device.back()
                        "Went back."
                    }
                    ScreenAction.Wait -> {
                        device.pause()
                        "Waited."
                    }
                }

                triedOnScreen.getOrPut(beforeFp) { mutableListOf() }.add(note)

                val after = device.read(previous = screen)
                val afterFp = after.fingerprint
                if (afterFp == beforeFp) {
                    // The plan counted on this step working; with nothing changed, the LLM decides again.
                    planned.clear()
                    if (++unchanged >= MAX_UNCHANGED) return Outcome.GaveUp("the screen stopped responding")
                    history += "$note The screen did not change."
                } else {
                    unchanged = 0
                    // Capture privacy-scrubbed navigation cues when an action changed the screen.
                    recordStepLearning(
                        kind = actedKind,
                        label = actedLabel,
                        beforePopup = beforePopup,
                        afterPopup = after.isPopupOverlay,
                        beforeHasEditable = beforeHasEditable,
                        afterHasEditable = after.nodes.any { it.editable },
                        dismissOut = learnedDismiss,
                        searchOut = learnedSearch,
                        playbookOut = learnedPlaybook
                    )
                    val visits = (visitCounts[afterFp] ?: 0) + 1
                    visitCounts[afterFp] = visits
                    if (visits >= MAX_SAME_SCREEN_VISITS) {
                        return Outcome.GaveUp("kept returning to the same screen without making progress")
                    }
                    if (visits >= 2) {
                        // Returning to a visited screen invalidates optimistic planned steps and warns the LLM.
                        planned.clear()
                        val previousTries = triedOnScreen[afterFp].orEmpty().takeLast(3).joinToString("; ")
                        val loopHint = if (previousTries.isNotEmpty()) {
                            " Note: you are back on a screen already visited ($visits times). Previously tried here: $previousTries. Try a different path."
                        } else {
                            " Note: you are back on a screen already visited ($visits times). Try a different path."
                        }
                        history += "$note$loopHint"
                    } else {
                        history += note
                    }
                }
                screen = after
            }
        } finally {
            lastHistory = history.toList()
            lastApp = screen.app
        }
    }

    private fun recordStepLearning(
        kind: String?,
        label: String?,
        beforePopup: Boolean,
        afterPopup: Boolean,
        beforeHasEditable: Boolean,
        afterHasEditable: Boolean,
        dismissOut: MutableList<String>,
        searchOut: MutableList<String>,
        playbookOut: MutableList<String>
    ) {
        when (kind) {
            "tap" -> {
                val safe = AppMemoryStore.sanitizeLabel(label) ?: return
                if (beforePopup && !afterPopup) dismissOut += safe
                if (!beforeHasEditable && afterHasEditable) searchOut += safe
                if (playbookOut.size < 6) playbookOut += "Tap \"$safe\""
            }
            "long_press" -> {
                val safe = AppMemoryStore.sanitizeLabel(label) ?: return
                if (playbookOut.size < 6) playbookOut += "LongPress \"$safe\""
            }
            "type" -> {
                // Never store user-entered text; generalize as <input>.
                if (playbookOut.size < 6) playbookOut += "Type <input>"
            }
        }
    }

    private suspend fun checkTapGuard(node: ScreenNode, screenLabels: List<String>): Outcome? =
        when (ActionGuard.forTap(node.tapLabels, screenLabels)) {
            ActionGuard.Verdict.BLOCK ->
                Outcome.HandedOver(ActionGuard.reason(node.tapLabels) ?: "this step")
            ActionGuard.Verdict.CONFIRM -> {
                val what = ActionGuard.reason(node.tapLabels) ?: node.label ?: "this"
                val reply = user.ask("Should I tap “$what”? Say confirm or cancel.", CONFIRM_CHOICES)
                    ?: return Outcome.NoAnswer
                // Anything short of a clear yes stops: this can't be taken back.
                if (FollowUpRules.confirmation(reply) != FollowUpRules.Confirmation.YES) Outcome.Declined else null
            }
            ActionGuard.Verdict.ALLOW -> null
        }

    /**
     * Asks the LLM for the next action and queues the steps it planned after it in [planned].
     * Null when the LLM can't be reached.
     */
    private suspend fun decide(
        goal: String,
        history: List<String>,
        screen: Screen,
        planned: ArrayDeque<Planned>,
        appHints: String?
    ): ScreenAction? {
        val turn = try {
            llm.nextScreenAction(goal, history.toList(), screen.describe(), appHints)
        } catch (e: LlmUnavailableException) {
            return null
        }
        planned.clear()
        for (step in turn.then) {
            val node = screen.node(targetOf(step)) ?: break
            planned += Planned(step, node)
        }
        return turn.action
    }

    /**
     * The next planned step, aimed at its element on [screen] (numbers change between reads), or
     * null when nothing is planned. If its element is gone, the rest of the plan is dropped.
     */
    private fun nextPlanned(planned: ArrayDeque<Planned>, screen: Screen, history: MutableList<String>): ScreenAction? {
        val step = planned.removeFirstOrNull() ?: return null
        val node = sameElement(step.node, screen)
        if (node == null) {
            planned.clear()
            history += "Your planned next steps were skipped: their element is no longer on screen."
            return null
        }
        return when (val action = step.action) {
            is ScreenAction.Tap -> action.copy(node = node.id)
            is ScreenAction.LongPress -> action.copy(node = node.id)
            is ScreenAction.Type -> action.copy(node = node.id)
            else -> null
        }
    }

    private fun targetOf(action: ScreenAction): Int? = when (action) {
        is ScreenAction.Tap -> action.node
        is ScreenAction.LongPress -> action.node
        is ScreenAction.Type -> action.node
        else -> null
    }

    private companion object {
        const val MAX_UNCHANGED = 3
        const val MAX_SAME_SCREEN_VISITS = 4
        val CONFIRM_CHOICES = listOf("Confirm", "Cancel")

        /**
         * Finds [old]'s element on [screen] in priority order:
         * 1. Exact match on label, viewId, editable, tappable.
         * 2. Unique viewId match with same editable flag (handles dynamic labels like "Cart (1)" → "Cart (2)").
         * 3. High-score [TextMatch] on label with same editable & tappable flags.
         */
        fun sameElement(old: ScreenNode, screen: Screen): ScreenNode? {
            if (old.label == null && old.viewId == null) return null

            val exact = screen.nodes.filter {
                it.label == old.label && it.viewId == old.viewId &&
                    it.editable == old.editable && it.tappable == old.tappable
            }
            if (exact.isNotEmpty()) {
                return exact.singleOrNull() ?: exact.firstOrNull { it.id == old.id }
            }

            if (old.viewId != null) {
                val byId = screen.nodes.filter { it.viewId == old.viewId && it.editable == old.editable }
                if (byId.size == 1) return byId.single()
            }

            val oldLabel = old.label
            if (oldLabel != null) {
                val fuzzy = screen.nodes
                    .filter { it.editable == old.editable && it.tappable == old.tappable && it.label != null }
                    .map { it to TextMatch.score(it.label, oldLabel) }
                    .filter { it.second >= TextMatch.EXACT_LOOSE }
                val bestScore = fuzzy.maxOfOrNull { it.second }
                if (bestScore != null) {
                    val top = fuzzy.filter { it.second == bestScore }.map { it.first }
                    return top.singleOrNull() ?: top.firstOrNull { it.id == old.id }
                }
            }
            return null
        }
    }
}
