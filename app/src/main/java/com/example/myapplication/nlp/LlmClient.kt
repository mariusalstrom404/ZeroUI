package com.example.myapplication.nlp

import android.content.res.AssetManager
import com.example.myapplication.automation.ScreenAction
import com.example.myapplication.automation.ScreenLlm
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
    suspend fun parseCommand(utterance: String, today: StayDate): List<LlmCommand>

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
 */
class OpenAiLlmClient(private val assets: AssetManager) : LlmClient, ScreenLlm {

    override suspend fun nextScreenAction(goal: String, history: List<String>, screen: String): ScreenAction {
        val input = LlmJson.screenInput(StayDate.today(), goal, history, screen)
        val text = post("screen_action", "agent_prompt.txt", "agent_schema.json", input, "step ${history.size + 1}")
        return LlmJson.screenAction(text)
    }

    override suspend fun parseCommand(utterance: String, today: StayDate): List<LlmCommand> {
        val input = LlmJson.commandInput(today, utterance)
        return LlmJson.commands(post("voice_commands", "command_prompt.txt", "command_schema.json", input), today)
    }

    override suspend fun understandAnswer(slot: String, question: String, answer: String, today: StayDate): LlmAnswer {
        val input = LlmJson.answerInput(today, slot, question, answer)
        return LlmJson.answer(post("follow_up_answer", "answer_prompt.txt", "answer_schema.json", input, slot), today)
    }

    /**
     * Opens the connection to OpenAI ahead of the first request, so its setup isn't waited for
     * after the user finishes speaking. A small, free request; failures are ignored.
     */
    suspend fun warmUp() = withContext(Dispatchers.IO) {
        if (!configured()) return@withContext
        val start = Timing.now()
        try {
            val connection = open("$API_URL/models/${LlmSecrets.OPENAI_MODEL}", "GET")
            val status = connection.responseCode
            readFully(connection, status)
            Timing.log("OpenAI warm-up: ${Timing.now() - start} ms (status $status)")
        } catch (e: IOException) {
            Timing.log("OpenAI warm-up failed after ${Timing.now() - start} ms: ${e.message}")
        }
    }

    private suspend fun post(name: String, promptFile: String, schemaFile: String, input: String, detail: String? = null): String =
        withContext(Dispatchers.IO) {
            if (!configured()) throw LlmUnavailableException("LlmSecrets.kt is not filled in")
            val label = if (detail == null) name else "$name ($detail)"
            val start = Timing.now()
            try {
                val body = LlmJson.request(LlmSecrets.OPENAI_MODEL, name, asset(schemaFile), asset(promptFile), input)
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
                // A long "connect and send" means a new connection was set up; a reused one takes a few ms.
                Timing.log(
                    "OpenAI $label: ${done - start} ms (connect and send ${sent - start}, " +
                        "model ${answered - sent}, download ${done - answered}), ${LlmJson.usage(reply)}"
                )
                if (status != HttpURLConnection.HTTP_OK) {
                    Timing.log("OpenAI $label returned $status: ${reply.take(300)}")
                    throw LlmUnavailableException("OpenAI returned $status")
                }
                LlmJson.outputText(reply)
            } catch (e: IOException) {
                Timing.log("OpenAI $label failed after ${Timing.now() - start} ms: ${e.message}")
                throw LlmUnavailableException("Couldn't reach OpenAI", e)
            }
        }

    private fun configured() =
        !LlmSecrets.OPENAI_API_KEY.startsWith("PASTE_") && !LlmSecrets.OPENAI_MODEL.startsWith("PASTE_")

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

    private companion object {
        const val API_URL = "https://api.openai.com/v1"
        const val CONNECT_TIMEOUT_MS = 5000
        const val READ_TIMEOUT_MS = 10000

        /** Prompts and schemas never change while the app runs. */
        val assetCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    }
}
