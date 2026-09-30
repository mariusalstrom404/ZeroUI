package com.example.myapplication.automation

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.myapplication.nlp.HotelQuery.Guests
import com.example.myapplication.nlp.StayDate
import kotlinx.coroutines.delay

/**
 * Drives the real Booking.com app (com.booking): searches stays, or opens a specific hotel and
 * reserves a room up to the guest-details form.
 *
 * Booking's home screen has no text field, so the generic "type into the search bar" path tapped
 * the middle of the "Accommodation search box" container, which is the dates row. This drives the
 * app's own flow instead, using the Compose test tags it exposes as resource ids:
 * destination box → destination screen (type, pick the matching suggestion) → date picker
 * (tap check-in and check-out, or keep its defaults) → rooms and guests (steppers, children's
 * ages) → Search → results list.
 *
 * Booking a hotel continues: its card in the results → "Select rooms" → a room's "Select" →
 * "Reserve" → the "Fill in your info" form. It never goes further: entering the guest's details,
 * payment and the final booking button are left to the user.
 */
class BookingAutomation(private val engine: AutomationEngine) {

    sealed class Result {
        /** The results list is showing. Fields are what could be read back from it. */
        data class Found(
            val properties: String?,
            val searchSummary: String?,
            val topResult: String?,
            /** False when dates were asked for but couldn't be picked, so the app's defaults were used. */
            val datesApplied: Boolean,
            /** False when guests were asked for but couldn't be set. */
            val guestsApplied: Boolean = true
        ) : Result()
        /** A room was selected and "Reserve" tapped; the app is on its guest-details form (if [formShown]). */
        data class Reserved(
            val hotel: String,
            val room: String?,
            val price: String?,
            val dates: String?,
            val datesApplied: Boolean,
            val guestsApplied: Boolean,
            val formShown: Boolean
        ) : Result()
        data object SearchBoxNotFound : Result()
        data object DestinationNotFound : Result()
        /** Booking.com suggested no property for the hotel name. */
        data object HotelNotFound : Result()
        data object ResultsNotShown : Result()
        /** The hotel opened but offered no room to select (sold out for the dates, or a layout we don't know). */
        data class NoRooms(val hotel: String) : Result()
        /** The room type asked for isn't offered for these guests; the room list is left open. */
        data class RoomNotOffered(val hotel: String, val room: String, val available: List<String>) : Result()
    }

    companion object {
        const val PACKAGE = "com.booking"
        private const val LOG = "ZeroUIAutomation"

        private const val DESTINATION_BOX = "stays-destination"
        private const val DATES_BOX = "stays-dates"
        private const val SEARCH_BUTTON = "search_box_cta_test_tag"
        private const val DATE_CONFIRM = "facet_date_picker_confirm"
        private const val CALENDAR = "jpc_date_picker_calendar"
        private const val RESULTS_LIST = "sr_list"
        private const val OCCUPANCY_BOX = "stays-occupancy"
        private const val OCCUPANCY_APPLY = "pp_occupancy_apply_cta"
        private const val AGE_INPUT = "numberpicker_input"
        private const val DIALOG_OK = "button1"
        private const val MAX_STEPPER_TAPS = 30

        private const val MAX_BACKS = 4
        private const val CALENDAR_SCROLLS = 14
        /** The calendar recomposes after each tap; give a day this long to reappear before scrolling. */
        private const val DAY_WAIT_MS = 2500L
        private const val ROOM_SCROLLS = 8

        private val PROPERTY_COUNT = Regex("^[\\d,.]+\\s+propert(?:y|ies)", RegexOption.IGNORE_CASE)
        /** Last price in a result card, e.g. "Original price 2091 HKD. Current price 1150 HKD", or "HK$ 3,936". */
        private val PRICE = Regex("(?:[A-Z]{2,3}\\$?|[$€£¥])\\s?[\\d,.]+\\d|[\\d,.]*\\d\\s?[A-Z]{3}\\b")
        /** Subtitle of a hotel in the destination suggestions ("Property in Osaka, Japan"), not a place. */
        private val PROPERTY_SUBTITLE = Regex("^(?:property|hotel|apartment|hostel|resort|guest\\s*house|inn|villa)\\b.*\\bin\\b", RegexOption.IGNORE_CASE)
        /** Room card titles on the "Choose Your Stay" list ("Cozy Queen Room", "Junior Suite"…). */
        private val ROOM_TITLE = Regex("\\b(?:room|suite|studio|apartment|dormitory|villa|bungalow|cabin|chalet|loft)\\b", RegexOption.IGNORE_CASE)
        /** The selection bar above Reserve: "HK$ 3,936 · 1 room". */
        private val SELECTION_BAR = Regex("\\d.*\\b\\d+\\s+rooms?\\b", RegexOption.IGNORE_CASE)
    }

