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
}
