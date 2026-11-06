package com.txw.ielts

import com.txw.ielts.data.Card
import com.txw.ielts.data.Session
import com.txw.ielts.data.StatusLabel
import com.txw.ielts.data.WrongTrendCalc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 错题本趋势：只读已有数据（练习 scoresJson + 错题卡字段），
 * 窗口边界、跟读百分制折算、反复错统计都必须在 JVM 上可验证。
 */
class WrongTrendTest {

    private val now = 1_700_000_000_000L
    private val day = 86_400_000L

    private fun session(
        module: String,
        at: Long,
        scores: String,
        status: String = StatusLabel.DONE,
    ) = Session(module = module, startedAt = at, scoresJson = scores, status = status)

    private fun card(
        source: String,
        tags: String,
        lapses: Int = 0,
        reps: Int = 0,
    ) = Card(source = source, tags = tags, lapses = lapses, reps = reps)

    @Test
    fun `近 7 天正确率回升时给出正 delta`() {
        val trend = WrongTrendCalc.build(
            sessions = listOf(
                session("reading", now - 10 * day, """{"correct":6,"total":10}"""),
                session("reading", now - 2 * day, """{"correct":8,"total":10}"""),
            ),
            wrongCards = emptyList(),
            now = now,
        )
        val reading = trend.modules.first { it.module == "reading" }
        assertEquals(80, reading.recentPercent)
        assertEquals(60, reading.previousPercent)
        assertEquals(20, reading.deltaPoints)
        assertEquals("阅读比上一周高 20 个百分点，保持这个节奏。", trend.diagnosis)
    }

    @Test
    fun `正确率下滑时优先提示重刷错题`() {
        val trend = WrongTrendCalc.build(
            sessions = listOf(
                session("listening", now - 9 * day, """{"correct":8,"total":10}"""),
                session("listening", now - 1 * day, """{"correct":5,"total":10}"""),
            ),
            wrongCards = emptyList(),
            now = now,
        )
        val listening = trend.modules.first { it.module == "listening" }
        assertEquals(-30, listening.deltaPoints)
        assertEquals("听力比上一周低 30 个百分点，先重刷错题再开新套题。", trend.diagnosis)
    }

    @Test
    fun `没有上期数据时只报近 7 天最薄弱科目`() {
        val trend = WrongTrendCalc.build(
            sessions = listOf(
                session("reading", now - 3 * day, """{"correct":4,"total":10}"""),
            ),
            wrongCards = emptyList(),
            now = now,
        )
        val reading = trend.modules.first { it.module == "reading" }
        assertNull(reading.deltaPoints)
        assertEquals(40, reading.recentPercent)
        assertEquals("阅读近 7 天正确率 40%，是当前最薄弱的一科。", trend.diagnosis)
    }

    @Test
    fun `错题卡按来源与标签分科并统计反复错和未练`() {
        val trend = WrongTrendCalc.build(
            sessions = emptyList(),
            wrongCards = listOf(
                card("bank", "bank,听力", lapses = 2, reps = 3),
                card("bank", "bank,阅读", lapses = 0, reps = 0),
                card("shadow", "shadow,跟读", lapses = 0, reps = 1),
            ),
            now = now,
        )
        assertEquals(3, trend.cardTotal)
        assertEquals(1, trend.listeningCards)
        assertEquals(1, trend.readingCards)
        assertEquals(1, trend.shadowCards)
        assertEquals(1, trend.repeatCards)
        assertEquals(1, trend.freshCards)
        assertTrue(trend.diagnosis.contains("反复遗忘"))
    }

    @Test
    fun `未完成的练习不计入趋势`() {
        val trend = WrongTrendCalc.build(
            sessions = listOf(
                session("reading", now - 1 * day, """{"correct":9,"total":10}""", StatusLabel.PENDING_EVAL),
            ),
            wrongCards = emptyList(),
            now = now,
        )
        assertTrue(trend.activeModules.isEmpty())
        assertTrue(trend.isEmpty)
        assertTrue(trend.diagnosis.isNotBlank())
    }

    @Test
    fun `跟读的 accuracy 按百分制并入趋势`() {
        val trend = WrongTrendCalc.build(
            sessions = listOf(
                session("shadow", now - 1 * day, """{"accuracy":75}"""),
            ),
            wrongCards = emptyList(),
            now = now,
        )
        val shadow = trend.modules.first { it.module == "shadow" }
        assertEquals(1, shadow.recent.attempts)
        assertEquals(75, shadow.recentPercent)
    }

    @Test
    fun `窗口边界 7 天内算本期 7 到 14 天算上期 更早忽略`() {
        val trend = WrongTrendCalc.build(
            sessions = listOf(
                session("reading", now - 7 * day, """{"correct":10,"total":10}"""),
                session("reading", now - 7 * day - 1, """{"correct":0,"total":10}"""),
                session("reading", now - 15 * day, """{"correct":10,"total":10}"""),
            ),
            wrongCards = emptyList(),
            now = now,
        )
        val reading = trend.modules.first { it.module == "reading" }
        assertEquals(1, reading.recent.attempts)
        assertEquals(100, reading.recentPercent)
        assertEquals(1, reading.previous.attempts)
        assertEquals(0, reading.previousPercent)
    }

    @Test
    fun `空分数和坏 JSON 不污染正确率`() {
        val trend = WrongTrendCalc.build(
            sessions = listOf(
                session("reading", now - 1 * day, """{"correct":0,"total":0}"""),
                session("reading", now - 2 * day, "not json"),
                session("reading", now - 3 * day, """{"correct":5,"total":10}"""),
            ),
            wrongCards = emptyList(),
            now = now,
        )
        val reading = trend.modules.first { it.module == "reading" }
        assertEquals(1, reading.recent.attempts)
        assertEquals(50, reading.recentPercent)
    }

    @Test
    fun `完全没数据时给出空状态文案而不崩`() {
        val trend = WrongTrendCalc.build(emptyList(), emptyList(), now)
        assertTrue(trend.isEmpty)
        assertFalse(trend.diagnosis.isBlank())
        assertEquals(0, trend.cardTotal)
    }
}