    private sealed class Setup {
        data class Ready(val datesApplied: Boolean, val guestsApplied: Boolean, val chosen: String?) : Setup()
        data class Failed(val result: Result) : Setup()
    }

    suspend fun searchStays(
        packageName: String,
        destination: String,
        checkIn: StayDate?,
        checkOut: StayDate?,
        guests: Guests?
    ): Result {
        Log.e(LOG, "[BOOKING] search destination=$destination checkIn=$checkIn checkOut=$checkOut guests=$guests")
        val ready = when (val setup = setUpSearch(packageName, destination, hotel = null, checkIn, checkOut, guests)) {
            is Setup.Failed -> return setup.result
            is Setup.Ready -> setup
        }
        if (!tapSearch(packageName) || waitForResults(packageName) == null) return Result.ResultsNotShown
        return readResults(packageName, ready.datesApplied).copy(guestsApplied = ready.guestsApplied)
    }

    /**
     * Opens [hotel] (in [destination], if given), selects a room — the first one matching [room],
     * else the first one listed — and taps Reserve. Stops on the guest-details form.
     */
    suspend fun reserve(
        packageName: String,
        hotel: String,
        destination: String,
        checkIn: StayDate?,
        checkOut: StayDate?,
        guests: Guests?,
        room: String?
    ): Result {
        Log.e(LOG, "[BOOKING] reserve hotel=$hotel destination=$destination room=$room checkIn=$checkIn checkOut=$checkOut guests=$guests")
        val query = if (destination.isEmpty() || hotel.contains(destination, ignoreCase = true)) hotel else "$hotel $destination"
        val ready = when (val setup = setUpSearch(packageName, query, hotel, checkIn, checkOut, guests)) {
            is Setup.Failed -> return setup.result
            is Setup.Ready -> setup
        }
        val name = ready.chosen ?: hotel

        // Searching a property shows the results with it first (or, sometimes, its page directly).
        if (!tapSearch(packageName)) return Result.ResultsNotShown
        val screen = waitForResults(packageName, orPropertyPage = true) ?: return Result.ResultsNotShown
        if (screen == Screen.RESULTS && !openHotelCard(packageName, name)) return Result.HotelNotFound

        // Property page → room list.
        engine.report("Opening the rooms")
        engine.dismissPopups(packageName)
        if (!engine.clickElement(Selector(text = "Select rooms", exact = true), timeoutMs = 15000, scrollIfNotFound = false, packageName = packageName)) {
            return Result.NoRooms(name)
        }
        val chosenRoom = when (val pick = selectRoom(packageName, room)) {
            is RoomPick.Picked -> pick.name
            is RoomPick.NotOffered -> return Result.RoomNotOffered(name, room!!, pick.available)
            RoomPick.Failed -> return Result.NoRooms(name)
        }

        // Reserve → guest details. Nothing past this point is ever tapped.
        val reserve = Selector(text = "Reserve", exact = true)
        if (engine.waitForElement(reserve, timeoutMs = 8000, packageName = packageName) == null) return Result.NoRooms(name)
        val price = selectionPrice(packageName)
        val dates = allNodes(packageName).firstNotNullOfOrNull { node ->
            node.text?.toString()?.takeIf { Regex("^[A-Z][a-z]{2} \\d{1,2} - [A-Z][a-z]{2} \\d{1,2}$").matches(it) }
        }
        engine.report("Tapping Reserve")
        if (!engine.clickElement(reserve, timeoutMs = 5000, scrollIfNotFound = false, packageName = packageName)) return Result.NoRooms(name)
        val form = waitForAny(packageName, 15000) { nodes ->
            nodes.any { it.text?.toString() == "Fill in your info" || it.contentDescription?.startsWith("First Name") == true }
        }
        Log.e(LOG, "[BOOKING] reserved room=$chosenRoom price=$price dates=$dates form=$form")
        return Result.Reserved(name, chosenRoom.ifEmpty { null }, price, dates, ready.datesApplied, ready.guestsApplied, form)
    }

