package com.txw.ielts

import com.txw.ielts.data.Session
import com.txw.ielts.llm.ReviewSummary
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AI 复盘输入的纯函数回归。
 * 关键点：听读没有 Band，只有 correct/total；直接读 overall 会变成 0.0，
 * 模型会误判「阅读 0 分」。这里直接锁住渲染规则与 7 天窗口。
 */
class ReviewSummaryTest {

    private val now = 1_800_000_000_000L

    @Test
    fun `听读显示正确率而不是 Band 0`() {
        val sessions = listOf(
            Session(
                module = "reading", status = "done", startedAt = now - 1_000,
                scoresJson = """{"correct":4,"total":8}""",
            ),
            Session(
                module = "listening", status = "done", startedAt = now - 2_000,
                scoresJson = """{"correct":6,"total":10}""",
            ),
        )
        val s = ReviewSummary.build(sessions, 6.5f, emptyList(), now)
        assertTrue(s, s.contains("阅读 4/8"))
        assertTrue(s, s.contains("听力 6/10"))
        assertFalse(s, s.contains("Band 0.0"))
    }

    @Test
    fun `写作显示 Band 与改进点`() {
        val sessions = listOf(
            Session(
                module = "writing", status = "done", startedAt = now - 1_000,
                scoresJson = """{"overall":6.5}""",
                fixesJson = """{"fixes":["主谓一致","字数与展开"]}""",
            ),
        )
        val s = ReviewSummary.build(sessions, 6.5f, listOf("衔接与分段" to 17), now)
        assertTrue(s, s.contains("Band 6.5"))
        assertTrue(s, s.contains("主谓一致"))
        assertTrue(s, s.contains("衔接与分段 (17)"))
        assertTrue(s, s.contains("Target Band: 6.5"))
    }

    @Test
    fun `复盘提示词不包含 null 改进点`() {
        // 模型输出的 [null] 曾在复盘摘要里变成字面量 "null" 喂回给模型。
        val sessions = listOf(
            Session(
                module = "writing", status = "done", startedAt = now - 1_000,
                scoresJson = """{"overall":6.5}""",
                fixesJson = """{"fixes":["主谓一致",null,"null"]}""",
            ),
        )
        val s = ReviewSummary.build(sessions, 6.5f, emptyList(), now)
        assertTrue(s, s.contains("主谓一致"))
        assertFalse(s, s.contains("null"))
    }

    @Test
    fun `超过 7 天的记录不进入复盘`() {
        val old = Session(
            module = "writing", status = "done", startedAt = now - 8L * 86_400_000L,
            scoresJson = """{"overall":7.0}""",
        )
        val s = ReviewSummary.build(listOf(old), 6.5f, emptyList(), now)
        assertTrue(s, s.contains("最近 7 天没有完成的练习"))
        assertFalse(s, s.contains("Band 7.0"))
    }

    @Test
    fun `未完成的记录不进入复盘`() {
        val pending = Session(
            module = "speaking", status = "pending_eval", startedAt = now - 1_000,
            scoresJson = "",
        )
        val s = ReviewSummary.build(listOf(pending), 6.5f, emptyList(), now)
        assertTrue(s, s.contains("最近 7 天没有完成的练习"))
    }

    @Test
    fun `考试日期会进入复盘输入`() {
        val s = ReviewSummary.build(
            sessions = emptyList(),
            targetBand = 6.5f,
            weaknesses = emptyList(),
            now = now,
            examDate = "2030-12-18",
        )
        assertTrue(s, s.contains("Exam: 2030-12-18"))
        assertTrue(s, s.contains("days left"))
    }
}
