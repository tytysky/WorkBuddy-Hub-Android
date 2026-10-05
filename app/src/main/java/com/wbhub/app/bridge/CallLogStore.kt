package com.wbhub.app.bridge

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One completed chat call, as recorded for the history view.
 *
 * Token counts and cost come from the upstream's own usage block, so the credit
 * figure reflects what the account was actually charged rather than an estimate.
 */
data class CallRecord(
    val timestamp: Long,
    val model: String,
    val outcome: Outcome,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val credits: Double = 0.0,
    val detail: String = "",
) {
    enum class Outcome { OK, FAILED }

    val totalTokens: Int get() = promptTokens + completionTokens

    val timeText: String
        get() = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
}

/**
 * Append-only history of bridge calls, kept in a small JSON file.
 *
 * Bounded on purpose: this is a convenience log, and an unbounded one would grow
 * without limit on a device that runs for weeks. The oldest entries are dropped
 * once the cap is reached.
 */
class CallLogStore(context: Context) {

    private val file = File(context.filesDir, "wb-call-log.json")
    private val lock = Any()

    fun load(): List<CallRecord> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(file.readText())
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                CallRecord(
                    timestamp = item.optLong("ts"),
                    model = item.optString("model"),
                    outcome = runCatching {
                        CallRecord.Outcome.valueOf(item.optString("outcome"))
                    }.getOrDefault(CallRecord.Outcome.FAILED),
                    promptTokens = item.optInt("prompt"),
                    completionTokens = item.optInt("completion"),
                    credits = item.optDouble("credits", 0.0),
                    detail = item.optString("detail"),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun append(record: CallRecord) {
        synchronized(lock) {
            val existing = load().toMutableList()
            existing.add(record)
            // Keep the newest entries; the log is a convenience, not a ledger.
            val trimmed = if (existing.size > MAX_ENTRIES) {
                existing.subList(existing.size - MAX_ENTRIES, existing.size).toList()
            } else {
                existing
            }
            val array = JSONArray()
            trimmed.forEach { entry ->
                array.put(
                    JSONObject().apply {
                        put("ts", entry.timestamp)
                        put("model", entry.model)
                        put("outcome", entry.outcome.name)
                        put("prompt", entry.promptTokens)
                        put("completion", entry.completionTokens)
                        put("credits", entry.credits)
                        put("detail", entry.detail)
                    },
                )
            }
            runCatching { file.writeText(array.toString()) }
        }
    }

    fun clear() {
        synchronized(lock) { runCatching { file.delete() } }
    }

    private companion object {
        const val MAX_ENTRIES = 500
    }
}

/** Totals derived from the recorded calls, for the summary row. */
data class UsageSummary(
    val calls: Int,
    val failures: Int,
    val promptTokens: Int,
    val completionTokens: Int,
    val credits: Double,
) {
    val totalTokens: Int get() = promptTokens + completionTokens

    companion object {
        fun of(records: List<CallRecord>): UsageSummary = UsageSummary(
            calls = records.count { it.outcome == CallRecord.Outcome.OK },
            failures = records.count { it.outcome == CallRecord.Outcome.FAILED },
            promptTokens = records.sumOf { it.promptTokens },
            completionTokens = records.sumOf { it.completionTokens },
            credits = records.sumOf { it.credits },
        )
    }
}