    /**
     * Home screen → destination typed and suggestion picked → dates set. With [hotel], picks the
     * best matching *property* suggestion and reports its full name in [Setup.Ready.chosen].
     */
    private suspend fun setUpSearch(
        packageName: String,
        query: String,
        hotel: String?,
        checkIn: StayDate?,
        checkOut: StayDate?,
        guests: Guests?
    ): Setup {
        // 1. Get to the Stays search box (the app may resume on results or a property page).
        if (!openSearchBox(packageName)) return Setup.Failed(Result.SearchBoxNotFound)

        // 2. Destination: tap the box, type, pick the suggestion.
        engine.report("Opening the destination field")
        if (!engine.clickElement(Selector(resourceId = DESTINATION_BOX), timeoutMs = 5000, scrollIfNotFound = false, packageName = packageName)) {
            return Setup.Failed(Result.SearchBoxNotFound)
        }
        delay(800)
        if (!engine.typeIntoApp(query, submit = false, packageName = packageName, timeoutMs = 8000)) {
            return Setup.Failed(Result.DestinationNotFound)
        }
        var chosen: String? = null
        if (hotel != null) {
            chosen = pickProperty(packageName, hotel) ?: return Setup.Failed(Result.HotelNotFound)
        } else if (!pickSuggestion(packageName, query)) {
            return Setup.Failed(Result.DestinationNotFound)
        }

        // 3. Dates. Picking a suggestion may open the date picker; otherwise open it ourselves.
        var datesApplied = true
        val confirm = Selector(resourceId = DATE_CONFIRM)
        var picker = engine.waitForElement(confirm, timeoutMs = 2500, packageName = packageName)
        if (picker == null && checkIn != null) {
            engine.clickElement(Selector(resourceId = DATES_BOX), timeoutMs = 4000, scrollIfNotFound = false, packageName = packageName)
            picker = engine.waitForElement(confirm, timeoutMs = 4000, packageName = packageName)
        }
        if (picker != null) {
            if (checkIn != null) {
                // The picker remembers its last tab; the days are only on "Calendar".
                engine.clickAny(listOf(Selector(text = "Calendar", exact = true)), timeoutMs = 1000, packageName = packageName)
                delay(600)
                val out = checkOut ?: checkIn.plusDays(1)
                datesApplied = tapDay(packageName, checkIn) && tapDay(packageName, out)
                if (!datesApplied) Log.e(LOG, "[BOOKING] couldn't pick $checkIn – $out, keeping the app's dates")
            }
            engine.clickElement(confirm, timeoutMs = 4000, scrollIfNotFound = false, packageName = packageName)
            delay(1000)
        } else if (checkIn != null) {
            datesApplied = false
        }

        // 4. Rooms and guests.
        val guestsApplied = guests == null || setGuests(packageName, guests)
        return Setup.Ready(datesApplied, guestsApplied, chosen)
    }

