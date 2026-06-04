package com.example.myapplication.nlp

/**
 * The intent *category* of an utterance, independent of its filled slots.
 *
 * Both the rule-based and ML parsers classify to an [IntentLabel] first; [IntentSlots]
 * then fills the slots from the utterance. This keeps slot extraction in one place no
 * matter which classifier produced the label.
 */
enum class IntentLabel {
    BATTERY,
    FLASHLIGHT,
    VOLUME,
    BRIGHTNESS,
    NAVIGATE,
    OPEN_APP,
    CHECK_BALANCE,
    TRANSFER,
    HISTORY,
    TOP_UP,
    FOOD_SEARCH,
    FOOD_ORDER,
    FOOD_CART,
    FOOD_CHECKOUT,
    FOOD_MENU,
    THROATS_POST,
    THROATS_REPOST,
    THROATS_COMMENT,
    CLICK_TEXT,
    UNKNOWN
}
