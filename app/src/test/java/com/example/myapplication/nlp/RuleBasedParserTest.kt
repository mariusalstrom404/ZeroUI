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
        assertEquals(AppIntent.CheckBalance, parser.parse("check my nccubank balance"))
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
    fun typeText_searchWithApp() {
        val intent = parser.parse("search for pizza on foodpanda") as AppIntent.TypeText
        assertEquals("pizza", intent.text)
        assertEquals("foodpanda", intent.app)
        assertEquals("pizza on foodpanda", intent.fullText)
        assertTrue(intent.submit)
    }

    @Test
    fun typeText_withoutApp() {
        val intent = parser.parse("search chicken rice") as AppIntent.TypeText
        assertEquals("chicken rice", intent.text)
        assertNull(intent.app)
    }

    @Test
    fun typeText_typeDoesNotSubmit() {
        val intent = parser.parse("type hello world") as AppIntent.TypeText
        assertEquals("hello world", intent.text)
        assertTrue(!intent.submit)
    }

    @Test
    fun typeText_foodGorillaKeepsDeepLink() {
        assertTrue(parser.parse("search for pizza on foodgorilla") is AppIntent.FoodSearch)
    }

    @Test
    fun appOrder_itemRestaurantAndApp() {
        val intent = parser.parse("Order Hawaiian Pizza from Pizza Hut on Foodpanda") as AppIntent.AppOrder
        assertEquals("Hawaiian Pizza", intent.item)
        assertEquals("Pizza Hut", intent.restaurant)
        assertEquals("Foodpanda", intent.app)
        assertTrue(!intent.requiresConfirmation)
    }

    @Test
    fun appOrder_lastSeparatorIsTheApp() {
        val intent = parser.parse("order fried rice from Din Tai Fung in Taipei 101 on foodpanda") as AppIntent.AppOrder
        assertEquals("fried rice", intent.item)
        assertEquals("Din Tai Fung in Taipei 101", intent.restaurant)
        assertEquals("foodpanda", intent.app)
    }

    @Test
    fun appOrder_withoutRestaurant() {
        val intent = parser.parse("order bubble tea on foodpanda") as AppIntent.AppOrder
        assertEquals("bubble tea", intent.item)
        assertNull(intent.restaurant)
    }

    @Test
    fun foodGorillaOrder_isUnchanged() {
        assertTrue(parser.parse("order a burger from MidOnald's on foodgorilla") is AppIntent.FoodOrder)
    }

    @Test
    fun searchAlone_isStillAClick() {
        assertTrue(parser.parse("search") is AppIntent.ClickText)
    }

    @Test
    fun unmatched_fallsBackToClickText() {
        val intent = parser.parse("frobnicate the widget")
        assertTrue(intent is AppIntent.ClickText)
        assertEquals("frobnicate the widget", (intent as AppIntent.ClickText).text)
    }
}
