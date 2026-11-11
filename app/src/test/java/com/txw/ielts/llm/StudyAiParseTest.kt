package com.txw.ielts.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模板 / 骨架解析是纯逻辑：模型会把 JSON 写坏、加前缀、包成 markdown，
 * 这里锁住「至少还能还原出一份可用结构」的兜底行为。
 */
class StudyAiParseTest {

    @Test
    fun `skeleton parses json paragraphs`() {
        val raw = """
            {"paragraphs":[
              {"label":"Introduction","lines":["It is often argued that 【A】.","While I accept that 【B】, I would argue that 【C】."],"hint":"先写两派再亮立场"},
              {"label":"Body 1","lines":["First and foremost, 【理由】."],"hint":"展开第一点"}
            ]}
        """.trimIndent()
        val s = StudyAi.parseSkeleton(raw)
        assertEquals(2, s.paragraphs.size)
        assertEquals("Introduction", s.paragraphs[0].label)
        assertEquals(2, s.paragraphs[0].lines.size)
        assertEquals("先写两派再亮立场", s.paragraphs[0].hint)
    }

    @Test
    fun `skeleton falls back to blank-line blocks`() {
        val raw = "It is often argued that 【A】.\n\nFirst and foremost, 【B】.\n\nIn conclusion, 【C】."
        val s = StudyAi.parseSkeleton(raw)
        assertEquals(3, s.paragraphs.size)
        assertEquals("Paragraph 2", s.paragraphs[1].label)
        assertTrue(s.paragraphs[2].lines.first().startsWith("In conclusion"))
    }

    @Test
    fun `skeleton draft text keeps paragraph breaks`() {
        val s = EssaySkeleton(
            listOf(
                SkeletonParagraph("P1", listOf("line1"), ""),
                SkeletonParagraph("P2", listOf("line2"), ""),
            )
        )
        assertEquals("line1\n\nline2", s.toDraftText())
    }

    @Test
    fun `template fill parses json and text`() {
        val json = StudyAi.parseTemplateFill("""{"filled":"In my view, fees should be means-tested.","why":"用于亮立场"}""")
        assertEquals("In my view, fees should be means-tested.", json.text)
        assertEquals("用于亮立场", json.why)

        val text = StudyAi.parseTemplateFill("Filled: First and foremost, cost matters.\nWhy: 主体段首句")
        assertEquals("First and foremost, cost matters.", text.text)
        assertEquals("主体段首句", text.why)
    }
}