    /**
     * Sets rooms, adults and children with the picker's +/- steppers, then each child's age in
     * its number-picker dialog, and applies. Returns false if anything couldn't be set.
     */
    private suspend fun setGuests(packageName: String, guests: Guests): Boolean {
        engine.report("Setting ${guests.label()}")
        if (!engine.clickElement(Selector(resourceId = OCCUPANCY_BOX), timeoutMs = 5000, scrollIfNotFound = false, packageName = packageName)) return false
        if (engine.waitForElement(Selector(resourceId = OCCUPANCY_APPLY), timeoutMs = 5000, packageName = packageName) == null) return false

        // Adults before rooms: the app won't allow more rooms than adults.
        var ok = setStepper(packageName, "Adults", guests.adults) &&
            setStepper(packageName, "Rooms", guests.rooms) &&
            setStepper(packageName, "Children", guests.childAges.size)
        if (ok) {
            for ((index, age) in guests.childAges.withIndex()) {
                if (!setChildAge(packageName, index, age)) {
                    ok = false
                    break
                }
            }
        }
        val applied = engine.clickElement(Selector(resourceId = OCCUPANCY_APPLY), timeoutMs = 4000, scrollIfNotFound = false, packageName = packageName)
        delay(800)
        if (!applied) engine.back() // close the sheet rather than leave it covering the Search button
        Log.e(LOG, "[BOOKING] guests $guests set=$ok applied=$applied")
        return ok && applied
    }

    /** Taps "Increase/Decrease [label]" until the stepper ("Adults 2") shows [target]. */
    private suspend fun setStepper(packageName: String, label: String, target: Int): Boolean {
        val value = Regex("^$label (\\d+)$", RegexOption.IGNORE_CASE)
        var last: Int? = null
        var stuck = 0
        repeat(MAX_STEPPER_TAPS) {
            val current = allNodes(packageName).firstNotNullOfOrNull { node ->
                node.contentDescription?.toString()?.let { value.find(it) }?.groupValues?.get(1)?.toInt()
            } ?: return false
            if (current == target) return true
            if (current == last && ++stuck >= 2) {
                Log.e(LOG, "[BOOKING] $label stuck at $current (wanted $target)")
                return false
            }
            last = current
            val button = engine.findNode(
                Selector(contentDescription = "${if (current < target) "Increase" else "Decrease"} $label", exact = true),
                packageName
            ) ?: return false
            engine.click(button)
            delay(350)
        }
        return false
    }

    /** Opens "Child N"'s age dialog and steps its number picker ("Select", "< 1 year old", "1 year old"…) to [age]. */
    private suspend fun setChildAge(packageName: String, index: Int, age: Int): Boolean {
        engine.report("Setting child ${index + 1}'s age to $age")
        if (!engine.clickElement(
                Selector(contentDescription = "Child ${index + 1}"),
                timeoutMs = 5000, scrollIfNotFound = true, packageName = packageName, maxScrolls = 3
            )
        ) return false
        if (engine.waitForElement(Selector(resourceId = AGE_INPUT), timeoutMs = 4000, packageName = packageName) == null) return false

        repeat(25) {
            val input = engine.findNode(Selector(resourceId = AGE_INPUT), packageName) ?: return false
            val current = shownAge(input.text?.toString())
            if (current == age) {
                val confirmed = engine.clickElement(Selector(resourceId = DIALOG_OK), timeoutMs = 3000, scrollIfNotFound = false, packageName = packageName)
                delay(600)
                return confirmed
            }
            val picker = allNodes(packageName).firstOrNull { it.isScrollable && it.className?.contains("NumberPicker") == true }
                ?: return false
            // Forward shows the next (older) value.
            if (!engine.scroll(picker, down = current < age)) return false
        }
        return false
    }

    /** "Select" → -1, "< 1 year old" → 0, "5 years old" → 5. */
    private fun shownAge(text: String?): Int {
        val t = text?.trim() ?: return -1
        if (t.startsWith("<")) return 0
        return Regex("^(\\d+)").find(t)?.groupValues?.get(1)?.toInt() ?: -1
    }

    private suspend fun tapSearch(packageName: String): Boolean {
        engine.dismissPopups(packageName)
        return engine.clickElement(Selector(resourceId = SEARCH_BUTTON), timeoutMs = 6000, scrollIfNotFound = false, packageName = packageName)
    }

    private enum class Screen { RESULTS, PROPERTY }

