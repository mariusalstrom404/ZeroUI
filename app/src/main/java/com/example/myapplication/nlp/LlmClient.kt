package com.example.myapplication.nlp

import android.content.res.AssetManager
import com.example.myapplication.automation.ScreenLlm
import com.example.myapplication.automation.ScreenTurn
import com.example.myapplication.debug.Timing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** OpenAI couldn't give a usable result: offline, timed out, not configured or invalid output. */
class LlmUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * One command from the LLM. [questions] holds its wording for details the user left out;
 * [bookingQuestions] does the same for a hotel booking, which [BookingConversation] collects.
 */
data class LlmCommand(
    val intent: AppIntent,
    val questions: Map<FollowUpSlot, String> = emptyMap(),
    val bookingQuestions: Map<BookingConversation.Slot, String> = emptyMap()
)

/** A follow-up answer the local rules couldn't understand, as read by the LLM. */
data class LlmAnswer(
    val status: Status,
    val value: String? = null,
    val checkIn: StayDate? = null,
    val checkOut: StayDate? = null,
    val adults: Int? = null,
    val children: Int? = null,
    val childAges: List<Int> = emptyList(),
    val rooms: Int? = null,
    val room: String? = null
) {
    enum class Status { FILLED, CONFIRM, CANCEL, NO_PREFERENCE, UNCLEAR }
}

interface LlmClient {
    @Throws(LlmUnavailableException::class)
    suspend fun parseCommand(utterance: String, today: StayDate, foregroundApp: String? = null): List<LlmCommand>

    /** [slot] names the detail being collected, or "CONFIRMATION" for a yes/no question. */
    @Throws(LlmUnavailableException::class)
    suspend fun understandAnswer(slot: String, question: String, answer: String, today: StayDate): LlmAnswer
}

/**
 * Calls OpenAI directly with the key in [LlmSecrets]. The prompts and schemas are in `assets/llm`,
 * where the test portal in `tools/test-portal` reads the same copies.
 *
 * Fast paths that don't change what the model answers:
 * - Each prompt's instructions are the same for every call; what changes (today's date, the
 *   detail being collected, the screen) goes at the end of the input. OpenAI can then reuse the
 *   processed start of the prompt, and `prompt_cache_key` sends calls of a kind to the same cache.
 * - Responses are read to the end and the connection is left open, so HttpURLConnection's
 *   process-wide pool reuses it. [warmUp] opens it while the user is still speaking.
 * - Requests ask for [REASONING_EFFORT]. A model that doesn't take that setting answers 400; the
 *   request is then sent again without it, and later requests leave it out.
 */
class OpenAiLlmClient(private val assets: AssetManager) : LlmClient, ScreenLlm {

    override suspend fun nextScreenAction(goal: String, history: List<String>, screen: String): ScreenTurn {
        val input = LlmJson.screenInput(StayDate.today(), goal, history, screen)
        val text = post("screen_action", "agent_prompt.txt", "agent_schema.json", input, "step ${history.size + 1}", preferredEffort = AGENT_REASONING_EFFORT)
        return LlmJson.screenTurn(text)
    }

    override suspend fun parseCommand(utterance: String, today: StayDate, foregroundApp: String?): List<LlmCommand> {
        val input = LlmJson.commandInput(today, utterance, foregroundApp)
        return LlmJson.commands(post("voice_commands", "command_prompt.txt", "command_schema.json", input, preferredEffort = FAST_REASONING_EFFORT), today)
    }

    override suspend fun understandAnswer(slot: String, question: String, answer: String, today: StayDate): LlmAnswer {
        val input = LlmJson.answerInput(today, slot, question, answer)
        return LlmJson.answer(post("follow_up_answer", "answer_prompt.txt", "answer_schema.json", input, slot, preferredEffort = FAST_REASONING_EFFORT), today)
    }

