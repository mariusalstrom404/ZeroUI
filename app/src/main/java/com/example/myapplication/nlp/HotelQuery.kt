package com.example.myapplication.nlp

import java.util.Calendar
import java.util.GregorianCalendar

/** A calendar day without time or time zone. [month] is 1-12. (java.time needs API 26; minSdk is 24.) */
data class StayDate(val year: Int, val month: Int, val day: Int) : Comparable<StayDate> {

    fun plusDays(days: Int): StayDate {
        val cal = GregorianCalendar(year, month - 1, day).apply { add(Calendar.DAY_OF_MONTH, days) }
        return of(cal)
    }

    /** "Oct 10", for replies and status messages. */
    fun label(): String = "${MONTH_NAMES[month - 1].take(3)} $day"

    /** "October 10, 2026": the end of the day cells' content descriptions in Booking.com's calendar. */
    fun longLabel(): String = "${MONTH_NAMES[month - 1]} $day, $year"

    override fun compareTo(other: StayDate): Int =
        compareValuesBy(this, other, { it.year }, { it.month }, { it.day })

    companion object {
        val MONTH_NAMES = listOf(
            "January", "February", "March", "April", "May", "June",
            "July", "August", "September", "October", "November", "December"
        )

        fun of(cal: Calendar) = StayDate(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))

        fun today(): StayDate = of(Calendar.getInstance())
    }
}

/**
 * Splits a Booking.com command into what to search for, whether to book, and the stay dates:
 * - "find a hotel in Tokyo from Oct 10 to 12 on booking.com" → search Tokyo, Oct 10 – Oct 12
 * - "book a hotel in Tokyo on booking.com" → book, but no hotel named yet (the UI asks which one)
 * - "book a twin room at the Hilton in Tokyo on booking.com" → book "Hilton" in Tokyo, "twin room"
 * - "book RIHGA Royal Hotel Osaka on booking.com" → book that hotel (a bare name after "book")
 *
 * Understands "from <date> to <date>", "on/from <date>", "for N nights", "today/tonight/tomorrow",
 * and dates written "Oct 10", "October 10th", "10 Oct", "10th of October" (optionally with a year).
 * A date without a year that has already passed this year means next year.
 */
object HotelQuery {

    data class Parsed(
        /** City/area to stay in; may be empty when only a [hotel] was named. */
        val destination: String,
        val checkIn: StayDate?,
        val checkOut: StayDate?,
        /** A specific property ("Hilton"), or null for "a hotel in <destination>". */
        val hotel: String? = null,
        /** "book"/"reserve" rather than "find"/"search". */
        val book: Boolean = false,
        /** Room type asked for ("twin room"), matched against the room names on the hotel's page. */
        val room: String? = null,
        /** Guests as said; null when not mentioned. */
        val adults: Int? = null,
        val children: Int? = null,
        /** Ages of the children, when given ("2 kids aged 5 and 8"). Booking.com needs one per child. */
        val childAges: List<Int> = emptyList(),
        val rooms: Int? = null
    )

    /** Who is staying: Booking.com's "rooms and guests" picker. */
    data class Guests(val adults: Int, val childAges: List<Int> = emptyList(), val rooms: Int = 1) {
        /** "2 adults, 1 child (5)". */
        fun label(): String = buildList {
            add(plural(adults, "adult"))
            if (childAges.isNotEmpty()) add("${plural(childAges.size, "child", "children")} (${childAges.joinToString(", ")})")
            if (rooms > 1) add(plural(rooms, "room"))
        }.joinToString(", ")
    }

    private fun plural(n: Int, one: String, many: String = "${one}s") = "$n ${if (n == 1) one else many}"

    private const val MONTH =
        "jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?"
    private const val DAY = "\\d{1,2}(?:st|nd|rd|th)?"
    private const val YEAR = "(?:,?\\s+\\d{4})?"
    private const val DATE =
        "(?:(?:$MONTH)\\.?\\s+$DAY$YEAR|$DAY\\s+(?:of\\s+)?(?:$MONTH)\\.?$YEAR|today|tonight|tomorrow)"

