package com.example.myapplication.nlp

import com.example.myapplication.automation.ScreenAction
import com.example.myapplication.automation.ScreenTurn
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale

/**
 * Builds OpenAI requests and turns the JSON that comes back into intents. The model's output is
 * untrusted: values out of range are dropped, so they read as missing and the user gets asked
 * instead of an action running on them.
 */
object LlmJson {

    private val ISO_DATE = Regex("^(\\d{4})-(\\d{2})-(\\d{2})$")

    fun isoDate(date: StayDate): String =
        String.format(Locale.ROOT, "%04d-%02d-%02d", date.year, date.month, date.day)

    /**
     * A Responses API request for strict JSON. `store: false` keeps it out of OpenAI's stored responses.
     * [reasoningEffort], when given, caps how long a reasoning model thinks before answering; these
     * are short, well-specified tasks where the default effort mostly adds latency.
     */
    fun request(
        model: String,
        name: String,
        schema: String,
        instructions: String,
        input: String,
        reasoningEffort: String? = null
    ): String {
        val format = JSONObject()
            .put("type", "json_schema")
            .put("strict", true)
            .put("name", name)
            .put("schema", JSONObject(schema))
        return JSONObject()
            .put("model", model)
            .put("store", false)
            // Calls of one kind share a prompt start, so route them to the same cache.
            .put("prompt_cache_key", "zeroui-$name")
            .put("instructions", instructions)
            .put("input", input)
            .put("text", JSONObject().put("format", format))
            .apply { if (reasoningEffort != null) put("reasoning", JSONObject().put("effort", reasoningEffort)) }
            .toString()
    }

    // Inputs put what changes between calls last; each prompt's instructions stay the same for
    // every call, so OpenAI can reuse their processing (see OpenAiLlmClient).

    fun commandInput(today: StayDate, utterance: String): String =
        "Today: ${isoDate(today)}\nCommand: $utterance"

    fun answerInput(today: StayDate, slot: String, question: String, answer: String): String =
        "Today: ${isoDate(today)}\nDetail being collected: $slot\nQuestion: $question\nReply: $answer"

    /** History only grows during a run, so each step's input starts with the previous step's. */
    fun screenInput(today: StayDate, goal: String, history: List<String>, screen: String): String {
        val steps = if (history.isEmpty()) "Nothing yet." else history.joinToString("\n")
        return "Today: ${isoDate(today)}\nGoal: $goal\n\nSo far:\n$steps\n\nCurrent screen:\n$screen"
    }

    /** Token counts from a Responses API reply, for the timing log; empty if it has none. */
    fun usage(json: String): String = try {
        val usage = JSONObject(json).optJSONObject("usage")
        if (usage == null) "" else {
            val cached = usage.optJSONObject("input_tokens_details")?.optInt("cached_tokens") ?: 0
            val reasoning = usage.optJSONObject("output_tokens_details")?.optInt("reasoning_tokens") ?: 0
            "tokens in ${usage.optInt("input_tokens")} (cached $cached), " +
                "out ${usage.optInt("output_tokens")} (reasoning $reasoning)"
        }
    } catch (e: JSONException) {
        ""
    }

