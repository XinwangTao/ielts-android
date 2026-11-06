package com.txw.ielts

import com.txw.ielts.llm.PromptBudget
import com.txw.ielts.llm.ScorePrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 评分类请求的 user 消息装配。
 *
 * 钉住的核心是「再长的输入也不会整段发出去」：云端口语评分曾把 ASR 原始转写直接拼进提示词
 * （`transcriptBody` 算好了没用），本地路径却裁了——同一段回答在两条通道上表现不一致。
 * 这里逐条锁死：裁中间、留两头、字数/语速/填充词仍按原文算。
 */
class ScorePromptTest {

    private val conclusion = "CONCLUSION: In short, the benefits outweigh the drawbacks."

    private fun longEssay(): String =
        "INTRO: I agree with this view about city life. " +
            "Body sentence with supporting detail about transport. ".repeat(600) +
            conclusion

    @Test
    fun `写作短输入原样保留并给出真实字数`() {
        val essay = "Some people think tests are useless. " +
            "I disagree because exams measure progress."
        val prompt = ScorePrompt.writing("Some people think tests are useless.", essay)
        assertTrue(prompt.startsWith("Task: Some people think tests are useless.\nWord count: 12\n\nEssay:\nSome people"))
        assertTrue(prompt.endsWith(essay.trim()))
        assertFalse(prompt.contains("middle trimmed"))
    }

    @Test
    fun `写作超长作文保留开头与结论`() {
        val essay = longEssay()
        val prompt = ScorePrompt.writing("Long essay topic", essay)
        assertTrue(prompt.contains("INTRO: I agree with this view about city life."))
        assertTrue("结论段不能被裁掉：${prompt.takeLast(80)}", prompt.contains(conclusion))
        assertTrue(prompt.contains("middle trimmed"))
        // 字数按原文算：裁过的正文不能让模型以为作文变短了（涉及 TR 封顶规则）。
        val words = ScorePrompt.wordCount(essay)
        assertTrue("长作文字数应远超正文：$words", words > 4000)
        assertTrue(prompt.contains("Word count: $words"))
    }

    @Test
    fun `写作超长题目也要裁`() {
        val topic = "Pasted passage about cities. ".repeat(4000)
        val prompt = ScorePrompt.writing(topic, "Short essay.")
        val topicLine = prompt.substringAfter("Task: ").substringBefore("\nWord count:")
        assertTrue(topicLine.length < topic.length)
        assertTrue(topicLine.contains("middle trimmed"))
        assertTrue("整条 user 消息要在预算内", PromptBudget.estimateTokens(prompt) <= PromptBudget.SCORE_INPUT_TOKENS + 100)
    }

    @Test
    fun `写作弱点提示只在有记录时追加`() {
        val plain = ScorePrompt.writing("Topic", "Essay body.")
        assertFalse(plain.contains("recurring weaknesses"))
        val hinted = ScorePrompt.writing("Topic", "Essay body.", listOf("主谓一致", "冠词"))
        assertTrue(hinted.contains("recurring weaknesses"))
        assertTrue(hinted.contains("主谓一致; 冠词"))
        assertTrue(hinted.indexOf("recurring weaknesses") > hinted.indexOf("Essay body."))
    }

    @Test
    fun `Task1 图表数据与报告都按预算裁`() {
        val chart = "Bar chart data point. ".repeat(5000)
        val report = "The chart shows an upward trend. ".repeat(800) + "Overall, sales doubled."
        val prompt = ScorePrompt.writingTask1(chart, report)
        assertTrue(prompt.startsWith("Chart data:\n"))
        assertTrue(prompt.contains("Overall, sales doubled."))
        assertTrue(prompt.contains("middle trimmed"))
        assertTrue(prompt.contains("Word count: ${ScorePrompt.wordCount(report)}"))
        assertTrue(PromptBudget.estimateTokens(prompt) <= PromptBudget.SCORE_INPUT_TOKENS * 2 + 200)
    }

    @Test
    fun `口语短转写原样保留并算出语速与填充词`() {
        val transcript = "I um want to talk about my uh hometown. It is er quite small."
        val prompt = ScorePrompt.speaking("Part 1", "Where is your hometown?", transcript, 60)
        assertTrue(prompt.contains("Speaking time: 60s"))
        assertTrue(prompt.contains("Word count: 14"))
        assertTrue(prompt.contains("Words per minute: 14"))
        assertTrue(prompt.contains("Filler words (um/uh/er): 3"))
        assertTrue(prompt.endsWith(transcript))
    }

    /**
     * 这条是本次修复的正题：本地路径一直裁、云端路径漏裁。
     */
    @Test
    fun `口语超长转写裁中间`() {
        val head = "First sentence of the answer about my city. "
        // 中部用互不相同的词，才能验证「被裁掉的是中间」而不是碰巧重复的片段。
        val middle = (1..10000).joinToString(" ") { "filler$it" } + " "
        val tail = "Last sentence closes with a short conclusion."
        val transcript = head + middle + tail

        val prompt = ScorePrompt.speaking("Part 2", "Describe a city", transcript, 600)

        assertTrue(prompt.contains("First sentence of the answer about my city."))
        assertTrue("结尾必须保留：${prompt.takeLast(120)}", prompt.contains(tail))
        assertTrue(prompt.contains("middle trimmed"))
        assertFalse("中部内容不该被整段发出去", prompt.contains(" filler5000 "))
        assertFalse(prompt.contains(" filler7000 "))
        // 指标取自原文，不受裁剪影响：
        assertTrue(prompt.contains("Word count: ${ScorePrompt.wordCount(transcript)}"))
        assertTrue(PromptBudget.estimateTokens(prompt) <= PromptBudget.SCORE_INPUT_TOKENS + 100)
    }

    @Test
    fun `时长为零时不编造语速`() {
        assertEquals(0, ScorePrompt.wordsPerMinute(120, 0))
        assertEquals(0, ScorePrompt.wordsPerMinute(120, -5))
        assertEquals(60, ScorePrompt.wordsPerMinute(200, 200))
    }

    @Test
    fun `填充词只统计独立词`() {
        assertEquals(0, ScorePrompt.fillerCount("The number of terms ahead of schedule."))
        assertEquals(0, ScorePrompt.wordCount(""))
        assertEquals(4, ScorePrompt.fillerCount("Um, I uh think erm it is hmm fine."))
    }
}
