package com.example.myapplication.nlp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationClosingTest {

    @Test
    fun goodbyesEndTheConversation() {
        listOf("No thanks", "nothing else", "That's all.", "thanks", "Thank you!", "never mind", "I'm done", "bye")
            .forEach { assertTrue(it, ConversationClosing.isClosing(it)) }
    }

    @Test
    fun requestsAreNotGoodbyes() {
        listOf("turn on wifi", "no wait, open the calculator", "thanks, now go home", "stop", "")
            .forEach { assertFalse(it, ConversationClosing.isClosing(it)) }
    }
}