    /** Waits for the results list (or, with [orPropertyPage], a property page). */
    private suspend fun waitForResults(packageName: String, orPropertyPage: Boolean = false): Screen? {
        engine.report("Waiting for the results")
        val deadline = System.currentTimeMillis() + 15000
        while (System.currentTimeMillis() < deadline) {
            if (engine.findNode(Selector(resourceId = RESULTS_LIST), packageName) != null) {
                delay(1500) // cards and the property count load after the list appears
                return Screen.RESULTS
            }
            if (orPropertyPage && engine.findNode(Selector(text = "Select rooms", exact = true), packageName) != null) {
                return Screen.PROPERTY
            }
            delay(400)
        }
        return null
    }

    /** Brings the Stays tab's search box on screen, backing out of deeper screens if needed. */
    private suspend fun openSearchBox(packageName: String): Boolean {
        val box = Selector(resourceId = DESTINATION_BOX)
        engine.report("Opening the Stays search")
        repeat(MAX_BACKS + 1) { attempt ->
            val timeout = if (attempt == 0) 8000L else 2500L
            if (engine.waitForElement(box, timeoutMs = timeout, packageName = packageName) != null) return true
            if (engine.dismissPopups(packageName)) return@repeat
            // On another tab (Car rental, Taxi…) of the home screen.
            if (engine.clickAny(listOf(Selector(text = "Stays", exact = true)), timeoutMs = 500, packageName = packageName)) {
                delay(800)
                return@repeat
            }
            Log.e(LOG, "[BOOKING] no search box, going back (${attempt + 1})")
            engine.back()
        }
        return engine.findNode(box, packageName) != null
    }

    /**
     * Taps the suggestion row for [destination]. If none matches (a spelling or a translated
     * name), takes the first suggestion, which is what pressing Enter in the app would do.
     */
    private suspend fun pickSuggestion(packageName: String, destination: String): Boolean {
        engine.report("Choosing “$destination”")
        val match = Selector(text = destination, classNameExclude = "EditText")
        if (engine.clickElement(match, timeoutMs = 6000, scrollIfNotFound = false, packageName = packageName)) return true

        val row = suggestionRows(packageName).firstOrNull { row ->
            engine.finder.flatten(row).none { it.text?.contains("current location", ignoreCase = true) == true }
        } ?: return false
        Log.e(LOG, "[BOOKING] no exact suggestion for “$destination”, taking the first one")
        return engine.click(row)
    }

    /**
     * Taps the property suggestion ("<name> / Property in Osaka, Japan") that best matches [hotel]
     * and returns its full name, or null if Booking suggests no property at all.
     */
    private suspend fun pickProperty(packageName: String, hotel: String): String? {
        engine.report("Looking for “$hotel”")
        val deadline = System.currentTimeMillis() + 8000
        while (System.currentTimeMillis() < deadline) {
            val properties = suggestionRows(packageName).mapNotNull { row ->
                val texts = engine.finder.flatten(row).mapNotNull { it.text?.toString()?.trim() }.filter { it.isNotEmpty() }
                val name = texts.firstOrNull() ?: return@mapNotNull null
                if (texts.drop(1).any { PROPERTY_SUBTITLE.containsMatchIn(it) }) row to name else null
            }
            // Best name match; with no match at all, Booking's own top property for the query.
            val best = properties.maxByOrNull { (_, name) -> TextMatch.score(name, hotel) }
            if (best != null) {
                Log.e(LOG, "[BOOKING] property suggestion “${best.second}” for “$hotel”")
                engine.report("Choosing “${best.second}”")
                return if (engine.click(best.first)) best.second else null
            }
            delay(400)
        }
        return null
    }

    /** Full-width clickable rows under the destination field, top to bottom. */
    private fun suggestionRows(packageName: String): List<AccessibilityNodeInfo> {
        val finder = engine.finder
        val field = finder.focusedEditable(packageName) ?: finder.textFields(packageName).firstOrNull() ?: return emptyList()
        val fieldBottom = finder.boundsOf(field).bottom
        val width = finder.boundsOf(finder.roots(packageName).firstOrNull() ?: return emptyList()).width()
        return allNodes(packageName).filter { node ->
            val b = finder.boundsOf(node)
            node.isClickable && node.isVisibleToUser && b.top >= fieldBottom && b.width() > width * 0.8
        }
    }

