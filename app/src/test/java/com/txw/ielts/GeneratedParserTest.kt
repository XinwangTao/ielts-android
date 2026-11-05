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
    fun `重复题目只保留第一次`() {
        val raw = """{"passage":"Body","questions":[
            {"prompt":"What is the hive made of?","answers":["wax"]},
            {"prompt":"  what is THE hive   made of?  ","answers":["wax"]},
            {"prompt":"Q3","answers":["ok"]}
        ]}"""
        val r = GeneratedParser.reading(raw, 5)
        assertEquals(2, r.questions.size)
        assertEquals("What is the hive made of?", r.questions[0].prompt)
        assertEquals("Q3", r.questions[1].prompt)
    }

    @Test
    fun `题干相同但答案不同的题保留`() {
        val raw = """{"passage":"Body","questions":[
            {"prompt":"Which word fits?","answers":["hive"]},
            {"prompt":"Which word fits?","answers":["comb"]}
        ]}"""
        val r = GeneratedParser.reading(raw, 5)
        assertEquals(2, r.questions.size)
    }

    @Test
    fun `choice 选项不足时降级为填空题`() {
        val raw = """{"passage":"Body","questions":[
            {"type":"Choice","prompt":"Q1","options":["only"],"answers":["only"]},
            {"type":"choice","prompt":"Q2","options":["A","B"],"answers":["A"]}
        ]}"""
        val r = GeneratedParser.reading(raw, 5)
        assertEquals("gap", r.questions[0].type)
        assertEquals(emptyList<String>(), r.questions[0].options)
        assertEquals("choice", r.questions[1].type)
        assertEquals(listOf("A", "B"), r.questions[1].options)
    }

    @Test
    fun `choice 答案写字母时折回选项原文`() {
        val raw = """{"passage":"Body","questions":[
            {"type":"choice","prompt":"Q1","options":["alpha","beta","gamma","delta"],"answers":["B"]},
            {"type":"choice","prompt":"Q2","options":["alpha","beta"],"answers":["(c)"]},
            {"type":"choice","prompt":"Q3","options":["alpha","beta","gamma"],"answers":["D."]}
        ]}"""
        val r = GeneratedParser.reading(raw, 5)
        assertEquals("beta", r.questions[0].answers[0])
        // 越界字母不猜，保持原样
        assertEquals("(c)", r.questions[1].answers[0])
        assertEquals("D.", r.questions[2].answers[0])
    }

    @Test
    fun `choice 答案带字母前缀时取选项原文`() {
        val raw = """{"passage":"Body","questions":[
            {"type":"choice","prompt":"Q1","options":["alpha","beta","gamma","delta"],"answers":["b. beta"]},
            {"type":"choice","prompt":"Q2","options":["alpha","beta","gamma"],"answers":["C) gamma"]},
            {"type":"choice","prompt":"Q3","options":["alpha","beta"],"answers":["A. beta"]}
        ]}"""
        val r = GeneratedParser.reading(raw, 5)
        assertEquals("beta", r.questions[0].answers[0])
        assertEquals("gamma", r.questions[1].answers[0])
        // 序号与原文不一致时按原文定位
        assertEquals("beta", r.questions[2].answers[0])
    }

    @Test
    fun `choice 答案已是选项原文时保持不动`() {
        val raw = """{"passage":"Body","questions":[
            {"type":"choice","prompt":"Q1","options":["Alpha","Beta"],"answers":["beta"]}
        ]}"""
        val r = GeneratedParser.reading(raw, 5)
        assertEquals("beta", r.questions[0].answers[0])
    }

    @Test
    fun `tfng 缺选项时补标准三选项并归一化答案`() {
        val raw = """{"passage":"Body","questions":[
            {"type":"TFNG","prompt":"Q1","answers":["T"]},
            {"type":"tfng","prompt":"Q2","answers":["ng"]}
        ]}"""
        val r = GeneratedParser.reading(raw, 5)
        assertEquals(listOf("TRUE", "FALSE", "NOT GIVEN"), r.questions[0].options)
        assertEquals("TRUE", r.questions[0].answers[0])
        assertEquals("NOT GIVEN", r.questions[1].answers[0])
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
