package com.txw.ielts

import com.txw.ielts.llm.ReviewAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReviewActionTest {

    @Test
    fun `中文写作建议跳到写作`() {
        assertEquals(
            "writing",
            ReviewAction.forTask("重写一段写作段落", "衔接与分段薄弱", "先写主题句和结论句"),
        )
    }

    @Test
    fun `口语建议跳到口语`() {
        assertEquals(
            "speaking",
            ReviewAction.forTask("改个人经历描述", "搭配错误频繁", "在口语练习里用三个句子描述一次经历"),
        )
    }

    @Test
    fun `词汇优先于说`() {
        // 「背 5 个搭配」里也出现「说」，但本质是词汇任务。
        assertEquals(
            "vocab",
            ReviewAction.forTask("背 5 个搭配", "用词不地道", "把 practical value 换成更自然的说法"),
        )
    }

    @Test
    fun `听力与阅读各自归位`() {
        assertEquals("listening", ReviewAction.forTask("精听一套听力", "", ""))
        assertEquals("reading", ReviewAction.forTask("做一篇阅读", "", ""))
    }

    @Test
    fun `英文建议也能识别`() {
        assertEquals("writing", ReviewAction.forTask("Rewrite one Task 2 body paragraph", "", ""))
        assertEquals("speaking", ReviewAction.forTask("Record a Part 2 answer", "", ""))
    }

    @Test
    fun `判定不了就不给入口`() {
        assertNull(ReviewAction.forTask("保持节奏", "每天 20 分钟", "别熬夜"))
        assertNull(ReviewAction.forTask("", "", ""))
    }
}
