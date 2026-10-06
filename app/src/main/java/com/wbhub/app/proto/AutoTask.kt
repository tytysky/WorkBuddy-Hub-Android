package com.wbhub.app.proto

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The daily bonus routine that runs after a check-in.
 *
 * Ported from the reference gateway's streak manager. The order matters:
 * a makeup card is spent first so the streak stays unbroken, then one-off
 * rewards are collected, then every unlocked tier is redeemed — redemption is
 * what hands out lottery draws, so the draws come last.
 *
 * Every step is idempotent upstream, so this can be re-run on every check-in
 * without duplicating rewards.
 */
class AutoTaskRunner(
    private val growth: GrowthClient = GrowthClient(),
) {

    /** One line of the routine's report, in the order the steps ran. */
    data class Step(val label: String, val ok: Boolean, val message: String)

    data class Result(
        val steps: List<Step> = emptyList(),
        val creditEarned: Int = 0,
    ) {
        val summary: String
            get() = if (steps.isEmpty()) "无可执行项" else steps.joinToString("\n") { it.message }
    }

    /**
     * Runs the whole routine for one account.
     *
     * Nothing here throws: a failing step is recorded and the routine moves on,
     * because a single unavailable step (a campaign that is not running, for
     * instance) must not stop the rest of the day's rewards.
     */
    fun runStreakBonus(credential: Credential): Result {
        // The streak and its tiers exist only on the CN build; the international
        // account has no growth programme to drive.
        if (credential.region != Wire.Region.CN) return Result()

        val steps = mutableListOf<Step>()
        var earned = 0

        // 1. Keep the streak alive before anything reads it: a gap costs seven
        //    days to rebuild, so this outranks the rewards below.
        makeupYesterday(credential, steps)

        // 2. One-off collections. Both report "already claimed" as a normal
        //    business code once they have been taken.
        runCatching { growth.claimGift(credential) }.getOrNull()?.let { step ->
            if (step.ok) steps += Step("gift", true, "新手礼包已领取")
        }
        runCatching { growth.claimCompensation(credential) }.getOrNull()?.let { step ->
            if (step.ok) steps += Step("compensation", true, "补偿已领取")
        }

        // 3. Redeem every unlocked tier.
        val streak = runCatching { growth.streak(credential) }.getOrElse {
            Log.e(TAG, "streak read failed", it)
            steps += Step("streak", false, "连登状态读取失败：${it.message ?: "未知错误"}")
            return Result(steps, earned)
        }
        for (tier in streak.redeemable) {
            val outcome = growth.redeemTier(credential, tier.id)
            if (outcome.ok) {
                earned += tier.credit
                steps += Step(
                    "redeem-${tier.id}",
                    true,
                    "兑换 ${tier.id} 档：+${tier.credit} 积分" +
                        (if (tier.chances > 0) "、抽奖 ×${tier.chances}" else ""),
                )
            } else {
                // A tier the upstream refuses is reported once; there is nothing
                // to retry within the same run.
                steps += Step("redeem-${tier.id}", false, "兑换 ${tier.id} 档未生效：${outcome.message}")
            }
        }

        // 4. Spend every draw. The count is read after redemption because that is
        //    what grants them.
        val chances = runCatching { growth.lotteryChances(credential) }.getOrDefault(0)
        if (chances > 0) {
            var drawn = 0
            for (i in 1..chances) {
                val outcome = growth.lotteryDraw(credential)
                if (!outcome.ok) {
                    steps += Step("lottery", false, "第 $i 次抽奖失败：${outcome.message}")
                    break
                }
                drawn++
            }
            if (drawn > 0) steps += Step("lottery", true, "抽奖完成 $drawn 次")
        }

        return Result(steps, earned)
    }

    /**
     * Reports today's activity once.
     *
     * A single report per day is enough: it lights the streak counter and
     * unlocks the first-Buddy task, and sending it repeatedly would only look
     * like scripted traffic.
     */
    fun reportActivity(credential: Credential, modelId: String = "deepseek-v4-flash"): Step {
        val conversationId = "wbhub-${System.currentTimeMillis()}"
        val outcome = growth.reportChatActivity(
            credential = credential,
            conversationId = conversationId,
            modelId = modelId,
        )
        return Step("activity", outcome.ok, if (outcome.ok) "活跃已上报" else "活跃上报失败：${outcome.message}")
    }

    /** Advances the cat's travel by one step. */
    fun runTravel(credential: Credential): Result {
        if (credential.region != Wire.Region.CN) return Result()
        val steps: List<Step> = runCatching { growth.travelOnce(credential) }.getOrElse { error ->
            listOf(GrowthClient.Step(false, "旅行失败：${error.message ?: "未知错误"}"))
        }.map { Step("travel", it.ok, it.message) }
        return Result(steps = steps)
    }

    /**
     * Reports the night-owl conversations.
     *
     * The window is 23:00-08:00 local time; outside it the upstream does not
     * count the events, so the run is refused rather than silently wasted. The
     * task must also be signed up for before its progress starts moving, and
     * only the missing count is reported so a repeat run cannot overshoot.
     */
    fun runNightOwl(credential: Credential): Result {
        if (credential.region != Wire.Region.CN) return Result()
        if (!growth.inNightWindow()) {
            return Result(listOf(Step("night", false, "不在夜猫子时段（23:00–08:00），此时上报不计分")))
        }

        val steps = mutableListOf<Step>()
        val task = runCatching { growth.task(credential, NIGHT_TASK_CODE) }.getOrElse {
            return Result(listOf(Step("night", false, "任务进度读取失败：${it.message ?: "未知错误"}")))
        }
        if (task == null) return Result(listOf(Step("night", false, "未找到夜猫子任务")))
        if (task.claimed || task.remaining <= 0) {
            return Result(listOf(Step("night", true, "夜猫子进度已达标")))
        }

        // Signing up is what makes the reports count; it is idempotent.
        if (task.acceptStatus == "not_accepted") {
            val accepted = growth.acceptTasks(credential, listOf(NIGHT_TASK_CODE))
            if (!accepted.ok) {
                return Result(listOf(Step("night", false, "报名失败：${accepted.message}")))
            }
            steps += Step("night", true, "已报名夜猫子任务")
        }

        var done = 0
        for (i in 1..task.remaining) {
            val outcome = growth.reportNightActivity(credential, i)
            if (!outcome.ok) {
                steps += Step("night", false, "第 $i 次上报失败：${outcome.message}")
                break
            }
            done++
            // Spacing keeps the batch from looking like scripted traffic.
            if (i < task.remaining) Thread.sleep(NIGHT_GAP_MS)
        }
        if (done > 0) steps += Step("night", true, "夜猫子已上报 $done 次（还差 ${task.remaining - done} 次）")
        return Result(steps = steps)
    }

    /** Spends a makeup card on yesterday when it was missed. */
    private fun makeupYesterday(credential: Credential, steps: MutableList<Step>) {
        val missed = runCatching { growth.missedYesterday(credential) }.getOrDefault(false)
        if (!missed) return
        val streak = runCatching { growth.streak(credential) }.getOrNull() ?: return
        if (streak.makeupCards <= 0) return
        val yesterday = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            .format(Date(System.currentTimeMillis() - 86_400_000L))
        val outcome = growth.useMakeupCard(credential, yesterday)
        steps += if (outcome.ok) {
            Step("makeup", true, "已用补签卡补签 $yesterday（保住连登）")
        } else {
            Step("makeup", false, "补签 $yesterday 失败：${outcome.message}")
        }
    }

    private companion object {
        const val NIGHT_TASK_CODE = "black_cat"
        const val NIGHT_GAP_MS = 4_000L
        const val TAG = "WBHub"
    }
}
