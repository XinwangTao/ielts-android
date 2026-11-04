package com.txw.ielts

import com.txw.ielts.llm.ScoreGate
import com.txw.ielts.ui.bandValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 评分准入门禁。回归的是 2026-10-31 OPPO 联调留下的脏数据：
 * overall=0 / 1 的假成绩被当成 done，把首页均分从 7.5 拉成 2.8。
 */
class ScoreGateTest {

    @Test
    fun `0 分不算成绩`() {
        assertFalse(ScoreGate.isValidBand(0.0))
    }

    @Test
    fun `0_5 是有效成绩的下限`() {
        assertTrue(ScoreGate.isValidBand(0.5))
    }

    @Test
    fun `常见分数都有效`() {
        listOf(1.0, 5.5, 6.5, 7.5, 8.0, 9.0).forEach {
            assertTrue("$it 应有效", ScoreGate.isValidBand(it))
        }
    }

    @Test
    fun `超出官方满分范围无效`() {
        assertFalse(ScoreGate.isValidBand(9.5))
        assertFalse(ScoreGate.isValidBand(12.0))
    }

    @Test
    fun `非法浮点无效`() {
        assertFalse(ScoreGate.isValidBand(Double.NaN))
        assertFalse(ScoreGate.isValidBand(-1.0))
    }

    @Test
    fun `无效成绩退回可重试而不是 done`() {
        assertEquals("pending_eval", ScoreGate.statusFor(0.0))
        assertEquals("pending_eval", ScoreGate.statusFor(Double.NaN))
    }

    @Test
    fun `有效成绩照常标 done`() {
        assertEquals("done", ScoreGate.statusFor(7.5))
        assertEquals("done", ScoreGate.statusFor(0.5))
    }

    @Test
    fun `空文本不送模型`() {
        assertFalse(ScoreGate.shouldScore(""))
        assertFalse(ScoreGate.shouldScore("   \n\t "))
        assertTrue(ScoreGate.shouldScore("Some people believe that…"))
    }

    // ---- bandValue：脏成绩不能进入首页均分 ----

    @Test
    fun `overall 为 0 的记录不参与均分`() {
        assertNull(bandValue("""{"TR":0,"CC":0,"LR":0,"GRA":0,"overall":0}"""))
    }

    @Test
    fun `overall 为 1 的记录仍然算成绩`() {
        // Band 1 是官方有效档位（真实弱成绩），不能因为难看就吞掉
        assertEquals(1.0, bandValue("""{"TR":1,"CC":1,"LR":1,"GRA":1,"overall":1}""")!!, 0.0001)
    }

    @Test
    fun `真实评分照常返回`() {
        assertEquals(7.5, bandValue("""{"TR":8,"CC":7,"LR":8,"GRA":8,"overall":7.5}""")!!, 0.0001)
    }

    @Test
    fun `没有 overall 字段的会话不计入均分`() {
        // shadow / 题库练习只记 accuracy / correct，不是 Band
        assertNull(bandValue("""{"accuracy":0.82}"""))
        assertNull(bandValue("""{"correct":7,"total":10}"""))
        assertNull(bandValue(""))
        assertNull(bandValue("not json"))
    }

    @Test
    fun `脏数据与真实成绩混合后均分只按真实成绩算`() {
        val rows = listOf(
            """{"overall":0}""",
            """{"overall":7.5}""",
        )
        val bands = rows.mapNotNull { bandValue(it) }
        assertEquals(1, bands.size)
        assertEquals(7.5, bands.average(), 0.0001)
    }
}
