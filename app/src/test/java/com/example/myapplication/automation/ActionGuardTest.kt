package com.example.myapplication.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionGuardTest {

    private fun tap(label: String) = ActionGuard.forTap(listOf(label))

    @Test
    fun paymentButtonsAreBlocked() {
        listOf("Pay now", "Checkout", "Go to checkout", "Place order", "Buy now", "Confirm payment", "結帳", "立即購買", "下单").forEach {
            assertEquals(it, ActionGuard.Verdict.BLOCK, tap(it))
        }
    }

    @Test
    fun consequentialButtonsNeedAYes() {
        listOf("Send", "Delete", "Post", "Reserve", "Allow", "傳送", "刪除").forEach {
            assertEquals(it, ActionGuard.Verdict.CONFIRM, tap(it))
        }
    }

    @Test
    fun ordinaryButtonsAreAllowed() {
        listOf("Add to cart", "Large", "Hawaiian Pizza", "Search", "Settings", "Payless Shoes", "Facebook").forEach {
            assertEquals(it, ActionGuard.Verdict.ALLOW, tap(it))
        }
    }

    @Test
    fun longDescriptionsAreNotButtonLabels() {
        val card = "Hotel Sunroute, 8.4 rating, free cancellation, no prepayment needed, pay at the property, HK$ 1,150"
        assertEquals(ActionGuard.Verdict.ALLOW, tap(card))
    }

    @Test
    fun aPayChildMakesTheWholeTargetBlocked() {
        assertEquals(ActionGuard.Verdict.BLOCK, ActionGuard.forTap(listOf("Total HK$ 120", "Pay")))
    }

    @Test
    fun blockIsReportedBeforeConfirm() {
        assertEquals("Pay", ActionGuard.reason(listOf("Send", "Pay")))
    }

    @Test
    fun privateFieldsAreRecognised() {
        assertTrue(ActionGuard.isPrivateField(listOf("Email address"), password = false))
        assertTrue(ActionGuard.isPrivateField(listOf("Card number"), password = false))
        assertTrue(ActionGuard.isPrivateField(emptyList(), password = true))
        assertTrue(ActionGuard.isPrivateField(listOf("Search"), password = true))
    }

    @Test
    fun searchAndMessageFieldsAreNotPrivate() {
        assertFalse(ActionGuard.isPrivateField(listOf("Search in email"), password = false))
        assertFalse(ActionGuard.isPrivateField(listOf("Message"), password = false))
    }

    @Test
    fun recognisesAPaymentScreen() {
        assertTrue(ActionGuard.isPaymentScreen(listOf("Your order", "Payment method", "Visa")))
        assertFalse(ActionGuard.isPaymentScreen(listOf("Your cart", "Hawaiian Pizza")))
    }
}
