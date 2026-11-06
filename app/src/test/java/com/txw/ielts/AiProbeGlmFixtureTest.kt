package com.txw.ielts

import com.txw.ielts.llm.GeneratedParser
import com.txw.ielts.llm.ScoreSanitizer
import com.txw.ielts.llm.StudyAi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跨模型回归：夹具来自 tools/ai_probe.py 跑 GLM-5.3-Flash（3500 预算）的原始响应。
 *
 * DeepSeek 的夹具在 /ai_probe；这里单独放 /ai_probe_glm，避免两套模型互相覆盖。
 * 目的：确认 App 的解析器不只对 DeepSeek 有效——GLM 更容易出现裸引号、斜杠答案、
 * 多给论点等格式差异，解析器必须都能吃下或安全降级。
 */
class AiProbeGlmFixtureTest {

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResourceAsStream("/ai_probe_glm/$name.txt")) {
            "缺少 GLM 夹具 /ai_probe_glm/$name.txt（先跑 tools/ai_probe.py --fixtures app/src/test/resources/ai_probe_glm）"
        }.bufferedReader(Charsets.UTF_8).use { it.readText() }

    @Test
    fun `GLM 评分输出可被 App 解析`() {
        val w = ScoreSanitizer.toWritingEval(
            fixture("scoring_writing_task2"), wordCount = 299, task = "task2", essay = "",
        )
        assertTrue("overall=${w.overall}", w.overall in 0.0..9.0)
        assertTrue(w.sentenceFixes.isNotEmpty())

        val w1 = ScoreSanitizer.toWritingEval(
            fixture("scoring_writing_task1"), wordCount = 160, task = "task1", essay = "",
        )
        assertTrue("TA/overall=${w1.tr}/${w1.overall}", w1.overall in 0.0..9.0)

        val sp = ScoreSanitizer.toSpeakingEval(fixture("scoring_speaking"), wordCount = 149)
        assertTrue("P=${sp.pronunciation}", sp.pronunciation in 0.0..9.0)
    }

    @Test
    fun `GLM 学习助手输出可被 App 解析`() {
        assertTrue(StudyAi.parseOutline(fixture("outline_task2")).arguments.isNotEmpty())
        assertTrue(StudyAi.parseOutline(fixture("outline_task1")).stance.isNotBlank())
        assertTrue(StudyAi.parseSample(fixture("speaking_sample")).answer.isNotBlank())
        assertTrue(StudyAi.parseSample(fixture("speaking_sample_part1")).answer.isNotBlank())
        assertTrue(StudyAi.parseSample(fixture("speaking_sample_part3")).answer.isNotBlank())
        assertTrue(StudyAi.parseVocab(fixture("vocab")).items.isNotEmpty())
        assertTrue(StudyAi.parseReview(fixture("ai_review")).summary.isNotBlank())
    }

    @Test
    fun `GLM 出题与精讲输出可被 App 解析`() {
        assertEquals(6, GeneratedParser.reading(fixture("reading_generation"), 6).questions.size)
        assertEquals(8, GeneratedParser.reading(fixture("reading_generation_8"), 8).questions.size)
        assertEquals(6, GeneratedParser.listening(fixture("listening_generation"), 6).questions.size)
        assertEquals(8, GeneratedParser.listening(fixture("listening_generation_8"), 8).questions.size)

        val b = GeneratedParser.bankExplain(fixture("bank_explain"))
        assertTrue(b.cn.isNotBlank())
        assertTrue(b.en.isNotBlank())
    }

    @Test
    fun `GLM 纯文本输出可用`() {
        assertTrue(fixture("speaking_follow_up").contains("?"))
        assertTrue(fixture("chart_prompt_polish").contains("1995"))
        assertTrue(fixture("chart_prompt_polish").contains("2015"))
    }
}