    private val RANGE = Regex(
        "\\b(?:from\\s+)?($DATE)\\s*(?:to|until|till|through|-|–)\\s*($DATE|$DAY)\\b",
        RegexOption.IGNORE_CASE
    )
    private val SINGLE = Regex(
        "\\b(?:(?:on|from|for|starting|arriving|check(?:ing)?\\s+in)\\s+)?($DATE)\\b",
        RegexOption.IGNORE_CASE
    )
    private val NIGHTS = Regex(
        "\\bfor\\s+(\\d+|a|one|two|three|four|five|six|seven|eight|nine|ten)\\s+nights?\\b",
        RegexOption.IGNORE_CASE
    )
    private val NUMBER_WORDS = listOf("one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten")

    // --- Guests: "for 2 adults and 1 child aged 5", "3 people", "2 twin rooms", "just me" ---
    private const val NUM = "\\d{1,2}|a|an|one|two|three|four|five|six|seven|eight|nine|ten"
    /** Counts for rooms leave out "a": "a twin room" is a room type, not a number of rooms. */
    private const val ROOM_NUM = "\\d{1,2}|one|two|three|four|five|six|seven|eight|nine|ten"
    private const val ROOM_TYPES = "single|double|twin|queen|king|family|deluxe|standard|superior|executive|triple|quadruple"
    private const val GUEST_NOUNS = "adults?|rooms?|people|persons|guests|travell?ers|pax|children|child|kids?"
    /** One age: not a count that belongs to the next part ("aged 5 and 8 and 2 adults"). */
    private const val AGE = "\\d{1,2}(?!\\d|\\s+(?:(?:$ROOM_TYPES)\\s+)?(?:$GUEST_NOUNS)\\b)"
    private const val AGES = "$AGE(?:\\s*(?:,|and|&)?\\s*$AGE)*"
    private const val ADULT_ITEM = "(?:$NUM)\\s+adults?"
    private const val CHILD_ITEM =
        "(?:$NUM)\\s+(?:children|child|kids?)(?:\\s*\\(?\\s*(?:aged?|ages?)\\s+$AGES\\)?|\\s*\\(?\\s*$AGES\\s+(?:years?\\s+old|yrs?|y/?o)\\)?)?"
    private const val PEOPLE_ITEM = "(?:$NUM)\\s+(?:people|persons|guests|travell?ers|pax)"
    private const val ROOMS_ITEM = "(?:$ROOM_NUM)\\s+(?:(?:$ROOM_TYPES)\\s+)?rooms?"
    private const val GUEST_ITEM = "(?:$ADULT_ITEM|$CHILD_ITEM|$PEOPLE_ITEM|$ROOMS_ITEM)"
    /** A whole guest phrase, with its "for"/"with" and the "and"s between its parts. */
    private val GUEST_PHRASE = Regex(
        "(?:\\b(?:for|with)\\s+)?\\b$GUEST_ITEM(?:\\s*(?:,|and|&|plus|with)?\\s*\\b$GUEST_ITEM)*\\b\\)?",
        RegexOption.IGNORE_CASE
    )
    private val ADULTS = Regex("\\b($NUM)\\s+adults?\\b", RegexOption.IGNORE_CASE)
    private val PEOPLE = Regex("\\b($NUM)\\s+(?:people|persons|guests|travell?ers|pax)\\b", RegexOption.IGNORE_CASE)
    private val CHILDREN = Regex("\\b($NUM)\\s+(?:children|child|kids?)\\b(.*)", RegexOption.IGNORE_CASE)
    private val ROOMS = Regex("\\b($ROOM_NUM)\\s+(?:($ROOM_TYPES)\\s+)?rooms?\\b", RegexOption.IGNORE_CASE)
    private val SOLO = Regex("\\b(?:(?:for|just|only)\\s+me|solo|alone|by\\s+myself|myself)\\b", RegexOption.IGNORE_CASE)
    private val COUPLE = Regex("\\b(?:for\\s+)?(?:a\\s+|the\\s+)?couple\\b", RegexOption.IGNORE_CASE)

