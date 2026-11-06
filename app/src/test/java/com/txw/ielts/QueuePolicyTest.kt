package com.txw.ielts

import com.txw.ielts.data.QueuePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 队列名额：到期卡占位后新卡只能补空位，且不能突破每日新卡额度。 */
class QueuePolicyTest {

    @Test
    fun `到期卡少时新卡按每日额度补`() {
        assertEquals(20, QueuePolicy.freshSlots(limit = 60, dueCount = 10, dailyLeft = 20))
    }

    @Test
    fun `到期卡占满时不再补新卡`() {
        assertEquals(0, QueuePolicy.freshSlots(limit = 60, dueCount = 60, dailyLeft = 20))
        assertEquals(0, QueuePolicy.freshSlots(limit = 60, dueCount = 70, dailyLeft = 20))
    }

    @Test
    fun `每日新卡额度用完后不再补`() {
        assertEquals(0, QueuePolicy.freshSlots(limit = 60, dueCount = 0, dailyLeft = 0))
        assertEquals(0, QueuePolicy.freshSlots(limit = 60, dueCount = 0, dailyLeft = -3))
    }

    @Test
    fun `名额取两种约束的较小值`() {
        assertEquals(5, QueuePolicy.freshSlots(limit = 60, dueCount = 55, dailyLeft = 20))
        assertEquals(3, QueuePolicy.freshSlots(limit = 10, dueCount = 7, dailyLeft = 20))
    }

    // —— 新卡名额拆分：反馈卡先占，剩下随机 ——

    @Test
    fun `反馈卡优先占满新卡名额`() {
        val split = QueuePolicy.freshSplit(slots = 10, feedbackAvailable = 8)
        assertEquals(8, split.feedback)
        assertEquals(2, split.random)
    }

    @Test
    fun `反馈卡多于名额时只占满名额`() {
        val split = QueuePolicy.freshSplit(slots = 5, feedbackAvailable = 30)
        assertEquals(5, split.feedback)
        assertEquals(0, split.random)
    }

    @Test
    fun `反馈卡超过每轮上限时剩下的留给随机新卡`() {
        // 一次练习攒了 40 条批注：本轮只拿 10 张，其余名额照常给词卡，
        // 否则整轮复习全变成改错，日常新卡永远排不进来。
        val split = QueuePolicy.freshSplit(slots = 20, feedbackAvailable = 40)
        assertEquals(QueuePolicy.FEEDBACK_FIRST_MAX, split.feedback)
        assertEquals(10, split.random)
    }

    @Test
    fun `没有反馈卡时名额全给随机新卡`() {
        val split = QueuePolicy.freshSplit(slots = 12, feedbackAvailable = 0)
        assertEquals(0, split.feedback)
        assertEquals(12, split.random)
    }

    @Test
    fun `没有名额时不分配也不出现负数`() {
        assertEquals(QueuePolicy.FreshSplit(0, 0), QueuePolicy.freshSplit(slots = 0, feedbackAvailable = 5))
        assertEquals(QueuePolicy.FreshSplit(0, 0), QueuePolicy.freshSplit(slots = -3, feedbackAvailable = 5))
    }

    @Test
    fun `两部分之和永远等于名额`() {
        for (slots in 0..25) {
            for (available in 0..25) {
                val split = QueuePolicy.freshSplit(slots, available)
                assertEquals("slots=$slots available=$available", slots, split.feedback + split.random)
                assertTrue(split.feedback >= 0 && split.random >= 0)
            }
        }
    }

    // —— 「今日新卡」显示口径必须与队列一致 ——

    @Test
    fun `反馈卡多到超过上限时今日新卡不算多出来的`() {
        // 攒了 40 条批注、没有普通新卡：进队列只会出现 10 张，页面不能写 40 或 20。
        assertEquals(10, QueuePolicy.newCardAvailable(dailyLeft = 20, feedbackPending = 40, otherNew = 0))
    }

    @Test
    fun `反馈卡未超上限时今日新卡等于两类之和`() {
        assertEquals(12, QueuePolicy.newCardAvailable(dailyLeft = 20, feedbackPending = 8, otherNew = 4))
    }

    @Test
    fun `今日额度是硬上限`() {
        assertEquals(20, QueuePolicy.newCardAvailable(dailyLeft = 20, feedbackPending = 8, otherNew = 100))
        assertEquals(0, QueuePolicy.newCardAvailable(dailyLeft = 0, feedbackPending = 8, otherNew = 100))
        assertEquals(0, QueuePolicy.newCardAvailable(dailyLeft = -5, feedbackPending = 8, otherNew = 1))
    }

    @Test
    fun `今日新卡与队列实际张数一致`() {
        // 任意反馈/普通新卡组合下，队列能给出的新卡数 = freshSplit 的两部分之和（不超过每日额度）。
        for (feedback in 0..30) {
            for (others in 0..30) {
                for (left in 0..8) {
                    val expected = QueuePolicy.newCardAvailable(left, feedback, others)
                    val slots = minOf(left, feedback + others)
                    val split = QueuePolicy.freshSplit(slots, feedback)
                    val inQueue = split.feedback + minOf(split.random, others)
                    assertEquals("feedback=$feedback others=$others left=$left", expected, inQueue)
                }
            }
        }
    }
}
