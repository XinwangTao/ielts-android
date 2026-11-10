package com.txw.ielts.data

/**
 * 首次「学过」的时间。
 *
 * 每日新卡额度必须按「第一次被评级」计数，不能用 lastReviewed：
 * 昨天学过的卡今天忘了再评，lastReviewed 会被改写，旧卡就被当成今天的新卡。
 * reps 从 0 变正的那一刻才写入，之后保持不变。
 */
object FirstLearned {
    fun next(existing: Long, repsAfter: Int, now: Long): Long =
        if (existing == 0L && repsAfter > 0) now else existing
}
