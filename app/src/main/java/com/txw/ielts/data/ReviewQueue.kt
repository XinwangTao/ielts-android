package com.txw.ielts.data

/**
 * 评分后的队列推进。
 *
 * SM-2 把「Again」排到明天，但这意味着用户刚答错的卡当场消失、要等一天才能再见到，
 * 反馈闭环是断的。这里补一层当场巩固：Again 的卡不离开本轮队列，而是隔几张再问一次；
 * 答对/困难/简单才真正前进。队列只剩一张时退化为立即重问，直到评到非 Again。
 */
object ReviewQueue {
    /** 答错后至少隔几张再出现（队列不够长时自然退化为靠后/立即）。 */
    const val RELEARN_GAP = 3

    data class Step(val queue: List<Card>, val index: Int, val done: Boolean)

    fun afterGrade(queue: List<Card>, index: Int, grade: Grade): Step {
        if (queue.isEmpty()) return Step(queue, 0, true)
        val at = index.coerceIn(0, queue.lastIndex)
        if (grade != Grade.AGAIN) {
            val next = at + 1
            return if (next >= queue.size) Step(queue, queue.size, true) else Step(queue, next, false)
        }
        // 取出当前卡，插到 at + RELEARN_GAP 处（上限为队尾），保持其余卡顺序。
        val rest = queue.toMutableList()
        val card = rest.removeAt(at)
        val insertAt = (at + RELEARN_GAP).coerceAtMost(rest.size)
        rest.add(insertAt, card)
        return Step(rest, at.coerceAtMost(rest.lastIndex), false)
    }
}
