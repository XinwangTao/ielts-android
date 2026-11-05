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
}
