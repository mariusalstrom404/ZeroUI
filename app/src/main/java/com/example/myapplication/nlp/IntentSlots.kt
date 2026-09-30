package com.example.myapplication.nlp

/**
 * Builds a concrete [AppIntent] (with slots filled from the utterance) for a given
 * [IntentLabel]. Shared by [RuleBasedParser] and the ML parser so slot extraction
 * lives in exactly one place.
 */
object IntentSlots {

    /** Leading verb of a "type this into the current app" command; group 2 is the verb. */
    val TYPE_TEXT_VERB = Regex("^(please\\s+)?(search\\s+for|search|look\\s+up|look\\s+for|type|enter|input)\\s+")

    /** The " on " / " in " before a trailing app name ("pizza on foodpanda", up to three words). */
    private val APP_SEPARATOR = Regex("\\s+(?:on|in)\\s+", RegexOption.IGNORE_CASE)

    /** "order <item> [from <restaurant>] on|in|using|via <app>"; group 2 is the verb. */
    val APP_ORDER = Regex("^(please\\s+)?(order|buy|get\\s+me)\\s+.+\\s+(?:on|in|using|via)\\s+\\S+")
    private val APP_ORDER_SEPARATOR = Regex("\\s+(?:on|in|using|via)\\s+", RegexOption.IGNORE_CASE)

