package com.example.myapplication.nlp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EntityExtractorsTest {

    @Test
    fun number_findsFirstRun() {
        assertEquals(50, EntityExtractors.number("transfer 50 to bob"))
        assertEquals(12, EntityExtractors.number("order item 12 please"))
    }

    @Test
    fun number_returnsNullWhenAbsent() {
        assertNull(EntityExtractors.number("transfer money to bob"))
    }

    @Test
    fun uuid_isExtracted() {
        val id = "550e8400-e29b-41d4-a716-446655440000"
        assertEquals(id, EntityExtractors.uuid("repost $id now"))
    }

    @Test
    fun uuid_returnsNullWhenAbsent() {
        assertNull(EntityExtractors.uuid("repost that post"))
    }

    @Test
    fun targetAfter_stripsKeywordAndLeadingTo() {
        assertEquals("the mall", EntityExtractors.targetAfter("navigate to the mall", listOf("navigate to")))
        assertEquals("the airport", EntityExtractors.targetAfter("take me to the airport", listOf("take me")))
    }

    @Test
    fun targetAfter_returnsEmptyWhenNoKeyword() {
        assertEquals("", EntityExtractors.targetAfter("hello there", listOf("open", "launch")))
    }
}
