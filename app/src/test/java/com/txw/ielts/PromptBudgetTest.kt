package com.txw.ielts

import com.txw.ielts.llm.PromptBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 提示词输入预算：估算、裁中间、按优先级装配。
 *
 * 这层出错不是崩溃，而是「模型看到的作文没有结尾 / 复盘不知道还有几天考试」，
 * 结果页看起来一切正常但建议跑偏，所以每条规则都钉住。
 */
class PromptBudgetTest {

    private fun essay(topic: String) = "INTRO: I agree with this view. " +
        "Body sentence about $topic. ".repeat(400) +
        "CONCLUSION: In short, the benefits outweigh the drawbacks."

    @Test
    fun `token 估算区分中日韩与拉丁文`() {
        assertEquals(0, PromptBudget.estimateTokens(""))
        assertEquals(2, PromptBudget.estimateTokens("hello"))
        assertEquals(4, PromptBudget.estimateTokens("你好世界"))
        // "Band 6.5 " 9 个窄字符 → 3 token；「口语」2 个宽字符 → 2 token
        assertEquals(5, PromptBudget.estimateTokens("Band 6.5 口语"))
    }

    @Test
    fun `预算内的原文原样返回`() {
        val text = "Task: Some people think tests are useless."
        val clipped = PromptBudget.clip(text, 200)
        assertEquals(text, clipped.text)
        assertEquals(0, clipped.removedChars)
    }

    @Test
    fun `超预算时保留开头与结尾并标注省略字数`() {
        val text = essay("public transport")
        assertTrue(PromptBudget.estimateTokens(text) > 100)
        val clipped = PromptBudget.clip(text, 100)
        assertTrue(clipped.text.startsWith("INTRO: I agree with this view."))
        assertTrue(clipped.text.endsWith("the benefits outweigh the drawbacks."))
        assertTrue(clipped.text.contains("middle trimmed:"))
        assertTrue(clipped.text.contains("characters"))
        assertTrue(clipped.removedChars > 0)
        assertTrue(PromptBudget.estimateTokens(clipped.text) <= 100)
    }

    @Test
    fun `英文裁剪不切断单词`() {
        val text = "alpha bravo charlie delta echo foxtrot golf hotel india juliet kilo lima " +
            "mike november oscar papa quebec romeo sierra tango uniform victor whiskey xray yankee zulu ".repeat(40)
        val clipped = PromptBudget.clip(text, 60)
        val head = clipped.text.substringBefore(" […")
        val tail = clipped.text.substringAfter("…] ")
        assertTrue("头段应在单词边界结束：${head.takeLast(20)}", head.last().isWhitespace())
        // 尾段要么从完整单词开始，要么本身就是空串
        if (tail.isNotEmpty()) {
            val first = tail.substringBefore(' ').trim()
            assertTrue("尾段首词被切断：$first", text.split(' ').any { it == first })
        }
    }

    @Test
    fun `中文没有空格也能两端保留完整字符`() {
        val text = "开头：我认为应当保留。".repeat(1) + "中间论证".repeat(500) + "结尾：综上，利大于弊。"
        val clipped = PromptBudget.clip(text, 80)
        assertTrue(clipped.text.startsWith("开头"))
        assertTrue(clipped.text.endsWith("利大于弊。"))
        assertTrue(PromptBudget.estimateTokens(clipped.text) <= 80)
    }

    @Test
    fun `预算为零时不保留正文但记下省略字数`() {
        val text = "some text here"
        val clipped = PromptBudget.clip(text, 0)
        assertEquals("", clipped.text)
        assertEquals(text.length, clipped.removedChars)
    }

    @Test
    fun `多段输入在预算内时原样拼接`() {
        val result = PromptBudget.fit(
            listOf(
                PromptBudget.Part("essay", "Essay body", priority = 1),
                PromptBudget.Part("corrections", "Corrections body", priority = 3),
            ),
            200,
        )
        assertEquals("Essay body\n\nCorrections body", result.text)
        assertFalse(result.changed)
        assertEquals(0, result.removedChars)
        assertEquals(result.originalTokens, result.tokens)
    }

    @Test
    fun `超预算先裁低优先级段`() {
        val result = PromptBudget.fit(
            listOf(
                PromptBudget.Part("essay", "Essay:\n" + essay("housing"), priority = 1),
                PromptBudget.Part("corrections", "Corrections:\n- a -> b (tense)", priority = 3),
            ),
            120,
        )
        assertEquals(listOf("essay"), result.trimmed)
        assertTrue(result.text.contains("Corrections:\n- a -> b (tense)"))
        assertTrue(result.text.indexOf("Essay:") < result.text.indexOf("Corrections:"))
        assertTrue(PromptBudget.estimateTokens(result.text) <= 120)
    }

    @Test
    fun `pinned 段排在最后才被裁`() {
        val essayText = essay("education")
        val result = PromptBudget.fit(
            listOf(
                PromptBudget.Part("essay", essayText, priority = 2),
                PromptBudget.Part("hint", "RECURRING WEAKNESS: articles. " + essayText, priority = 2, pinned = true),
            ),
            100,
        )
        assertEquals("essay", result.trimmed.first())
        assertTrue(PromptBudget.estimateTokens(result.text) <= 100)
    }

    @Test
    fun `预算极小时也不越界`() {
        val result = PromptBudget.fit(
            listOf(
                PromptBudget.Part("a", "x ".repeat(2000), priority = 1),
                PromptBudget.Part("b", "y ".repeat(2000), priority = 2),
                PromptBudget.Part("c", "z ".repeat(2000), priority = 3, pinned = true),
            ),
            20,
        )
        assertTrue(PromptBudget.estimateTokens(result.text) <= 20)
        assertEquals(3, result.trimmed.size)
        assertTrue(result.removedChars > 0)
    }

    @Test
    fun `空输入列表返回空文本`() {
        val result = PromptBudget.fit(emptyList(), 100)
        assertEquals("", result.text)
        assertFalse(result.changed)
    }

    @Test
    fun `正常长度的作文不会触发评分输入裁剪`() {
        val normal = "Nowadays, many students study abroad. " +
            "First, they gain independence. Second, they improve their English. " +
            "In conclusion, the advantages outweigh the disadvantages."
        assertEquals(normal, PromptBudget.clipText(normal, PromptBudget.SCORE_INPUT_TOKENS))
        assertTrue(PromptBudget.STUDY_INPUT_TOKENS < PromptBudget.SCORE_INPUT_TOKENS)
    }
}
