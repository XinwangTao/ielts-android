package com.txw.ielts

import com.txw.ielts.llm.GeneratedParser
import com.txw.ielts.llm.GeneratedQuestion
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
    fun `choice 答案越界时整题丢弃 而不是留下怎么点都判错的题`() {
        val raw = """{"passage":"Body","questions":[
            {"type":"choice","prompt":"Q1","options":["alpha","beta","gamma","delta"],"answers":["B"]},
            {"type":"choice","prompt":"Q2","options":["alpha","beta"],"answers":["(c)"]},
            {"type":"choice","prompt":"Q3","options":["alpha","beta","gamma"],"answers":["D."]}
        ]}"""
        val r = GeneratedParser.reading(raw, 5)
        // Q2/Q3 的答案折不回任何选项：UI 只有一个正确答案字符串，
        // 用户点遍所有选项也会判错，留下就是白做。只保留能作答的 Q1。
        assertEquals(1, r.questions.size)
        assertEquals("Q1", r.questions[0].prompt)
        assertEquals("beta", r.questions[0].answers[0])
    }

    @Test
    fun `choice 答案与选项只差大小写或句末标点时保留`() {
        // 判分用 AnswerKey.normalize，大小写/标点不同仍能判对，不能误删。
        val raw = """{"passage":"Body","questions":[
            {"type":"choice","prompt":"Q1","options":["alpha","beta"],"answers":["Beta!"]}
        ]}"""
        val r = GeneratedParser.reading(raw, 5)
        assertEquals(1, r.questions.size)
        assertEquals("Beta!", r.questions[0].answers[0])
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
    fun `YES NO NOT GIVEN 判断题不被改成 TRUE FALSE`() {
        // 云模型爱把判断题写成 Yes/No/Not Given，答案只写 Y/N；
        // 若补成 TRUE/FALSE 三选项，答案 "Y" 与选项「YES」对不上，用户选对也会判错。
        val raw = """{"passage":"Body","questions":[
            {"type":"Yes/No/Not Given","prompt":"Q1","answers":["Y"]},
            {"type":"ynng","prompt":"Q2","options":["YES","NO","NOT GIVEN"],"answers":["N"]},
            {"type":"判断题","prompt":"Q3","options":["Yes","No","Not Given"],"answers":["NG"]}
        ]}"""
        val r = GeneratedParser.reading(raw, 5)
        assertEquals(listOf("tfng", "tfng", "tfng"), r.questions.map { it.type })
        assertEquals(listOf("YES", "NO", "NOT GIVEN"), r.questions[0].options)
        assertEquals("YES", r.questions[0].answers[0])
        assertEquals("NO", r.questions[1].answers[0])
        // 小写/混排选项统一成 UI 文案，答案与选项完全一致，选中态才能正确高亮。
        assertEquals(listOf("YES", "NO", "NOT GIVEN"), r.questions[2].options)
        assertEquals("NOT GIVEN", r.questions[2].answers[0])
    }

    @Test
    fun `小写 tfng 选项与漏写的 NOT GIVEN 被补齐`() {
        val raw = """{"passage":"Body","questions":[
            {"type":"tfng","prompt":"Q1","options":["true","false"],"answers":["T"]}
        ]}"""
        val r = GeneratedParser.reading(raw, 5)
        assertEquals(listOf("TRUE", "FALSE", "NOT GIVEN"), r.questions[0].options)
        assertEquals("TRUE", r.questions[0].answers[0])
    }

    @Test
    fun `无题型时 YES 答案也推断为判断题而不是填空`() {
        val raw = """{"passage":"Body","questions":[
            {"prompt":"Q1","options":["YES","NO","NOT GIVEN"],"answers":["Y"]},
            {"prompt":"Q2","answers":["YES"]}
        ]}"""
        val r = GeneratedParser.reading(raw, 5)
        assertEquals("tfng", r.questions[0].type)
        assertEquals("YES", r.questions[0].answers[0])
        assertEquals("tfng", r.questions[1].type)
        assertEquals(listOf("YES", "NO", "NOT GIVEN"), r.questions[1].options)
        assertEquals("YES", r.questions[1].answers[0])
    }

    @Test
    fun `题型别名归一化到 choice tfng gap`() {
        val raw = """{"passage":"Body","questions":[
            {"type":"Multiple Choice","prompt":"Q1","options":["alpha","beta"],"answers":["B"]},
            {"type":"True/False/Not Given","prompt":"Q2","answers":["F"]},
            {"type":"fill-in-the-blank","prompt":"Q3","answers":["hive"]},
            {"type":"multiple_choice","prompt":"Q4","options":["alpha","beta"],"answers":["alpha"]},
            {"type":"填空题","prompt":"Q5","answers":["comb"]}
        ]}"""
        val r = GeneratedParser.reading(raw, 5)
        assertEquals(listOf("choice", "tfng", "gap", "choice", "gap"), r.questions.map { it.type })
        assertEquals("beta", r.questions[0].answers[0])
        assertEquals(listOf("TRUE", "FALSE", "NOT GIVEN"), r.questions[1].options)
        assertEquals("FALSE", r.questions[1].answers[0])
    }

    @Test
    fun `题型缺失或认不出时按字段推断`() {
        val raw = """{"passage":"Body","questions":[
            {"prompt":"Q1","options":["alpha","beta"],"answers":["B"]},
            {"prompt":"Q2","answers":["NG"]},
            {"type":"matching","prompt":"Q3","options":["alpha","beta"],"answers":["b. beta"]},
            {"type":"essay","prompt":"Q4","answers":["hive"]}
        ]}"""
        val r = GeneratedParser.reading(raw, 5)
        assertEquals("choice", r.questions[0].type)
        assertEquals("beta", r.questions[0].answers[0])
        assertEquals("tfng", r.questions[1].type)
        assertEquals("NOT GIVEN", r.questions[1].answers[0])
        assertEquals("choice", r.questions[2].type)
        assertEquals("beta", r.questions[2].answers[0])
        assertEquals("gap", r.questions[3].type)
    }

    @Test
    fun `答案写成数字或布尔时整题不再被判残题丢弃`() {
        // 听力里的价格/时间/数量题，模型（尤其本地 4B）常把答案写成裸 JSON 数字。
        // 只认 String 的话这些题会以「缺答案」被整题丢掉，用户还没做就少了几道。
        val raw = """{"transcript":"A: The ticket costs 35 pounds.","questions":[
            {"type":"gap","prompt":"Q1 How much is the ticket?","answers":[35]},
            {"type":"gap","prompt":"Q2 How many days?","answers":[20.0]},
            {"type":"gap","prompt":"Q3 Price in pounds?","answers":[12.5]},
            {"type":"gap","prompt":"Q4 Still open?","answers":[true]}
        ]}"""
        val l = GeneratedParser.listening(raw, 5)
        assertEquals(4, l.questions.size)
        assertEquals("35", l.questions[0].answers[0])
        // 20.0 解析成 Double，但整数值要还原成「20」，否则用户打 20 会被判错
        assertEquals("20", l.questions[1].answers[0])
        assertEquals("12.5", l.questions[2].answers[0])
        assertEquals("true", l.questions[3].answers[0])
    }

    @Test
    fun `单个数字答案与数字选项也折成文本`() {
        val raw = """{"passage":"Body","questions":[
            {"type":"gap","prompt":"Q1","answers":35},
            {"type":"choice","prompt":"Q2","options":[1970,1980,1990],"answers":[1980]},
            {"type":"gap","prompt":"Q3","answers":[null,"hive"]}
        ]}"""
        val r = GeneratedParser.reading(raw, 5)
        assertEquals("35", r.questions[0].answers[0])
        assertEquals(listOf("1970", "1980", "1990"), r.questions[1].options)
        assertEquals("1980", r.questions[1].answers[0])
        // 数组里的 null 跳过，其余照收
        assertEquals("hive", r.questions[2].answers[0])
    }

    @Test
    fun `tfng 答案认不出时丢弃 避免留下无对应选项的题`() {
        val raw = """{"passage":"Body","questions":[
            {"type":"tfng","prompt":"Q1","answers":["MAYBE"]},
            {"type":"tfng","prompt":"Q2","answers":["T"]}
        ]}"""
        val r = GeneratedParser.reading(raw, 5)
        assertEquals(1, r.questions.size)
        assertEquals("Q2", r.questions[0].prompt)
        assertEquals("TRUE", r.questions[0].answers[0])
    }

    @Test
    fun `选项题的答案全部对不上选项时报错 而不是给一套废题`() {
        val raw = """{"passage":"Body","questions":[
            {"type":"choice","prompt":"Q1","options":["alpha","beta"],"answers":["E"]},
            {"type":"tfng","prompt":"Q2","answers":["NOT SURE"]}
        ]}"""
        try {
            GeneratedParser.reading(raw, 5)
            fail("全部题都不可作答时应报错")
        } catch (e: Exception) {
            assertTrue(e.message!!.contains("对不上"))
        }
    }

    @Test
    fun `填空题答案不受选项校验影响`() {
        // gap 题本来就没有选项，答案可以是任意词；校验只针对 choice/tfng。
        val raw = """{"passage":"Body","questions":[
            {"type":"gap","prompt":"Q1","answers":["hive"]},
            {"type":"choice","prompt":"Q2","options":["alpha","beta"],"answers":["E"]}
        ]}"""
        val r = GeneratedParser.reading(raw, 5)
        assertEquals(1, r.questions.size)
        assertEquals("hive", r.questions[0].answers[0])
    }

    @Test
    fun `问答题型的选项题保留可作答部分`() {
        // 多选题给了多个可接受答案：只要有一个能落到选项上，用户就仍能作答。
        val raw = """{"passage":"Body","questions":[
            {"type":"choice","prompt":"Q1","options":["alpha","beta","gamma"],"answers":["E","beta"]}
        ]}"""
        val r = GeneratedParser.reading(raw, 5)
        assertEquals(1, r.questions.size)
        assertEquals(listOf("E", "beta"), r.questions[0].answers)
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

    @Test
    fun `重发生成时优先保留更完整的一份`() {
        fun q(i: Int, answers: Boolean = true, en: Boolean = true) = GeneratedQuestion(
            type = "gap", prompt = "p$i", options = emptyList(),
            answers = if (answers) listOf("a") else emptyList(),
            explain = "e", explainEn = if (en) "en" else "", keys = emptyList(),
        )
        val eightMissingOne = (1..8).map { q(it, en = it != 8) }
        val sevenComplete = (1..7).map { q(it) }
        // 题数优先：8 题缺一个解析，仍好于 7 题齐全（用户要的是 8 题）。
        assertTrue(GeneratedParser.completeness(eightMissingOne) > GeneratedParser.completeness(sevenComplete))
        assertEquals(8, GeneratedParser.pickMoreComplete(sevenComplete, eightMissingOne).size)
        // 题数相同则比「答案 + 解析齐全」的题数。
        val eightMissingTwo = (1..8).map { q(it, answers = it <= 6) }
        assertEquals(eightMissingOne, GeneratedParser.pickMoreComplete(eightMissingOne, eightMissingTwo))
    }
}
