package com.txw.ielts

import com.txw.ielts.data.ExamTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** 考试日期与目标分：日期算错会显示「距考试 -3 天」，吸附算错会让目标分变成 6.49。 */
class ExamTargetTest {

    /** 取某个本地日期的中午，避开夏令时/时区边界导致的差一天。 */
    private fun noonOf(iso: String): Long =
        LocalDate.parse(iso).atTime(LocalTime.NOON).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test
    fun `daysUntil 正常日期`() {
        val now = noonOf("2026-03-01")
        assertEquals(14, ExamTarget.daysUntil("2026-03-15", now))
        assertEquals(1, ExamTarget.daysUntil("2026-03-02", now))
    }

    @Test
    fun `daysUntil 当天为 0`() {
        assertEquals(0, ExamTarget.daysUntil("2026-03-01", noonOf("2026-03-01")))
    }

    @Test
    fun `daysUntil 已过为负数`() {
        assertEquals(-3, ExamTarget.daysUntil("2026-02-26", noonOf("2026-03-01")))
    }

    @Test
    fun `daysUntil 非法输入返回 null`() {
        val now = noonOf("2026-03-01")
        assertNull(ExamTarget.daysUntil("", now))
        assertNull(ExamTarget.daysUntil("2026-13-01", now))
        assertNull(ExamTarget.daysUntil("2026/03/01", now))
        assertNull(ExamTarget.daysUntil("下周", now))
    }

    @Test
    fun `daysUntil 允许首尾空格`() {
        assertEquals(14, ExamTarget.daysUntil("  2026-03-15  ", noonOf("2026-03-01")))
    }

    @Test
    fun `reviewInterval 未设日期或已考完保持原间隔`() {
        assertEquals(2, ExamTarget.reviewInterval(2, null))
        assertEquals(3, ExamTarget.reviewInterval(3, null))
        assertEquals(3, ExamTarget.reviewInterval(3, -1))
    }

    @Test
    fun `reviewInterval 考前七天内每天都练`() {
        assertEquals(1, ExamTarget.reviewInterval(3, 7))
        assertEquals(1, ExamTarget.reviewInterval(3, 1))
        assertEquals(1, ExamTarget.reviewInterval(3, 0))
    }

    @Test
    fun `reviewInterval 考前两周内间隔减半且不小于一天`() {
        assertEquals(1, ExamTarget.reviewInterval(2, 14))
        assertEquals(1, ExamTarget.reviewInterval(3, 8))
        assertEquals(2, ExamTarget.reviewInterval(4, 10))
    }

    @Test
    fun `reviewInterval 两周以外保持原间隔`() {
        assertEquals(2, ExamTarget.reviewInterval(2, 15))
        assertEquals(3, ExamTarget.reviewInterval(3, 90))
    }

    @Test
    fun `snapBand 吸附到 0_5 步长`() {
        assertEquals(6.5f, ExamTarget.snapBand(6.5f))
        assertEquals(6.0f, ExamTarget.snapBand(6.2f))
        assertEquals(6.5f, ExamTarget.snapBand(6.7f))
        assertEquals(7.0f, ExamTarget.snapBand(7.0f))
    }

    @Test
    fun `snapBand 限制范围并处理非法值`() {
        assertEquals(9.0f, ExamTarget.snapBand(12f))
        assertEquals(4.0f, ExamTarget.snapBand(3f))
        assertEquals(6.5f, ExamTarget.snapBand(null))
        assertEquals(6.5f, ExamTarget.snapBand(Float.NaN))
        assertEquals(7.5f, ExamTarget.snapBand(null, fallback = 7.5f))
    }

    @Test
    fun `overallBand 四科齐平直接等于该分`() {
        assertEquals(6.5, ExamTarget.overallBand(6.5, 6.5, 6.5, 6.5)!!, 1e-9)
        assertEquals(7.0, ExamTarget.overallBand(7.0, 7.0, 7.0, 7.0)!!, 1e-9)
    }

    @Test
    fun `overallBand 落在半档正好时向上进`() {
        // 6.125 → 6.5? 不是：6.125 离 6.0 更近，官方给 6.0。向上取整会白送半档。
        assertEquals(6.0, ExamTarget.overallBand(6.0, 6.0, 6.0, 6.5)!!, 1e-9)
        // 6.375（四科 6.5/6.5/6.5/6.0 与 6.0/6.5/6.5/6.5 都是 6.375）→ 6.5
        assertEquals(6.5, ExamTarget.overallBand(6.5, 6.5, 6.5, 6.0)!!, 1e-9)
        // 6.25 正好卡在中间（6+6+6+7=25），官方向上 → 6.5
        assertEquals(6.5, ExamTarget.overallBand(6.0, 6.0, 6.0, 7.0)!!, 1e-9)
        // 6.75 → 7.0
        assertEquals(7.0, ExamTarget.overallBand(6.5, 7.0, 7.0, 6.5)!!, 1e-9)
    }

    @Test
    fun `overallBand 缺科返回 null`() {
        assertNull(ExamTarget.overallBand(6.5, null, 6.5, 6.5))
        assertNull(ExamTarget.overallBand(null, null, null, null))
        assertNull(ExamTarget.overallBand(6.5, 6.5, 6.5, Double.NaN))
    }
}
