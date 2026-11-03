package com.txw.ielts.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 连续打卡天数。
 *
 * 首页和统计页原本各写了一份：一份用 java.time.LocalDate，一份用 Calendar +
 * "年-一年中的第几天" 拼 key。两者语义相同但实现不同，容易在跨年/时区上漂移。
 * 这里只留一份，并把「今天」和时区做成参数，好在 JVM 上直接测。
 */
object StreakCalc {

    /** 把时间戳归一到所在时区的日期 key（yyyy-MM-dd）。 */
    fun dayKey(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().toString()

    /**
     * 从 today 往前数连续有记录的天数。
     * 今天还没练不算断签：从昨天开始算（否则每天零点打卡会归零）。
     */
    fun currentStreak(days: Set<String>, today: LocalDate): Int {
        var d = if (days.contains(today.toString())) today else today.minusDays(1)
        var n = 0
        while (days.contains(d.toString())) {
            n++
            d = d.minusDays(1)
        }
        return n
    }

    fun currentStreak(
        sessions: List<Session>,
        nowMs: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): Int {
        if (sessions.isEmpty()) return 0
        val days = sessions.mapTo(HashSet()) { dayKey(it.startedAt, zone) }
        return currentStreak(days, Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate())
    }
}
