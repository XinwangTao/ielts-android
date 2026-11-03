package com.txw.ielts

import com.txw.ielts.data.Session
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 题型判定回归测试。
 *
 * 题型决定评分用 Task 1 还是 Task 2 的评分标准（TR 要求、字数门槛完全不同），
 * 猜错的代价是把图表作文按议论文批。v3 起题型入库，旧数据才需要启发式回退。
 */
class TaskGuessTest {

    @Test
    fun `图表类题干判为 task1`() {
        assertEquals("task1", guessTask("The chart below shows the number of visitors to three museums."))
        assertEquals("task1", guessTask("The graph below gives information about coffee sales."))
        assertEquals("task1", guessTask("The table below shows the proportion of households."))
        assertEquals("task1", guessTask("The diagram illustrates the process of making paper."))
        assertEquals("task1", guessTask("The maps show the town centre in 1990 and today."))
    }

    @Test
    fun `议论文题干判为 task2`() {
        assertEquals(
            "task2",
            guessTask("Some people believe that university education should be free. Discuss both views."),
        )
        assertEquals("task2", guessTask("Many people think technology makes life more complex."))
        // 题干里出现 chart 但同时要求 discuss：是议论文，不能误判成图表题
        assertEquals("task2", guessTask("The chart is often cited. Discuss both views and give your opinion."))
    }

    @Test
    fun `题型字段优先于启发式`() {
        // 入库的题型压过题干里的关键词：哪怕题干含 discuss，字段说 task1 就按 task1 评分
        val s = Session(topic = "Discuss the chart", task = "task1")
        assertEquals("task1", s.resolvedTask())
    }

    @Test
    fun `旧数据无题型时回退启发式`() {
        assertEquals("task1", Session(topic = "The chart below shows sales.").resolvedTask())
        assertEquals("task2", Session(topic = "Discuss both views.").resolvedTask())
    }
}
