package com.example.myapplication.automation

import com.example.myapplication.nlp.ResumeRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppMemoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun recordsNavigationHintsAndPromotesRepeatedChoices() {
        val store = AppMemoryStore()
        store.record(
            "com.example.shop",
            AppLearning(
                dismissLabels = listOf("Skip"),
                searchEntries = listOf("Search products"),
                playbook = "Tap \"Search products\" → Type <input> → Tap \"Filter\"",
                userChoices = listOf("Large")
            )
        )
        // First choice count is 1 — not yet promoted to frequent preference.
        val firstHints = store.hintsFor("com.example.shop")!!
        assertTrue(firstHints.contains("\"Skip\""))
        assertTrue(firstHints.contains("\"Search products\""))
        assertTrue(firstHints.contains("Type <input>"))
        assertFalse(firstHints.contains("User often prefers"))

        // Second time choosing "Large" promotes it as a soft preference hint.
        store.record("com.example.shop", AppLearning(userChoices = listOf("Large")))
        val secondHints = store.hintsFor("com.example.shop")!!
        assertTrue(secondHints.contains("User often prefers: \"Large\""))
    }

    @Test
    fun sanitizerBlocksPrivateSensitiveAndPaymentLabels() {
        assertNull(AppMemoryStore.sanitizeLabel("alice@example.com"))
        assertNull(AppMemoryStore.sanitizeLabel("Card 41112222"))
        assertNull(AppMemoryStore.sanitizeLabel("Password"))
        assertNull(AppMemoryStore.sanitizeLabel("Pay now"))
        assertNull(AppMemoryStore.sanitizeLabel("A".repeat(40)))
        assertEquals("Search", AppMemoryStore.sanitizeLabel("  Search  "))
        assertEquals("以後再說", AppMemoryStore.sanitizeLabel("以後再說"))
    }

    @Test
    fun persistsToDiskAndClearsCleanly() {
        val file = tempFolder.newFile("app_memory.json")
        file.delete()
        val writer = AppMemoryStore(file)
        writer.record(
            "com.spotify.music",
            AppLearning(dismissLabels = listOf("Not now"), searchEntries = listOf("Search"))
        )
        assertEquals(1, writer.appCount())

        val reader = AppMemoryStore(file)
        assertEquals(1, reader.appCount())
        val hints = reader.hintsFor("com.spotify.music")
        assertNotNull(hints)
        assertTrue(hints!!.contains("Not now"))
        assertTrue(hints.contains("Search"))

        reader.clear()
        assertEquals(0, reader.appCount())
        assertNull(reader.hintsFor("com.spotify.music"))
        assertFalse(file.exists())
    }

    @Test
    fun evictsOldestAppsBeyondCap() {
        var now = 1000L
        val store = AppMemoryStore(clock = { ++now })
        for (i in 1..(AppMemoryStore.MAX_APPS + 5)) {
            store.record("com.app.$i", AppLearning(dismissLabels = listOf("Close")))
        }
        assertEquals(AppMemoryStore.MAX_APPS, store.appCount())
        assertNull(store.entryFor("com.app.1"))
        assertNotNull(store.entryFor("com.app.${AppMemoryStore.MAX_APPS + 5}"))
    }

    @Test
    fun resumeRulesRecognizeChineseAndEnglishReplies() {
        listOf("Resume", "continue", "I'm done", "done", "繼續", "繼續任務", "好了", "登入好了", "可以繼續了").forEach {
            assertTrue("Expected resume for '$it'", ResumeRules.isResume(it))
            assertFalse("Did not expect cancel for '$it'", ResumeRules.isCancel(it))
        }
        listOf("Cancel", "stop", "never mind", "取消", "算了", "不用了", "結束任務").forEach {
            assertTrue("Expected cancel for '$it'", ResumeRules.isCancel(it))
            assertFalse("Did not expect resume for '$it'", ResumeRules.isResume(it))
        }
        // Unrelated new commands should be neither resume nor cancel.
        assertFalse(ResumeRules.isResume("What's the weather today?"))
        assertFalse(ResumeRules.isCancel("What's the weather today?"))
    }
}
