package com.txw.ielts.data

import kotlin.math.max
import kotlin.math.roundToInt

/** 四档自评，对应 SM-2 的 q=2/3/4/5。 */
enum class Grade { AGAIN, HARD, GOOD, EASY }

data class Schedule(
    val ease: Double,
    val intervalDays: Int,
    val reps: Int,
    val lapses: Int,
    val dueAt: Long,
)

object Sm2 {
    private const val DAY = 86_400_000L
    private const val MAX_INTERVAL = 180

    fun next(card: Card, grade: Grade, now: Long = System.currentTimeMillis()): Schedule {
        val q = when (grade) { Grade.AGAIN -> 2; Grade.HARD -> 3; Grade.GOOD -> 4; Grade.EASY -> 5 }
        var ease = card.ease
        var reps = card.reps
        var interval = card.intervalDays
        var lapses = card.lapses

        if (q < 3) {
            reps = 0
            interval = 1
            lapses += 1
            ease = max(1.3, ease - 0.2)
        } else {
            reps += 1
            interval = when (reps) {
                1 -> 1
                2 -> 3
                else -> max(1, (interval * ease).roundToInt())
            }
            if (q == 3) interval = max(1, (interval * 0.8).roundToInt())
            if (q == 5) interval = (interval * 1.3).roundToInt()
            ease = (ease + (0.1 - (5 - q) * (0.08 + (5 - q) * 0.02))).coerceIn(1.3, 2.8)
        }
        interval = interval.coerceAtMost(MAX_INTERVAL)
        return Schedule(
            ease = ease,
            intervalDays = interval,
            reps = reps,
            lapses = lapses,
            dueAt = now + interval * DAY,
        )
    }

    /**
     * 评级按钮上的「下次什么时候再见」小字。
     *
     * 四档按钮原来只有「认识/模糊/太简单」，用户点之前不知道代价：
     * 同一张卡点「认识」和点「太简单」到底差几天，只能点完才发现。
     * 这里直接把 [next] 算出的间隔说在前面。
     *
     * Again 不是「明天再见」这么简单：本轮队列还会隔几张再问一次
     * （[ReviewQueue.RELEARN_GAP]），所以按钮上写「本轮再问」，
     * 调度落到明天这件事由结果文案和队列共同保证，不必在按钮上再挤一行。
     */
    fun previewLabel(card: Card, grade: Grade, now: Long = System.currentTimeMillis()): String {
        if (grade == Grade.AGAIN) return "本轮再问"
        val days = next(card, grade, now).intervalDays
        return if (days <= 1) "明天" else "$days 天后"
    }
}
