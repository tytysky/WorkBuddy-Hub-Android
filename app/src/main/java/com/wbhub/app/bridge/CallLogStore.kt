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
    /**
     * Address the call came from, empty for this device.
     *
     * Recorded because the endpoint can serve the local network, and "which
     * peer ran this" is the first question when the account's usage does not
     * match what this device did.
     */
    val sourceIp: String = "",
) {
    enum class Outcome { OK, FAILED }

    val totalTokens: Int get() = promptTokens + completionTokens

    /** Whether the call arrived over the network rather than from this device. */
    val isRemote: Boolean get() = sourceIp.isNotEmpty()

    val timeText: String
        get() = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
}

/**
 * Running totals across every call this install has ever served.
 *
 * Kept apart from the record list on purpose: the list is capped because a view
 * cannot grow forever, while a total that lost its oldest entries would be
 * wrong rather than merely short. Clearing the list is therefore a display
 * action and leaves these alone.
 */
data class CallTotals(
    val calls: Int = 0,
    val failures: Int = 0,
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val credits: Double = 0.0,
) {
    val totalTokens: Long get() = promptTokens + completionTokens

    /** Folds one finished call into the running figures. */
    fun plus(record: CallRecord): CallTotals = copy(
        calls = if (record.outcome == CallRecord.Outcome.OK) calls + 1 else calls,
        failures = if (record.outcome == CallRecord.Outcome.FAILED) failures + 1 else failures,
        promptTokens = promptTokens + record.promptTokens,
        completionTokens = completionTokens + record.completionTokens,
        credits = credits + record.credits,
    )
}

/**
 * Append-only history of bridge calls, kept in a small JSON file.
 *
 * Bounded on purpose: this is a convenience log, and an unbounded one would grow
 * without limit on a device that runs for weeks. The oldest entries are dropped
 * once the cap is reached.
 */
class CallLogStore(private val context: Context) {

    private val file = File(context.filesDir, "wb-call-log.json")
    private val totalsFile = File(context.filesDir, "wb-call-totals.json")
    private val lock = Any()

    /** Reads the running totals; an absent file means nothing has been served. */
    fun totals(): CallTotals {
        if (!totalsFile.exists()) return CallTotals()
        return runCatching {
            val json = JSONObject(totalsFile.readText())
            CallTotals(
                calls = json.optInt("calls"),
                failures = json.optInt("failures"),
                promptTokens = json.optLong("prompt"),
                completionTokens = json.optLong("completion"),
                credits = json.optDouble("credits", 0.0),
            )
        }.getOrDefault(CallTotals())
    }

    /**
     * Discards the running totals.
     *
     * Separate from clearing the list: the list is a view, the totals are a
     * record, and a user tidying the view is not asking to lose the record.
     */
    fun clearTotals() {
        synchronized(lock) { runCatching { totalsFile.delete() } }
    }

    /**
     * Trims the file to the current limit.
     *
     * Called when the setting is lowered, so the effect is immediate rather
     * than deferred until the next call arrives.
     */
    fun trimToLimit() {
        synchronized(lock) {
            val limit = BridgeSettings.callLogLimit(context)
            val existing = load()
            if (existing.size <= limit) return
            write(existing.subList(existing.size - limit, existing.size))
        }
    }

    /**
     * Roughly how large the history would be at [count] records.
     *
     * Measured from what is already stored rather than assumed, so the figure
     * shown reflects this device's own records instead of an average.
     */
    fun estimatedBytes(count: Int): Long {
        val existing = load()
        val perRecord = if (existing.isEmpty()) {
            // Nothing to measure yet; a record is a few hundred bytes of JSON.
            DEFAULT_BYTES_PER_RECORD
        } else {
            file.length().coerceAtLeast(1L) / existing.size
        }
        return perRecord * count
    }

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
                    sourceIp = item.optString("sourceIp"),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun append(record: CallRecord) {
        synchronized(lock) {
            val existing = load().toMutableList()
            existing.add(record)
            // The cap is read per append so a change takes effect immediately.
            val limit = BridgeSettings.callLogLimit(context)
            val trimmed = if (existing.size > limit) {
                existing.subList(existing.size - limit, existing.size).toList()
            } else {
                existing
            }
            write(trimmed)
            // The totals are folded in here rather than derived from the file
            // afterwards, because the file only holds the newest entries and a
            // total recomputed from it would shrink as they age out.
            runCatching {
                val next = totals().plus(record)
                totalsFile.writeText(
                    JSONObject().apply {
                        put("calls", next.calls)
                        put("failures", next.failures)
                        put("prompt", next.promptTokens)
                        put("completion", next.completionTokens)
                        put("credits", next.credits)
                    }.toString(),
                )
            }
        }
    }

    /** Replaces the stored records. */
    private fun write(records: List<CallRecord>) {
        val array = JSONArray()
        records.forEach { entry ->
            array.put(
                JSONObject().apply {
                    put("ts", entry.timestamp)
                    put("model", entry.model)
                    put("outcome", entry.outcome.name)
                    put("prompt", entry.promptTokens)
                    put("completion", entry.completionTokens)
                    put("credits", entry.credits)
                    put("detail", entry.detail)
                    if (entry.sourceIp.isNotEmpty()) put("sourceIp", entry.sourceIp)
                },
            )
        }
        runCatching { file.writeText(array.toString()) }
    }

    /**
     * Empties the visible history.
     *
     * The running totals are left as they are: this clears a list the user is
     * looking at, not the record of what the endpoint has served.
     */
    fun clear() {
        synchronized(lock) { runCatching { file.delete() } }
    }

    private companion object {
        /**
         * Used before anything has been recorded.
         *
         * A record is a timestamp, a model name, a few counters and usually an
         * empty detail field; the JSON overhead dominates, which is why the
         * estimate is only ever a rough one.
         */
        const val DEFAULT_BYTES_PER_RECORD = 200L
    }
}
