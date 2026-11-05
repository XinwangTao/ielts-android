package com.txw.ielts.data

/**
 * AI 反馈卡（练习批注 error + 逐句改法 fix）的淘汰策略。
 *
 * 这两类卡是「用户还没消化的反馈」，所以不进普通 AI 池的淘汰（见 CardDao.evictGenerated），
 * 但也不能无限增长：一次评分就沉淀 5~8 张，几十次练习后列表会长到没有意义。
 * 这里只淘汰**已经复习过**的反馈卡：复习次数少的、创建早的先走；
 * 从未复习的一张都不删 —— 没练就被删等于反馈丢失。
 */
object FeedbackCap {
    /** 反馈卡总上限（error + fix）。按每次练习 5~8 张估算，约 40~60 次练习。 */
    const val CAP = 300

    /** 超过上限时需要淘汰的卡 id；不足上限返回空。 */
    fun victims(cards: List<Card>, cap: Int = CAP): List<Long> {
        val over = cards.size - cap
        if (over <= 0) return emptyList()
        return cards.asSequence()
            .filter { it.reps > 0 }
            .sortedWith(compareBy<Card> { it.reps }.thenBy { it.createdAt })
            .take(over)
            .map { it.id }
            .toList()
    }
}