    fun build(label: IntentLabel, command: String): AppIntent {
        val lower = command.lowercase()

        return when (label) {
            // FoodGorilla has a search deep link; keep using it even if the ML parser picked TYPE_TEXT.
            IntentLabel.TYPE_TEXT -> if (lower.contains("foodgorilla") || lower.contains("food gorilla")) {
                build(IntentLabel.FOOD_SEARCH, command)
            } else {
                typeText(command)
            }

            IntentLabel.BATTERY -> AppIntent.Battery

            IntentLabel.FLASHLIGHT ->
                AppIntent.Flashlight(
                    enable = !lower.contains("off") &&
                            !lower.contains("stop")
                )

            IntentLabel.VOLUME -> {
                val level = EntityExtractors.number(command)
                AppIntent.Volume(
                    volumeChange(lower, level),
                    level
                )
            }

            IntentLabel.BRIGHTNESS -> {
                val level = EntityExtractors.number(command)
                AppIntent.Brightness(
                    brightnessChange(lower, level),
                    level
                )
            }

            IntentLabel.GREETING -> AppIntent.Greeting

            IntentLabel.SAVE_HISTORY -> AppIntent.SaveHistory

            IntentLabel.CLEAR_HISTORY -> AppIntent.ClearHistory

            IntentLabel.REPEAT_LAST -> AppIntent.RepeatLast

            IntentLabel.TAKE_SCREENSHOT -> AppIntent.TakeScreenshot

            IntentLabel.CAMERA_ACTION ->
                AppIntent.CameraAction(
                    takePhoto =
                        lower.contains("take") ||
                                lower.contains("snap") ||
                                lower.contains("拍")
                )

            IntentLabel.NAVIGATE -> {
                val dest =
                    EntityExtractors
                        .targetAfter(
                            command,
                            listOf(
                                "navigate to",
                                "take me to",
                                "go to",
                                "navigate"
                            )
                        )
                        .takeIf {
                            it.isNotEmpty() &&
                                    it != "somewhere"
                        }

                val mode = when {
                    lower.contains("bus") ->
                        "bus"

                    lower.contains("walk") ->
                        "walking"

                    lower.contains("driv") ||
                            lower.contains("car") ->
                        "driving"

                    else ->
                        null
                }

                AppIntent.Navigate(
                    dest,
                    mode
                )
            }

            IntentLabel.OPEN_APP ->
                AppIntent.OpenApp(
                    EntityExtractors.targetAfter(
                        command,
                        listOf(
                            "open",
                            "launch"
                        )
                    )
                )

            IntentLabel.CHECK_BALANCE ->
                AppIntent.CheckBalance

            IntentLabel.TRANSFER -> {
                val amount =
                    EntityExtractors.number(command)

                val recipient =
                    EntityExtractors
                        .targetAfter(
                            command,
                            listOf("to")
                        )
                        .split(" ")
                        .firstOrNull()
                        ?.takeIf {
                            it.isNotEmpty()
                        }

                AppIntent.Transfer(
                    amount,
                    recipient
                )
            }

            IntentLabel.HISTORY ->
                AppIntent.TransactionHistory

            IntentLabel.TOP_UP ->
                AppIntent.TopUp

            // The ML parser can map "search for pizza on foodpanda" here; only FoodGorilla
            // itself has the deep link, any other app gets typed into.
            IntentLabel.FOOD_SEARCH -> if (!lower.contains("foodgorilla") && !lower.contains("food gorilla")) {
                typeText(command)
            } else {
                val query =
                    EntityExtractors
                        .targetAfter(
                            command,
                            listOf(
                                "search for",
                                "find",
                                "search"
                            )
                        )
                        .replace(
                            "on foodgorilla",
                            "",
                            true
                        )
                        .replace(
                            "on food gorilla",
                            "",
                            true
                        )
                        .trim()

                AppIntent.FoodSearch(
                    query
                )
            }

            IntentLabel.FOOD_ORDER -> if (
                !lower.contains("foodgorilla") && !lower.contains("food gorilla") && APP_ORDER.containsMatchIn(lower)
            ) {
                appOrder(command)
            } else {
                val itemId =
                    EntityExtractors.number(command)

                var query =
                    EntityExtractors
                        .targetAfter(
                            command,
                            listOf(
                                "order",
                                "buy",
                                "get"
                            )
                        )
                        .replace(
                            "from foodgorilla",
                            "",
                            true
                        )
                        .replace(
                            "from food gorilla",
                            "",
                            true
                        )
                        .replace(
                            "on foodgorilla",
                            "",
                            true
                        )
                        .replace(
                            "on food gorilla",
                            "",
                            true
                        )
                        .trim()

                // Better extraction for "item from restaurant"
                val fromIndex =
                    query.indexOf(
                        " from ",
                        ignoreCase = true
                    )

                val finalQuery =
                    if (fromIndex != -1) {
                        val item =
                            query
                                .substring(
                                    0,
                                    fromIndex
                                )
                                .trim()

                        val restaurant =
                            query
                                .substring(
                                    fromIndex + 6
                                )
                                .trim()

                        "$item from $restaurant"
                    } else {
                        query
                    }

                AppIntent.FoodOrder(
                    itemId,
                    finalQuery.takeIf {
                        it.isNotEmpty()
                    }
                )
            }

            IntentLabel.FOOD_CART ->
                AppIntent.FoodCart

            IntentLabel.FOOD_CHECKOUT ->
                AppIntent.FoodCheckout

            IntentLabel.FOOD_MENU -> {
                val restaurant =
                    EntityExtractors
                        .targetAfter(
                            command,
                            listOf(
                                "menu for",
                                "menu of",
                                "menu"
                            )
                        )
                        .replace(
                            "on foodgorilla",
                            "",
                            true
                        )
                        .replace(
                            "on food gorilla",
                            "",
                            true
                        )
                        .trim()

                AppIntent.FoodMenu(
                    restaurant
                )
            }

            IntentLabel.THROATS_POST -> {
                val content =
                    EntityExtractors
                        .targetAfter(
                            command,
                            listOf(
                                "post",
                                "throat",
                                "throats"
                            )
                        )
                        .replace(
                            "on throats",
                            "",
                            true
                        )
                        .replace(
                            "to throats",
                            "",
                            true
                        )
                        .trim()

                AppIntent.ThroatsPost(
                    content
                )
            }

            IntentLabel.THROATS_REPOST ->
                AppIntent.ThroatsRepost(
                    EntityExtractors.uuid(command)
                )

            IntentLabel.THROATS_COMMENT -> {
                val content =
                    EntityExtractors
                        .targetAfter(
                            command,
                            listOf(
                                "comment",
                                "reply",
                                "on",
                                "to"
                            )
                        )
                        .split("on")
                        .first()
                        .trim()

                AppIntent.ThroatsComment(
                    EntityExtractors.uuid(command),
                    content
                )
            }

            IntentLabel.CLICK_TEXT ->
                AppIntent.ClickText(
                    command.trim()
                )

            IntentLabel.UNKNOWN ->
                AppIntent.Unknown
        }
    }

