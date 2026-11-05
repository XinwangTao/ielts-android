package com.txw.ielts

import com.txw.ielts.llm.GeneratedParser
import com.txw.ielts.llm.ScoreSanitizer
import com.txw.ielts.llm.StudyAi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 真实模型输出回归：夹具来自 tools/ai_probe.py 跑 CC Switch
 * （`ccs-claude-openai--deepseek-v4.1-flash[1m]`）的原始响应。
 *
 * 目的不是再测一次 prompt，而是确认「模型真实吐出来的东西」能被 App 的解析器吃下：
 * JSON 围栏、字符串内裸换行、缩写/等价答案、缺 P 兜底这些线上会遇到的情况。
 * prompt 改动后重新跑 ai_probe 并更新夹具即可。
 */
class AiProbeFixtureTest {

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResourceAsStream("/ai_probe/$name.txt")) {
            "缺少夹具 /ai_probe/$name.txt（先跑 tools/ai_probe.py --fixtures）"
        }.bufferedReader(Charsets.UTF_8).use { it.readText() }

    @Test
    fun `写作 Task2 真实输出可解析且分项完整`() {
        val e = ScoreSanitizer.toWritingEval(
            fixture("scoring_writing_task2"), wordCount = 299, task = "task2", essay = "",
        )
        assertTrue("overall=${e.overall}", e.overall in 0.0..9.0)
        assertTrue("sentence_fixes 为空", e.sentenceFixes.isNotEmpty())
        assertEquals("top_fixes 应为 3 条", 3, e.fixes.size)
        assertTrue("polished 为空", e.polished.isNotBlank())
    }

    @Test
    fun `弱作文触发 150 词以下 TR 封顶`() {
        val e = ScoreSanitizer.toWritingEval(
            fixture("scoring_writing_weak"), wordCount = 80, task = "task2", essay = "",
        )
        assertEquals("80 词的 Task2 必须封 TR=4.0", 4.0, e.tr, 0.0)
        assertTrue("弱作文 overall=${e.overall}", e.overall <= 6.0)
    }

    @Test
    fun `写作 Task1 真实输出可解析`() {
        val e = ScoreSanitizer.toWritingEval(
            fixture("scoring_writing_task1"), wordCount = 160, task = "task1", essay = "",
        )
        assertTrue("TA=${e.tr}", e.tr in 0.0..9.0)
        assertEquals("top_fixes 应为 3 条", 3, e.fixes.size)
        assertTrue(e.polished.isNotBlank())
    }

    @Test
    fun `口语真实输出可解析且 P 不缺失`() {
        val e = ScoreSanitizer.toSpeakingEval(fixture("scoring_speaking"), wordCount = 149)
        assertTrue("P=${e.pronunciation}", e.pronunciation in 0.0..9.0)
        assertTrue(e.fixes.isNotEmpty())
        assertTrue(e.sentenceFixes.isNotEmpty())
    }

    @Test
    fun `提纲真实输出结构完整`() {
        val o = StudyAi.parseOutline(fixture("outline_task2"))
        assertTrue(o.stance.isNotBlank())
        assertEquals(3, o.arguments.size)
        assertEquals(5, o.phrases.size)
    }

    @Test
    fun `Task1 提纲真实输出可解析`() {
        val o = StudyAi.parseOutline(fixture("outline_task1"))
        assertTrue(o.stance.isNotBlank())
        assertTrue(o.arguments.size >= 2)
        assertTrue(o.phrases.isNotEmpty())
    }

    @Test
    fun `口语范例真实输出结构完整`() {
        val s = StudyAi.parseSample(fixture("speaking_sample"))
        assertTrue(s.answer.isNotBlank())
        assertEquals(5, s.keywords.size)
        assertEquals(3, s.structure.size)
    }

    @Test
    fun `生词卡真实输出条数与中文释义`() {
        val v = StudyAi.parseVocab(fixture("vocab"))
        assertTrue("items=${v.items.size}", v.items.size in 5..8)
        assertTrue(v.items.all { it.phrase.isNotBlank() && it.meaning.isNotBlank() && it.example.isNotBlank() })
    }

    @Test
    fun `AI 阅读题真实输出可解析且 6 题`() {
        val r = GeneratedParser.reading(fixture("reading_generation"), count = 6)
        assertTrue(r.title.isNotBlank())
        assertTrue(r.passage.isNotBlank())
        assertEquals(6, r.questions.size)
        assertTrue(r.questions.all { it.prompt.isNotBlank() && it.answers.isNotEmpty() })
    }

    @Test
    fun `AI 听力题真实输出可解析且 6 题`() {
        val l = GeneratedParser.listening(fixture("listening_generation"), count = 6)
        assertTrue(l.transcript.isNotBlank())
        assertEquals(6, l.questions.size)
    }

    @Test
    fun `AI 阅读题 8 题真实输出可解析`() {
        val r = GeneratedParser.reading(fixture("reading_generation_8"), count = 8)
        assertTrue(r.passage.isNotBlank())
        assertEquals(8, r.questions.size)
    }

    @Test
    fun `AI 听力题 8 题真实输出可解析`() {
        val l = GeneratedParser.listening(fixture("listening_generation_8"), count = 8)
        assertTrue(l.transcript.isNotBlank())
        assertEquals(8, l.questions.size)
    }

    @Test
    fun `错题精讲真实输出可解析`() {
        val b = GeneratedParser.bankExplain(fixture("bank_explain"))
        assertTrue(b.cn.isNotBlank())
        assertTrue(b.en.isNotBlank())
        assertTrue(b.keys.isNotEmpty())
    }

    @Test
    fun `口语追问是单个问句`() {
        val q = fixture("speaking_follow_up").trim()
        assertTrue(q.contains("?"))
        assertTrue(q.length < 240)
    }

    @Test
    fun `图表题干润色保留年份`() {
        val p = fixture("chart_prompt_polish")
        assertTrue(p.contains("1995"))
        assertTrue(p.contains("2015"))
    }
}
