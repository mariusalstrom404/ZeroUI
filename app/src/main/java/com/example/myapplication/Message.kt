package com.example.myapplication

/** Visual & semantic category of an assistant message so important states stand out clearly. */
enum class MessageKind {
    /** Standard conversational reply or user message. */
    NORMAL,
    /** Intermediate step update (e.g., "Searching Booking.com…"), shown compactly. */
    PROGRESS,
    /** High-risk confirmation prompt (transfer, checkout, consequential tap). */
    CONFIRM,
    /** Safety handover (payment screen or private input left for the user). */
    HANDOVER,
    /** Error or unable-to-complete notice. */
    ERROR
}

/**
 * One chat message. [choices] are answers shown as buttons under the latest assistant message;
 * [time] is when it was added, in milliseconds since the epoch.
 */
data class Message(
    val text: String,
    val isUser: Boolean,
    val choices: List<String> = emptyList(),
    val time: Long = System.currentTimeMillis(),
    val kind: MessageKind = MessageKind.NORMAL
) {
    /** Alias for [time] used by UI components. */
    val timestamp: Long get() = time
}
