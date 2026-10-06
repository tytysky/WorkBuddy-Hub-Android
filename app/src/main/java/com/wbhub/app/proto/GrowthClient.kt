package com.wbhub.app.proto

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Growth-domain calls used by the daily bonus routines.
 *
 * The growth endpoints live on the chat base (`copilot.tencent.com`) and
 * authenticate with the billing-style headers, so they reuse the wire rules
 * rather than the chat path.
 *
 * Every call here is idempotent by construction: the upstream answers a
 * repeated action with a business code rather than an error, and callers treat
 * the resulting message as a normal outcome.
 */
class GrowthClient {

    /** Streak state: consecutive days, makeup cards, and the redemption tiers. */
    data class StreakState(
        val days: Int = 0,
        val nextTier: String = "",
        val nextTierRemaining: Int = 0,
        val makeupCards: Int = 0,
        val tiers: List<Tier> = emptyList(),
    ) {
        data class Tier(
            val id: String,
            val status: String,
            val days: Int,
            val credit: Int,
            val energy: Int,
            val cards: Int,
            val chances: Int,
        )

        /** Tiers that are unlocked but not yet redeemed. */
        val redeemable: List<Tier> get() = tiers.filter { it.status !in setOf("locked", "claimed") }
    }

    /** Outcome of one step, so the caller can report what actually happened. */
    data class Step(val ok: Boolean, val message: String)

    /**
     * Consecutive-login state. Throws only on transport or protocol failure;
     * a business-level refusal is not expected on this read.
     */
    fun streak(credential: Credential): StreakState {
        val data = growth(credential, "GET", "/activity/growth/streak", null)
        val streak = data.optJSONObject("streak") ?: JSONObject()
        val cards = data.optJSONObject("makeup_cards") ?: JSONObject()
        val redemption = data.optJSONObject("redemption_status") ?: JSONObject()
        val rawTiers = redemption.optJSONArray("tiers")

        // The status per tier arrives in three flat fields rather than inside the
        // tier rows, so each row is matched back to its status by id.
        val statusById = mapOf(
            "7d" to redemption.optString("tier_7d_status"),
            "14d" to redemption.optString("tier_14d_status"),
            "28d" to redemption.optString("tier_28d_status"),
        )
        val tiers = buildList {
            for (i in 0 until (rawTiers?.length() ?: 0)) {
                val row = rawTiers?.optJSONObject(i) ?: continue
                val id = row.optString("tier")
                add(
                    StreakState.Tier(
                        id = id,
                        status = statusById[id].orEmpty(),
                        days = row.optInt("days"),
                        credit = row.optInt("credit"),
                        energy = row.optInt("energy"),
                        cards = row.optInt("cards"),
                        chances = row.optInt("chances"),
                    )
                )
            }
        }
        return StreakState(
            days = streak.optInt("days"),
            nextTier = streak.optString("next_tier"),
            nextTierRemaining = streak.optInt("next_tier_remaining"),
            makeupCards = cards.optInt("balance"),
            tiers = tiers,
        )
    }

    /** Redeems one tier. An unreached tier is refused upstream; that is expected. */
    fun redeemTier(credential: Credential, tier: String): Step =
        step(credential, "POST", "/activity/growth/redeem", JSONObject().apply {
            put("tier", tier)
            put("client_token", clientToken())
        })

    /** Remaining lottery draws. Only redemptions hand these out. */
    fun lotteryChances(credential: Credential): Int {
        val data = growth(credential, "GET", "/activity/growth/lottery/summary", null)
        return data.optInt("chances")
    }

    /** Draws once. The prize payload varies by campaign and is only logged. */
    fun lotteryDraw(credential: Credential): Step =
        step(credential, "POST", "/activity/growth/lottery/draw", JSONObject().apply {
            put("client_token", clientToken())
        })

