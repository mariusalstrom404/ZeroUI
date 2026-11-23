package com.example.myapplication.automation

import com.example.myapplication.nlp.LlmRetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenPrunerAndRetryTest {

    @Test
    fun interactiveControlsAtBottomAreNeverDroppedByDenseStaticText() {
        val candidates = mutableListOf<ScreenNodePruner.Candidate<String>>()
        // 50 dense static text rows at the top of a product page
        for (i in 0 until 50) {
            candidates += ScreenNodePruner.Candidate(
                order = i,
                node = ScreenNode(i, "Product detail line $i"),
                interactive = false,
                payload = "static-$i"
            )
        }
        // Bottom action bar with interactive buttons
        candidates += ScreenNodePruner.Candidate(
            order = 50,
            node = ScreenNode(50, "Add to cart", tappable = true, region = "bottom-bar"),
            interactive = true,
            payload = "btn-cart"
        )
        candidates += ScreenNodePruner.Candidate(
            order = 51,
            node = ScreenNode(51, "Checkout", tappable = true, region = "bottom-bar"),
            interactive = true,
            payload = "btn-checkout"
        )

        val selected = ScreenNodePruner.select(candidates, maxNodes = 10)
        assertEquals(10, selected.size)
        val labels = selected.map { it.node.label }
        assertTrue(labels.contains("Add to cart"))
        assertTrue(labels.contains("Checkout"))
        // Preserves original visual reading order
        assertEquals(selected.map { it.order }.sorted(), selected.map { it.order })
    }

    @Test
    fun deduplicatesRepeatedStaticLabelsWhileKeepingIdenticalInteractiveButtons() {
        val candidates = listOf(
            ScreenNodePruner.Candidate(0, ScreenNode(0, "Free delivery"), interactive = false, payload = "s0"),
            ScreenNodePruner.Candidate(1, ScreenNode(1, "Free delivery"), interactive = false, payload = "s1"),
            ScreenNodePruner.Candidate(2, ScreenNode(2, "Add", tappable = true), interactive = true, payload = "b0"),
            ScreenNodePruner.Candidate(3, ScreenNode(3, "Add", tappable = true), interactive = true, payload = "b1")
        )
        val selected = ScreenNodePruner.select(candidates, maxNodes = 10)
        assertEquals(listOf("s0", "b0", "b1"), selected.map { it.payload })
    }

    @Test
    fun retryPolicyRetriesTransientStatusesAndNotClientErrors() {
        listOf(408, 429, 500, 502, 503, 504).forEach { code ->
            assertTrue("Expected retry for HTTP $code", LlmRetry.isRetryableStatus(code))
        }
        listOf(200, 400, 401, 403, 404).forEach { code ->
            assertFalse("Did not expect retry for HTTP $code", LlmRetry.isRetryableStatus(code))
        }
        assertTrue(LlmRetry.backoffMs(0) < LlmRetry.backoffMs(1))
    }
}
