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
    data class Ask(val question: String?) : ScreenAction()
    data class Done(val summary: String?) : ScreenAction()
    data class GiveUp(val reason: String?) : ScreenAction()
}

fun interface ScreenLlm {
    @Throws(LlmUnavailableException::class)
    suspend fun nextScreenAction(goal: String, history: List<String>, screen: String): ScreenAction
}

/** Reads and acts on the real screen; a fake stands in for it in tests. */
interface ScreenDevice {
    /** Waits for the screen to stop changing, then reads it. Ids stay valid until the next read. */
    suspend fun read(): Screen
    suspend fun tap(id: Int): Boolean
    suspend fun type(id: Int, text: String, submit: Boolean): Boolean
    suspend fun scroll(down: Boolean): Boolean
    suspend fun back()
    suspend fun pause()
}

/** Asks the user something and returns their reply, or null if they didn't answer. */
fun interface AgentUser {
    suspend fun ask(question: String): String?
}

/**
 * Works towards a goal in any app, one validated action at a time: read the screen, get one action
 * from the LLM, check it in code, perform it, see whether the screen changed.
 *
 * The LLM picks elements by number, so it can't invent one. It never decides what is safe: paying
 * and entering private details end the run, and other consequential taps need the user's yes.
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
        data object OutOfSteps : Outcome()
        data object Unavailable : Outcome()
    }

    suspend fun run(goal: String, maxSteps: Int = DEFAULT_MAX_STEPS): Outcome {
        val history = mutableListOf<String>()
        var unchanged = 0
        var screen = device.read()

        repeat(maxSteps) {
            if (ActionGuard.isPaymentScreen(screen.nodes.mapNotNull { it.label })) {
                return Outcome.HandedOver("the payment screen")
            }
            val action = try {
                llm.nextScreenAction(goal, history.toList(), screen.describe())
            } catch (e: LlmUnavailableException) {
                return Outcome.Unavailable
            }

            val note: String = when (action) {
                is ScreenAction.Done -> return Outcome.Done(action.summary)
                is ScreenAction.GiveUp -> return Outcome.GaveUp(action.reason)
                is ScreenAction.Ask -> {
                    val question = action.question
                    if (question.isNullOrBlank()) {
                        "ask_user needs a question."
                    } else {
                        val answer = user.ask(question) ?: return Outcome.NoAnswer
                        if (FollowUpRules.read(FollowUpSlot.TEXT, answer) == FollowUpRules.Reading.Cancel) return Outcome.Declined
                        // A question changes nothing on screen, and that isn't being stuck.
                        history += "You asked: $question The user answered: $answer"
                        screen = device.read()
                        return@repeat
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
                                val reply = user.ask("Should I tap “$what”? Say confirm or cancel.") ?: return Outcome.NoAnswer
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

            val after = device.read()
            if (after.fingerprint == screen.fingerprint) {
                if (++unchanged >= MAX_UNCHANGED) return Outcome.GaveUp("the screen stopped responding")
                history += "$note The screen did not change."
            } else {
                unchanged = 0
                history += note
            }
            screen = after
        }
        return Outcome.OutOfSteps
    }

    companion object {
        const val DEFAULT_MAX_STEPS = 15
        const val RECOVERY_MAX_STEPS = 10
        private const val MAX_UNCHANGED = 3
    }
}