    /**
     * Opens the connection to OpenAI ahead of the first request, so its setup isn't waited for
     * after the user finishes speaking. Throttled by [WARMUP_COOLDOWN_MS] so an active pooled
     * connection isn't occupied by a redundant GET right before a real POST.
     */
    suspend fun warmUp() = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext
        val now = Timing.now()
        if (now - lastNetworkActivityAt < WARMUP_COOLDOWN_MS) return@withContext
        if (!warmingUp.compareAndSet(false, true)) return@withContext
        try {
            val connection = open("$API_URL/models/${LlmSecrets.OPENAI_MODEL}", "GET")
            val status = connection.responseCode
            readFully(connection, status)
            lastNetworkActivityAt = Timing.now()
            Timing.log("OpenAI warm-up: ${lastNetworkActivityAt - now} ms (status $status)")
        } catch (e: IOException) {
            Timing.log("OpenAI warm-up failed after ${Timing.now() - now} ms: ${e.message}")
        } finally {
            warmingUp.set(false)
        }
    }

    private suspend fun post(
        name: String,
        promptFile: String,
        schemaFile: String,
        input: String,
        detail: String? = null,
        preferredEffort: String = FAST_REASONING_EFFORT
    ): String =
        withContext(Dispatchers.IO) {
            if (!isConfigured()) throw LlmUnavailableException("LlmSecrets.kt is not filled in")
            val label = if (detail == null) name else "$name ($detail)"
            var effort: String? = resolveEffort(preferredEffort)
            var (status, reply) = send(label, name, promptFile, schemaFile, input, effort)
            // If the model rejects "minimal" (or reasoning altogether), step down once and remember it.
            while (status == HttpURLConnection.HTTP_BAD_REQUEST && effort != null && reply.contains("reasoning")) {
                val next = nextLowerEffort(effort)
                rejectedEfforts.add(effort)
                Timing.log("OpenAI $label: effort '$effort' rejected, retrying with ${next ?: "none"}")
                effort = next
                val retry = send(label, name, promptFile, schemaFile, input, effort)
                status = retry.first
                reply = retry.second
            }
            checked(label, status, reply)
        }

    private fun resolveEffort(preferred: String): String? {
        var candidate: String? = preferred
        while (candidate != null && candidate in rejectedEfforts) {
            candidate = nextLowerEffort(candidate)
        }
        return candidate
    }

    private fun nextLowerEffort(effort: String): String? = when (effort) {
        "minimal" -> "low"
        else -> null
    }

    private fun checked(label: String, status: Int, reply: String): String {
        if (status != HttpURLConnection.HTTP_OK) {
            Timing.log("OpenAI $label returned $status: ${reply.take(300)}")
            throw LlmUnavailableException("OpenAI returned $status")
        }
        return LlmJson.outputText(reply)
    }

    /** One request; returns the HTTP status and the reply body. */
    private fun send(
        label: String,
        name: String,
        promptFile: String,
        schemaFile: String,
        input: String,
        effort: String?
    ): Pair<Int, String> {
        val start = Timing.now()
        try {
            val body = LlmJson.request(LlmSecrets.OPENAI_MODEL, name, asset(schemaFile), asset(promptFile), input, effort)
                .toByteArray()
            val connection = open("$API_URL/responses", "POST").apply {
                doOutput = true
                // Sent as it's written; otherwise the whole body waits in a buffer until the response is read.
                setFixedLengthStreamingMode(body.size)
                setRequestProperty("Content-Type", "application/json")
            }
            connection.outputStream.use { it.write(body) }
            val sent = Timing.now()
            val status = connection.responseCode
            val answered = Timing.now()
            val reply = readFully(connection, status)
            val done = Timing.now()
            lastNetworkActivityAt = done
            // A long "connect and send" means a new connection was set up; a reused one takes a few ms.
            Timing.log(
                "OpenAI $label: ${done - start} ms (connect and send ${sent - start}, " +
                    "model ${answered - sent}, download ${done - answered}), ${LlmJson.usage(reply)}"
            )
            return status to reply
        } catch (e: IOException) {
            Timing.log("OpenAI $label failed after ${Timing.now() - start} ms: ${e.message}")
            throw LlmUnavailableException("Couldn't reach OpenAI", e)
        }
    }

    private fun open(url: String, method: String) =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Authorization", "Bearer ${LlmSecrets.OPENAI_API_KEY}")
        }

    /**
     * Reads the whole reply, error or not, and closes the stream. That hands the connection back
     * to the pool for the next request; disconnect() would close it instead.
     */
    private fun readFully(connection: HttpURLConnection, status: Int): String {
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        return stream?.bufferedReader()?.use { it.readText() } ?: ""
    }

    private fun asset(file: String): String =
        assetCache.getOrPut(file) { assets.open("llm/$file").bufferedReader().use { it.readText() } }

    companion object {
        private const val API_URL = "https://api.openai.com/v1"
        private const val CONNECT_TIMEOUT_MS = 5000
        private const val READ_TIMEOUT_MS = 10000
        private const val WARMUP_COOLDOWN_MS = 25_000L

        /** Short classification & follow-up parsing prefer minimal reasoning for lower latency. */
        private const val FAST_REASONING_EFFORT = "minimal"
        /** Multi-step screen navigation keeps low reasoning for better decision accuracy. */
        private const val AGENT_REASONING_EFFORT = "low"

        private val rejectedEfforts = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        private val warmingUp = java.util.concurrent.atomic.AtomicBoolean(false)
        @Volatile private var lastNetworkActivityAt = 0L

        /** Prompts and schemas never change while the app runs. */
        private val assetCache = java.util.concurrent.ConcurrentHashMap<String, String>()

        /** True when both API key and model in [LlmSecrets] are filled with non-placeholder values. */
        fun isConfigured(): Boolean =
            LlmSecrets.OPENAI_API_KEY.isNotBlank() &&
                LlmSecrets.OPENAI_MODEL.isNotBlank() &&
                !LlmSecrets.OPENAI_API_KEY.startsWith("PASTE_") &&
                !LlmSecrets.OPENAI_MODEL.startsWith("PASTE_")
    }
}
