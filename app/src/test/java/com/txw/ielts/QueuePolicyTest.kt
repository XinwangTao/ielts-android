package com.txw.ielts

import com.txw.ielts.data.QueuePolicy
import org.junit.Assert.assertEquals
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
}
