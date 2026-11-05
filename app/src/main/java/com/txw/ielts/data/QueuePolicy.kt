package com.txw.ielts.data

/**
 * 每日队列的名额分配。
 *
 * 原实现「到期卡 + 每日新卡额度」两段各自取数：到期卡 55 张、每日新卡 20 张时，
 * 队列会变成 75 张，超出 limit=60 的硬上限，用户一打开练习就是看不到头的队列。
 * 新卡只能补到期卡剩下的位置，同时仍受「今日已学新卡」额度约束。
 */
object QueuePolicy {
    /** 到期卡占位后，还能补几张新卡；永远不会为负。 */
    fun freshSlots(limit: Int, dueCount: Int, dailyLeft: Int): Int =
        minOf(limit - dueCount, dailyLeft).coerceAtLeast(0)
}
