package com.txw.ielts

import com.txw.ielts.data.MockScore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 模考总分合成：权重与缺段处理都会直接影响首页/结果页展示。 */
class MockScoreTest {

    @Test
    fun `写作按 Task1 一 比 Task2 二 加权`() {
        // (6.0 + 2×7.0) / 3 = 6.666… → 6.5
        assertEquals(6.5, MockScore.writing(6.0, 7.0)!!, 1e-9)
        // (7.0 + 2×6.0) / 3 = 6.333… → 6.5
        assertEquals(6.5, MockScore.writing(7.0, 6.0)!!, 1e-9)
    }

    @Test
    fun `缺一段不合成总分`() {
        assertNull(MockScore.writing(6.5, null))
        assertNull(MockScore.writing(null, 7.0))
    }

    @Test
    fun `口语取平均并吸附 0_5 步长`() {
        assertEquals(6.5, MockScore.average(listOf(6.0, 7.0))!!, 1e-9)
        assertNull(MockScore.average(emptyList()))
    }
}