    /**
     * "on booking.com", "using Booking", "booking dot com"… — what names the app. Speech often
     * drops the dot ("booking com"), and the command cleanup used to remove it ("bookingcom").
     */
    private val BOOKING_APP = Regex(
        "(?:\\s+(?:on|in|using|via|with|through|at|from))?\\s+booking(?:\\s*\\.\\s*com|\\s+dot\\s+com|\\s*com)?(?:\\s+app)?(?=\\s|$)",
        RegexOption.IGNORE_CASE
    )
    private val MENTIONS_BOOKING = Regex(
        "booking\\s*(?:\\.\\s*|dot\\s+)?com\\b|\\b(?:on|using|via|with|through)\\s+booking\\b",
        RegexOption.IGNORE_CASE
    )

    /**
     * Leading "book me a cheap twin room at" and similar. Groups: 1 verb, 2 room type,
     * 3 generic noun (hotel/room/…), 4 preposition. (No named groups: they need API 26.)
     */
    private val LEAD_IN = Regex(
        "^(?:please\\s+)?(?:(search\\s+for|search|find\\s+me|find|look\\s+for|look\\s+up|i\\s+want\\s+to\\s+book|book\\s+me|book|reserve|get\\s+me|get|show\\s+me|show|i\\s+want|i\\s+need)\\s+)?" +
            "(?:(?:a|an|the|some|cheap|good|nice|me)\\s+)*" +
            "(?:(single|double|twin|queen|king|family|deluxe|standard|superior|executive|triple|quadruple)\\s+)?" +
            "(?:(hotels?|stays?|rooms?|accommodations?|places?\\s+to\\s+stay|properties|hostels?|suites?)(?:\\s+|$))?" +
            "(?:(in|at|near|around|to|for)\\s+)?",
        RegexOption.IGNORE_CASE
    )
    private val BOOK_VERB = Regex("book|reserve", RegexOption.IGNORE_CASE)
    /** "the Hilton in Tokyo": the last " in " separates the hotel from the city ("Holiday Inn" is safe). */
    private val HOTEL_CITY_SEPARATOR = Regex("\\s+in\\s+", RegexOption.IGNORE_CASE)
    private val TRAILING_FILLER = Regex("\\s+(?:on|in|from|for|at|to)$", RegexOption.IGNORE_CASE)

    /** True when [command] names Booking.com as the app to use. */
    fun mentionsBooking(command: String): Boolean = MENTIONS_BOOKING.containsMatchIn(command)

