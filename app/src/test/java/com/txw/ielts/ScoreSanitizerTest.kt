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
    fun `GRA 只按语法类建议压分，不再按总条数`() {
        // 提示词强制 2–5 条「即使写得好也要挑最弱的句子」，总条数只反映服从度。
        // 只有文风建议、没有语法问题 → 不给满分但也不埋没。
        val styleOnly = ScoreSanitizer.sanitizeWriting(
            7.0, 7.0, 7.0, 8.0, 8.0, wordCount = 300,
            sentenceFixCount = 5, grammarIssueCount = 0,
        )
        assertEquals(7.5, styleOnly.gra, 0.0)

        // 确实提到语法问题 → 比文风再多压 0.5
        val oneGrammar = ScoreSanitizer.sanitizeWriting(
            7.0, 7.0, 7.0, 8.0, 8.0, wordCount = 300,
            sentenceFixCount = 5, grammarIssueCount = 1,
        )
        assertEquals(7.0, oneGrammar.gra, 0.0)

        val fourGrammar = ScoreSanitizer.sanitizeWriting(
            7.0, 7.0, 7.0, 8.0, 8.0, wordCount = 300,
            sentenceFixCount = 5, grammarIssueCount = 4,
        )
        assertEquals(6.5, fourGrammar.gra, 0.0)
    }

    @Test
    fun `语法类判定只认明确措辞`() {
        assertTrue(ScoreSanitizer.isGrammarIssue("时态不一致，应为过去式"))
        assertTrue(ScoreSanitizer.isGrammarIssue("主语和谓语单复数不匹配"))
        assertTrue(ScoreSanitizer.isGrammarIssue("缺少冠词"))
        assertTrue(ScoreSanitizer.isGrammarIssue("介词搭配错误"))
        // 文风与内容批评不算语法
        assertTrue(!ScoreSanitizer.isGrammarIssue("句子空洞，'contribute to the economy' 过于宽泛，逻辑跳跃"))
        assertTrue(!ScoreSanitizer.isGrammarIssue("表达重复，缺乏具体情境"))
        assertTrue(!ScoreSanitizer.isGrammarIssue("'disengage' 用词泛泛，不精确"))
        assertTrue(!ScoreSanitizer.isGrammarIssue("'provided that' 反复使用，显得机械"))
    }

    /**
     * 真机回归（OPPO PKJ110，2026-11-04，id=9，269 词）：模型被提示词逼出 5 条建议，
     * 逐条检查全是文风/内容问题，一条语法错误没提。旧规则按总条数命中「≥4 → GRA ≤ 6.5」，
     * 把 GRA 从 8.0 压到 6.5，反而掩盖了这篇真正的短板（TR 7.0，零例证）。
     */
    @Test
    fun `真机 id9 的 5 条文风建议不应把 GRA 压到 6_5`() {
        val realIssues = listOf(
            "句子结构生硬，'has divided opinion for decades' 与主题不直接相关，且'decades'夸大其词，缺乏具体依据",
            "表达重复，'read beyond the syllabus' 和 'persist' 缺乏具体情境，'original work' 过于模糊，无例证支持",
            "'disengage' 用词泛泛，'expensive' 用于抽象概念不精确，'taxpayer' 太宽泛，缺乏数据或机制说明",
            "句子空洞，'contribute to the economy' 过于宽泛，无具体说明如何贡献，逻辑跳跃",
            "'allowed to choose' 过于简单，'well funded' 缺乏具体性，'provided that' 反复使用，显得机械",
        )
        val fixes = realIssues.map { com.txw.ielts.llm.SentenceFix("s", it, "b") }
        assertEquals("这 5 条都不是语法问题", 0, ScoreSanitizer.grammarIssueCount(fixes))

        val s = ScoreSanitizer.sanitizeWriting(
            tr = 7.0, cc = 8.0, lr = 8.0, gra = 8.0, overall = 8.0,
            wordCount = 269, task = "task2",
            essayText = "no evidence markers here at all just prose",
            sentenceFixCount = fixes.size,
            grammarIssueCount = ScoreSanitizer.grammarIssueCount(fixes),
            hasFixes = true,
        )
        assertEquals("GRA 不该是 6.5", 7.5, s.gra, 0.0)
        assertEquals(7.0, s.tr, 0.0) // 零例证仍然生效
        assertEquals(7.5, s.overall, 0.0)
    }

    @Test
    fun `逐句改法过滤空条_未改动与重复条目`() {
        // 4B 被提示词逼着列 2–5 条时，常拿同一句话交差：只改大小写/句末标点，甚至原样返回。
        // 这些条目会变成「原句 → 原句」的展示，并沉淀成正面=背面的改错卡。
        val raw = """
            {"TR":7,"CC":7,"LR":7,"GRA":7,"overall":7,
             "sentence_fixes":[
               {"sentence":"I go to school yesterday.","issue":"时态","better":"I went to school yesterday."},
               {"sentence":"This is fine.","issue":"无需修改","better":"this is fine"},
               {"sentence":"Another sentence.","issue":"无需修改","better":"Another sentence!"},
               {"sentence":"   ","issue":"空原句","better":"Real better."},
               {"sentence":"No rewrite.","issue":"空改写","better":"   "},
               {"sentence":"I go to school yesterday.","issue":"重复","better":"Yesterday I went to school."}
             ]}
        """.trimIndent()
        val eval = ScoreSanitizer.toWritingEval(raw, wordCount = 300, essay = "For example, 60% of them did.")
        assertEquals(1, eval.sentenceFixes.size)
        assertEquals("I go to school yesterday.", eval.sentenceFixes[0].sentence)
        assertEquals("时态", eval.sentenceFixes[0].issue)
        assertEquals("I went to school yesterday.", eval.sentenceFixes[0].better)
    }

    @Test
    fun `全是未改动条目时不算列过错误`() {
        // 没有真实改写 = 模型没找到错误，GRA 不应按「列了错误」封顶。
        val raw = """
            {"TR":8,"CC":8,"LR":8,"GRA":8,"overall":8,
             "top_fixes":["表达更具体"],
             "sentence_fixes":[{"sentence":"Same.","issue":"无需修改","better":"same"}]}
        """.trimIndent()
        val eval = ScoreSanitizer.toWritingEval(raw, wordCount = 300, essay = "For example, 60% of them did.")
        assertTrue(eval.sentenceFixes.isEmpty())
        assertEquals(8.0, eval.gra, 0.0)
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

    @Test
    fun `Task2 零例证时 TR 封顶 7_0`() {
        val essay = "Some people argue that students should choose their own majors. " +
            "Others believe only practical subjects matter. I believe both sides have merit."
        // 两处以上例证：不压
        val withExample = ScoreSanitizer.sanitizeWriting(
            8.0, 7.0, 7.0, 7.0, 8.0, wordCount = 300,
            essayText = "$essay For example, many graduates switch jobs, and about 60% report regret.",
        )
        assertEquals(8.0, withExample.tr, 0.0)
        // 只有一处例证：压到 7.5
        val one = ScoreSanitizer.sanitizeWriting(
            8.0, 7.0, 7.0, 7.0, 8.0, wordCount = 300, essayText = "$essay For example, many graduates.",
        )
        assertEquals(7.5, one.tr, 0.0)
        // 零例证：压到 7.0
        val none = ScoreSanitizer.sanitizeWriting(
            8.0, 7.0, 7.0, 7.0, 8.0, wordCount = 300, essayText = essay,
        )
        assertEquals(7.0, none.tr, 0.0)
        // 字数不足时走字数规则，不被例证规则改写
        val short = ScoreSanitizer.sanitizeWriting(
            8.0, 7.0, 7.0, 7.0, 8.0, wordCount = 200, essayText = essay,
        )
        assertEquals(5.0, short.tr, 0.0)
    }

    @Test
    fun `例证标记统计不会被大小写和标点搞错`() {
        assertEquals(0, ScoreSanitizer.evidenceKinds("Universities should teach skills."))
        assertEquals(1, ScoreSanitizer.evidenceKinds("Many fields, such as law, need passion."))
        assertEquals(1, ScoreSanitizer.evidenceKinds("Many fields, e.g. law, need passion."))
        assertEquals(1, ScoreSanitizer.evidenceKinds("About 60% of graduates switch jobs."))
        assertEquals(2, ScoreSanitizer.evidenceKinds("For instance, about 60% of graduates switch jobs."))
    }

    @Test
    fun `兜底解析不能丢掉 sentence_fixes 和 GRA 封顶`() {
        // 复现真机情况：top_fixes 里带裸引号 → 整体 JSONObject 解析失败 → 走 fallbackParse
        val broken = """
            {"sentence_fixes":[{"sentence":"Moreover a university is not a factory.",
            "issue":"过度重复使用连接词","better":"Moreover, universities are not factories."}],
            "GRA":8,"LR":8,"TR":8,"CC":8,"overall":8,
            "top_fixes":[""bad quote start],"polished":"x"}
        """.trimIndent()
        val eval = ScoreSanitizer.toWritingEval(broken, wordCount = 300, essay = "no example markers here")
        // 逐句改法必须活下来
        assertEquals(1, eval.sentenceFixes.size)
        assertEquals("Moreover, universities are not factories.", eval.sentenceFixes[0].better)
        // 列了 1 条错误 → GRA ≤ 7.5（真机 bug：这里曾是 8.0）
        assertEquals(7.5, eval.gra, 0.0)
    }

    @Test
    fun `口语缺 P 时按三项均值估算而不是整次评分失败`() {
        // 真机 id=21：模型返回完整口语 JSON 但漏了 P，旧代码直接抛错，整次评分作废。
        val raw = """
            {"sentence_fixes":[],"GRA":6.5,"LR":7,"FC":7,"overall":7,
             "top_fixes":["a","b","c"],"improved":"x"}
        """.trimIndent()
        val eval = ScoreSanitizer.toSpeakingEval(raw, wordCount = 120)
        // (7 + 7 + 6.5) / 3 = 6.833 → 7.0
        assertEquals(7.0, eval.pronunciation, 0.0)
        assertTrue(eval.estimatedP)
        // 估算说明不能混进 fixes，否则会被「高频问题」统计成发音弱点
        assertTrue(eval.fixes.none { it.contains("估算") })
    }

    @Test
    fun `口语 P 用别名 PR 也能识别`() {
        val raw = """{"GRA":7,"LR":7,"FC":7,"PR":7.5,"overall":7,"top_fixes":["a"]}"""
        val eval = ScoreSanitizer.toSpeakingEval(raw, wordCount = 120)
        assertEquals(7.5, eval.pronunciation, 0.0)
        // 明确给了 P，就不该再插一条「估算」说明
        assertTrue(!eval.estimatedP)
        assertTrue(eval.fixes.none { it.contains("估算") })
    }
}
