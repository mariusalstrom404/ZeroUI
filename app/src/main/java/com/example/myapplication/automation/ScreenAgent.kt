package com.example.myapplication.automation

import com.example.myapplication.nlp.FollowUpRules
import com.example.myapplication.nlp.FollowUpSlot
import com.example.myapplication.nlp.LlmUnavailableException

/** One element on screen, as text only. The LLM refers to it by [id]. */
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
    val tapLabels: List<String> = listOfNotNull(label)
)

/** What the accessibility tree shows right now. Never includes an image of the screen. */
data class Screen(val app: String?, val nodes: List<ScreenNode>) {

    fun describe(): String = buildString {
        append("App: ").append(app ?: "unknown").append('\n')
        for (node in nodes) {
            append('[').append(node.id).append(']')
            if (node.tappable) append(" tap")
            if (node.editable) append(" type")
            if (node.scrollable) append(" scroll")
            if (!node.enabled) append(" disabled")
            if (node.password) append(" password")
            node.label?.let { append(" \"").append(it).append('"') }
            node.viewId?.let { append(" id=").append(it) }
            append('\n')
        }
    }

    /** Equal for two reads of an unchanged screen; numbering is left out on purpose. */
    val fingerprint: String
        get() = nodes.joinToString("|") { "${it.label}:${it.viewId}:${it.enabled}:${it.editable}" }

    fun node(id: Int?): ScreenNode? = nodes.firstOrNull { it.id == id }
}

