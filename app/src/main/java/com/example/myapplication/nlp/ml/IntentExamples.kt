package com.example.myapplication.nlp.ml

import com.example.myapplication.nlp.IntentLabel

/**
 * Canonical example utterances per intent, used as the nearest-neighbour bank for
 * embedding-based classification. No training pipeline is required — adding a phrasing
 * is as simple as adding a string here.
 *
 * Slot placeholders (a destination, an app name, etc.) are written naturally; the
 * embedder captures the intent shape, and [com.example.myapplication.nlp.IntentSlots]
 * extracts the actual values from the user's utterance afterwards.
 *
 * [IntentLabel.CLICK_TEXT] and [IntentLabel.UNKNOWN] are intentionally absent: they are
 * fallbacks, not things to match positively.
 */
object IntentExamples {

    val DEFAULT: Map<IntentLabel, List<String>> = mapOf(
        IntentLabel.BATTERY to listOf(
            "what's my battery level", "how much battery do I have", "battery percentage",
            "is my phone charged"
        ),
        IntentLabel.FLASHLIGHT to listOf(
            "turn on the flashlight", "switch on the torch", "turn off the flashlight",
            "it's dark, give me some light"
        ),
        IntentLabel.VOLUME to listOf(
            "turn the volume up", "make it louder", "lower the volume", "set volume to 50"
        ),
        IntentLabel.BRIGHTNESS to listOf(
            "make the screen brighter", "dim the display", "set brightness to 80",
            "increase brightness"
        ),
        IntentLabel.NAVIGATE to listOf(
            "navigate to the mall", "take me to the airport", "give me directions home",
            "how do I get to the station"
        ),
        IntentLabel.OPEN_APP to listOf(
            "open Spotify", "launch the camera", "start the calculator app"
        ),
        IntentLabel.CHECK_BALANCE to listOf(
            "check my nccubank balance", "how much money do I have in nccubank",
            "what's my account balance", "check my balance", "what's my balance",
            "how much money do I have", "show me my balance"
        ),
        IntentLabel.TRANSFER to listOf(
            "transfer 50 to Bob", "send 20 dollars to Alice", "pay 100 to John"
        ),
        IntentLabel.HISTORY to listOf(
            "show my transaction history", "view my recent transactions", "my payment history"
        ),
        IntentLabel.TOP_UP to listOf(
            "top up my account", "refill my balance", "deposit money"
        ),
        IntentLabel.FOOD_SEARCH to listOf(
            "search for pizza on foodgorilla", "find sushi on food gorilla",
            "look for burgers on foodgorilla"
        ),
        IntentLabel.FOOD_ORDER to listOf(
            "order item 12 from foodgorilla", "buy a pizza on food gorilla",
            "place an order on foodgorilla"
        ),
        IntentLabel.FOOD_CART to listOf(
            "open my foodgorilla cart", "show my food gorilla cart", "view my cart on foodgorilla"
        ),
        IntentLabel.FOOD_CHECKOUT to listOf(
            "checkout on foodgorilla", "complete my foodgorilla order", "go to food gorilla checkout"
        ),
        IntentLabel.FOOD_MENU to listOf(
            "show the menu for Mario's on foodgorilla", "open the menu on food gorilla"
        ),
        IntentLabel.THROATS_POST to listOf(
            "post hello world on throats", "create a throats post saying good morning"
        ),
        IntentLabel.THROATS_REPOST to listOf(
            "repost that", "share this post again", "repost it"
        ),
        IntentLabel.THROATS_COMMENT to listOf(
            "comment nice on that post", "reply to this with great job"
        )
    )
}
