package com.example.myapplication.nlp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HotelQueryTest {

    private val today = StayDate(2026, 10, 1)

    private fun parse(command: String) = HotelQuery.parse(command, today)

    @Test
    fun destinationOnly() {
        val p = parse("find a hotel in Tokyo on booking.com")
        assertEquals("Tokyo", p.destination)
        assertNull(p.checkIn)
        assertNull(p.checkOut)
    }

    @Test
    fun bookingNameVariants() {
        for (command in listOf(
            "search for hotels in Paris on booking.com",
            "search for hotels in Paris on booking com",
            "search for hotels in Paris on bookingcom",
            "search for hotels in Paris using Booking",
            "search for hotels in Paris on booking dot com"
        )) {
            assertTrue(command, HotelQuery.mentionsBooking(command))
            assertEquals(command, "Paris", parse(command).destination)
        }
        assertFalse(HotelQuery.mentionsBooking("stop booking the meeting room"))
    }

    @Test
    fun dateRangeWithMonthOnBothEnds() {
        val p = parse("hotels in New York from October 10 to October 12 on booking.com")
        assertEquals("New York", p.destination)
        assertEquals(StayDate(2026, 10, 10), p.checkIn)
        assertEquals(StayDate(2026, 10, 12), p.checkOut)
    }

    @Test
    fun dateRangeSharingTheMonth() {
        val p = parse("book a room in Osaka Oct 10th to 13th on booking.com")
        assertEquals("Osaka", p.destination)
        assertEquals(StayDate(2026, 10, 10), p.checkIn)
        assertEquals(StayDate(2026, 10, 13), p.checkOut)
    }

    @Test
    fun dayBeforeMonth() {
        val p = parse("find stays in Taipei from 3 Nov to 5 Nov on booking.com")
        assertEquals("Taipei", p.destination)
        assertEquals(StayDate(2026, 11, 3), p.checkIn)
        assertEquals(StayDate(2026, 11, 5), p.checkOut)
    }

    @Test
    fun nightsFromCheckIn() {
        val p = parse("hotel in Seoul on November 20 for 3 nights on booking.com")
        assertEquals("Seoul", p.destination)
        assertEquals(StayDate(2026, 11, 20), p.checkIn)
        assertEquals(StayDate(2026, 11, 23), p.checkOut)
    }

    @Test
    fun relativeDates() {
        val tonight = parse("find a hotel in Kyoto tonight on booking.com")
        assertEquals("Kyoto", tonight.destination)
        assertEquals(today, tonight.checkIn)
        assertEquals(StayDate(2026, 10, 2), tonight.checkOut)

        val tomorrow = parse("hotels in Kyoto tomorrow for two nights on booking.com")
        assertEquals(StayDate(2026, 10, 2), tomorrow.checkIn)
        assertEquals(StayDate(2026, 10, 4), tomorrow.checkOut)
    }

    @Test
    fun pastDateMeansNextYear() {
        val p = parse("hotels in London from Mar 3 to Mar 5 on booking.com")
        assertEquals(StayDate(2027, 3, 3), p.checkIn)
        assertEquals(StayDate(2027, 3, 5), p.checkOut)
    }

    @Test
    fun rangeAcrossNewYear() {
        val p = parse("hotels in Sydney from Dec 30 to Jan 2 on booking.com")
        assertEquals(StayDate(2026, 12, 30), p.checkIn)
        assertEquals(StayDate(2027, 1, 2), p.checkOut)
    }

    @Test
    fun bookNamedHotel() {
        val p = parse("book the Shinagawa Prince Hotel on booking.com")
        assertTrue(p.book)
        assertEquals("Shinagawa Prince Hotel", p.hotel)
        assertEquals("", p.destination)
    }

    @Test
    fun bookNamedHotelInCity() {
        val p = parse("book the Hilton in Tokyo from Dec 5 to Dec 8 on booking.com")
        assertTrue(p.book)
        assertEquals("Hilton", p.hotel)
        assertEquals("Tokyo", p.destination)
        assertEquals(StayDate(2026, 12, 5), p.checkIn)
        assertEquals(StayDate(2026, 12, 8), p.checkOut)
    }

    @Test
    fun nameStartingWithHotelWord() {
        val p = parse("book the Hotel Gracery in Shinjuku on booking.com")
        assertEquals("Hotel Gracery", p.hotel)
        assertEquals("Shinjuku", p.destination)
    }

    @Test
    fun holidayInnIsNotSplitOnIn() {
        val p = parse("book Holiday Inn Express in Osaka on booking.com")
        assertEquals("Holiday Inn Express", p.hotel)
        assertEquals("Osaka", p.destination)
    }

    @Test
    fun roomTypeAtHotel() {
        val p = parse("reserve a twin room at RIHGA Royal Hotel Osaka for Dec 5 to Dec 8 on booking.com")
        assertTrue(p.book)
        assertEquals("twin room", p.room)
        assertEquals("RIHGA Royal Hotel Osaka", p.hotel)
        assertEquals(StayDate(2026, 12, 5), p.checkIn)
    }

    @Test
    fun bookWithoutHotelLeavesItToTheUser() {
        val p = parse("book a hotel in Tokyo on booking.com")
        assertTrue(p.book)
        assertNull(p.hotel)
        assertEquals("Tokyo", p.destination)
    }

    @Test
    fun searchIsNotBooking() {
        val p = parse("find a hotel in Tokyo on booking.com")
        assertFalse(p.book)
        assertNull(p.hotel)
    }

    @Test
    fun commandRoundTrips() {
        val booked = parse(HotelQuery.command("Tokyo", "Hilton", StayDate(2026, 12, 5), StayDate(2026, 12, 8), book = true, room = "twin room"))
        assertEquals(HotelQuery.Parsed("Tokyo", StayDate(2026, 12, 5), StayDate(2026, 12, 8), "Hilton", true, "twin room"), booked)

        val search = parse(HotelQuery.command("Tokyo", null, null, null, book = false))
        assertEquals(HotelQuery.Parsed("Tokyo", null, null, null, false, null), search)

        val nextYear = parse(HotelQuery.command("London", "The Savoy", StayDate(2027, 3, 3), StayDate(2027, 3, 5), book = true))
        assertEquals("Savoy", nextYear.hotel)
        assertEquals(StayDate(2027, 3, 3), nextYear.checkIn)
    }

    @Test
    fun parserRoutesBookingToHotelSearch() {
        val parser = RuleBasedParser()
        assertTrue(parser.parse("search for hotels in Tokyo on booking.com") is AppIntent.HotelSearch)
        assertTrue(parser.parse("find me a place to stay in Rome on booking.com") is AppIntent.HotelSearch)
        assertEquals(AppIntent.OpenApp("Booking.com"), parser.parse("open booking.com"))
        val book = parser.parse("book the Hilton in Tokyo on booking.com") as AppIntent.HotelSearch
        assertTrue(book.book)
        assertEquals("Hilton", book.hotel)
    }

    @Test
    fun longLabelMatchesBookingCalendar() {
        assertEquals("October 10, 2026", StayDate(2026, 10, 10).longLabel())
        assertEquals("Oct 10", StayDate(2026, 10, 10).label())
    }
}
