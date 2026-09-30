package com.example.myapplication.nlp

/**
 * Structured representation of a single user command, produced by an [IntentParser].
 *
 * This is the single source of truth shared by the conversational UI
 * (which decides how to respond / prompt) and the accessibility service
 * (which executes the action). Slot filling (amounts, recipients, app names,
 * UUIDs) is done by [EntityExtractors] and carried on these intents.
 *
 * [requiresConfirmation] marks high-risk actions (money movement, checkout) that
 * the UI must verbally confirm before dispatching for execution.
 */
sealed class AppIntent(val requiresConfirmation: Boolean = false) {

    // --- System ---
    data object Battery : AppIntent()
    data class Flashlight(val enable: Boolean) : AppIntent()
    data class Volume(val change: Change, val level: Int? = null) : AppIntent()
    data class Brightness(val change: Change, val level: Int? = null) : AppIntent()
    data object Greeting : AppIntent()
    data object SaveHistory : AppIntent()
    data object ClearHistory : AppIntent()
    data object RepeatLast : AppIntent()
    data object TakeScreenshot : AppIntent()
    data class CameraAction(val takePhoto: Boolean = false) : AppIntent()

    // --- Navigation / apps ---
    data class Navigate(val destination: String?, val mode: String? = null) : AppIntent()
    data class OpenApp(val name: String) : AppIntent()

    // --- NCCUbank ---
    data object CheckBalance : AppIntent()
    data class Transfer(val amount: Int?, val recipient: String?) : AppIntent(requiresConfirmation = true)
    data object TransactionHistory : AppIntent()
    data object TopUp : AppIntent()

    // --- FoodGorilla ---
    data class FoodSearch(val query: String) : AppIntent()
    data class FoodOrder(val itemId: Int?, val query: String?) : AppIntent(requiresConfirmation = true)
    data object FoodCart : AppIntent()
    data object FoodCheckout : AppIntent(requiresConfirmation = true)
    data class FoodMenu(val restaurant: String) : AppIntent()

    // --- Throats ---
    data class ThroatsPost(val content: String) : AppIntent()
    data class ThroatsRepost(val postId: String?) : AppIntent()
    data class ThroatsComment(val postId: String?, val content: String) : AppIntent()

    // --- Any app ---
    /**
     * Types [text] into the current app's search/text field, e.g. "search for pizza on foodpanda".
     * [app] is the trailing "on/in <name>" if there was one; the service decides whether it is
     * really an installed app (then [text] is typed) or part of the text (then [fullText] is).
     * [submit] presses the keyboard's search/enter key.
     */
    data class TypeText(
        val text: String,
        val app: String? = null,
        val submit: Boolean = true,
        val fullText: String = text
    ) : AppIntent()

    /**
     * "order Hawaiian Pizza from Pizza Hut on Foodpanda": find [restaurant] in a real delivery
     * [app] and add [item] to the cart. Stops before checkout, so no confirmation is needed.
     */
    data class AppOrder(val item: String, val restaurant: String?, val app: String) : AppIntent()

    /**
     * A Booking.com stay (see [HotelQuery]). Without [book] it searches [destination] (or [hotel])
     * and reads back the results. With [book] and a [hotel] it opens that hotel, picks a room
     * ([room] if given) and taps Reserve, stopping at the guest-details form: the user enters their
     * details and completes the booking. With [book] but no [hotel], the UI asks which hotel first.
     * Dates and guests are optional for a search (the app's current ones are kept); for a booking
     * the UI collects whatever is missing (hotel, dates, guests, children's ages, room type) first.
     */
    data class HotelSearch(
        val destination: String,
        val checkIn: StayDate? = null,
        val checkOut: StayDate? = null,
        val hotel: String? = null,
        val book: Boolean = false,
        val room: String? = null,
        val adults: Int? = null,
        val children: Int? = null,
        val childAges: List<Int> = emptyList(),
        val rooms: Int? = null
    ) : AppIntent() {
        val guests: HotelQuery.Guests?
            get() = adults?.takeIf { (children ?: 0) <= childAges.size }
                ?.let { HotelQuery.Guests(it, childAges.take(children ?: 0), rooms ?: 1) }
    }

    // --- Fallbacks ---
    /** No specific intent matched; [text] is the raw utterance for a best-effort click. */
    data class ClickText(val text: String) : AppIntent()
    data object Unknown : AppIntent()

    enum class Change { UP, DOWN, SET }
}
