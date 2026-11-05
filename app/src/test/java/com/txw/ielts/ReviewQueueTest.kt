package com.txw.ielts

import com.txw.ielts.data.Card
import com.txw.ielts.data.Grade
import com.txw.ielts.data.ReviewQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 复习队列推进回归：Again 必须留在本轮（隔几张再问），其余评分才前进。
 * 这条链路一旦退化，答错的卡会直接消失到明天，AI 批注卡等于白沉淀。
 */
class ReviewQueueTest {

    private fun queue(vararg ids: Long): List<Card> = ids.map { Card(id = it, front = "card$it") }

    @Test
    fun `答对后前进一张`() {
        val step = ReviewQueue.afterGrade(queue(1, 2, 3), 0, Grade.GOOD)
        assertEquals(1, step.index)
        assertFalse(step.done)
        assertEquals(listOf(1L, 2L, 3L), step.queue.map { it.id })
    }

    @Test
    fun `最后一张答对即完成`() {
        val step = ReviewQueue.afterGrade(queue(1, 2, 3), 2, Grade.HARD)
        assertEquals(3, step.index)
        assertTrue(step.done)
    }

    @Test
    fun `答错不前进且卡移到本轮靠后位置`() {
        val step = ReviewQueue.afterGrade(queue(1, 2, 3, 4, 5), 0, Grade.AGAIN)
        assertFalse(step.done)
        assertEquals(0, step.index)
        assertEquals(listOf(2L, 3L, 4L, 1L, 5L), step.queue.map { it.id })
    }

    @Test
    fun `队列不够长时答错卡排到队尾`() {
        val step = ReviewQueue.afterGrade(queue(1, 2), 0, Grade.AGAIN)
        assertEquals(listOf(2L, 1L), step.queue.map { it.id })
        assertEquals(0, step.index)
    }

    @Test
    fun `单张卡答错立即重问而不是结束`() {
        val step = ReviewQueue.afterGrade(queue(1), 0, Grade.AGAIN)
        assertFalse(step.done)
        assertEquals(0, step.index)
        assertEquals(listOf(1L), step.queue.map { it.id })
    }

    @Test
    fun `最后一张答错立即重问`() {
        val step = ReviewQueue.afterGrade(queue(1, 2, 3), 2, Grade.AGAIN)
        assertFalse(step.done)
        assertEquals(3, step.queue.size)
        assertEquals(2, step.index)
        assertEquals(3L, step.queue[step.index].id)
        assertTrue(step.index in 0..2)
    }

    @Test
    fun `不修改调用方传入的队列`() {
        val original = queue(1, 2, 3, 4)
        ReviewQueue.afterGrade(original, 1, Grade.AGAIN)
        assertEquals(listOf(1L, 2L, 3L, 4L), original.map { it.id })
    }

    @Test
    fun `空队列直接完成`() {
        assertTrue(ReviewQueue.afterGrade(emptyList(), 0, Grade.GOOD).done)
    }
}
