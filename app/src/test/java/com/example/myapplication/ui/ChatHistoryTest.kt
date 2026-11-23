package com.example.myapplication.ui

import com.example.myapplication.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Locale
import java.util.TimeZone

class ChatHistoryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val utc = TimeZone.getTimeZone("UTC")
    private val locale = Locale.UK

    /** 2026-11-09 16:42 UTC. */
    private val t = 1_794_242_520_000L

    private val session = ChatSession(
        t,
        listOf(
            Message("Hi! I'm ZeroUI.", false, listOf("Check my battery"), t),
            Message("turn off bluetooth", true, time = t + 60_000),
            Message("Bluetooth is off.", false, time = t + 63_000)
        )
    )

    @Test
    fun transcriptNamesSpeakersWithTimesAndSkipsTheGreeting() {
        assertEquals(
            "[16:43] You: turn off bluetooth\n[16:43] ZeroUI: Bluetooth is off.",
            ChatTranscript.format(session.messages, locale, utc)
        )
    }

    @Test
    fun sessionTranscriptHasDateHeading() {
        assertTrue(ChatTranscript.formatSession(session, locale, utc).startsWith("ZeroUI conversation, 9 Nov 2026\n\n[16:43] You:"))
    }

    @Test
    fun titlesSayTodayYesterdayOrDate() {
        assertEquals("Today, 16:42", ChatTranscript.sessionTitle(t, t + 3_600_000, locale, utc))
        assertEquals("Yesterday, 16:42", ChatTranscript.sessionTitle(t, t + 86_400_000, locale, utc))
        assertEquals("9 Nov 2026, 16:42", ChatTranscript.sessionTitle(t, t + 5 * 86_400_000L, locale, utc))
    }

    @Test
    fun previewIsFirstThingTheUserSaid() {
        assertEquals("turn off bluetooth", session.preview)
        assertEquals(1, session.userMessageCount)
    }

    @Test
    fun onlyConversationsWithTheUserAreKept() {
        assertFalse(ChatSessions.worthKeeping(listOf(Message("Hi!", false))))
        assertTrue(ChatSessions.worthKeeping(session.messages))
    }

    @Test
    fun idleAfterTenMinutes() {
        assertFalse(ChatSessions.isIdle(t, t + ChatSessions.IDLE_MS - 1))
        assertTrue(ChatSessions.isIdle(t, t + ChatSessions.IDLE_MS))
        assertFalse(ChatSessions.isIdle(null, t))
    }

    @Test
    fun codecRoundTrip() {
        val other = ChatSession(t - 1000, listOf(Message("go home", true, time = t - 1000)))
        assertEquals(listOf(session, other), ChatHistoryCodec.decode(ChatHistoryCodec.encode(listOf(session, other))))
    }

    @Test
    fun brokenFileGivesNoSessions() {
        assertEquals(emptyList<ChatSession>(), ChatHistoryCodec.decode("not json"))
    }

    @Test
    fun legacyChatBecomesOneSession() {
        val legacy = """[{"text":"open settings","isUser":true},{"text":"Opening Settings.","isUser":false,"choices":["OK"]}]"""
        val migrated = ChatHistoryCodec.decodeLegacy(legacy, t)!!
        assertEquals(t, migrated.id)
        assertEquals(listOf("open settings", "Opening Settings."), migrated.messages.map { it.text })
        assertEquals(listOf("OK"), migrated.messages[1].choices)
        assertTrue(migrated.messages.all { it.time == t })
        assertNull(ChatHistoryCodec.decodeLegacy("[]", t))
    }

    @Test
    fun storeSavesAndLoads() {
        val store = ChatHistoryStore(File(folder.root, "chat_history.json"))
        assertEquals(emptyList<ChatSession>(), store.load())
        store.save(listOf(session))
        store.save(listOf(session, session.copy(id = t + 1)))
        assertEquals(2, store.load().size)
    }
}