    /**
     * Whether yesterday was left unchecked. The heatmap reports one cell per
     * day with a zero score for a missed day.
     */
    fun missedYesterday(credential: Credential): Boolean {
        val data = growth(credential, "GET", "/activity/growth/heatmap", null)
        val cells = data.optJSONArray("cells") ?: return false
        val yesterday = dayString(offsetDays = -1)
        for (i in 0 until cells.length()) {
            val cell = cells.optJSONObject(i) ?: continue
            if (cell.optString("date").take(10) == yesterday) return cell.optInt("score") == 0
        }
        return false
    }

    /** Spends one makeup card on a past date, keeping the streak alive. */
    fun useMakeupCard(credential: Credential, date: String): Step =
        step(credential, "POST", "/activity/growth/makeup-cards/use", JSONObject().apply {
            put("target_date", date)
        })

    /** New-user gift. One per account; already claimed is reported as a message. */
    fun claimGift(credential: Credential): Step =
        step(credential, "POST", "/billing/meter/claim-gift", JSONObject(), billingBase = true)

    /** Campaign compensation, when one is running. */
    fun claimCompensation(credential: Credential): Step =
        step(credential, "POST", "/billing/meter/claim-compensation", JSONObject(), billingBase = true)

    // ------------------------------------------------------------------ //
    // Growth tasks
    // ------------------------------------------------------------------ //

    /** One row of the growth task list. */
    data class Task(
        val code: String,
        val current: Int = 0,
        val target: Int = 0,
        val acceptStatus: String = "",
    ) {
        val claimed: Boolean get() = acceptStatus == "claimed"

        /** Work still owed before the task pays out. */
        val remaining: Int get() = if (target <= 0) 0 else (target - current).coerceAtLeast(0)
    }

