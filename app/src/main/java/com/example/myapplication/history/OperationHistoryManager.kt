package com.example.myapplication.history

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class OperationRecord(
    val id: String,
    val timestamp: Long,
    val utterance: String,
    val description: String,
    val status: String
)

object OperationHistoryManager {

    private const val PREF_NAME = "operation_history_prefs"
    private const val KEY_HISTORY = "history_records"
    private const val MAX_RECORDS = 50

    fun addRecord(context: Context, utterance: String, description: String, status: String): OperationRecord {
        val records = getRecords(context).toMutableList()
        val newRecord = OperationRecord(
            id = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            utterance = utterance,
            description = description,
            status = status
        )
        records.add(0, newRecord)
        if (records.size > MAX_RECORDS) {
            records.subList(MAX_RECORDS, records.size).clear()
        }
        saveRecords(context, records)
        return newRecord
    }

    private fun saveRecords(context: Context, records: List<OperationRecord>) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val array = JSONArray()
        records.forEach {
            val obj = JSONObject()
            obj.put("id", it.id)
            obj.put("timestamp", it.timestamp)
            obj.put("utterance", it.utterance)
            obj.put("description", it.description)
            obj.put("status", it.status)
            array.put(obj)
        }
        prefs.edit().putString(KEY_HISTORY, array.toString()).apply()
    }

    fun getRecords(context: Context): List<OperationRecord> {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
        val list = mutableListOf<OperationRecord>()
        try {
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    OperationRecord(
                        id = obj.optString("id", UUID.randomUUID().toString()),
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                        utterance = obj.optString("utterance", ""),
                        description = obj.optString("description", ""),
                        status = obj.optString("status", "SUCCESS")
                    )
                )
            }
        } catch (e: Exception) {
            // Ignore parse errors
        }
        return list
    }

    fun clearHistory(context: Context) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_HISTORY).apply()
    }

    fun formatHistorySummary(context: Context, limit: Int = 5): String {
        val records = getRecords(context).take(limit)
        if (records.isEmpty()) {
            return "No operation history recorded yet."
        }
        val sb = StringBuilder("Here is your recent operation history:\n\n")
        records.forEachIndexed { index, rec ->
            val statusSymbol = when (rec.status.uppercase()) {
                "SUCCESS" -> "✓"
                "FAILED" -> "✗"
                "CANCELLED" -> "⊘"
                else -> "•"
            }
            sb.append("${index + 1}. [$statusSymbol] ${rec.description}")
            if (rec.utterance.isNotBlank()) {
                sb.append(" (\"${rec.utterance}\")")
            }
            sb.append("\n")
        }
        return sb.toString().trimEnd()
    }
}