    fun parse(command: String, today: StayDate = StayDate.today()): Parsed {
        var rest = " ${command.trim()} ".replace(BOOKING_APP, " ")

        var nights: Int? = null
        NIGHTS.find(rest)?.let { match ->
            val n = match.groupValues[1].lowercase()
            nights = n.toIntOrNull() ?: if (n == "a") 1 else NUMBER_WORDS.indexOf(n) + 1
            rest = rest.removeRange(match.range)
        }

        var checkIn: StayDate? = null
        var checkOut: StayDate? = null
        val range = RANGE.find(rest)
        if (range != null) {
            checkIn = parseDate(range.groupValues[1], today, null)
            checkOut = checkIn?.let { parseDate(range.groupValues[2], today, it) }
            rest = rest.removeRange(range.range)
        } else {
            SINGLE.find(rest)?.let { match ->
                checkIn = parseDate(match.groupValues[1], today, null)
                rest = rest.removeRange(match.range)
            }
        }

        if (checkIn == null && nights != null) checkIn = today
        val start = checkIn
        if (start != null && (checkOut == null || checkOut!! <= start)) {
            checkOut = start.plusDays(nights ?: 1)
        }

        val guests = parseGuests(rest)
        rest = guests.rest

        val text = rest.replace(Regex("\\s+"), " ").trim().replace(TRAILING_FILLER, "").trim()
        val lead = LEAD_IN.find(text)!!
        val body = text.substring(lead.range.last + 1).trim()
        val verb = lead.groupValues[1]
        val roomType = lead.groupValues[2]
        val noun = lead.groupValues[3].lowercase()
        val preposition = lead.groupValues[4].lowercase()

        val book = BOOK_VERB.containsMatchIn(verb)
        val room = when {
            noun.startsWith("suite") -> listOf(roomType, "suite").filter { it.isNotEmpty() }.joinToString(" ")
            roomType.isNotEmpty() -> "$roomType room"
            else -> guests.roomType?.let { "$it room" }
        }
        fun result(destination: String, hotel: String?) = Parsed(
            destination, checkIn, checkOut, hotel, book, room,
            guests.adults, guests.children, guests.childAges, guests.rooms
        )
        // "at X" always names a place to stay; so does a name right after "book" ("book the
        // Hotel Gracery", where "Hotel" is part of the name). "a hotel in X" names only the area.
        val namesHotel = preposition == "at" || (book && preposition.isEmpty())
        if (!namesHotel || body.isEmpty()) return result(body, null)

        val nameText = if (preposition.isEmpty() && noun.isNotEmpty()) "${lead.groupValues[3]} $body" else body
        val name = nameText.replace(Regex("^the\\s+", RegexOption.IGNORE_CASE), "")
        val separator = HOTEL_CITY_SEPARATOR.findAll(name).lastOrNull()
        return if (separator != null && separator.range.first > 0) {
            result(name.substring(separator.range.last + 1).trim(), name.substring(0, separator.range.first).trim())
        } else {
            result("", name)
        }
    }

    private class GuestParse(
        val rest: String,
        val adults: Int? = null,
        val children: Int? = null,
        val childAges: List<Int> = emptyList(),
        val rooms: Int? = null,
        val roomType: String? = null
    )

    /**
     * Pulls the guest phrases out of [text]: "for 2 adults and 1 child aged 5", "3 people",
     * "2 twin rooms … for 4 adults" (several phrases), "just me"…
     */
    private fun parseGuests(text: String): GuestParse {
        val phrases = GUEST_PHRASE.findAll(text).toList()
        if (phrases.isNotEmpty()) {
            val p = phrases.joinToString(" ; ") { it.value }
            var rest = text
            for (phrase in phrases.asReversed()) rest = rest.removeRange(phrase.range)
            val adults = ADULTS.find(p)?.let { number(it.groupValues[1]) } ?: PEOPLE.find(p)?.let { number(it.groupValues[1]) }
            val child = CHILDREN.find(p)
            val children = child?.let { number(it.groupValues[1]) }
            // Ages come after the children count; don't read a room or adult count as an age.
            val ages = child?.groupValues?.get(2)?.replace(ROOMS, "")?.replace(ADULTS, "")?.replace(PEOPLE, "")
                ?.let { Regex("\\d{1,2}").findAll(it).map { m -> m.value.toInt() }.filter { a -> a in 0..17 }.toList() }
                .orEmpty()
            val rooms = ROOMS.find(p)
            return GuestParse(
                rest, adults, children, ages,
                rooms?.let { number(it.groupValues[1]) }, rooms?.groupValues?.get(2)?.lowercase()?.ifEmpty { null }
            )
        }
        SOLO.find(text)?.let { return GuestParse(text.removeRange(it.range), adults = 1) }
        COUPLE.find(text)?.let { return GuestParse(text.removeRange(it.range), adults = 2) }
        return GuestParse(text)
    }

    private fun number(word: String): Int {
        val w = word.lowercase()
        return w.toIntOrNull() ?: if (w == "a" || w == "an") 1 else NUMBER_WORDS.indexOf(w) + 1
    }

