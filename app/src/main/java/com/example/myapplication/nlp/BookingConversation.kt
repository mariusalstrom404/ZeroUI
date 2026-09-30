package com.example.myapplication.nlp

/**
 * Collects what a Booking.com booking needs before anything is tapped, asking only for what the
 * user hasn't said yet: where → which hotel (or "any", which turns it into a search) → dates →
 * guests → children's ages → room type. An answer may fill several details at once
 * ("Dec 5 to 8 for 2 adults"), and a request that already has everything finishes immediately.
 *
 * The result is a command in words [HotelQuery.parse] reads back, sent to the service as usual.
 */
class BookingConversation(start: AppIntent.HotelSearch, private val today: StayDate = StayDate.today()) {

    enum class Slot { DESTINATION, HOTEL, DATES, GUESTS, CHILD_AGES, ROOM }

    sealed class Outcome {
        data class Ask(val slot: Slot, val question: String) : Outcome()
        /** Everything is known: say [summary], then send [command] to the service. */
        data class Done(val command: String, val summary: String) : Outcome()
        data object Cancelled : Outcome()
    }

    private var destination = start.destination
    private var hotel = start.hotel
    private var anyHotel = false
    private var checkIn = start.checkIn
    private var checkOut = start.checkOut
    private var adults = start.adults
    private var children = start.children
    private var childAges = start.childAges
    private var rooms = start.rooms
    private var room = start.room
    private var anyRoom = false

    companion object {
        private val NO_PREFERENCE = Regex(
            "^(?:any|anything|anywhere|any ?one|whatever|no|nope|none|not sure|no idea|i don'?t know|dont know|" +
                "no preference|doesn'?t matter|you choose|you pick|skip|just search|just show|show me|" +
                "(?:the )?(?:first|cheapest|any)(?: one| room| available| hotel)?)\\b",
            RegexOption.IGNORE_CASE
        )
        private val CANCEL = Regex("^(?:cancel|never ?mind|forget it|stop)\\b", RegexOption.IGNORE_CASE)
        /** "the Hilton", "book the Hilton", "let's go with the Hilton please" → "Hilton". */
        private val ANSWER_LEAD_IN = Regex(
            "^(?:(?:please\\s+)?(?:book|reserve|i'?d like|i want|let'?s do|let'?s go with|go with|how about)\\s+)?(?:(?:a|an|at|the)\\s+)*",
            RegexOption.IGNORE_CASE
        )
        private val ROOM_TYPES = setOf(
            "single", "double", "twin", "queen", "king", "family", "deluxe", "standard", "superior", "executive", "triple", "quadruple"
        )
    }

    fun start(): Outcome = next(retry = false)

    fun answer(text: String): Outcome {
        val answer = text.trim().trimEnd('.', '!', '?')
        if (CANCEL.containsMatchIn(answer)) return Outcome.Cancelled
        val slot = missing() ?: return next(retry = false)
        val noPreference = NO_PREFERENCE.containsMatchIn(answer)

        when (slot) {
            Slot.DESTINATION -> {
                val parsed = HotelQuery.parse("find hotels in $answer", today)
                destination = parsed.destination.ifEmpty { answer }
                absorb(parsed)
            }
            Slot.HOTEL -> if (noPreference) {
                anyHotel = true
            } else {
                val parsed = HotelQuery.parse("book ${answer.replace(ANSWER_LEAD_IN, "")}", today)
                hotel = parsed.hotel ?: parsed.destination.ifEmpty { null }
                if (parsed.hotel != null && parsed.destination.isNotEmpty()) destination = parsed.destination
                absorb(parsed)
            }
            Slot.DATES -> absorb(HotelQuery.parse(answer, today))
            Slot.GUESTS -> {
                absorb(HotelQuery.parseGuestAnswer(answer))
                absorb(HotelQuery.parse(answer, today))
            }
            Slot.CHILD_AGES -> {
                val ages = HotelQuery.parseAges(answer)
                if (ages.size >= (children ?: 0)) childAges = ages.take(children ?: 0)
            }
            Slot.ROOM -> if (noPreference) anyRoom = true else room = roomType(answer)
        }
        return next(retry = missing() == slot)
    }

    /** Fills whatever [parsed] mentions that isn't known yet; never overwrites an earlier answer. */
    private fun absorb(parsed: HotelQuery.Parsed) {
        if (checkIn == null && parsed.checkIn != null) {
            checkIn = parsed.checkIn
            checkOut = parsed.checkOut
        }
        if (adults == null) adults = parsed.adults
        if (children == null && parsed.children != null) {
            children = parsed.children
            childAges = parsed.childAges
        }
        if (rooms == null) rooms = parsed.rooms
        if (room == null) room = parsed.room
    }

    private fun roomType(answer: String): String {
        val cleaned = answer.replace(ANSWER_LEAD_IN, "").replace(Regex("\\s+please$", RegexOption.IGNORE_CASE), "").trim()
        return if (cleaned.lowercase() in ROOM_TYPES) "${cleaned.lowercase()} room" else cleaned
    }

    private fun missing(): Slot? = when {
        destination.isEmpty() && hotel == null -> Slot.DESTINATION
        hotel == null && !anyHotel -> Slot.HOTEL
        checkIn == null -> Slot.DATES
        adults == null -> Slot.GUESTS
        (children ?: 0) > childAges.size -> Slot.CHILD_AGES
        hotel != null && room == null && !anyRoom -> Slot.ROOM
        else -> null
    }

    private fun next(retry: Boolean): Outcome {
        val slot = missing() ?: return done()
        val sorry = if (retry) "Sorry, I didn't catch that. " else ""
        return Outcome.Ask(slot, sorry + question(slot))
    }

    private fun question(slot: Slot): String = when (slot) {
        Slot.DESTINATION -> "Where would you like to stay?"
        Slot.HOTEL -> "Which hotel in $destination would you like to book? Say its name, or say “any” and I'll show you the hotels there."
        Slot.DATES -> "When are you staying? For example, “Dec 5 to Dec 8” or “tomorrow for 2 nights”."
        Slot.GUESTS -> "How many guests? For example, “2 adults” or “2 adults and 1 child”."
        Slot.CHILD_AGES -> if ((children ?: 0) == 1) {
            "How old is the child? Booking.com needs their age."
        } else {
            "How old are the $children children? Booking.com needs each age, for example “5 and 8”."
        }
        Slot.ROOM -> "What type of room would you like at $hotel? For example single, double, twin or a suite. Say “any” for the first one available."
    }

    private fun done(): Outcome.Done {
        val guests = HotelQuery.Guests(adults ?: 2, childAges.take(children ?: 0), rooms ?: 1)
        val start = checkIn
        val dates = start?.let { "${it.label()} – ${(checkOut ?: it.plusDays(1)).label()}" }
        val booking = hotel != null
        val command = HotelQuery.command(destination, hotel, checkIn, checkOut, book = booking, room = room, guests = guests)
        val details = listOfNotNull(dates, guests.label(), room?.takeIf { booking }).joinToString(", ")
        val summary = if (booking) {
            "Okay, booking $hotel: $details. I'll stop before payment so you can check everything."
        } else {
            "Okay, I'll show you the hotels in $destination: $details."
        }
        return Outcome.Done(command, summary)
    }
}
