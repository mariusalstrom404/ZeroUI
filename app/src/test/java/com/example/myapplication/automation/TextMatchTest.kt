package com.example.myapplication.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextMatchTest {

    @Test
    fun exactBeatsContains() {
        // "Cart" must prefer the Cart button over "Add to Cart".
        assertTrue(TextMatch.score("Cart", "Cart") > TextMatch.score("Add to Cart", "Cart"))
    }

    @Test
    fun exactItemBeatsLongerItemContainingIt() {
        assertTrue(TextMatch.score("Burger", "burger") > TextMatch.score("Cheese Burger Combo", "burger"))
    }

    @Test
    fun prefixBeatsMidStringMatch() {
        assertEquals(TextMatch.PREFIX, TextMatch.score("Cart (2)", "cart"))
        assertEquals(TextMatch.CONTAINS_WORDS, TextMatch.score("Add to Cart", "cart"))
    }

    @Test
    fun punctuationAndSpacingAreIgnoredForEquality() {
        assertEquals(TextMatch.EXACT, TextMatch.score("MidOnald’s", "midonald's"))
        assertEquals(TextMatch.EXACT_LOOSE, TextMatch.score("MidOnald's", "midonalds"))
        assertEquals(TextMatch.EXACT_LOOSE, TextMatch.score("Top-up", "top up"))
    }

    @Test
    fun partialWordsDoNotMatchAsWholeWords() {
        // "Added to cart" toast must not look like the "Add to Cart" button.
        assertEquals(TextMatch.NONE, TextMatch.score("Added to cart", "Add to Cart"))
    }

    @Test
    fun shortTargetsDoNotMatchInsideOtherWords() {
        assertEquals(TextMatch.NONE, TextMatch.score("Free shipping", "hi"))
    }

    @Test
    fun longerTargetsStillMatchLoosely() {
        assertEquals(TextMatch.CONTAINS_LOOSE, TextMatch.score("TopUpButton", "top up"))
    }

    @Test
    fun extraWordsBetweenTargetWordsStillMatch() {
        val s = TextMatch.score("Hawaiian Personal Pizza Combo", "Hawaiian Pizza")
        assertTrue(s in TextMatch.WORDS_MIN..TextMatch.WORDS_MAX)
        // Uber Eats style button.
        assertTrue(TextMatch.score("Add 1 to order • \$12.99", "Add to order") >= TextMatch.WORDS_MIN)
    }

    @Test
    fun pluralsStemsAndTyposMatch() {
        assertTrue(TextMatch.score("Hawaiian Pizzas", "Hawaiian Pizza") >= TextMatch.WORDS_MIN)
        assertTrue(TextMatch.score("Hawaiian Pizza", "Hawaii Pizza") >= TextMatch.WORDS_MIN)
        assertTrue(TextMatch.score("Margherita Pizza", "Margarita Pizza") >= TextMatch.WORDS_MIN)
        assertTrue(TextMatch.score("Chicken Wings", "chiken wing") >= TextMatch.WORDS_MIN)
    }

    @Test
    fun wordOrderMayDiffer() {
        val swapped = TextMatch.score("Pizza, Hawaiian", "Hawaiian Pizza")
        assertTrue(swapped >= TextMatch.WORDS_MIN)
        assertTrue(swapped < TextMatch.score("Hawaiian Pizza (Large)", "Hawaiian Pizza"))
    }

    @Test
    fun chineseWithExtraCharactersMatches() {
        assertTrue(TextMatch.score("夏威夷個人披薩套餐", "夏威夷披薩") >= TextMatch.WORDS_MIN)
    }

    @Test
    fun tighterMatchesRankHigher() {
        val target = "Hawaiian Pizza"
        val exact = TextMatch.score("Hawaiian Pizza", target)
        val combo = TextMatch.score("Hawaiian Personal Pizza Combo", target)
        val longText = TextMatch.score(
            "Our famous Hawaiian style ham with pineapple on a thin crust pizza base, baked fresh daily", target
        )
        assertTrue(exact > combo)
        assertTrue(combo > longText)
    }

    @Test
    fun missingWordsDoNotMatch() {
        // Every spoken word must be on screen: a different pizza is not a partial match.
        assertEquals(TextMatch.NONE, TextMatch.score("Pepperoni Pizza", "Hawaiian Pizza"))
        assertEquals(TextMatch.NONE, TextMatch.score("Transfer to Alice", "Transfer to Bob"))
    }

    @Test
    fun shortWordsAreNeverFuzzy() {
        // One letter apart, but short names must not be confused.
        assertEquals(TextMatch.NONE, TextMatch.score("Transfer to Rob", "Transfer to Bob"))
        assertEquals(TextMatch.NONE, TextMatch.score("Hot deals", "Hat"))
    }

    @Test
    fun singleCjkCharacterDoesNotMatchInsideAWord() {
        assertEquals(TextMatch.NONE, TextMatch.score("購買", "買"))
    }

    @Test
    fun blankInputsNeverMatch() {
        assertEquals(TextMatch.NONE, TextMatch.score(null, "Cart"))
        assertEquals(TextMatch.NONE, TextMatch.score("Cart", " "))
    }
}
