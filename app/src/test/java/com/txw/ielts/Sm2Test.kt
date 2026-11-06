package com.txw.ielts

import com.txw.ielts.data.Card
import com.txw.ielts.data.Grade
import com.txw.ielts.data.Sm2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SM-2 调度回归。评分按钮直接写 dueAt，调度一旦漂移，复习节奏就全乱：
 * 答错的卡不该隔一个月再来，答对的卡也不该天天出现。
 */
class Sm2Test {

    private val now = 1_700_000_000_000L
    private val day = 86_400_000L

    private fun startOfDayPlus(ms: Long, days: Int): Long {
        val zone = java.time.ZoneId.systemDefault()
        return java.time.Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
            .plusDays(days.toLong())
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()
    }

    private fun card(
        ease: Double = 2.5,
        interval: Int = 0,
        reps: Int = 0,
        lapses: Int = 0,
    ) = Card(id = 1, ease = ease, intervalDays = interval, reps = reps, lapses = lapses)

    @Test
    fun `新卡答错 重置次数并排到明天`() {
        val s = Sm2.next(card(), Grade.AGAIN, now)
        assertEquals(0, s.reps)
        assertEquals(1, s.intervalDays)
        assertEquals(1, s.lapses)
        assertEquals(2.3, s.ease, 0.0001)
        assertEquals(startOfDayPlus(now, 1), s.dueAt)
    }

    @Test
    fun `新卡答对 明天再见且 ease 不变`() {
        val s = Sm2.next(card(), Grade.GOOD, now)
        assertEquals(1, s.reps)
        assertEquals(1, s.intervalDays)
        assertEquals(2.5, s.ease, 0.0001)
        assertEquals(startOfDayPlus(now, 1), s.dueAt)
    }

    @Test
    fun `到期时间落在本地日界线`() {
        // 23:50 评的卡，次日 08:00 打开 App 就应该在队列里，而不是等到 23:50。
        val late = 1_700_006_000_000L
        val s = Sm2.next(card(), Grade.GOOD, late)
        val zone = java.time.ZoneId.systemDefault()
        val due = java.time.Instant.ofEpochMilli(s.dueAt).atZone(zone).toLocalDate()
        val expected = java.time.Instant.ofEpochMilli(late).atZone(zone).toLocalDate().plusDays(1)
        assertEquals(expected, due)
        assertEquals(0, java.time.LocalTime.ofInstant(java.time.Instant.ofEpochMilli(s.dueAt), zone).hour)
    }

    @Test
    fun `新卡困难 间隔一天但 ease 下调`() {
        val s = Sm2.next(card(), Grade.HARD, now)
        assertEquals(1, s.reps)
        assertEquals(1, s.intervalDays)
        assertEquals(2.36, s.ease, 0.0001)
    }

    @Test
    fun `新卡简单 首轮仍是一天但 ease 上调`() {
        val s = Sm2.next(card(), Grade.EASY, now)
        assertEquals(1, s.reps)
        assertEquals(1, s.intervalDays)
        assertEquals(2.6, s.ease, 0.0001)
    }

    @Test
    fun `第二次答对间隔三天`() {
        val s = Sm2.next(card(reps = 1, interval = 1), Grade.GOOD, now)
        assertEquals(2, s.reps)
        assertEquals(3, s.intervalDays)
    }

    @Test
    fun `第三次起按 ease 增长并四舍五入`() {
        val s = Sm2.next(card(reps = 2, interval = 3), Grade.GOOD, now)
        assertEquals(3, s.reps)
        assertEquals(8, s.intervalDays)  // 3 * 2.5 = 7.5 -> 8
    }

    @Test
    fun `困难按 0_8 折减 简单按 1_3 放大`() {
        assertEquals(2, Sm2.next(card(reps = 1, interval = 3), Grade.HARD, now).intervalDays)  // 2.4 -> 2
        assertEquals(4, Sm2.next(card(reps = 1, interval = 3), Grade.EASY, now).intervalDays)  // 3.9 -> 4
    }

    @Test
    fun `ease 下限 1_3 上限 2_8`() {
        val low = Sm2.next(card(ease = 1.3), Grade.AGAIN, now)
        assertEquals(1.3, low.ease, 0.0001)
        val high = Sm2.next(card(ease = 2.8, reps = 2, interval = 10), Grade.EASY, now)
        assertEquals(2.8, high.ease, 0.0001)
    }

    @Test
    fun `间隔上限 180 天`() {
        val s = Sm2.next(card(ease = 2.5, reps = 5, interval = 170), Grade.GOOD, now)
        assertEquals(180, s.intervalDays)
    }

    @Test
    fun `答错会保留历史 lapse 并让 ease 逐步下降`() {
        val s = Sm2.next(card(ease = 2.0, lapses = 2), Grade.AGAIN, now)
        assertEquals(3, s.lapses)
        assertEquals(1.8, s.ease, 0.0001)
        assertTrue(s.dueAt > now)
    }

    // ---- 评级按钮上的「下次什么时候再见」：说的必须就是点下去会发生的事 ----

    @Test
    fun `新卡三档答对按钮都写明天`() {
        // 首轮 SM-2 无论难易都是 1 天，按钮如实显示「明天」，不许为了好看编一个更大的数。
        assertEquals("明天", Sm2.previewLabel(card(), Grade.HARD, now))
        assertEquals("明天", Sm2.previewLabel(card(), Grade.GOOD, now))
        assertEquals("明天", Sm2.previewLabel(card(), Grade.EASY, now))
    }

    @Test
    fun `答错按钮写本轮再问而不是明天`() {
        assertEquals("本轮再问", Sm2.previewLabel(card(), Grade.AGAIN, now))
    }

    @Test
    fun `按钮文案与 next 的间隔一致`() {
        val mature = card(reps = 2, interval = 3)
        listOf(Grade.HARD, Grade.GOOD, Grade.EASY).forEach { grade ->
            val days = Sm2.next(mature, grade, now).intervalDays
            assertEquals("$days 天后", Sm2.previewLabel(mature, grade, now))
        }
    }

    @Test
    fun `已学卡按各自间隔显示天数`() {
        val mature = card(reps = 2, interval = 3)
        // 第三次起按 ease 增长：3*2.5=8，困难再打 0.8 折 → 6，简单再放大 1.3 → 10。
        assertEquals("6 天后", Sm2.previewLabel(mature, Grade.HARD, now))
        assertEquals("8 天后", Sm2.previewLabel(mature, Grade.GOOD, now))
        assertEquals("10 天后", Sm2.previewLabel(mature, Grade.EASY, now))
    }

    @Test
    fun `间隔上限同样体现在按钮上`() {
        val long = card(ease = 2.5, reps = 5, interval = 170)
        assertEquals("180 天后", Sm2.previewLabel(long, Grade.GOOD, now))
    }

    @Test
    fun `预览只读卡上的调度状态 不写回`() {
        val c = card(reps = 1, interval = 1, ease = 2.5)
        Sm2.previewLabel(c, Grade.EASY, now)
        assertEquals(2.5, c.ease, 0.0001)
        assertEquals(1, c.intervalDays)
        assertEquals(1, c.reps)
    }
}
