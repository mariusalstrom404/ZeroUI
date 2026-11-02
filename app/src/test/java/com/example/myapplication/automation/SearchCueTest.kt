package com.example.myapplication.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchCueTest {

    private fun score(text: String? = null, desc: String? = null, hint: String? = null, id: String? = null) =
        SearchCue.score(text, desc, hint, id)

    @Test
    fun searchBarsAndIcons() {
        assertTrue(score(text = "Search in Drive") > 0)
        assertTrue(score(desc = "Search") > 0)
        assertTrue(score(hint = "Search for restaurants") > 0)
        assertTrue(score(text = "搜尋") > 0)
        assertTrue(score(hint = "在 YouTube 中搜索") > 0)
        assertTrue(score(desc = "Find") > 0)
        assertTrue(score(id = "com.app:id/action_search") > 0)
        assertTrue(score(id = "com.app:id/query_input") > 0)
    }

    @Test
    fun plainSearchLabelBeatsSentence() {
        assertTrue(score(desc = "Search") > score(text = "Tap to search nearby"))
    }

    @Test
    fun notMistakenForSearch() {
        assertEquals(SearchCue.NONE, score(text = "Research papers"))
        assertEquals(SearchCue.NONE, score(text = "Recent searches"))
        assertEquals(SearchCue.NONE, score(text = "Clear search history"))
        assertEquals(SearchCue.NONE, score(text = "No search results"))
        assertEquals(SearchCue.NONE, score(desc = "Clear search"))
        assertEquals(SearchCue.NONE, score(text = "最近搜尋"))
        assertEquals(SearchCue.NONE, score(text = "Find friends near you and share photos with them today"))
        assertEquals(SearchCue.NONE, score(text = "Find my phone"))
        assertEquals(SearchCue.NONE, score(id = "com.app:id/recent_search_list"))
    }
}
