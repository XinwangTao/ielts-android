package com.txw.ielts

import com.txw.ielts.llm.StudyAi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本地 AI 的三个解析器：4B 模型常把 JSON 包在代码块里、或者干脆写坏，
 * 解析层必须「能解析就结构化展示，不能解析就退原文」，不能把内容吞掉。
 */
class StudyAiTest {

    @Test
    fun `提纲解析正常 JSON`() {
        val raw = """
            {"stance":"Universities should let students choose.","arguments":[{"point":"Interest drives effort","explain":"Students work harder on subjects they enjoy","example":"Finland's flexible curriculum"}],"phrases":["play a vital role","a sense of fulfilment"]}
        """.trimIndent()
        val o = StudyAi.parseOutline(raw)
        assertEquals("Universities should let students choose.", o.stance)
        assertEquals(1, o.arguments.size)
        assertEquals("Interest drives effort", o.arguments[0].point)
        assertEquals(2, o.phrases.size)
        assertTrue(o.raw.isBlank())
    }

    @Test
    fun `提纲容忍代码块和前后说明`() {
        val raw = """
            Here is the plan:
            ```json
            {"stance":"Both sides have merits.","arguments":[{"point":"p","explain":"e","example":"x"}],"phrases":["on balance"]}
            ```
            Hope this helps.
        """.trimIndent()
        val o = StudyAi.parseOutline(raw)
        assertEquals("Both sides have merits.", o.stance)
        assertEquals(1, o.arguments.size)
    }

    @Test
    fun `提纲坏 JSON 退原文`() {
        val raw = "I cannot produce JSON, but here are three ideas: health, money, time."
        val o = StudyAi.parseOutline(raw)
        // 退原文时 raw 非空，所以 isEmpty=false —— 界面靠 raw 显示内容。
        assertTrue(o.stance.isBlank())
        assertTrue(o.arguments.isEmpty())
        assertEquals(raw, o.raw)
    }

    @Test
    fun `提纲过滤空论点`() {
        val raw = """{"stance":"s","arguments":[{"point":"","explain":"","example":""},{"point":"keep","explain":"e","example":"x"}],"phrases":["","a"]}"""
        val o = StudyAi.parseOutline(raw)
        assertEquals(1, o.arguments.size)
        assertEquals("keep", o.arguments[0].point)
        assertEquals(1, o.phrases.size)
    }

    @Test
    fun `口语范例解析`() {
        val raw = """{"answer":"I would say...","keywords":["time management","a steep learning curve"],"structure":["先回答","再举例","最后总结"]}"""
        val s = StudyAi.parseSample(raw)
        assertEquals("I would say...", s.answer)
        assertEquals(2, s.keywords.size)
        assertEquals(3, s.structure.size)
    }

    @Test
    fun `口语范例坏 JSON 退原文`() {
        val raw = "Part 2 sample: I learned to cook during university..."
        val s = StudyAi.parseSample(raw)
        assertEquals(raw, s.answer)
        assertTrue(s.keywords.isEmpty())
    }

    @Test
    fun `生词解析去空去重`() {
        val raw = """
            {"items":[
              {"phrase":"play a vital role","meaning":"起关键作用","example":"Parks play a vital role in cities."},
              {"phrase":"Play a Vital Role","meaning":"重复","example":"dup"},
              {"phrase":"","meaning":"空","example":""},
              {"phrase":"a sense of fulfilment","meaning":"成就感","example":"Volunteering gives her a sense of fulfilment."}
            ]}
        """.trimIndent()
        val v = StudyAi.parseVocab(raw)
        assertEquals(2, v.items.size)
        assertEquals("play a vital role", v.items[0].phrase)
        assertEquals("a sense of fulfilment", v.items[1].phrase)
    }

    @Test
    fun `生词坏 JSON 退原文`() {
        val raw = "Useful phrases: play a vital role; a sense of fulfilment."
        val v = StudyAi.parseVocab(raw)
        assertTrue(v.items.isEmpty())
        assertEquals(raw, v.raw)
    }

    @Test
    fun `生词解析保留中文释义`() {
        val raw = """{"items":[{"phrase":"bear the cost","meaning":"承担费用","example":"Taxpayers bear the cost of public services."}]}"""
        val v = StudyAi.parseVocab(raw)
        assertEquals("承担费用", v.items[0].meaning)
        assertEquals("Taxpayers bear the cost of public services.", v.items[0].example)
    }

    @Test
    fun `复盘解析正常 JSON`() {
        val raw = """
            {"summary":"最近写作 6.0，衔接和展开是短板。","tasks":[
              {"title":"今天写一篇 Task 2","why":"展开不足","how":"写 280 词并检查每个论点"},
              {"title":"复盘错题","why":"衔接弱","how":"重刷 3 张衔接错题卡"},
              {"title":"口语 10 分钟","why":"保持输出","how":"录一段 Part 2 并跟读"}
            ],"encouragement":"每天一个小闭环就够。"}
        """.trimIndent()
        val r = StudyAi.parseReview(raw)
        assertEquals("最近写作 6.0，衔接和展开是短板。", r.summary)
        assertEquals(3, r.tasks.size)
        assertEquals("今天写一篇 Task 2", r.tasks[0].title)
        assertEquals("每天一个小闭环就够。", r.encouragement)
    }

    @Test
    fun `复盘坏 JSON 退原文`() {
        val raw = "先写一篇 Task 2，再复习错题。"
        val r = StudyAi.parseReview(raw)
        assertEquals(raw, r.raw)
        assertTrue(r.tasks.isEmpty())
    }

    @Test
    fun `复盘缺少 tasks 时不崩`() {
        val r = StudyAi.parseReview("""{"summary":"保持节奏","encouragement":"继续"}""")
        assertEquals("保持节奏", r.summary)
        assertTrue(r.tasks.isEmpty())
    }

    @Test
    fun `追问去掉前缀引号与解释`() {
        assertEquals(
            "Why do you think practical skills are undervalued?",
            StudyAi.cleanFollowUp("Question: \"Why do you think practical skills are undervalued?\""),
        )
        assertEquals(
            "How could schools balance exams and life skills?",
            StudyAi.cleanFollowUp("Here is a follow-up question:\nHow could schools balance exams and life skills?"),
        )
        assertEquals("", StudyAi.cleanFollowUp("   "))
    }

    @Test
    fun `追问模型返回 JSON 时取 question 字段`() {
        // 真机实测（OPPO qwen3-4b）：提示词要求纯文本，模型仍返回 JSON 包装。
        assertEquals(
            "Do you think educational systems should prioritize student interests over job market demands?",
            StudyAi.cleanFollowUp(
                """{"question": "Do you think educational systems should prioritize student interests over job market demands?"}"""
            ),
        )
        assertEquals(
            "Should schools teach practical skills?",
            StudyAi.cleanFollowUp("""好的，以下是问题：{"follow_up": "Should schools teach practical skills?"}"""),
        )
    }

    @Test
    fun `解析容忍 JSON 后面的说明文字和花括号`() {
        // 旧实现用 lastIndexOf('}')，会被尾部说明里的括号带偏；现在与评分共用括号配对提取。
        val raw = """
            Here is the plan:
            {"stance":"s","arguments":[{"point":"p","explain":"e","example":"x"}],"phrases":["a"]}
            Hope this helps. }
        """.trimIndent()
        val o = StudyAi.parseOutline(raw)
        assertEquals("s", o.stance)
        assertEquals(1, o.arguments.size)
        assertEquals("a", o.phrases.single())
    }
}
