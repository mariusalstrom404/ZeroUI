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
    fun blankInputsNeverMatch() {
        assertEquals(TextMatch.NONE, TextMatch.score(null, "Cart"))
        assertEquals(TextMatch.NONE, TextMatch.score("Cart", " "))
    }
}
