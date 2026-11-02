package com.example.myapplication.nlp

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandConversationTest {

    private val today = StayDate(2026, 10, 1)

    private class FakeLlm : LlmClient {
        var commands: List<LlmCommand> = emptyList()
        var answer: LlmAnswer? = null
        var unavailable = false
        var parseCalls = 0
        var answerCalls = 0

        override suspend fun parseCommand(utterance: String, today: StayDate): List<LlmCommand> {
            parseCalls++
            if (unavailable) throw LlmUnavailableException("down")
            return commands
        }

        override suspend fun understandAnswer(slot: String, question: String, answer: String, today: StayDate): LlmAnswer {
            answerCalls++
            if (unavailable) throw LlmUnavailableException("down")
            return this.answer ?: LlmAnswer(LlmAnswer.Status.UNCLEAR)
        }
    }

    private val llm = FakeLlm()
    private val conversation = CommandConversation(llm) { today }

    private fun say(text: String) = runBlocking { conversation.handle(text) }

    private fun commands(vararg intents: AppIntent) {
        llm.commands = intents.map { LlmCommand(it) }
    }

    private val transfer = AppIntent.Transfer(50, "Bob")

    @Test
    fun simpleCommandRunsAtOnce() {
        commands(AppIntent.TakeScreenshot)
        assertEquals(listOf<AppIntent>(AppIntent.TakeScreenshot), say("take a screenshot").run)
    }

    @Test
    fun proxyDownRunsNothing() {
        llm.unavailable = true
        val turn = say("transfer 50 to Bob")
        assertTrue(turn.run.isEmpty())
        assertTrue(turn.says.single().contains("can't reach"))
    }

    @Test
    fun transferNeedsConfirmation() {
        commands(transfer)
        val asked = say("transfer 50 to Bob")
        assertTrue(asked.run.isEmpty())
        assertTrue(asked.says.single().contains("50 to Bob"))
        assertEquals(listOf<AppIntent>(transfer), say("yes").run)
    }

    @Test
    fun chainedTransferIsConfirmedBeforeAnythingRuns() {
        commands(AppIntent.CheckBalance, transfer)
        assertTrue(say("check my balance and transfer 50 to Bob").run.isEmpty())
        assertEquals(listOf(AppIntent.CheckBalance, transfer), say("confirm").run)
    }

    @Test
    fun decliningDropsOnlyThatCommand() {
        commands(transfer, AppIntent.CheckBalance)
        say("transfer 50 to Bob and check my balance")
        assertEquals(listOf<AppIntent>(AppIntent.CheckBalance), say("no").run)
    }

    @Test
    fun repeatedTransferIsConfirmedAgain() {
        commands(transfer)
        say("transfer 50 to Bob")
        say("yes")
        commands(AppIntent.RepeatLast)
        assertTrue(say("repeat").run.isEmpty())
        assertEquals(listOf<AppIntent>(transfer), say("yes").run)
    }

    @Test
    fun repeatWithNothingBefore() {
        commands(AppIntent.RepeatLast)
        val turn = say("repeat")
        assertTrue(turn.run.isEmpty())
        assertTrue(turn.says.single().contains("previous command"))
    }

    @Test
    fun negativeAnswerContainingYesWordDoesNotConfirm() {
        commands(transfer)
        say("transfer 50 to Bob")
        assertTrue(say("no, I'm not sure").run.isEmpty())
        assertEquals(0, llm.answerCalls)
    }

    @Test
    fun confirmationFallsBackToLlmOnlyWhenRulesAreUnclear() {
        commands(transfer)
        say("transfer 50 to Bob")
        llm.answer = LlmAnswer(LlmAnswer.Status.CONFIRM)
        assertEquals(listOf<AppIntent>(transfer), say("absolutely, send it").run)
        assertEquals(1, llm.answerCalls)
    }

    @Test
    fun unclearConfirmationCancelsWhenLlmIsDown() {
        commands(transfer)
        say("transfer 50 to Bob")
        llm.unavailable = true
        val turn = say("absolutely, send it")
        assertTrue(turn.run.isEmpty())
        assertTrue(turn.says.single().contains("cancelled"))
    }

    @Test
    fun missingDetailsAskTheLlmsQuestionThenConfirm() {
        llm.commands = listOf(
            LlmCommand(AppIntent.Transfer(null, null), mapOf(FollowUpSlot.AMOUNT to "How much would you like to send?"))
        )
        assertEquals("How much would you like to send?", say("send some money").says.single())
        assertEquals(FollowUpSlot.RECIPIENT.defaultQuestion(), say("50").says.single())
        assertTrue(say("to Bob").says.single().contains("50 to Bob"))
        assertEquals(listOf<AppIntent>(transfer), say("yes").run)
        assertEquals(0, llm.answerCalls)
        assertEquals(1, llm.parseCalls)
    }

    @Test
    fun llmReadsAnAnswerTheRulesCannot() {
        commands(AppIntent.Transfer(null, "Bob"))
        say("send money to Bob")
        llm.answer = LlmAnswer(LlmAnswer.Status.FILLED, value = "50")
        assertTrue(say("fifty dollars").says.single().contains("50 to Bob"))
        assertEquals(1, llm.answerCalls)
    }

    @Test
    fun llmValueThatCannotFillTheSlotAsksAgain() {
        commands(AppIntent.Transfer(null, "Bob"))
        say("send money to Bob")
        llm.answer = LlmAnswer(LlmAnswer.Status.FILLED, value = "a lot")
        assertTrue(say("a lot").says.single().startsWith("Sorry"))
    }

    @Test
    fun cancellingADetailDropsTheCommand() {
        commands(AppIntent.Transfer(null, "Bob"))
        say("send money to Bob")
        val turn = say("never mind")
        assertTrue(turn.run.isEmpty())
        assertTrue(turn.says.single().contains("cancelled"))
    }

    @Test
    fun unknownIsNeverRun() {
        commands(AppIntent.Unknown)
        val turn = say("blah blah")
        assertTrue(turn.run.isEmpty())
        assertTrue(turn.says.single().contains("didn't understand"))
    }

    @Test
    fun bookingCollectsDetailsThenRuns() {
        commands(AppIntent.HotelSearch("Tokyo", hotel = "Hilton", book = true))
        assertTrue(say("book the Hilton in Tokyo").run.isEmpty())
        assertTrue(say("Dec 5 to Dec 8").run.isEmpty())
        assertTrue(say("2 adults").run.isEmpty())
        val done = say("any").run.single() as AppIntent.HotelSearch
        assertEquals("Hilton", done.hotel)
        assertEquals(StayDate(2026, 12, 5), done.checkIn)
        assertEquals(2, done.adults)
        assertEquals(0, llm.answerCalls)
    }

    @Test
    fun bookingFallsBackToLlm() {
        commands(AppIntent.HotelSearch("Tokyo", hotel = "Hilton", book = true))
        say("book the Hilton in Tokyo")
        llm.answer = LlmAnswer(LlmAnswer.Status.FILLED, checkIn = StayDate(2026, 12, 5), checkOut = StayDate(2026, 12, 7))
        assertTrue(say("the first weekend of December").says.single().contains("How many guests"))
        assertEquals(1, llm.answerCalls)
    }
}
