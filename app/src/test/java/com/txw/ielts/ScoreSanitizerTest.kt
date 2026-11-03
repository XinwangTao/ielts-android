package com.txw.ielts

import com.txw.ielts.llm.ScoreSanitizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 评分后处理回归测试。
 *
 * 这一段是整个 App 的「信誉底线」：本地 4B 模型系统性虚高（任何文章都给四项 8.0），
 * 全靠 ScoreSanitizer 用确定性规则压回诚实区间。规则退化 = 分数全变 8.0。
 */
class ScoreSanitizerTest {

    @Test
    fun `overall 必须按 0_5 步长取整`() {
        assertEquals(5.0, ScoreSanitizer.band(5.2), 0.0)
        assertEquals(5.5, ScoreSanitizer.band(5.3), 0.0)
        assertEquals(6.0, ScoreSanitizer.band(6.0), 0.0)
        // 脏输入不能变成 NaN
        assertEquals(0.0, ScoreSanitizer.band(-1.0), 0.0)
        assertEquals(0.0, ScoreSanitizer.band(Double.NaN), 0.0)
    }

    @Test
    fun `Task2 字数不足按官方规则封顶 TR`() {
        // 100 词 < 150 → TR 封 4.0
        val short = ScoreSanitizer.sanitizeWriting(7.0, 7.0, 7.0, 7.0, 7.0, wordCount = 100)
        assertEquals(4.0, short.tr, 0.0)
        // 200 词 < 250 → TR 封 5.0
        val mid = ScoreSanitizer.sanitizeWriting(7.0, 7.0, 7.0, 7.0, 7.0, wordCount = 200)
        assertEquals(5.0, mid.tr, 0.0)
        // 300 词 → 不封顶
        val ok = ScoreSanitizer.sanitizeWriting(7.0, 7.0, 7.0, 7.0, 7.0, wordCount = 300)
        assertEquals(7.0, ok.tr, 0.0)
    }

    @Test
    fun `Task1 用 150 词门槛而不是 Task2 的 250 词`() {
        // 120 词：Task1 只算「字数不足」，封 5.0（若误用 Task2 规则会封到 4.0）
        val t1 = ScoreSanitizer.sanitizeWriting(7.0, 7.0, 7.0, 7.0, 7.0, wordCount = 120, task = "task1")
        assertEquals(5.0, t1.tr, 0.0)
        val t1short = ScoreSanitizer.sanitizeWriting(7.0, 7.0, 7.0, 7.0, 7.0, wordCount = 90, task = "task1")
        assertEquals(4.0, t1short.tr, 0.0)
        // 160 词已经够 Task1
        val t1ok = ScoreSanitizer.sanitizeWriting(7.0, 7.0, 7.0, 7.0, 7.0, wordCount = 160, task = "task1")
        assertEquals(7.0, t1ok.tr, 0.0)
    }

    @Test
    fun `公式化连接词堆砌压低 CC`() {
        val four = "Moreover, the trend grew. Furthermore, it doubled. In addition, costs fell. Firstly, note this."
        val cc4 = ScoreSanitizer.sanitizeWriting(
            7.0, 8.0, 7.0, 7.0, 8.0, wordCount = 300, essayText = four,
        )
        assertEquals(7.0, cc4.cc, 0.0)

        val six = four + " Secondly, it continued. In conclusion, it will grow."
        val cc6 = ScoreSanitizer.sanitizeWriting(
            7.0, 8.0, 7.0, 7.0, 8.0, wordCount = 300, essayText = six,
        )
        assertEquals(6.5, cc6.cc, 0.0)
    }

    @Test
    fun `模型自列错误越多 GRA 越低`() {
        val one = ScoreSanitizer.sanitizeWriting(
            7.0, 7.0, 7.0, 8.0, 8.0, wordCount = 300, sentenceFixCount = 1,
        )
        assertEquals(7.5, one.gra, 0.0)
        val four = ScoreSanitizer.sanitizeWriting(
            7.0, 7.0, 7.0, 8.0, 8.0, wordCount = 300, sentenceFixCount = 4,
        )
        assertEquals(6.5, four.gra, 0.0)
    }

    @Test
    fun `带改进点的文章 overall 不超过 7_5`() {
        val s = ScoreSanitizer.sanitizeWriting(
            8.0, 8.0, 8.0, 8.0, 8.0, wordCount = 400, hasFixes = true,
        )
        assertTrue("overall=${s.overall}", s.overall <= 7.5)
        // 没有改进点时 8.0 可以保留
        val clean = ScoreSanitizer.sanitizeWriting(
            8.0, 8.0, 8.0, 8.0, 8.0, wordCount = 400, hasFixes = false,
        )
        assertEquals(8.0, clean.overall, 0.0)
    }

    @Test
    fun `overall 是四项均值而不是模型自报值`() {
        val s = ScoreSanitizer.sanitizeWriting(
            6.0, 6.5, 7.0, 7.0, 5.0, wordCount = 300,
        )
        // (6.0+6.5+7.0+7.0)/4 = 6.625 → 6.5
        assertEquals(6.5, s.overall, 0.0)
    }

    @Test
    fun `口语太短按 4_0 封顶`() {
        val short = ScoreSanitizer.sanitizeSpeaking(6.0, 6.0, 6.0, 6.0, 6.0, wordCount = 15)
        assertEquals(4.0, short.overall, 0.0)
        val ok = ScoreSanitizer.sanitizeSpeaking(6.0, 6.0, 6.0, 6.0, 6.0, wordCount = 120)
        assertEquals(6.0, ok.overall, 0.0)
    }

    @Test
    fun `polished 多余引号会被清掉`() {
        assertEquals("Hello world.", ScoreSanitizer.cleanPolished("\"Hello world.\""))
        assertEquals("Hello world.", ScoreSanitizer.cleanPolished("  Hello world.  "))
        assertEquals("a\nb", ScoreSanitizer.cleanPolished("a\\nb"))
    }
}
