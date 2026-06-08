package com.example.myapplication.nlp

/**
 * Builds a concrete [AppIntent] (with slots filled from the utterance) for a given
 * [IntentLabel]. Shared by [RuleBasedParser] and the ML parser so slot extraction
 * lives in exactly one place.
 */
object IntentSlots {

    fun build(label: IntentLabel, command: String): AppIntent {
        val lower = command.lowercase()
        return when (label) {
            IntentLabel.BATTERY -> AppIntent.Battery

            IntentLabel.FLASHLIGHT ->
                AppIntent.Flashlight(enable = !lower.contains("off") && !lower.contains("stop"))

            IntentLabel.VOLUME -> {
                val level = EntityExtractors.number(command)
                AppIntent.Volume(volumeChange(lower, level), level)
            }

            IntentLabel.BRIGHTNESS -> {
                val level = EntityExtractors.number(command)
                AppIntent.Brightness(brightnessChange(lower, level), level)
            }

            IntentLabel.GREETING -> AppIntent.Greeting
            IntentLabel.SAVE_HISTORY -> AppIntent.SaveHistory
            IntentLabel.CLEAR_HISTORY -> AppIntent.ClearHistory
            IntentLabel.REPEAT_LAST -> AppIntent.RepeatLast
            IntentLabel.TAKE_SCREENSHOT -> AppIntent.TakeScreenshot
            IntentLabel.CAMERA_ACTION -> AppIntent.CameraAction(takePhoto = lower.contains("take") || lower.contains("snap") || lower.contains("拍"))

            IntentLabel.NAVIGATE -> {
                val dest = EntityExtractors
                    .targetAfter(command, listOf("navigate to", "take me to", "go to", "navigate"))
                    .takeIf { it.isNotEmpty() && it != "somewhere" }
                val mode = when {
                    lower.contains("bus") -> "bus"
                    lower.contains("walk") -> "walking"
                    lower.contains("driv") || lower.contains("car") -> "driving"
                    else -> null
                }
                AppIntent.Navigate(dest, mode)
            }

            IntentLabel.OPEN_APP ->
                AppIntent.OpenApp(EntityExtractors.targetAfter(command, listOf("open", "launch")))

            IntentLabel.CHECK_BALANCE -> AppIntent.CheckBalance

            IntentLabel.TRANSFER -> {
                val amount = EntityExtractors.number(command)
                val recipient = EntityExtractors.targetAfter(command, listOf("to"))
                    .split(" ").firstOrNull()?.takeIf { it.isNotEmpty() }
                AppIntent.Transfer(amount, recipient)
            }

            IntentLabel.HISTORY -> AppIntent.TransactionHistory
            IntentLabel.TOP_UP -> AppIntent.TopUp

            IntentLabel.FOOD_SEARCH -> {
                val query = EntityExtractors.targetAfter(command, listOf("search for", "find", "search"))
                    .replace("on foodgorilla", "", true)
                    .replace("on food gorilla", "", true).trim()
                AppIntent.FoodSearch(query)
            }

            IntentLabel.FOOD_ORDER -> {
                val itemId = EntityExtractors.number(command)
                val query = EntityExtractors.targetAfter(command, listOf("order", "buy"))
                    .replace("from foodgorilla", "", true)
                    .replace("from food gorilla", "", true).trim().takeIf { it.isNotEmpty() }
                AppIntent.FoodOrder(itemId, query)
            }

            IntentLabel.FOOD_CART -> AppIntent.FoodCart
            IntentLabel.FOOD_CHECKOUT -> AppIntent.FoodCheckout

            IntentLabel.FOOD_MENU -> {
                val restaurant = EntityExtractors.targetAfter(command, listOf("menu for", "menu of", "menu"))
                    .replace("on foodgorilla", "", true)
                    .replace("on food gorilla", "", true).trim()
                AppIntent.FoodMenu(restaurant)
            }

            IntentLabel.THROATS_POST -> {
                val content = EntityExtractors.targetAfter(command, listOf("post", "throat", "throats"))
                    .replace("on throats", "", true)
                    .replace("to throats", "", true).trim()
                AppIntent.ThroatsPost(content)
            }

            IntentLabel.THROATS_REPOST -> AppIntent.ThroatsRepost(EntityExtractors.uuid(command))

            IntentLabel.THROATS_COMMENT -> {
                val content = EntityExtractors.targetAfter(command, listOf("comment", "reply", "on", "to"))
                    .split("on").first().trim()
                AppIntent.ThroatsComment(EntityExtractors.uuid(command), content)
            }

            IntentLabel.CLICK_TEXT -> AppIntent.ClickText(command.trim())
            IntentLabel.UNKNOWN -> AppIntent.Unknown
        }
    }

    private fun volumeChange(lower: String, level: Int?): AppIntent.Change = when {
        level != null && lower.contains("set") -> AppIntent.Change.SET
        lower.contains("up") || lower.contains("raise") || lower.contains("increase") || lower.contains("louder") -> AppIntent.Change.UP
        lower.contains("down") || lower.contains("lower") || lower.contains("decrease") || lower.contains("quieter") -> AppIntent.Change.DOWN
        level != null -> AppIntent.Change.SET
        else -> AppIntent.Change.UP
    }

    private fun brightnessChange(lower: String, level: Int?): AppIntent.Change = when {
        level != null && lower.contains("set") -> AppIntent.Change.SET
        lower.contains("up") || lower.contains("brighter") || lower.contains("increase") -> AppIntent.Change.UP
        lower.contains("down") || lower.contains("dimmer") || lower.contains("dim") || lower.contains("decrease") -> AppIntent.Change.DOWN
        level != null -> AppIntent.Change.SET
        else -> AppIntent.Change.UP
    }
}
