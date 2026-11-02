package com.example.myapplication.nlp

import android.content.res.AssetManager
import com.example.myapplication.automation.ScreenAction
import com.example.myapplication.automation.ScreenLlm
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
 */
class OpenAiLlmClient(private val assets: AssetManager) : LlmClient, ScreenLlm {

    override suspend fun nextScreenAction(goal: String, history: List<String>, screen: String): ScreenAction {
        val instructions = asset("agent_prompt.txt").replace("{today}", LlmJson.isoDate(StayDate.today()))
        val steps = if (history.isEmpty()) "Nothing yet." else history.joinToString("\n")
        val input = "Goal: $goal\n\nSo far:\n$steps\n\nCurrent screen:\n$screen"
        return LlmJson.screenAction(post("screen_action", "agent_schema.json", instructions, input))
    }

    override suspend fun parseCommand(utterance: String, today: StayDate): List<LlmCommand> {
        val instructions = asset("command_prompt.txt").replace("{today}", LlmJson.isoDate(today))
        return LlmJson.commands(post("voice_commands", "command_schema.json", instructions, utterance), today)
    }

    override suspend fun understandAnswer(slot: String, question: String, answer: String, today: StayDate): LlmAnswer {
        val instructions = asset("answer_prompt.txt")
            .replace("{today}", LlmJson.isoDate(today))
            .replace("{slot}", slot)
        val input = "Question: $question\nReply: $answer"
        return LlmJson.answer(post("follow_up_answer", "answer_schema.json", instructions, input), today)
    }

    private suspend fun post(name: String, schemaFile: String, instructions: String, input: String): String =
        withContext(Dispatchers.IO) {
            if (LlmSecrets.OPENAI_API_KEY.startsWith("PASTE_") || LlmSecrets.OPENAI_MODEL.startsWith("PASTE_")) {
                throw LlmUnavailableException("LlmSecrets.kt is not filled in")
            }
            var connection: HttpURLConnection? = null
            try {
                val body = LlmJson.request(LlmSecrets.OPENAI_MODEL, name, asset(schemaFile), instructions, input)
                connection = (URL(RESPONSES_URL).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    doOutput = true
                    setRequestProperty("Authorization", "Bearer ${LlmSecrets.OPENAI_API_KEY}")
                    setRequestProperty("Content-Type", "application/json")
                }
                connection.outputStream.use { it.write(body.toByteArray()) }
                val status = connection.responseCode
                if (status != HttpURLConnection.HTTP_OK) throw LlmUnavailableException("OpenAI returned $status")
                LlmJson.outputText(connection.inputStream.bufferedReader().use { it.readText() })
            } catch (e: IOException) {
                throw LlmUnavailableException("Couldn't reach OpenAI", e)
            } finally {
                connection?.disconnect()
            }
        }

    private fun asset(file: String): String =
        assets.open("llm/$file").bufferedReader().use { it.readText() }

    private companion object {
        const val RESPONSES_URL = "https://api.openai.com/v1/responses"
        const val CONNECT_TIMEOUT_MS = 5000
        const val READ_TIMEOUT_MS = 10000
    }
}
