package com.txw.ielts

import com.txw.ielts.data.Card
import com.txw.ielts.data.FeedbackCap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 反馈卡淘汰回归：只删复习过的，未复习的一张不删；复习次数少的、创建早的先删。
 * 这条策略错了，要么反馈被悄悄删掉（用户没看到批评），要么卡池无限膨胀。
 */
class FeedbackCapTest {

    private fun card(id: Long, reps: Int, createdAt: Long, source: String = "error") =
        Card(id = id, front = "f$id", source = source, reps = reps, createdAt = createdAt)

    @Test
    fun `未超上限不淘汰`() {
        val cards = (1L..10L).map { card(it, reps = 1, createdAt = it) }
        assertTrue(FeedbackCap.victims(cards, cap = 10).isEmpty())
    }

    @Test
    fun `从未复习的反馈卡永不淘汰`() {
        val cards = listOf(
            card(1, reps = 0, createdAt = 1),
            card(2, reps = 0, createdAt = 2),
            card(3, reps = 2, createdAt = 3),
        )
        assertEquals(listOf(3L), FeedbackCap.victims(cards, cap = 2))
    }

    @Test
    fun `复习次数少的先淘汰，同次数删更早创建的`() {
        val cards = listOf(
            card(1, reps = 5, createdAt = 1),
            card(2, reps = 1, createdAt = 10),
            card(3, reps = 1, createdAt = 5),
            card(4, reps = 0, createdAt = 2),
        )
        assertEquals(listOf(3L, 2L), FeedbackCap.victims(cards, cap = 2))
    }

    @Test
    fun `超限数量大于已复习卡时只删已复习部分`() {
        val cards = listOf(
            card(1, reps = 0, createdAt = 1),
            card(2, reps = 1, createdAt = 2),
        )
        assertEquals(listOf(2L), FeedbackCap.victims(cards, cap = 0))
    }
}