    /** "search for pizza on foodpanda" → TypeText("pizza", app = "foodpanda", submit = true). */
    private fun typeText(command: String): AppIntent.TypeText {
        val trimmed = command.trim()
        // The ML parser may send phrasings the rule regex doesn't cover ("find sushi on ...").
        val verb = TYPE_TEXT_VERB.find(trimmed.lowercase())
            ?: Regex("^(please\\s+)?(find|look\\s+for)\\s+").find(trimmed.lowercase())
        val rest = if (verb != null) trimmed.substring(verb.range.last + 1).trim() else trimmed
        val submit = verb == null || !verb.groupValues[2].let { it == "type" || it == "enter" || it == "input" }

        // Last "on/in" wins: "rice in soup on foodpanda" → app "foodpanda".
        val separator = APP_SEPARATOR.findAll(rest).lastOrNull()
        val app = separator?.let { rest.substring(it.range.last + 1).trim() }
        return if (separator != null && separator.range.first > 0 && app!!.split(Regex("\\s+")).size <= 3) {
            AppIntent.TypeText(rest.substring(0, separator.range.first).trim(), app, submit, rest)
        } else {
            AppIntent.TypeText(rest, null, submit)
        }
    }

    /** "order Hawaiian Pizza from Pizza Hut on Foodpanda" → AppOrder("Hawaiian Pizza", "Pizza Hut", "Foodpanda"). */
    private fun appOrder(command: String): AppIntent.AppOrder {
        val trimmed = command.trim()
        val verb = APP_ORDER.find(trimmed.lowercase())!!
        val afterVerb = trimmed.substring(verb.groups[2]!!.range.last + 1).trim()
        // Last "on/in" separates the app, so "from Pizza Hut in Taipei on Foodpanda" keeps "in Taipei".
        val separator = APP_ORDER_SEPARATOR.findAll(afterVerb).last()
        val app = afterVerb.substring(separator.range.last + 1).trim()
        val body = afterVerb.substring(0, separator.range.first).trim()

        val from = body.lowercase().lastIndexOf(" from ")
        return if (from > 0) {
            AppIntent.AppOrder(body.substring(0, from).trim(), body.substring(from + 6).trim(), app)
        } else {
            AppIntent.AppOrder(body, null, app)
        }
    }

    private fun volumeChange(
        lower: String,
        level: Int?
    ): AppIntent.Change =
        when {
            level != null &&
                    lower.contains("set") ->
                AppIntent.Change.SET

            lower.contains("up") ||
                    lower.contains("raise") ||
                    lower.contains("increase") ||
                    lower.contains("louder") ->
                AppIntent.Change.UP

            lower.contains("down") ||
                    lower.contains("lower") ||
                    lower.contains("decrease") ||
                    lower.contains("quieter") ->
                AppIntent.Change.DOWN

            level != null ->
                AppIntent.Change.SET

            else ->
                AppIntent.Change.UP
        }

    private fun brightnessChange(
        lower: String,
        level: Int?
    ): AppIntent.Change =
        when {
            level != null &&
                    lower.contains("set") ->
                AppIntent.Change.SET

            lower.contains("up") ||
                    lower.contains("brighter") ||
                    lower.contains("increase") ->
                AppIntent.Change.UP

            lower.contains("down") ||
                    lower.contains("dimmer") ||
                    lower.contains("dim") ||
                    lower.contains("decrease") ->
                AppIntent.Change.DOWN

            level != null ->
                AppIntent.Change.SET

            else ->
                AppIntent.Change.UP
        }
}