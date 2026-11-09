package com.example.myapplication.ui

import com.example.myapplication.Message
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** One past conversation. [id] is when it started. */
data class ChatSession(val id: Long, val messages: List<Message>) {
    /** The first thing the user said, which is what people remember a chat by. */
    val preview: String
        get() = messages.firstOrNull { it.isUser }?.text ?: messages.firstOrNull()?.text ?: ""
    val userMessageCount: Int
        get() = messages.count { it.isUser }
}

/** When a conversation is worth keeping and when the screen should start a new one. */
object ChatSessions {
    /** Reopening ZeroUI after this long without a message starts a new conversation on screen. */
    const val IDLE_MS = 10 * 60 * 1000L
    const val MAX_SESSIONS = 50
    const val MAX_MESSAGES_PER_SESSION = 300

    /** Only conversations where the user said something; a lone greeting isn't history. */
    fun worthKeeping(messages: List<Message>): Boolean = messages.any { it.isUser }

    fun isIdle(lastMessageTime: Long?, now: Long): Boolean =
        lastMessageTime != null && now - lastMessageTime >= IDLE_MS
}

/** Plain-text versions of conversations, for copying. */
object ChatTranscript {

    /**
     * "You: …" / "ZeroUI: …", one message per line, with each message's time. The greeting before
     * the user's first message is left out.
     */
    fun format(
        messages: List<Message>,
        locale: Locale = Locale.getDefault(),
        timeZone: TimeZone = TimeZone.getDefault()
    ): String {
        val time = SimpleDateFormat("HH:mm", locale).apply { this.timeZone = timeZone }
        return messages.dropWhile { !it.isUser }.joinToString("\n") { m ->
            "[${time.format(Date(m.time))}] ${if (m.isUser) "You" else "ZeroUI"}: ${m.text}"
        }
    }

    /** A heading line with the date, then [format]. */
    fun formatSession(
        session: ChatSession,
        locale: Locale = Locale.getDefault(),
        timeZone: TimeZone = TimeZone.getDefault()
    ): String {
        val date = SimpleDateFormat("d MMM yyyy", locale).apply { this.timeZone = timeZone }
        return "ZeroUI conversation, ${date.format(Date(session.id))}\n\n" + format(session.messages, locale, timeZone)
    }

    /** "Today, 16:42", "Yesterday, 09:10" or "3 Nov 2026, 16:42". */
    fun sessionTitle(
        time: Long,
        now: Long,
        locale: Locale = Locale.getDefault(),
        timeZone: TimeZone = TimeZone.getDefault()
    ): String {
        val clock = SimpleDateFormat("HH:mm", locale).apply { this.timeZone = timeZone }.format(Date(time))
        return when (daysBetween(time, now, timeZone)) {
            0 -> "Today, $clock"
            1 -> "Yesterday, $clock"
            else -> SimpleDateFormat("d MMM yyyy", locale).apply { this.timeZone = timeZone }.format(Date(time)) + ", $clock"
        }
    }

    private fun daysBetween(earlier: Long, later: Long, timeZone: TimeZone): Int {
        fun dayStart(t: Long) = Calendar.getInstance(timeZone).apply {
            timeInMillis = t
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        // Rounded, so a daylight-saving day of 23 or 25 hours still counts as one.
        return Math.round((dayStart(later) - dayStart(earlier)) / 86_400_000.0).toInt()
    }
}

/** Sessions to and from JSON. Unreadable entries are skipped rather than losing the rest. */
object ChatHistoryCodec {

    fun encode(sessions: List<ChatSession>): String {
        val array = JSONArray()
        for (session in sessions) {
            val messages = JSONArray()
            session.messages.forEach { messages.put(encode(it)) }
            array.put(JSONObject().put("id", session.id).put("messages", messages))
        }
        return JSONObject().put("version", 1).put("sessions", array).toString()
    }

    fun decode(json: String): List<ChatSession> {
        val sessions = try {
            JSONObject(json).getJSONArray("sessions")
        } catch (e: Exception) {
            return emptyList()
        }
        return (0 until sessions.length()).mapNotNull { i ->
            try {
                val o = sessions.getJSONObject(i)
                ChatSession(o.getLong("id"), messages(o.getJSONArray("messages"), o.getLong("id")))
            } catch (e: Exception) {
                null
            }
        }
    }

    /** The chat saved by older versions: one list of messages, without times. */
    fun decodeLegacy(json: String, savedAt: Long): ChatSession? = try {
        ChatSession(savedAt, messages(JSONArray(json), savedAt)).takeIf { it.messages.isNotEmpty() }
    } catch (e: Exception) {
        null
    }

    private fun encode(m: Message): JSONObject {
        val o = JSONObject().put("text", m.text).put("isUser", m.isUser).put("time", m.time)
        if (m.choices.isNotEmpty()) o.put("choices", JSONArray(m.choices))
        return o
    }

    private fun messages(array: JSONArray, defaultTime: Long): List<Message> =
        (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val choices = o.optJSONArray("choices")?.let { c -> List(c.length()) { c.getString(it) } } ?: emptyList()
            Message(o.optString("text"), o.optBoolean("isUser"), choices, o.optLong("time", defaultTime))
        }
}

/** Keeps sessions in one file, written whole to a temporary file and swapped in, so a crash can't half-write it. */
class ChatHistoryStore(private val file: File) {

    fun load(): List<ChatSession> =
        if (file.exists()) ChatHistoryCodec.decode(file.readText()) else emptyList()

    fun save(sessions: List<ChatSession>) {
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(ChatHistoryCodec.encode(sessions))
        if (!temp.renameTo(file)) {
            file.delete()
            temp.renameTo(file)
        }
    }
}
