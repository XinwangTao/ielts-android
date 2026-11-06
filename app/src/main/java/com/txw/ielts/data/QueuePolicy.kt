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

    /**
     * 每轮新卡名额里最多先放几张「练习反馈卡」（批注 error / 逐句改法 fix）。
     *
     * 一次评分沉淀 5~8 张反馈卡，两轮的量封顶：既保证刚收到的纠错能马上复现，
     * 又不至于整轮复习都变成改错，把日常词汇新卡挤没。
     */
    const val FEEDBACK_FIRST_MAX = 10

    /** 新卡名额的拆分：先给反馈卡，剩下的给随机新卡。 */
    data class FreshSplit(val feedback: Int, val random: Int)

    /**
     * 反馈卡与新卡的分配。
     *
     * 为什么要拆：反馈卡入库时 `reps=0`，和 168 张内置词汇卡一样算「新卡」，
     * 而新卡是 `ORDER BY RANDOM()` 取的——刚做完一篇写作的 8 条批注，
     * 要跟内置词库抢每日那十几个名额，实际经常一张都轮不到，
     * 用户看到的「复习」全是背单词，自己刚错的地方反而没人提醒。
     * 现在反馈卡按时间倒序先占名额（上限 [FEEDBACK_FIRST_MAX]），其余名额照旧随机。
     */
    fun freshSplit(
        slots: Int,
        feedbackAvailable: Int,
        feedbackMax: Int = FEEDBACK_FIRST_MAX,
    ): FreshSplit {
        if (slots <= 0) return FreshSplit(0, 0)
        if (feedbackAvailable <= 0 || feedbackMax <= 0) return FreshSplit(0, slots)
        val feedback = minOf(slots, feedbackAvailable, feedbackMax)
        return FreshSplit(feedback, slots - feedback)
    }

    /**
     * 语料页「今日新卡」该显示几张：和 [freshSplit] 同一口径，免得显示 20 张、进去只有 10 张。
     * 反馈卡多到超过每轮上限时，多出来的这轮不会出现，就不该算进今天的量。
     */
    fun newCardAvailable(
        dailyLeft: Int,
        feedbackPending: Int,
        otherNew: Int,
        feedbackMax: Int = FEEDBACK_FIRST_MAX,
    ): Int {
        if (dailyLeft <= 0) return 0
        val feedback = minOf(feedbackPending.coerceAtLeast(0), feedbackMax)
        return minOf(feedback + otherNew.coerceAtLeast(0), dailyLeft)
    }
}