    /** Taps the result card for [name]; the top bar (a Button that also names the hotel) is skipped. */
    private suspend fun openHotelCard(packageName: String, name: String): Boolean {
        engine.report("Opening “$name”")
        return engine.clickElement(
            Selector(contentDescription = name, classNameExclude = "Button"),
            timeoutMs = 10000, scrollIfNotFound = true, packageName = packageName, maxScrolls = 3
        )
    }

    private sealed class RoomPick {
        /** [name] is "" when the room's title couldn't be read. */
        data class Picked(val name: String) : RoomPick()
        data class NotOffered(val available: List<String>) : RoomPick()
        data object Failed : RoomPick()
    }

    /**
     * On "Choose Your Stay", taps "Select" for the first rate of the room matching [room], or of
     * the first room when no type was asked for. A type that isn't on the list is never swapped
     * for another room: nothing is selected and the list's room names are returned instead.
     */
    private suspend fun selectRoom(packageName: String, room: String?): RoomPick {
        val select = Selector(text = "Select", exact = true)
        engine.report("Choosing a room")
        if (engine.waitForElement(select, timeoutMs = 15000, packageName = packageName) == null) return RoomPick.Failed

        var anchorBottom = Int.MIN_VALUE
        var roomName: String? = null
        if (room != null) {
            engine.report("Looking for a $room")
            val title = engine.waitForElement(
                Selector(text = room, classNameExclude = "EditText"),
                timeoutMs = 12000, scrollIfNotFound = true, packageName = packageName, maxScrolls = ROOM_SCROLLS
            )
            if (title == null) {
                Log.e(LOG, "[BOOKING] no “$room” on the room list")
                return RoomPick.NotOffered(listRoomTitles(packageName))
            }
            anchorBottom = engine.finder.boundsOf(title).bottom
            roomName = title.text?.toString()
        }

        repeat(2) {
            val button = selectButtons(packageName).firstOrNull { engine.finder.boundsOf(it).top > anchorBottom }
            if (button != null) {
                val name = roomName ?: roomTitleAbove(packageName, button)
                engine.report("Selecting ${name ?: "the room"}")
                return if (engine.click(button)) RoomPick.Picked(name ?: "") else RoomPick.Failed
            }
            // The room's rates are below the fold: bring them up, then take the first one showing.
            engine.scroll(true, packageName)
            anchorBottom = Int.MIN_VALUE
        }
        return RoomPick.Failed
    }

    /** Every room name on the list, top to bottom; leaves the list scrolled back to the top. */
    private suspend fun listRoomTitles(packageName: String): List<String> {
        engine.report("Reading the available rooms")
        scrollToTop(packageName)
        val titles = LinkedHashSet<String>()
        for (i in 0..ROOM_SCROLLS) {
            titles += visibleRoomTitles(packageName)
            if (!engine.scroll(true, packageName)) break
        }
        scrollToTop(packageName)
        return titles.toList()
    }

    private suspend fun scrollToTop(packageName: String) {
        for (i in 0..ROOM_SCROLLS + 2) if (!engine.scroll(false, packageName)) break
    }

    private fun visibleRoomTitles(packageName: String): List<String> =
        allNodes(packageName)
            .filter { it.isVisibleToUser }
            .sortedBy { engine.finder.boundsOf(it).top }
            .mapNotNull { node -> node.text?.toString()?.trim()?.takeIf { isRoomTitle(it) } }

    private fun isRoomTitle(text: String): Boolean =
        ROOM_TITLE.containsMatchIn(text) && text.length < 90 && !text.first().isDigit() &&
            !text.startsWith("Sleeps", true) && !text.startsWith("Size", true)

    /** Visible "Select" buttons, top to bottom, left to right. */
    private fun selectButtons(packageName: String): List<AccessibilityNodeInfo> =
        allNodes(packageName)
            .filter { it.isVisibleToUser && it.text?.toString()?.trim() == "Select" }
            .sortedWith(compareBy({ engine.finder.boundsOf(it).top }, { engine.finder.boundsOf(it).left }))

