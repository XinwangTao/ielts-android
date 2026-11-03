package com.txw.ielts

import com.txw.ielts.data.Session
import com.txw.ielts.data.StreakCalc
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class StreakCalcTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    /** 2026-11-03 12:00 +08:00 */
    private val now = 1793688000000L

    private fun session(daysAgo: Long, at: Int = 12): Session {
        val ms = LocalDate.now(zone).minusDays(daysAgo)
            .atTime(at, 0).atZone(zone).toInstant().toEpochMilli()
        return Session(module = "writing", topic = "t", startedAt = ms)
    }

    private fun nowMs(): Long =
        LocalDate.now(zone).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `无记录时连续天数为 0`() {
        assertEquals(0, StreakCalc.currentStreak(emptyList(), nowMs(), zone))
    }

    @Test
    fun `今天练过算 1 天`() {
        assertEquals(1, StreakCalc.currentStreak(listOf(session(0)), nowMs(), zone))
    }

    @Test
    fun `今天没练但昨天练了，仍算 1 天（不算断签）`() {
        assertEquals(1, StreakCalc.currentStreak(listOf(session(1)), nowMs(), zone))
    }

    @Test
    fun `连续三天到今天`() {
        assertEquals(3, StreakCalc.currentStreak(
            listOf(session(0), session(1), session(2)), nowMs(), zone
        ))
    }

    @Test
    fun `中断后的更早记录不计入`() {
        // 今天、昨天有；前天断；大前天也有 → 只算 2
        assertEquals(2, StreakCalc.currentStreak(
            listOf(session(0), session(1), session(3), session(4)), nowMs(), zone
        ))
    }

    @Test
    fun `同一天多次练习只算一天`() {
        assertEquals(1, StreakCalc.currentStreak(
            listOf(session(0, 8), session(0, 12), session(0, 22)), nowMs(), zone
        ))
    }

    @Test
    fun `今天和昨天都没练则归零`() {
        assertEquals(0, StreakCalc.currentStreak(listOf(session(2)), nowMs(), zone))
    }

    @Test
    fun `纯函数版：key 集合跨月连续`() {
        val days = setOf("2026-10-31", "2026-11-01", "2026-11-02", "2026-11-03")
        assertEquals(4, StreakCalc.currentStreak(days, LocalDate.of(2026, 11, 3)))
    }

    @Test
    fun `纯函数版：今天缺席从昨天起算`() {
        val days = setOf("2026-11-01", "2026-11-02")
        assertEquals(2, StreakCalc.currentStreak(days, LocalDate.of(2026, 11, 3)))
    }
}
