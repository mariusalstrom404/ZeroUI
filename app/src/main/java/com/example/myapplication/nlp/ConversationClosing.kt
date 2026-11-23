package com.example.myapplication.nlp

/**
 * Recognises the user saying they're done ("no thanks", "that's all", "never mind"), so ZeroUI
 * stops listening instead of sending it to the LLM as a request. Only meant for when nothing has
 * been asked: as the answer to a question, "no" is an answer, not a goodbye.
 */
object ConversationClosing {

    private val PHRASES = setOf(
        "no", "nope", "nah", "no more", "nothing", "nothing else", "nothing more",
        "thats all", "thats it", "that is all", "that is it", "thats everything", "all done",
        "im done", "i am done", "im good", "i am good", "im fine", "all good", "done",
        "never mind", "nevermind", "forget it", "stop listening", "bye", "goodbye", "see you",
        "thats all thanks", "no that is all", "no thats all", "no thats it"
    )

    fun isClosing(utterance: String): Boolean {
        if (utterance.isBlank()) return false
        // Normalising drops polite words, so "thanks" or "thank you" on their own end up empty.
        val text = FastPathParser.normalize(utterance)
        return text.isEmpty() || text in PHRASES
    }
}
