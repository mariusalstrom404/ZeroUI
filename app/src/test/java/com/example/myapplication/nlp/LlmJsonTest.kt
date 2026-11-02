package com.example.myapplication.nlp

import com.example.myapplication.automation.ScreenAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmJsonTest {

    private val today = StayDate(2026, 10, 1)

    private fun intent(fields: String): AppIntent =
        LlmJson.commands("""{"commands":[{$fields,"missing":[]}]}""", today).single().intent

    @Test
    fun readsTransfer() {
        assertEquals(AppIntent.Transfer(50, "Bob"), intent(""""intent":"TRANSFER","amount":50,"recipient":"Bob""""))
    }

    @Test
    fun invalidAmountReadsAsMissing() {
        assertNull((intent(""""intent":"TRANSFER","amount":-5,"recipient":"Bob"""") as AppIntent.Transfer).amount)
    }

    @Test
    fun keepsSeveralCommandsInOrder() {
        val commands = LlmJson.commands(
            """{"commands":[{"intent":"CHECK_BALANCE","missing":[]},{"intent":"TAKE_SCREENSHOT","missing":[]}]}""", today
        )
        assertEquals(listOf(AppIntent.CheckBalance, AppIntent.TakeScreenshot), commands.map { it.intent })
    }

    @Test
    fun readsMissingQuestions() {
        val command = LlmJson.commands(
            """{"commands":[{"intent":"TRANSFER","amount":null,"recipient":null,"missing":[{"slot":"AMOUNT","question":"How much?"}]}]}""", today
        ).single()
        assertEquals("How much?", command.questions[FollowUpSlot.AMOUNT])
    }

    @Test
    fun readsHotel() {
        val hotel = intent(
            """"intent":"HOTEL","destination":"Tokyo","hotel":"Hilton","book":true,"check_in":"2026-12-05","check_out":"2026-12-08","adults":2,"children":1,"child_ages":[5],"rooms":1,"room":"twin room""""
        ) as AppIntent.HotelSearch
        assertEquals("Tokyo", hotel.destination)
        assertEquals(StayDate(2026, 12, 5), hotel.checkIn)
        assertEquals(StayDate(2026, 12, 8), hotel.checkOut)
        assertEquals(listOf(5), hotel.childAges)
        assertTrue(hotel.book)
    }

    @Test
    fun badDatesAreDropped() {
        val past = intent(""""intent":"HOTEL","destination":"Tokyo","check_in":"2026-09-01","check_out":"2026-09-02"""") as AppIntent.HotelSearch
        assertNull(past.checkIn)
        val impossible = intent(""""intent":"HOTEL","destination":"Tokyo","check_in":"2026-02-31","check_out":null""") as AppIntent.HotelSearch
        assertNull(impossible.checkIn)
    }

    @Test
    fun checkOutNotAfterCheckInBecomesOneNight() {
        val stay = intent(""""intent":"HOTEL","destination":"Tokyo","check_in":"2026-12-05","check_out":"2026-12-05"""") as AppIntent.HotelSearch
        assertEquals(StayDate(2026, 12, 6), stay.checkOut)
    }

    @Test
    fun unknownIntentNameIsUnknown() {
        assertEquals(AppIntent.Unknown, intent(""""intent":"LAUNCH_ROCKET""""))
    }

    @Test(expected = LlmUnavailableException::class)
    fun malformedJsonIsUnavailable() {
        LlmJson.commands("not json", today)
    }

    @Test(expected = LlmUnavailableException::class)
    fun noCommandsIsUnavailable() {
        LlmJson.commands("""{"commands":[]}""", today)
    }

    @Test
    fun requestAsksForStrictUnstoredJson() {
        val request = org.json.JSONObject(LlmJson.request("m", "voice_commands", """{"type":"object"}""", "rules", "hi"))
        assertEquals("m", request.getString("model"))
        assertEquals(false, request.getBoolean("store"))
        val format = request.getJSONObject("text").getJSONObject("format")
        assertEquals("json_schema", format.getString("type"))
        assertTrue(format.getBoolean("strict"))
        assertEquals("object", format.getJSONObject("schema").getString("type"))
    }

    @Test
    fun readsTextFromAResponse() {
        val reply = """{"output":[{"type":"reasoning"},{"type":"message","content":[{"type":"output_text","text":"{\"commands\":[]}"}]}]}"""
        assertEquals("""{"commands":[]}""", LlmJson.outputText(reply))
    }

    @Test(expected = LlmUnavailableException::class)
    fun responseWithoutTextIsUnavailable() {
        LlmJson.outputText("""{"output":[{"type":"message","content":[{"type":"refusal","refusal":"no"}]}]}""")
    }

    @Test
    fun readsAnswer() {
        val answer = LlmJson.answer("""{"answer":{"status":"filled","value":"50","adults":2,"child_ages":[99,5]}}""", today)
        assertEquals(LlmAnswer.Status.FILLED, answer.status)
        assertEquals("50", answer.value)
        assertEquals(2, answer.adults)
        assertEquals(listOf(5), answer.childAges)
    }

    @Test
    fun readsAnswerWithOnlyAStatus() {
        val answer = LlmJson.answer("""{"answer":{"status":"unclear"}}""", today)
        assertEquals(LlmAnswer.Status.UNCLEAR, answer.status)
        assertNull(answer.value)
        assertEquals(emptyList<Int>(), answer.childAges)
    }

    @Test
    fun bookingQuestionsGoToTheBookingConversation() {
        val command = LlmJson.commands(
            """{"commands":[{"intent":"HOTEL","destination":"Tokyo","hotel":null,"book":true,"missing":[""" +
                """{"slot":"HOTEL","question":"Which hotel?"},{"slot":"DATES","question":"When?"},{"slot":"DESTINATION","question":"Where?"}]}]}""",
            today
        ).single()
        assertEquals("Which hotel?", command.bookingQuestions[BookingConversation.Slot.HOTEL])
        assertEquals("When?", command.bookingQuestions[BookingConversation.Slot.DATES])
        assertTrue(command.questions.isEmpty())
    }

    @Test
    fun hotelSearchQuestionsStayFollowUps() {
        val command = LlmJson.commands(
            """{"commands":[{"intent":"HOTEL","destination":null,"hotel":null,"book":false,"missing":[{"slot":"DESTINATION","question":"Where?"}]}]}""",
            today
        ).single()
        assertEquals("Where?", command.questions[FollowUpSlot.DESTINATION])
        assertTrue(command.bookingQuestions.isEmpty())
    }

    @Test
    fun readsAppTask() {
        assertEquals(
            AppIntent.AppTask("turn on dark mode", "Settings"),
            intent(""""intent":"APP_TASK","goal":"turn on dark mode","app":"Settings"""")
        )
        assertEquals(AppIntent.Unknown, intent(""""intent":"APP_TASK","goal":null,"app":null"""))
    }

    @Test
    fun readsScreenActions() {
        assertEquals(
            ScreenAction.Tap(7),
            LlmJson.screenAction("""{"action":"tap","node":7,"text":null,"submit":false,"say":null}""")
        )
        assertEquals(
            ScreenAction.Type(2, "pizza", true),
            LlmJson.screenAction("""{"action":"type","node":2,"text":"pizza","submit":true,"say":null}""")
        )
        assertEquals(
            ScreenAction.Ask("Small or large?"),
            LlmJson.screenAction("""{"action":"ask_user","node":null,"text":null,"submit":false,"say":"Small or large?"}""")
        )
    }

    @Test
    fun negativeNodeReadsAsMissing() {
        assertEquals(ScreenAction.Tap(null), LlmJson.screenAction("""{"action":"tap","node":-1}"""))
    }

    @Test(expected = LlmUnavailableException::class)
    fun unknownScreenActionIsUnavailable() {
        LlmJson.screenAction("""{"action":"pay","node":1}""")
    }

    @Test
    fun readsIntentWithoutDetailFields() {
        val command = LlmJson.commands("""{"commands":[{"intent":"BATTERY"}]}""", today).single()
        assertEquals(AppIntent.Battery, command.intent)
        assertTrue(command.questions.isEmpty())
    }
}