    /** The model's text from a Responses API reply. */
    @Throws(LlmUnavailableException::class)
    fun outputText(json: String): String = readJson(json) { root ->
        val output = root.optJSONArray("output") ?: JSONArray()
        for (i in 0 until output.length()) {
            val item = output.getJSONObject(i)
            if (item.optString("type") != "message") continue
            val content = item.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val part = content.getJSONObject(j)
                if (part.optString("type") == "output_text") return@readJson part.getString("text")
            }
        }
        throw LlmUnavailableException("No text in the response")
    }

    @Throws(LlmUnavailableException::class)
    fun commands(json: String, today: StayDate): List<LlmCommand> = readJson(json) { root ->
        val commands = root.getJSONArray("commands")
        if (commands.length() == 0) throw LlmUnavailableException("No commands in the response")
        List(commands.length()) { command(commands.getJSONObject(it), today) }
    }

    @Throws(LlmUnavailableException::class)
    fun answer(json: String, today: StayDate): LlmAnswer = readJson(json) { reply ->
        val root = reply.getJSONObject("answer")
        val status = LlmAnswer.Status.entries.firstOrNull { it.name.equals(root.getString("status"), ignoreCase = true) }
            ?: throw LlmUnavailableException("Unknown answer status")
        val (checkIn, checkOut) = stayDates(root, today)
        LlmAnswer(
            status,
            root.text("value"),
            checkIn,
            checkOut,
            root.count("adults", 1..30),
            root.count("children", 0..20),
            root.childAges(),
            root.count("rooms", 1..30),
            root.text("room")
        )
    }

    private fun <T> readJson(json: String, read: (JSONObject) -> T): T = try {
        read(JSONObject(json))
    } catch (e: JSONException) {
        throw LlmUnavailableException("Invalid JSON from OpenAI", e)
    }

    private fun command(o: JSONObject, today: StayDate): LlmCommand {
        val intent = intent(o, today)
        val booking = intent is AppIntent.HotelSearch && intent.book
        val questions = mutableMapOf<FollowUpSlot, String>()
        val bookingQuestions = mutableMapOf<BookingConversation.Slot, String>()
        val missing = o.optJSONArray("missing") ?: JSONArray()
        for (i in 0 until missing.length()) {
            val entry = missing.getJSONObject(i)
            val name = entry.optString("slot")
            val question = entry.text("question") ?: continue
            // A booking's details are collected by BookingConversation, so its wording goes there.
            if (booking) {
                BookingConversation.Slot.entries.firstOrNull { it.name == name }?.let { bookingQuestions[it] = question }
            } else {
                FollowUpSlot.entries.firstOrNull { it.name == name }?.let { questions[it] = question }
            }
        }
        return LlmCommand(intent, questions, bookingQuestions)
    }

    private fun intent(o: JSONObject, today: StayDate): AppIntent = when (o.getString("intent")) {
        "BATTERY" -> AppIntent.Battery
        "FLASHLIGHT" -> AppIntent.Flashlight(o.bool("enable") ?: true)
        "VOLUME" -> o.level().let { AppIntent.Volume(o.change(it), it) }
        "BRIGHTNESS" -> o.level().let { AppIntent.Brightness(o.change(it), it) }
        "GREETING" -> AppIntent.Greeting
        "SAVE_HISTORY" -> AppIntent.SaveHistory
        "CLEAR_HISTORY" -> AppIntent.ClearHistory
        "REPEAT_LAST" -> AppIntent.RepeatLast
        "TAKE_SCREENSHOT" -> AppIntent.TakeScreenshot
        "CAMERA_ACTION" -> AppIntent.CameraAction(o.bool("take_photo") ?: false)
        "NAVIGATE" -> AppIntent.Navigate(o.text("destination"), o.text("mode")?.takeIf { it in FollowUpRules.TRAVEL_MODES })
        "OPEN_APP" -> AppIntent.OpenApp(o.text("app").orEmpty())
        "CHECK_BALANCE" -> AppIntent.CheckBalance
        "TRANSFER" -> AppIntent.Transfer(o.count("amount", 1..Int.MAX_VALUE), o.text("recipient"))
        "BANK_HISTORY" -> AppIntent.TransactionHistory
        "TOP_UP" -> AppIntent.TopUp
        "FOOD_SEARCH" -> AppIntent.FoodSearch(o.text("query").orEmpty())
        "FOOD_ORDER" -> AppIntent.FoodOrder(o.count("item_id", 1..Int.MAX_VALUE), foodQuery(o))
        "FOOD_CART" -> AppIntent.FoodCart
        "FOOD_CHECKOUT" -> AppIntent.FoodCheckout
        "FOOD_MENU" -> AppIntent.FoodMenu(o.text("restaurant").orEmpty())
        "THROATS_POST" -> AppIntent.ThroatsPost(o.text("content").orEmpty())
        "THROATS_REPOST" -> AppIntent.ThroatsRepost(o.text("post_id"))
        "THROATS_COMMENT" -> AppIntent.ThroatsComment(o.text("post_id"), o.text("content").orEmpty())
        "TYPE_TEXT" -> typeText(o)
        "APP_ORDER" -> AppIntent.AppOrder(o.text("item").orEmpty(), o.text("restaurant"), o.text("app").orEmpty())
        "HOTEL" -> hotel(o, today)
        "CLICK_TEXT" -> o.text("text")?.let { AppIntent.ClickText(it) } ?: AppIntent.Unknown
        "APP_TASK" -> o.text("goal")?.let { AppIntent.AppTask(it, o.text("app")) } ?: AppIntent.Unknown
        else -> AppIntent.Unknown
    }

    /** One action of the screen agent. Whether its element exists is checked by [ScreenAgent]. */
    @Throws(LlmUnavailableException::class)
    fun screenAction(json: String): ScreenAction = screenTurn(json).action

    /**
     * The agent's action plus the taps and typing it planned right after it on the same screen.
     * Follow-ups are capped at [MAX_FOLLOW_UPS]; unusable ones are dropped, never guessed at.
     */
    @Throws(LlmUnavailableException::class)
    fun screenTurn(json: String): ScreenTurn = readJson(json) { o ->
        val action = action(o) ?: throw LlmUnavailableException("Unknown screen action")
        val then = o.optJSONArray("then") ?: JSONArray()
        val followUps = List(then.length()) { then.optJSONObject(it) }
            .mapNotNull { step -> step?.let { action(it) } }
            .filter { it is ScreenAction.Tap || it is ScreenAction.Type }
            .take(MAX_FOLLOW_UPS)
        // Nothing can follow a step that ends the turn or waits for the user.
        val ends = action !is ScreenAction.Tap && action !is ScreenAction.Type
        ScreenTurn(action, if (ends) emptyList() else followUps)
    }

    private const val MAX_FOLLOW_UPS = 3
    private const val MAX_CHOICES = 6

    private fun action(o: JSONObject): ScreenAction? {
        val node = o.count("node", 0..Int.MAX_VALUE)
        return when (o.optString("action")) {
            "tap" -> ScreenAction.Tap(node)
            "type" -> ScreenAction.Type(node, o.text("text"), o.bool("submit") ?: false)
            "scroll_down" -> ScreenAction.ScrollDown
            "scroll_up" -> ScreenAction.ScrollUp
            "back" -> ScreenAction.Back
            "wait" -> ScreenAction.Wait
            "ask_user" -> ScreenAction.Ask(o.text("say"), o.choices())
            "done" -> ScreenAction.Done(o.text("say"))
            "give_up" -> ScreenAction.GiveUp(o.text("say"))
            else -> null
        }
    }

    /** Short answers the user can tap instead of saying them. */
    private fun JSONObject.choices(): List<String> {
        val array = optJSONArray("choices") ?: return emptyList()
        return List(array.length()) { array.optString(it).trim() }
            .filter { it.isNotEmpty() && it.length <= 60 }
            .distinct()
            .take(MAX_CHOICES)
    }

    /** The service reads a FoodGorilla order as "item from restaurant". */
    private fun foodQuery(o: JSONObject): String? {
        val item = o.text("item") ?: return null
        return o.text("restaurant")?.let { "$item from $it" } ?: item
    }

    private fun typeText(o: JSONObject): AppIntent.TypeText {
        val text = o.text("text").orEmpty()
        val app = o.text("app")
        return AppIntent.TypeText(text, app, o.bool("submit") ?: true, if (app != null) "$text on $app" else text)
    }

    private fun hotel(o: JSONObject, today: StayDate): AppIntent.HotelSearch {
        val (checkIn, checkOut) = stayDates(o, today)
        return AppIntent.HotelSearch(
            destination = o.text("destination").orEmpty(),
            checkIn = checkIn,
            checkOut = checkOut,
            hotel = o.text("hotel"),
            book = o.bool("book") ?: false,
            room = o.text("room"),
            adults = o.count("adults", 1..30),
            children = o.count("children", 0..20),
            childAges = o.childAges(),
            rooms = o.count("rooms", 1..30)
        )
    }

    /** A check-in in the past is dropped; a check-out not after it becomes one night. */
    private fun stayDates(o: JSONObject, today: StayDate): Pair<StayDate?, StayDate?> {
        val checkIn = o.date("check_in")?.takeIf { it >= today } ?: return null to null
        val checkOut = o.date("check_out")?.takeIf { it > checkIn } ?: checkIn.plusDays(1)
        return checkIn to checkOut
    }

    private fun JSONObject.date(key: String): StayDate? {
        val match = text(key)?.let { ISO_DATE.find(it) } ?: return null
        val (year, month, day) = match.destructured
        if (month.toInt() !in 1..12) return null
        val calendar = GregorianCalendar(year.toInt(), month.toInt() - 1, 1)
        if (day.toInt() !in 1..calendar.getActualMaximum(Calendar.DAY_OF_MONTH)) return null
        return StayDate(year.toInt(), month.toInt(), day.toInt())
    }

    private fun JSONObject.text(key: String): String? =
        if (isNull(key)) null else optString(key).trim().ifEmpty { null }

    private fun JSONObject.bool(key: String): Boolean? =
        if (isNull(key) || opt(key) !is Boolean) null else getBoolean(key)

    /** Only a whole number in [range]; JSON numbers may arrive as Int or Long. */
    private fun JSONObject.count(key: String, range: IntRange): Int? {
        if (isNull(key)) return null
        val number = (opt(key) as? Number)?.toLong() ?: return null
        return number.takeIf { it in range.first.toLong()..range.last.toLong() }?.toInt()
    }

    private fun JSONObject.level(): Int? = count("level", 0..100)

    private fun JSONObject.change(level: Int?): AppIntent.Change =
        AppIntent.Change.entries.firstOrNull { it.name == optString("change") }
            ?.takeUnless { it == AppIntent.Change.SET && level == null }
            ?: if (level != null) AppIntent.Change.SET else AppIntent.Change.UP

    private fun JSONObject.childAges(): List<Int> {
        val ages = optJSONArray("child_ages") ?: return emptyList()
        return List(ages.length()) { ages.optInt(it, -1) }.filter { it in 0..17 }
    }
}