    /** Reads just a guest answer ("2", "two adults", "2 adults and a child"): a bare number means adults. */
    fun parseGuestAnswer(answer: String): Parsed {
        val parsed = parseGuests(" ${answer.trim()} ")
        val bare = if (parsed.adults == null && parsed.children == null) {
            Regex("^\\s*(?:for\\s+)?($NUM)\\b", RegexOption.IGNORE_CASE).find(answer)?.let { number(it.groupValues[1]) }
        } else null
        return Parsed("", null, null, adults = parsed.adults ?: bare, children = parsed.children,
            childAges = parsed.childAges, rooms = parsed.rooms, room = parsed.roomType?.let { "$it room" })
    }

    /** Ages from an answer like "5 and 8" or "a baby and a 4 year old" (a baby is 0). */
    fun parseAges(answer: String): List<Int> {
        val lower = answer.lowercase()
        val babies = Regex("\\b(?:baby|infant|newborn|under\\s+(?:1|one))\\b").findAll(lower).count()
        val ages = Regex("\\b(\\d{1,2})\\b").findAll(lower).map { it.groupValues[1].toInt() }.filter { it in 0..17 }.toList()
        return List(babies) { 0 } + ages
    }

    /**
     * The command for a stay, in words [parse] reads back; used to continue a conversation
     * ("book a hotel in Tokyo" → "Which one?" → "the Hilton").
     */
    fun command(
        destination: String,
        hotel: String?,
        checkIn: StayDate?,
        checkOut: StayDate?,
        book: Boolean,
        room: String? = null,
        guests: Guests? = null
    ): String {
        val place = if (destination.isNotEmpty()) " in $destination" else ""
        val what = when {
            hotel == null -> "find hotels$place"
            book && room != null -> "book a $room at $hotel$place"
            book -> "book $hotel$place"
            else -> "find a room at $hotel$place"
        }
        val dates = checkIn?.let { start ->
            val end = checkOut ?: start.plusDays(1)
            " from ${start.label()} ${start.year} to ${end.label()} ${end.year}"
        } ?: ""
        // No "and": the command pipeline splits chained commands on it.
        val who = guests?.let { g ->
            buildString {
                append(" for ${plural(g.adults, "adult")}")
                if (g.childAges.isNotEmpty()) append(" ${plural(g.childAges.size, "child", "children")} aged ${g.childAges.joinToString(" ")}")
                if (g.rooms > 1) append(" ${plural(g.rooms, "room")}")
            }
        } ?: ""
        return "$what$dates$who on booking.com"
    }

    /**
     * Parses one date phrase. [after] is the check-in when parsing a check-out, so "Oct 10 to 12"
     * takes October from it and "Dec 30 to Jan 2" rolls into the next year.
     */
    private fun parseDate(text: String, today: StayDate, after: StayDate?): StayDate? {
        val lower = text.lowercase().trim()
        when (lower) {
            "today", "tonight" -> return today
            "tomorrow" -> return today.plusDays(1)
        }
        val day = Regex("(\\d{1,2})(?:st|nd|rd|th)?(?!\\d)").find(lower)?.groupValues?.get(1)?.toInt() ?: return null
        val year = Regex("\\b(\\d{4})\\b").find(lower)?.groupValues?.get(1)?.toInt()
        val monthWord = Regex("[a-z]{3,}").findAll(lower).map { it.value }.firstOrNull { word ->
            StayDate.MONTH_NAMES.any { it.lowercase().startsWith(word.take(3)) }
        }
        val month = monthWord?.let { word -> StayDate.MONTH_NAMES.indexOfFirst { it.lowercase().startsWith(word.take(3)) } + 1 }
            ?: after?.month
            ?: return null
        if (day !in 1..31) return null

        val floor = after ?: today
        var date = StayDate(year ?: floor.year, month, day)
        if (year == null && date < floor) date = date.copy(year = date.year + 1)
        return date
    }
}
