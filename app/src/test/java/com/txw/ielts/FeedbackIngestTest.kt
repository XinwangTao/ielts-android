package com.txw.ielts

import com.txw.ielts.data.CardText
import com.txw.ielts.data.FeedbackIngest
import com.txw.ielts.data.IngestResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「加入背诵」的入库账目：重复/不可用的条目不能再静默当成成功。
 *
 * 这些用例锁住两件事：1) 去重与计数口径与 CardRepo 原有规则一致（CardText.key）；
 * 2) 结果页文案能分清「真加了」「全是重复」「内容本身不可用」。
 */
class FeedbackIngestTest {

    private fun keys(vararg fronts: String) = fronts.map { CardText.key(it) }.toSet()

    // ---- 批注 ----

    @Test
    fun `批注全部新增时计数与卡片内容正确`() {
        val plan = FeedbackIngest.planFixes(
            listOf("  Use   a wider range of tenses.  ", "Add a clear topic sentence."),
            emptySet(),
        )
        assertEquals(2, plan.ingest.added)
        assertEquals(0, plan.ingest.duplicate)
        assertEquals(0, plan.ingest.invalid)
        assertEquals("Use a wider range of tenses.", plan.accepted[0].front)
        assertEquals("Use   a wider range of tenses.", plan.accepted[0].back)
    }

    @Test
    fun `批注与库内已有卡重复时计入 duplicate 而不是新增`() {
        val plan = FeedbackIngest.planFixes(
            listOf("Add a clear topic sentence."),
            keys("add a clear topic sentence"),
        )
        assertEquals(0, plan.ingest.added)
        assertEquals(1, plan.ingest.duplicate)
        assertTrue(plan.accepted.isEmpty())
    }

    @Test
    fun `长批注同前缀但后半句不同 不应被当成重复`() {
        val prefix = "Your second body paragraph lacks a clear topic sentence, and the example"
        val a = "$prefix is unrelated to the claim."
        val b = "$prefix is too detailed for the word limit."
        // 正面截断到 FIX_MAX 后两条完全相同；判重键若也截断，就会把 b 静默丢掉。
        assertEquals(CardText.front(a, com.txw.ielts.data.CardText.FIX_MAX),
            CardText.front(b, com.txw.ielts.data.CardText.FIX_MAX))
        val plan = FeedbackIngest.planFixes(listOf(a, b), emptySet())
        assertEquals(2, plan.ingest.added)
        assertEquals(0, plan.ingest.duplicate)
    }

    @Test
    fun `同批内重复的批注只收第一条`() {
        val plan = FeedbackIngest.planFixes(
            listOf("Vary the sentence structure.", "vary the sentence structure!"),
            emptySet(),
        )
        assertEquals(1, plan.ingest.added)
        assertEquals(1, plan.ingest.duplicate)
    }

    @Test
    fun `空白批注计入 invalid`() {
        val plan = FeedbackIngest.planFixes(listOf("   ", "\n\t"), emptySet())
        assertEquals(0, plan.ingest.added)
        assertEquals(2, plan.ingest.invalid)
    }

    @Test
    fun `批注正面按上限截断并补省略号`() {
        val raw = "x".repeat(100)
        val plan = FeedbackIngest.planFixes(listOf(raw), emptySet())
        assertEquals(CardText.FIX_MAX + 1, plan.accepted[0].front.length)
        assertTrue(plan.accepted[0].front.endsWith("…"))
        // 背面保留完整批注，不跟着截断。
        assertEquals(raw, plan.accepted[0].back)
    }

    // ---- 逐句改法 ----

    @Test
    fun `逐句改法入库为改错卡并带中文问题`() {
        val plan = FeedbackIngest.planSentences(
            listOf(Triple("I has a car.", "主谓不一致", "I have a car.")),
            emptySet(),
        )
        assertEquals(1, plan.ingest.added)
        assertEquals("改错：I has a car.", plan.accepted[0].front)
        assertEquals("I have a car.\n\n（主谓不一致）", plan.accepted[0].back)
    }

    @Test
    fun `问题为空时背面不带空括号`() {
        val plan = FeedbackIngest.planSentences(
            listOf(Triple("He go home.", "", "He goes home.")),
            emptySet(),
        )
        assertEquals("He goes home.", plan.accepted[0].back)
    }

    @Test
    fun `原句与改法只差标点或大小写时计入 invalid`() {
        val plan = FeedbackIngest.planSentences(
            listOf(
                Triple("I have a car.", "微调", "i have a car"),
                Triple("He goes home.", "微调", "He goes home!"),
            ),
            emptySet(),
        )
        assertEquals(0, plan.ingest.added)
        assertEquals(2, plan.ingest.invalid)
    }

    @Test
    fun `逐句改法缺原句或改法时计入 invalid`() {
        val plan = FeedbackIngest.planSentences(
            listOf(Triple("", "空", "Fixed."), Triple("Original.", "空", "  ")),
            emptySet(),
        )
        assertEquals(2, plan.ingest.invalid)
    }

    @Test
    fun `逐句改法与库内已有改错卡重复时计入 duplicate`() {
        val plan = FeedbackIngest.planSentences(
            listOf(Triple("I has a car.", "主谓不一致", "I have a car.")),
            keys("改错：i has a car"),
        )
        assertEquals(0, plan.ingest.added)
        assertEquals(1, plan.ingest.duplicate)
    }

    // ---- 账目与文案 ----

    @Test
    fun `两笔账相加并统计总数`() {
        val sum = IngestResult(added = 2, duplicate = 1) +
            IngestResult(added = 1, invalid = 3)
        assertEquals(IngestResult(added = 3, duplicate = 1, invalid = 3), sum)
        assertEquals(7, sum.total)
    }

    @Test
    fun `空账目没有可说的`() {
        assertNull(FeedbackIngest.summary(IngestResult()))
    }

    @Test
    fun `有新增时文案给出张数并提示重复与不可用`() {
        val msg = FeedbackIngest.summary(IngestResult(added = 3, duplicate = 2, invalid = 1))!!
        assertTrue(msg.startsWith("已加入背诵：3 张新卡"))
        assertTrue(msg.contains("2 条与已有卡片重复"))
        assertTrue(msg.contains("1 条内容不可用"))
        assertTrue(msg.contains("下轮复习"))
    }

    @Test
    fun `全是重复时不能写成已加入`() {
        val msg = FeedbackIngest.summary(IngestResult(duplicate = 5))!!
        assertFalse(msg.startsWith("已加入背诵"))
        assertTrue(msg.contains("都已经在背诵里"))
    }

    @Test
    fun `全不可用时说清是内容问题`() {
        val msg = FeedbackIngest.summary(IngestResult(invalid = 4))!!
        assertTrue(msg.startsWith("没有可加入的内容"))
        assertTrue(msg.contains("标点"))
    }

    @Test
    fun `重复与不可用都有一张没加时分开计数`() {
        val msg = FeedbackIngest.summary(IngestResult(duplicate = 2, invalid = 1))!!
        assertTrue(msg.contains("2 条已在背诵里"))
        assertTrue(msg.contains("1 条内容不可用"))
    }
}
