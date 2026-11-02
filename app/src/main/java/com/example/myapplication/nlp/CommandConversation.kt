package com.example.myapplication.nlp

/**
 * Takes each utterance to the point where intents can run.
 *
 * A new command goes to the LLM. Every resulting command then collects its missing details and,
 * if it's high risk, an explicit confirmation before anything runs, so a chained or repeated
 * transfer is confirmed like any other. Follow-up answers go to [FollowUpRules] or
 * [BookingConversation] first, and to the LLM only when those can't understand them.
 */
class CommandConversation(
    private val llm: LlmClient,
    private val today: () -> StayDate = { StayDate.today() }
) {

    /** What to do for one utterance: say [says] in order, then run [run] in order. */
    data class Turn(val says: List<String> = emptyList(), val run: List<AppIntent> = emptyList())

    private sealed class Waiting {
        data class Detail(val command: LlmCommand, val slot: FollowUpSlot, val question: String) : Waiting()
        data class Confirm(val intent: AppIntent, val question: String) : Waiting()
        data class Booking(val conversation: BookingConversation, val question: String) : Waiting()
    }

    private var waiting: Waiting? = null
    private val queue = ArrayDeque<LlmCommand>()
    private val ready = mutableListOf<AppIntent>()
    private var lastRun: List<AppIntent> = emptyList()

    suspend fun handle(utterance: String): Turn {
        val says = mutableListOf<String>()
        when (val current = waiting) {
            null -> {
                val commands = try {
                    llm.parseCommand(utterance, today())
                } catch (e: LlmUnavailableException) {
                    return Turn(listOf(UNAVAILABLE))
                }
                queue.clear()
                ready.clear()
                queue.addAll(commands)
            }
            is Waiting.Detail -> answerDetail(current, utterance, says)
            is Waiting.Confirm -> answerConfirm(current, utterance, says)
            is Waiting.Booking -> answerBooking(current, utterance, says)
        }
        return advance(says)
    }

    /** Works through the queue until a command needs the user, or everything is ready to run. */
    private fun advance(says: MutableList<String>): Turn {
        if (waiting != null) return Turn(says)
        while (queue.isNotEmpty()) {
            val command = queue.removeFirst()
            val intent = command.intent
            when {
                intent === AppIntent.RepeatLast -> {
                    if (lastRun.isEmpty()) says += "I don't have a previous command to repeat yet."
                    // Back through the queue, so a repeated transfer is confirmed again.
                    else queue.addAll(0, lastRun.map { LlmCommand(it) })
                }
                intent === AppIntent.Unknown -> says += "Sorry, I didn't understand that."
                intent is AppIntent.HotelSearch && intent.book -> {
                    val conversation = BookingConversation(intent, today())
                    if (!applyBooking(conversation, conversation.start(), says)) return Turn(says)
                }
                intent.missingSlots().isNotEmpty() -> {
                    val slot = intent.missingSlots().first()
                    val question = command.questions[slot] ?: slot.defaultQuestion()
                    waiting = Waiting.Detail(command, slot, question)
                    says += question
                    return Turn(says)
                }
                intent.requiresConfirmation -> {
                    val question = confirmationPrompt(intent)
                    waiting = Waiting.Confirm(intent, question)
                    says += question
                    return Turn(says)
                }
                else -> ready += intent
            }
        }
        val run = ready.toList()
        ready.clear()
        if (run.isNotEmpty()) lastRun = run
        return Turn(says, run)
    }

    private suspend fun answerDetail(current: Waiting.Detail, answer: String, says: MutableList<String>) {
        val slot = current.slot
        var value: String? = null
        when (val reading = FollowUpRules.read(slot, answer)) {
            is FollowUpRules.Reading.Value -> value = reading.value
            FollowUpRules.Reading.Cancel -> return cancelWaiting(says)
            FollowUpRules.Reading.Unclear -> {
                val understood = askLlm(slot.name, current.question, answer)
                if (understood?.status == LlmAnswer.Status.CANCEL) return cancelWaiting(says)
                if (understood?.status == LlmAnswer.Status.FILLED) value = understood.value
            }
        }
        val filled = value?.let { current.command.intent.fill(slot, it) }
        if (filled == null) {
            says += "Sorry, I didn't catch that. ${current.question}"
            return
        }
        waiting = null
        queue.addFirst(current.command.copy(intent = filled))
    }

    private suspend fun answerConfirm(current: Waiting.Confirm, answer: String, says: MutableList<String>) {
        // Anything short of a clear yes, including no reply from the LLM, cancels: this can move money.
        val confirmed = when (FollowUpRules.confirmation(answer)) {
            FollowUpRules.Confirmation.YES -> true
            FollowUpRules.Confirmation.NO -> false
            FollowUpRules.Confirmation.UNCLEAR ->
                askLlm(CONFIRMATION, current.question, answer)?.status == LlmAnswer.Status.CONFIRM
        }
        waiting = null
        if (confirmed) {
            says += "Okay, proceeding."
            ready += current.intent
        } else {
            says += CANCELLED
        }
    }

    private suspend fun answerBooking(current: Waiting.Booking, answer: String, says: MutableList<String>) {
        val byRules = current.conversation.answer(answer)
        val outcome = if (byRules is BookingConversation.Outcome.Ask && byRules.retry) {
            askLlm(byRules.slot.name, current.question, answer)?.let { current.conversation.apply(it) } ?: byRules
        } else {
            byRules
        }
        waiting = null
        applyBooking(current.conversation, outcome, says)
    }

    /** Returns true when the booking no longer needs the user. */
    private fun applyBooking(
        conversation: BookingConversation,
        outcome: BookingConversation.Outcome,
        says: MutableList<String>
    ): Boolean = when (outcome) {
        is BookingConversation.Outcome.Ask -> {
            waiting = Waiting.Booking(conversation, outcome.question)
            says += outcome.question
            false
        }
        is BookingConversation.Outcome.Done -> {
            says += outcome.summary
            ready += outcome.intent
            true
        }
        BookingConversation.Outcome.Cancelled -> {
            says += CANCELLED
            true
        }
    }

    private fun cancelWaiting(says: MutableList<String>) {
        waiting = null
        says += CANCELLED
    }

    /** Null when the LLM can't be reached; the caller then treats the answer as not understood. */
    private suspend fun askLlm(slot: String, question: String, answer: String): LlmAnswer? = try {
        llm.understandAnswer(slot, question, answer, today())
    } catch (e: LlmUnavailableException) {
        null
    }

    private fun confirmationPrompt(intent: AppIntent): String = when (intent) {
        is AppIntent.Transfer ->
            "You're about to transfer ${intent.amount} to ${intent.recipient}. Say 'confirm' to proceed, or 'cancel' to stop."
        is AppIntent.FoodOrder ->
            "You're about to place an order on FoodGorilla${intent.query?.let { " for $it" } ?: ""}. Say 'confirm' to proceed, or 'cancel' to stop."
        AppIntent.FoodCheckout ->
            "You're about to check out on FoodGorilla. Say 'confirm' to proceed, or 'cancel' to stop."
        else -> "Are you sure? Say 'confirm' to proceed, or 'cancel' to stop."
    }

    private companion object {
        const val CONFIRMATION = "CONFIRMATION"
        const val CANCELLED = "Okay, I've cancelled that."
        const val UNAVAILABLE = "I can't reach my language service right now. Please check your connection and try again."
    }
}
