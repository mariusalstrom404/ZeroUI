package com.example.myapplication.nlp

import com.example.myapplication.nlp.BookingConversation.Outcome
import com.example.myapplication.nlp.BookingConversation.Slot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BookingConversationTest {

    private val today = StayDate(2026, 10, 1)
    private val parser = RuleBasedParser()

    private fun start(command: String): Pair<BookingConversation, Outcome> {
        val intent = parser.parse(command) as AppIntent.HotelSearch
        val conversation = BookingConversation(intent, today)
        return conversation to conversation.start()
    }

    private fun Outcome.slot() = (this as Outcome.Ask).slot

    // --- guest parsing ---

    @Test
    fun guestsInTheRequest() {
        val p = HotelQuery.parse("book the Hilton in Tokyo from Dec 5 to Dec 8 for 2 adults and 1 child aged 5 on booking.com", today)
        assertEquals("Hilton", p.hotel)
        assertEquals("Tokyo", p.destination)
        assertEquals(2, p.adults)
        assertEquals(1, p.children)
        assertEquals(listOf(5), p.childAges)
    }

    @Test
    fun guestWordings() {
        assertEquals(3, HotelQuery.parse("hotels in Paris for 3 people on booking.com", today).adults)
        assertEquals(1, HotelQuery.parse("hotels in Paris just me on booking.com", today).adults)
        assertEquals(2, HotelQuery.parse("hotels in Paris for a couple on booking.com", today).adults)
        val kids = HotelQuery.parse("hotels in Paris with 2 kids aged 5 and 8 and 2 adults on booking.com", today)
        assertEquals(listOf(5, 8), kids.childAges)
        assertEquals(2, kids.adults)
        assertEquals("Paris", kids.destination)
    }

    @Test
    fun roomCountAndType() {
        val p = HotelQuery.parse("book 2 twin rooms at the Hilton for 4 adults on booking.com", today)
        assertEquals(2, p.rooms)
        assertEquals("twin room", p.room)
        assertEquals("Hilton", p.hotel)
        assertEquals(4, p.adults)
    }

    @Test
    fun guestAnswers() {
        assertEquals(2, HotelQuery.parseGuestAnswer("2").adults)
        assertEquals(2, HotelQuery.parseGuestAnswer("two adults").adults)
        val family = HotelQuery.parseGuestAnswer("2 adults and a child")
        assertEquals(2, family.adults)
        assertEquals(1, family.children)
        assertEquals(listOf(0, 4), HotelQuery.parseAges("a baby and a 4 year old"))
    }

    @Test
    fun commandCarriesGuests() {
        val guests = HotelQuery.Guests(2, listOf(5, 8), rooms = 2)
        val command = HotelQuery.command("Tokyo", "Hilton", StayDate(2026, 12, 5), StayDate(2026, 12, 8), book = true, room = "twin room", guests = guests)
        assertTrue(command, !command.contains(" and "))
        val p = HotelQuery.parse(command, today)
        assertEquals("Hilton", p.hotel)
        assertEquals("twin room", p.room)
        assertEquals(2, p.adults)
        assertEquals(listOf(5, 8), p.childAges)
        assertEquals(2, p.rooms)
    }

    // --- conversation ---

    @Test
    fun everythingGivenStartsRightAway() {
        val (_, outcome) = start("book a twin room at the Hilton in Tokyo from Dec 5 to Dec 8 for 2 adults on booking.com")
        assertTrue(outcome is Outcome.Done)
        val p = HotelQuery.parse((outcome as Outcome.Done).command, today)
        assertEquals("Hilton", p.hotel)
        assertEquals("twin room", p.room)
        assertEquals(2, p.adults)
        assertEquals(StayDate(2026, 12, 5), p.checkIn)
    }

    @Test
    fun asksForEachMissingDetailInTurn() {
        val (c, first) = start("book a hotel in Osaka on booking.com")
        assertEquals(Slot.HOTEL, first.slot())
        assertEquals(Slot.DATES, c.answer("the RIHGA Royal Hotel").slot())
        assertEquals(Slot.GUESTS, c.answer("Dec 5 to Dec 8").slot())
        assertEquals(Slot.CHILD_AGES, c.answer("2 adults and 1 child").slot())
        assertEquals(Slot.ROOM, c.answer("6").slot())
        val done = c.answer("double") as Outcome.Done
        val p = HotelQuery.parse(done.command, today)
        assertTrue(p.book)
        assertEquals("RIHGA Royal Hotel", p.hotel)
        assertEquals("Osaka", p.destination)
        assertEquals(StayDate(2026, 12, 8), p.checkOut)
        assertEquals(2, p.adults)
        assertEquals(listOf(6), p.childAges)
        assertEquals("double room", p.room)
    }

    @Test
    fun oneAnswerCanFillSeveralDetails() {
        val (c, _) = start("book the Hilton in Tokyo on booking.com")
        val next = c.answer("Dec 5 to 8 for 2 adults")
        assertEquals(Slot.ROOM, next.slot())
        assertTrue(c.answer("any") is Outcome.Done)
    }

    @Test
    fun anyHotelBecomesASearchWithoutRoomQuestion() {
        val (c, _) = start("book a hotel in Kyoto from Dec 5 to Dec 8 on booking.com")
        assertEquals(Slot.GUESTS, c.answer("any").slot())
        val done = c.answer("just me") as Outcome.Done
        val p = HotelQuery.parse(done.command, today)
        assertTrue(!p.book)
        assertEquals("Kyoto", p.destination)
        assertEquals(1, p.adults)
    }

    @Test
    fun unclearAnswerIsAskedAgain() {
        val (c, _) = start("book the Hilton in Tokyo on booking.com")
        val again = c.answer("sometime soon") as Outcome.Ask
        assertEquals(Slot.DATES, again.slot)
        assertTrue(again.question.startsWith("Sorry"))
    }

    @Test
    fun askWhereFirstWhenNothingGiven() {
        val (c, first) = start("book a hotel on booking.com")
        assertEquals(Slot.DESTINATION, first.slot())
        assertEquals(Slot.HOTEL, c.answer("Tokyo").slot())
    }

    @Test
    fun cancel() {
        val (c, _) = start("book a hotel in Osaka on booking.com")
        assertEquals(Outcome.Cancelled, c.answer("never mind"))
    }

    @Test
    fun aloftKeepsItsA() {
        val (c, _) = start("book a hotel in Osaka on booking.com")
        c.answer("Aloft Osaka Dojima")
        c.answer("Dec 5 to Dec 8")
        c.answer("2 adults")
        val done = c.answer("any") as Outcome.Done
        assertEquals("Aloft Osaka Dojima", HotelQuery.parse(done.command, today).hotel)
    }
}