    /**
     * Reads the task list.
     *
     * The rows arrive with two shapes for progress: either a nested
     * `progress.current/target` pair or flat `current`/`target` fields, and
     * both are accepted because the shape varies per task type.
     */
    fun tasks(credential: Credential): List<Task> {
        val data = growth(credential, "GET", "/v2/activity/growth/tasks", null)
        val rows = data.optJSONArray("tasks") ?: return emptyList()
        val result = mutableListOf<Task>()
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val progress = row.optJSONObject("progress")
            result += Task(
                code = row.optString("task_code"),
                current = progress?.optInt("current") ?: row.optInt("current"),
                target = progress?.optInt("target") ?: row.optInt("target"),
                acceptStatus = row.optString("accept_status"),
            )
        }
        return result
    }

    /** Signs up for a task. Progress only starts counting after this. */
    fun acceptTasks(credential: Credential, codes: List<String>): Step {
        val body = JSONObject().put("task_codes", org.json.JSONArray(codes))
        return step(credential, "POST", "/v2/activity/growth/tasks/accept", body)
    }

    /** Finds one task row, or null when the list does not carry it. */
    fun task(credential: Credential, code: String): Task? =
        tasks(credential).firstOrNull { it.code == code }

    // ------------------------------------------------------------------ //
    // Cat travel
    // ------------------------------------------------------------------ //

    /** Where the adoptable cat currently stands. */
    data class TravelState(
        val state: String = "",
        val dailyLimitReached: Boolean = false,
        val recordId: Long = 0,
        val rewardCredit: Int = 0,
        val arriveAt: Long = 0,
    ) {
        val traveling: Boolean get() = state == "traveling"
        val arrived: Boolean get() = state == "arrived"

        /** Minutes until the cat lands, floored at zero. */
        fun minutesUntilArrival(nowSeconds: Long): Long =
            if (arriveAt <= 0) 0 else ((arriveAt - nowSeconds) / 60).coerceAtLeast(0)
    }

    /**
     * Advances the cat's travel by one step: adopt when there is no cat, then
     * send it out, then collect on arrival.
     *
     * The upstream resets the outbound allowance at midnight CST, so a second
     * call on the same day reports that the daily run is already used rather
     * than sending the cat out again.
     */
    fun travelOnce(credential: Credential): List<Step> {
        val result = mutableListOf<Step>()
        val status = runCatching { travelStatus(credential) }.getOrElse {
            result += Step(false, "旅行状态读取失败：${it.message ?: "未知错误"}")
            return result
        }

        // An empty status means there is no cat yet; adopting it also pays out
        // the 300-point first-Buddy reward, which is gated on today's activity
        // having been reported.
        if (status.state.isEmpty()) {
            adopt(credential, result)
        }

        if (status.arrived && status.recordId != 0L) {
            val claimed = claimTravel(credential, status.recordId)
            result += if (claimed.ok) {
                if (claimed.credit > 0) {
                    Step(true, "猫猫旅行奖励已领取：+${claimed.credit} 积分")
                } else {
                    Step(true, "猫猫旅行奖励已领取")
                }
            } else {
                Step(false, "领取旅行奖励失败：${claimed.message}")
            }
            return result
        }

        if (status.traveling) {
            // Say when to come back rather than just "later": the trip lasts an
            // hour, and a bare prompt leaves the user guessing.
            val now = System.currentTimeMillis() / 1000
            val clock = SimpleDateFormat("HH:mm", Locale.US).format(Date(status.arriveAt * 1000))
            val minutes = status.minutesUntilArrival(now)
            val eta = when {
                status.arriveAt <= 0 -> ""
                minutes <= 0 -> "（即将到站，可再次点击领取）"
                else -> "，约 $minutes 分钟后"
            }
            result += Step(
                false,
                "猫猫还在路上，$clock 到站$eta；到站后再点一次「猫猫旅行」即可领取" +
                    (if (status.rewardCredit > 0) "（预计 +${status.rewardCredit} 积分）" else ""),
            )
            return result
        }

        if (status.dailyLimitReached) {
            result += Step(false, "今日已派出过猫猫")
            return result
        }

        val departed = depart(credential, LOCATION_ID)
        result += if (departed.ok) {
            // Re-read so the reply can state the return time as well.
            val after = runCatching { travelStatus(credential) }.getOrNull()
            val clock = after?.takeIf { it.arriveAt > 0 }
                ?.let { SimpleDateFormat("HH:mm", Locale.US).format(Date(it.arriveAt * 1000)) }
            val minutes = after?.minutesUntilArrival(System.currentTimeMillis() / 1000) ?: 0
            val eta = when {
                clock == null -> ""
                minutes <= 0 -> "，即将到站"
                else -> "，约 $minutes 分钟后（$clock）到站"
            }
            Step(true, "猫猫已出发去旅行$eta；到站后再点一次本按钮即可领取奖励")
        } else {
            Step(false, "派出失败：${departed.message}")
        }
        return result
    }

    private fun travelStatus(credential: Credential): TravelState {
        val data = growth(credential, "GET", "/activity/growth/buddy/travel/status", null)
        return TravelState(
            state = data.optString("state"),
            dailyLimitReached = data.optBoolean("daily_limit_reached"),
            recordId = data.optLong("record_id"),
            rewardCredit = data.optInt("reward_credit"),
            arriveAt = data.optLong("arrive_at"),
        )
    }

    private fun depart(credential: Credential, locationId: Int): Step =
        step(credential, "POST", "/activity/growth/buddy/travel/depart", JSONObject().apply {
            put("location_id", locationId)
        })

    private fun claimTravel(credential: Credential, recordId: Long): ClaimResult {
        val text = runCatching {
            send(
                "${Wire.chatBase(credential.region)}/activity/growth/buddy/travel/claim",
                "POST",
                Wire.growthHeaders(
                    region = credential.region,
                    accessToken = credential.accessToken,
                    uid = credential.uid,
                    domain = credential.domain,
                    enterpriseId = credential.enterpriseId,
                ),
                JSONObject().put("record_id", recordId).toString(),
            )
        }.getOrElse { return ClaimResult(false, 0, it.message ?: "请求失败") }
        val envelope = runCatching { JSONObject(text) }.getOrNull()
            ?: return ClaimResult(false, 0, text.take(120))
        if (envelope.optInt("code") != 0) {
            return ClaimResult(false, 0, envelope.optString("msg").ifEmpty { "未知错误" })
        }
        val data = envelope.optJSONObject("data") ?: JSONObject()
        return ClaimResult(true, data.optInt("reward_credit"), "")
    }

    private data class ClaimResult(val ok: Boolean, val credit: Int, val message: String)

    /**
     * Adopts the first cat. The upstream refuses while today's activity report
     * has not landed, so a refusal is reported rather than retried here.
     */
    private fun adopt(credential: Credential, result: MutableList<Step>) {
        val agreement = step(credential, "POST", "/activity/growth/buddy/agreement", JSONObject().apply {
            put("agree", true)
        })
        if (!agreement.ok) {
            result += Step(false, "Buddy 协议签署失败：${agreement.message}")
            return
        }
        val first = step(credential, "POST", "/activity/growth/buddy/first", JSONObject())
        result += if (first.ok) {
            Step(true, "已领养第一只 Buddy")
        } else {
            Step(false, "领养 Buddy 未生效：${first.message}")
        }
    }

    // ------------------------------------------------------------------ //
    // Night owl
    // ------------------------------------------------------------------ //

    /** Whether the night-owl counting window (23:00-08:00 local) is open. */
    fun inNightWindow(): Boolean {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return hour >= 23 || hour < 8
    }

    /**
     * Reports the model used by the night-owl task.
     *
     * The task counts glm-5.2 conversations inside the 23:00-08:00 window, so
     * the event carries that model id rather than the default one.
     */
    fun reportNightActivity(credential: Credential, index: Int): Step {
        val stamp = System.currentTimeMillis()
        val conversationId = "wbhub-night-$stamp-$index"
        val outcome = reportChatActivity(
            credential = credential,
            conversationId = conversationId,
            requestId = conversationId,
            modelId = NIGHT_MODEL_ID,
            modelName = NIGHT_MODEL_NAME,
        )
        return Step(outcome.ok, if (outcome.ok) "夜猫子对话已上报" else "夜猫子上报失败：${outcome.message}")
    }

    private companion object {
        /** All four destinations pay the same, so there is no best pick. */
        const val LOCATION_ID = 4
        const val NIGHT_MODEL_ID = "glm-5.2"
        const val NIGHT_MODEL_NAME = "GLM-5.2"
        const val TAG = "WBHub"
    }

    /**
     * Reports one `chat_request_send` activity event.
     *
     * The full client shape is sent rather than a minimal three-field body: the
     * upstream accepts short bodies with a 200 and then drops them silently, and
     * the field list is also how a specific model gets credited. `userId` is
     * mandatory for the event to count.
     */
    fun reportChatActivity(
        credential: Credential,
        conversationId: String,
        requestId: String = "",
        modelId: String = "deepseek-v4-flash",
        modelName: String = "",
    ): Step {
        val now = System.currentTimeMillis()
        val event = JSONObject().apply {
            put("eventCode", "chat_request_send")
            put("timestamp", now)
            put("reportDelay", 0)
            put("mode", "craft")
            put("conversationId", conversationId)
            put("requestId", requestId.ifEmpty { conversationId })
            put("inputLength", 12)
            put("requestModelId", modelId)
            put("requestModelName", modelName.ifEmpty { modelId })
            put("isPlan", false)
            put("isAutoExecuteTerminal", false)
            put("isAutoModify", false)
            put("codebaseEnable", false)
            put("maxToken", 0)
            put("maxSteps", 0)
            put("temperature", 0)
            put("maxRetries", 0)
            put("mentionContexts", emptyList<String>())
            put("knowledgeId", emptyList<String>())
            put("knowledgeName", emptyList<String>())
            put("codebaseId", "")
            put("mentionContextCount", 0)
            put("command", "")
            put("expertId", "")
            put("recommendId", "")
            put("skillId", "")
            put("skillCount", 0)
            put("totalCount", 0)
            put("fileUri", "")
            put("presentAt", now)
            put("traceId", "")
            put("rootRequestId", requestId.ifEmpty { conversationId })
            put("parentConversationId", conversationId)
            put("agentName", "default")
            put("agentType", "conversation")
            put("userId", credential.uid)
        }
        // The upstream expects a bare array, not an object wrapper: sending
        // {"events":[...]} is accepted with a 200 and then rejected as
        // "eventCode is empty or not string", so the envelope is skipped.
        val body = JSONArray().put(event).toString()
        return stepRaw(credential, "${Wire.billingBase(credential.region)}/v2/report", body)
    }

    // ------------------------------------------------------------------ //
    // Transport
    // ------------------------------------------------------------------ //

    /**
     * Sends a growth request and unwraps the envelope.
     *
     * Growth answers arrive as `{code, msg, data}`; a non-zero code carries the
     * business reason in prose, which callers surface rather than treat as a
     * hard failure.
     */
    private fun growth(credential: Credential, method: String, path: String, body: JSONObject?): JSONObject {
        val text = send(
            "${Wire.chatBase(credential.region)}$path",
            method,
            Wire.growthHeaders(
                region = credential.region,
                accessToken = credential.accessToken,
                uid = credential.uid,
                domain = credential.domain,
                enterpriseId = credential.enterpriseId,
            ),
            body?.toString(),
        )
        val envelope = runCatching { JSONObject(text) }.getOrElse {
            throw IllegalStateException("growth returned non-JSON: ${text.take(160)}")
        }
        val code = envelope.optInt("code")
        if (code != 0) throw GrowthRefusal(code, envelope.optString("msg").ifEmpty { text.take(160) })
        return envelope.optJSONObject("data") ?: JSONObject()
    }

    /** Runs a growth call whose business refusal is an ordinary outcome. */
    private fun step(credential: Credential, method: String, path: String, body: JSONObject?, billingBase: Boolean = false): Step {
        val url = if (billingBase) {
            "${Wire.billingBase(credential.region)}$path"
        } else {
            "${Wire.chatBase(credential.region)}$path"
        }
        val headers = if (billingBase) {
            Wire.billingHeaders(
                region = credential.region,
                accessToken = credential.accessToken,
                uid = credential.uid,
                domain = credential.domain,
                enterpriseId = credential.enterpriseId,
            )
        } else {
            Wire.growthHeaders(
                region = credential.region,
                accessToken = credential.accessToken,
                uid = credential.uid,
                domain = credential.domain,
                enterpriseId = credential.enterpriseId,
            )
        }
        return stepRaw(credential, url, body?.toString(), headers)
    }

    private fun stepRaw(
        credential: Credential,
        url: String,
        body: String?,
        headers: Map<String, String> = Wire.growthHeaders(
            region = credential.region,
            accessToken = credential.accessToken,
            uid = credential.uid,
            domain = credential.domain,
            enterpriseId = credential.enterpriseId,
        ),
    ): Step {
        return try {
            val text = send(url, "POST", headers, body)
            val envelope = runCatching { JSONObject(text) }.getOrNull()
                ?: return Step(true, text.take(160))
            val code = envelope.optInt("code")
            val message = envelope.optString("msg").ifEmpty { "OK" }
            Step(code == 0, message)
        } catch (e: GrowthRefusal) {
            Step(false, e.message.orEmpty())
        } catch (e: Exception) {
            Log.e(TAG, "growth call failed: $url", e)
            Step(false, e.message ?: "请求失败")
        }
    }

    private fun send(url: String, method: String, headers: Map<String, String>, body: String?): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = Wire.JSON_TIMEOUT_MS
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            if (body != null) {
                doOutput = true
                outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            }
        }
        return try {
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
        } finally {
            conn.disconnect()
        }
    }

    /** Idempotency token shaped like the one the web front end generates. */
    private fun clientToken(): String = UUID.randomUUID().toString()

    private fun dayString(offsetDays: Int): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(System.currentTimeMillis() + offsetDays * 86_400_000L))

    /** A business-level refusal, kept distinct so callers can soften it. */
    class GrowthRefusal(val code: Int, message: String) : Exception(message)
}