/** One action chosen by the LLM. */
sealed class ScreenAction {
    data class Tap(val node: Int?) : ScreenAction()
    data class Type(val node: Int?, val text: String?, val submit: Boolean) : ScreenAction()
    data object ScrollDown : ScreenAction()
    data object ScrollUp : ScreenAction()
    data object Back : ScreenAction()
    data object Wait : ScreenAction()
    /** [choices] are the options on screen, offered to the user as buttons to tap. */
    data class Ask(val question: String?, val choices: List<String> = emptyList()) : ScreenAction()
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
    suspend fun type(id: Int, text: String, submit: Boolean): Boolean
    suspend fun scroll(down: Boolean): Boolean
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
 * There's no limit on the number of steps. A run ends when the LLM says done or gives up, the
 * screen stops changing, a guarded step is reached, the user declines, or the user taps stop.
 *
 * The LLM picks elements by number, so it can't invent one. It never decides what is safe: paying
 * and entering private details end the run, and other consequential taps need the user's yes.
 * Steps it planned ahead ([ScreenTurn.then]) go through the same checks, one at a time, on the
 * screen as it is when their turn comes.
 */
class ScreenAgent(
    private val device: ScreenDevice,
    private val llm: ScreenLlm,
    private val user: AgentUser,
    private val onProgress: (String) -> Unit = {}
) {

    sealed class Outcome {
        data class Done(val summary: String?) : Outcome()
        /** Reached something only the user may do; [what] names it. Nothing was tapped there. */
        data class HandedOver(val what: String) : Outcome()
        data object Declined : Outcome()
        data object NoAnswer : Outcome()
        data class GaveUp(val reason: String?) : Outcome()
        data object Unavailable : Outcome()
    }

    /** A step planned on an earlier screen, with the element it was meant for there. */
    private class Planned(val action: ScreenAction, val node: ScreenNode)

    suspend fun run(goal: String): Outcome {
        val history = mutableListOf<String>()
        val planned = ArrayDeque<Planned>()
        var unchanged = 0
        var screen = device.read()

        while (true) {
            if (ActionGuard.isPaymentScreen(screen.nodes.mapNotNull { it.label })) {
                return Outcome.HandedOver("the payment screen")
            }
            val action = nextPlanned(planned, screen, history)
                ?: decide(goal, history, screen, planned)
                ?: return Outcome.Unavailable

            val note: String = when (action) {
                is ScreenAction.Done -> return Outcome.Done(action.summary)
                is ScreenAction.GiveUp -> return Outcome.GaveUp(action.reason)
                is ScreenAction.Ask -> {
                    val question = action.question
                    if (question.isNullOrBlank()) {
                        "ask_user needs a question."
                    } else {
                        val answer = user.ask(question, action.choices) ?: return Outcome.NoAnswer
                        if (FollowUpRules.read(FollowUpSlot.TEXT, answer) == FollowUpRules.Reading.Cancel) return Outcome.Declined
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
                        when (ActionGuard.forTap(node.tapLabels)) {
                            ActionGuard.Verdict.BLOCK ->
                                return Outcome.HandedOver(ActionGuard.reason(node.tapLabels) ?: "this step")
                            ActionGuard.Verdict.CONFIRM -> {
                                val what = ActionGuard.reason(node.tapLabels) ?: node.label ?: "this"
                                val reply = user.ask("Should I tap “$what”? Say confirm or cancel.", CONFIRM_CHOICES)
                                    ?: return Outcome.NoAnswer
                                // Anything short of a clear yes stops: this can't be taken back.
                                if (FollowUpRules.confirmation(reply) != FollowUpRules.Confirmation.YES) return Outcome.Declined
                            }
                            ActionGuard.Verdict.ALLOW -> {}
                        }
                        onProgress("Tapping “${node.label ?: "element"}”")
                        if (device.tap(node.id)) "Tapped [${node.id}] \"${node.label}\"." else "Tapping [${node.id}] failed."
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
                            if (device.type(node.id, text, action.submit)) "Typed \"$text\" into [${node.id}]." else "Typing into [${node.id}] failed."
                        }
                    }
                }
                ScreenAction.ScrollDown -> if (device.scroll(true)) "Scrolled down." else "Couldn't scroll down further."
                ScreenAction.ScrollUp -> if (device.scroll(false)) "Scrolled up." else "Couldn't scroll up further."
                ScreenAction.Back -> {
                    device.back()
                    "Went back."
                }
                ScreenAction.Wait -> {
                    device.pause()
                    "Waited."
                }
            }

            val after = device.read(previous = screen)
            if (after.fingerprint == screen.fingerprint) {
                // The plan counted on this step working; with nothing changed, the LLM decides again.
                planned.clear()
                if (++unchanged >= MAX_UNCHANGED) return Outcome.GaveUp("the screen stopped responding")
                history += "$note The screen did not change."
            } else {
                unchanged = 0
                history += note
            }
            screen = after
        }
    }

    /**
     * Asks the LLM for the next action and queues the steps it planned after it in [planned].
     * Null when the LLM can't be reached.
     */
    private suspend fun decide(goal: String, history: List<String>, screen: Screen, planned: ArrayDeque<Planned>): ScreenAction? {
        val turn = try {
            llm.nextScreenAction(goal, history.toList(), screen.describe())
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
            is ScreenAction.Type -> action.copy(node = node.id)
            else -> null
        }
    }

    private fun targetOf(action: ScreenAction): Int? = when (action) {
        is ScreenAction.Tap -> action.node
        is ScreenAction.Type -> action.node
        else -> null
    }

    private companion object {
        const val MAX_UNCHANGED = 3
        val CONFIRM_CHOICES = listOf("Confirm", "Cancel")

        /**
         * [old]'s element on [screen]: the one node that looks the same. Among several look-alikes
         * only one at the same number counts, and an element with no label or id can't be told apart.
         */
        fun sameElement(old: ScreenNode, screen: Screen): ScreenNode? {
            val alike = screen.nodes.filter {
                it.label == old.label && it.viewId == old.viewId &&
                    it.editable == old.editable && it.tappable == old.tappable
            }
            if (old.label == null && old.viewId == null) return null
            return alike.singleOrNull() ?: alike.firstOrNull { it.id == old.id }
        }
    }
}
