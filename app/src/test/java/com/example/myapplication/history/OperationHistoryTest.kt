package com.example.myapplication.history

import org.junit.Assert.assertEquals
import org.junit.Test

class OperationHistoryTest {

    @Test
    fun operationRecordCreation() {
        val record = OperationRecord(
            id = "test-id",
            timestamp = 1000L,
            utterance = "take a screenshot",
            description = "Taking a screenshot",
            status = "SUCCESS"
        )
        assertEquals("test-id", record.id)
        assertEquals(1000L, record.timestamp)
        assertEquals("take a screenshot", record.utterance)
        assertEquals("Taking a screenshot", record.description)
        assertEquals("SUCCESS", record.status)
    }
}
