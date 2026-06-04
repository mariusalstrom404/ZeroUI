package com.example.myapplication.nlp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleBasedParserTest {

    private val parser = RuleBasedParser()

    @Test
    fun battery() {
        assertEquals(AppIntent.Battery, parser.parse("what's my battery level"))
    }

    @Test
    fun checkBalance_requiresFinanceContext() {
        assertEquals(AppIntent.CheckBalance, parser.parse("check my mockfinance balance"))
        // "balance" alone (no finance/mock) should NOT classify as CheckBalance
        assertTrue(parser.parse("balance the books") is AppIntent.ClickText)
    }

    @Test
    fun transfer_extractsAmountAndRecipient() {
        val intent = parser.parse("transfer 50 to bob")
        assertTrue(intent is AppIntent.Transfer)
        intent as AppIntent.Transfer
        assertEquals(50, intent.amount)
        assertEquals("bob", intent.recipient)
    }

    @Test
    fun transfer_requiresConfirmation() {
        assertTrue(parser.parse("transfer 20 to alice").requiresConfirmation)
    }

    @Test
    fun navigate_withDestinationAndMode() {
        val intent = parser.parse("navigate to the mall by bus")
        assertTrue(intent is AppIntent.Navigate)
        intent as AppIntent.Navigate
        assertEquals("the mall by bus", intent.destination)
        assertEquals("bus", intent.mode)
    }

    @Test
    fun navigate_withoutDestinationIsNull() {
        val intent = parser.parse("navigate") as AppIntent.Navigate
        assertNull(intent.destination)
    }

    @Test
    fun openApp_extractsName() {
        val intent = parser.parse("open Spotify") as AppIntent.OpenApp
        assertEquals("Spotify", intent.name)
    }

    @Test
    fun flashlight_onAndOff() {
        assertTrue((parser.parse("turn on the flashlight") as AppIntent.Flashlight).enable)
        assertTrue(!(parser.parse("turn off the flashlight") as AppIntent.Flashlight).enable)
    }

    @Test
    fun volume_directions() {
        assertEquals(AppIntent.Change.UP, (parser.parse("turn the volume up") as AppIntent.Volume).change)
        assertEquals(AppIntent.Change.DOWN, (parser.parse("lower the volume") as AppIntent.Volume).change)
        val set = parser.parse("set volume to 30") as AppIntent.Volume
        assertEquals(AppIntent.Change.SET, set.change)
        assertEquals(30, set.level)
    }

    @Test
    fun foodGorilla_branches() {
        assertTrue(parser.parse("search for pizza on foodgorilla") is AppIntent.FoodSearch)
        assertTrue(parser.parse("open my foodgorilla cart") is AppIntent.FoodCart)
        assertTrue(parser.parse("checkout on foodgorilla").requiresConfirmation)
    }

    @Test
    fun throats_post() {
        val intent = parser.parse("post hello world on throats") as AppIntent.ThroatsPost
        assertEquals("hello world", intent.content)
    }

    @Test
    fun unmatched_fallsBackToClickText() {
        val intent = parser.parse("frobnicate the widget")
        assertTrue(intent is AppIntent.ClickText)
        assertEquals("frobnicate the widget", (intent as AppIntent.ClickText).text)
    }
}
