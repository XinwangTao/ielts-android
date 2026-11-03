package com.txw.ielts

import com.txw.ielts.llm.GeneratedParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 出题/精讲的 JSON 解析：云端包围栏、本地模型缺字段，这两种脏数据都得能兜住，
 * 否则断网回退本地时用户只会看到一句「生成失败」。
 */
class GeneratedParserTest {

    @Test
    fun `带 markdown 围栏的阅读 JSON 能解析并按请求数截断`() {
        val raw = """
            ```json
            {"title":"Urban bees","passage":"Paragraph text","questions":[
              {"type":"choice","prompt":"Q1","options":["A","B","C","D"],"answers":["A"],"explain":"中文解析","explain_en":"en","keys":["bee","蜂"]},
              {"type":"gap","prompt":"Q2","answers":["hive"]},
              {"type":"gap","prompt":"Q3","answers":["pollen"]}
            ]}
            ```
        """.trimIndent()
        val r = GeneratedParser.reading(raw, 2)
        assertEquals("Urban bees", r.title)
        assertEquals(2, r.questions.size)
        assertEquals("A", r.questions[0].answers[0])
        assertEquals(listOf("bee", "蜂"), r.questions[0].keys)
    }

    @Test
    fun `缺题干或答案的残题被丢掉，其余照常返回`() {
        val raw = """{"passage":"Body","questions":[
            {"type":"gap","prompt":"","answers":["x"]},
            {"type":"gap","prompt":"Q2","answers":[]},
            {"type":"gap","prompt":"Q3","answers":["ok"]}
        ]}"""
        val r = GeneratedParser.reading(raw, 5)
        assertEquals(1, r.questions.size)
        assertEquals("Q3", r.questions[0].prompt)
        assertEquals("gap", r.questions[0].type)
    }

    @Test
    fun `单个字符串形式的答案也收下`() {
        val raw = """{"transcript":"A: hello","questions":[{"prompt":"Q1","answers":"hive"}]}"""
        val l = GeneratedParser.listening(raw, 3)
        assertEquals("hive", l.questions[0].answers[0])
        assertEquals("gap", l.questions[0].type)
    }

    @Test
    fun `没有可用题目或没有正文时报错`() {
        try {
            GeneratedParser.reading("""{"passage":"Body","questions":[]}""", 3)
            fail("空题集应报错")
        } catch (e: Exception) {
            assertTrue(e.message!!.isNotBlank())
        }
        try {
            GeneratedParser.reading("""{"questions":[{"prompt":"Q","answers":["a"]}]}""", 3)
            fail("缺正文应报错")
        } catch (e: Exception) {
            assertTrue(e.message!!.isNotBlank())
        }
    }

    @Test
    fun `精讲兼容 cn-en 与 explain 两套键名`() {
        val a = GeneratedParser.bankExplain("""{"cn":"中文","en":"English","keys":["k1"]}""")
        assertEquals("中文", a.cn)
        assertEquals("English", a.en)
        val b = GeneratedParser.bankExplain("""{"explain":"只有中文","explain_en":"only en"}""")
        assertEquals("只有中文", b.cn)
        assertEquals("only en", b.en)
        assertEquals(emptyList<String>(), b.keys)
    }

    @Test
    fun `精讲完全空白时报错`() {
        try {
            GeneratedParser.bankExplain("""{"cn":"","en":""}""")
            fail("空精讲应报错")
        } catch (e: Exception) {
            assertTrue(e.message!!.isNotBlank())
        }
    }
}
