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

        override suspend fun parseCommand(utterance: String, today: StayDate, foregroundApp: String?): List<LlmCommand> {
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

    private val navToAirport = AppIntent.Navigate("airport", "bus")

    @Test
    fun simpleCommandRunsAtOnce() {
        commands(AppIntent.TakeScreenshot)
        assertEquals(listOf<AppIntent>(AppIntent.TakeScreenshot), say("take a screenshot").run)
    }

    @Test
    fun nonsenseIsMarkedNotUnderstood() {
        commands(AppIntent.Unknown)
        val turn = say("dada")
        assertTrue(turn.run.isEmpty())
        assertEquals(false, turn.understood)
    }

    @Test
    fun partlyUnderstoodStillRuns() {
        commands(AppIntent.Unknown, AppIntent.TakeScreenshot)
        val turn = say("blah and take a screenshot please, sort of")
        assertEquals(true, turn.understood)
        assertEquals(listOf<AppIntent>(AppIntent.TakeScreenshot), turn.run)
    }

    @Test
    fun systemCommandSkipsTheLlm() {
        val turn = say("turn on wifi and open the calculator")
        assertEquals(
            listOf(AppIntent.SystemToggle(SystemSetting.WIFI, true), AppIntent.OpenSystemApp(SystemApp.CALCULATOR)),
            turn.run
        )
        assertEquals(0, llm.parseCalls)
    }

    @Test
    fun systemCommandWorksOffline() {
        llm.unavailable = true
        assertEquals(listOf<AppIntent>(AppIntent.SystemControl(SystemAction.HOME)), say("go home").run)
    }

    @Test
    fun otherCommandsStillGoToTheLlm() {
        commands(AppIntent.AppTask("message Bob"))
        say("turn on wifi and message Bob")
        assertEquals(1, llm.parseCalls)
    }

    @Test
    fun proxyDownRunsNothing() {
        llm.unavailable = true
        val turn = say("message Bob on WhatsApp")
        assertTrue(turn.run.isEmpty())
        assertTrue(turn.says.single().contains("can't reach"))
    }

    @Test
    fun modeFollowUpOffersButtons() {
        commands(AppIntent.Navigate("airport", null))
        val asked = say("take me to the airport")
        assertTrue(asked.run.isEmpty())
        assertEquals(listOf("Bus", "Walking", "Driving"), asked.choices)
        assertEquals(listOf<AppIntent>(navToAirport), say("Bus").run)
    }

    @Test
    fun chainedCommandsCollectMissingDetailsBeforeRunning() {
        commands(AppIntent.Battery, AppIntent.Navigate("airport", null))
        assertTrue(say("check battery and navigate to the airport").run.isEmpty())
        assertEquals(listOf(AppIntent.Battery, navToAirport), say("bus").run)
    }

    @Test
    fun repeatRunsPreviousCommandAgain() {
        commands(navToAirport)
        assertEquals(listOf<AppIntent>(navToAirport), say("bus to the airport").run)
        commands(AppIntent.RepeatLast)
        assertEquals(listOf<AppIntent>(navToAirport), say("repeat").run)
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
        assertEquals(FollowUpRules.Confirmation.NO, FollowUpRules.confirmation("no, I'm not sure"))
        assertEquals(FollowUpRules.Confirmation.YES, FollowUpRules.confirmation("Confirm"))
        assertEquals(FollowUpRules.Confirmation.UNCLEAR, FollowUpRules.confirmation("what's the weather"))
    }

    @Test
    fun missingDetailsAskTheLlmsQuestionThenRun() {
        llm.commands = listOf(
            LlmCommand(
                AppIntent.Navigate(null, null),
                mapOf(FollowUpSlot.DESTINATION to "Where would you like to navigate?")
            )
        )
        assertEquals("Where would you like to navigate?", say("I need directions").says.single())
        assertEquals(FollowUpSlot.MODE.defaultQuestion(), say("airport").says.single())
        assertEquals(listOf<AppIntent>(navToAirport), say("bus").run)
        assertEquals(0, llm.answerCalls)
        assertEquals(1, llm.parseCalls)
    }

    @Test
    fun llmReadsAnAnswerTheRulesCannot() {
        commands(AppIntent.Navigate("airport", null))
        say("take me to the airport")
        llm.answer = LlmAnswer(LlmAnswer.Status.FILLED, value = "driving")
        assertEquals(listOf<AppIntent>(AppIntent.Navigate("airport", "driving")), say("I'll take my sedan").run)
        assertEquals(1, llm.answerCalls)
    }

    @Test
    fun llmValueThatCannotFillTheSlotAsksAgain() {
        commands(AppIntent.Navigate("airport", null))
        say("take me to the airport")
        llm.answer = LlmAnswer(LlmAnswer.Status.FILLED, value = "teleport")
        assertTrue(say("by teleport").says.single().startsWith("Sorry"))
    }

    @Test
    fun cancellingADetailDropsTheCommand() {
        commands(AppIntent.Navigate("airport", null))
        say("take me to the airport")
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
    fun bookingAsksWithTheLlmsWording() {
        llm.commands = listOf(
            LlmCommand(
                AppIntent.HotelSearch("", book = true),
                bookingQuestions = mapOf(
                    BookingConversation.Slot.DESTINATION to "Which city are you heading to?",
                    BookingConversation.Slot.DATES to "Which nights do you need?"
                )
            )
        )
        assertEquals("Which city are you heading to?", say("book a hotel").says.single())
        // HOTEL wasn't worded by the LLM, so the built-in question is used.
        assertTrue(say("Tokyo").says.single().startsWith("Which hotel in Tokyo"))
        assertEquals("Which nights do you need?", say("any").says.single())
    }

    @Test
    fun bookingStillAsksWhenTheLlmListsNothing() {
        commands(AppIntent.HotelSearch("", book = true))
        assertEquals("Where would you like to stay?", say("book a hotel").says.single())
    }

    @Test
    fun aRetriedBookingQuestionUsesTheBuiltInExamples() {
        llm.commands = listOf(
            LlmCommand(
                AppIntent.HotelSearch("Tokyo", hotel = "Hilton", book = true),
                bookingQuestions = mapOf(BookingConversation.Slot.DATES to "Which nights?")
            )
        )
        assertEquals("Which nights?", say("book the Hilton in Tokyo").says.single())
        assertTrue(say("purple").says.single().startsWith("Sorry, I didn't catch that. When are you staying?"))
    }

    @Test
    fun bookingFallsBackToLlm() {
        commands(AppIntent.HotelSearch("Tokyo", hotel = "Hilton", book = true))
        say("book the Hilton in Tokyo")
        llm.answer = LlmAnswer(LlmAnswer.Status.FILLED, checkIn = StayDate(2026, 12, 5), checkOut = StayDate(2026, 12, 7))
        assertTrue(say("the first weekend of December").says.single().contains("How many guests"))
        assertEquals(1, llm.answerCalls)
    }

    @Test
    fun alarmFollowUpParsesLocallyWithoutLlm() {
        val askTurn = say("幫我設鬧鐘")
        assertTrue(askTurn.run.isEmpty())
        assertTrue(askTurn.choices.isNotEmpty())
        val doneTurn = say("早上七點半")
        assertEquals(listOf<AppIntent>(AppIntent.SetAlarm(7, 30)), doneTurn.run)
        assertEquals(0, llm.parseCalls)
        assertEquals(0, llm.answerCalls)
    }

    @Test
    fun timerFollowUpParsesLocallyWithoutLlm() {
        val askTurn = say("幫我倒計時")
        assertTrue(askTurn.run.isEmpty())
        assertTrue(askTurn.choices.isNotEmpty())
        val doneTurn = say("10 分鐘")
        assertEquals(listOf<AppIntent>(AppIntent.SetTimer(600)), doneTurn.run)
        assertEquals(0, llm.parseCalls)
        assertEquals(0, llm.answerCalls)
    }
}