    /** The nearest room title above [button] ("Cozy Queen Room"). */
    private fun roomTitleAbove(packageName: String, button: AccessibilityNodeInfo): String? {
        val top = engine.finder.boundsOf(button).top
        return allNodes(packageName)
            .filter { node ->
                val text = node.text?.toString() ?: return@filter false
                node.isVisibleToUser && engine.finder.boundsOf(node).top < top && ROOM_TITLE.containsMatchIn(text) && text.length < 60
            }
            .maxByOrNull { engine.finder.boundsOf(it).top }
            ?.text?.toString()
    }

    /** "HK$ 3,936" from the selection bar above Reserve. */
    private fun selectionPrice(packageName: String): String? =
        allNodes(packageName).firstNotNullOfOrNull { node ->
            val text = node.text?.toString()?.replace(' ', ' ') ?: return@firstNotNullOfOrNull null
            if (SELECTION_BAR.containsMatchIn(text)) PRICE.find(text)?.value else null
        }

    private suspend fun waitForAny(
        packageName: String,
        timeoutMs: Long,
        found: (List<AccessibilityNodeInfo>) -> Boolean
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (found(allNodes(packageName))) return true
            delay(500)
        }
        return false
    }

    /**
     * Taps [date] in the calendar ("Saturday, October 10, 2026"), scrolling the month list down to
     * it. That list sits inside a sideways pager (Calendar / I'm flexible), so it is scrolled
     * directly: the generic [AutomationEngine.scroll] would pick the pager and switch tabs.
     */
    private suspend fun tapDay(packageName: String, date: StayDate): Boolean {
        engine.report("Choosing ${date.label()}")
        repeat(CALENDAR_SCROLLS + 1) {
            val deadline = System.currentTimeMillis() + DAY_WAIT_MS
            while (System.currentTimeMillis() < deadline) {
                val day = findDay(packageName, date)
                if (day != null) {
                    val tapped = engine.click(day)
                    delay(700)
                    return tapped
                }
                delay(300)
            }
            val list = calendarList(packageName) ?: return false
            if (!engine.scroll(list, down = true)) return false
        }
        return false
    }

    /** "October 1, 2026" can't match "October 11, 2026": the label starts with the month name. */
    private fun findDay(packageName: String, date: StayDate): AccessibilityNodeInfo? {
        val label = date.longLabel()
        return allNodes(packageName).firstOrNull { node ->
            node.isVisibleToUser && node.contentDescription?.contains(label, ignoreCase = true) == true
        }
    }

    private fun calendarList(packageName: String): AccessibilityNodeInfo? {
        val calendar = allNodes(packageName).firstOrNull {
            it.viewIdResourceName?.let { id -> id == CALENDAR || id.endsWith(":id/$CALENDAR") } == true
        } ?: return null
        return engine.finder.flatten(calendar).firstOrNull { it.isScrollable }
    }

    private fun allNodes(packageName: String): List<AccessibilityNodeInfo> =
        engine.finder.roots(packageName).flatMap { engine.finder.flatten(it) }

    private fun readResults(packageName: String, datesApplied: Boolean): Result.Found {
        val nodes = allNodes(packageName)
        val count = nodes.firstNotNullOfOrNull { node ->
            node.text?.toString()?.trim()?.takeIf { PROPERTY_COUNT.containsMatchIn(it) }
        }
        // The top bar button reads "Tokyo · Oct 01 - Oct 02".
        val summary = nodes.firstNotNullOfOrNull { node ->
            node.contentDescription?.toString()?.takeIf { node.className?.contains("Button") == true && it.contains(" - ") }
        }
        // Result cards describe themselves in one multi-line description; the name is the first line.
        val top = nodes.asSequence()
            .mapNotNull { it.contentDescription?.toString() }
            .filter { it.contains("\n") && !it.contains("\nAd.\n") && it.contains("price", ignoreCase = true) }
            .firstOrNull()
            ?.let { card ->
                val name = card.substringBefore("\n").trimEnd('.')
                val price = PRICE.findAll(card.substringBefore("Additional charges")).lastOrNull()?.value
                if (price != null) "$name, $price" else name
            }
        Log.e(LOG, "[BOOKING] results count=$count summary=$summary top=$top")
        return Result.Found(count, summary, top, datesApplied)
    }
}
