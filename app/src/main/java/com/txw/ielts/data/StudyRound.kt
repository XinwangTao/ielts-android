package com.txw.ielts.data

/**
 * 学习页「一轮复习」的状态机（纯逻辑，Composable 只负责渲染）。
 *
 * 为什么要有：原来队列/进度/翻面散在 `MainActivity` 的 `remember` 里，而评级按钮的
 * 「已点击」标志是 Composable 内部的 `remember { mutableStateOf(false) }`——它只被置 true、
 * 从来没人复位。于是评完第一张卡之后，后面每张卡的四个按钮都是灰的（换卡不解锁），
 * 只有退出学习页再进来才恢复；单卡队列选「不认识」当场重问时更是彻底卡死。
 *
 * 这里把状态收成一份可测的快照，锁改用「正在写库的那张卡」表示：
 * 锁只对发起评级的那张卡生效，队列一推进（含 Again 后同一张卡重新出现）就自动解锁，
 * 连点评两次分的问题仍然挡得住。
 */
data class StudyRound(
    val queue: List<Card> = emptyList(),
    val index: Int = 0,
    val revealed: Boolean = false,
    /** 正在写库的那张卡；为 null 表示空闲。 */
    val busyCardId: Long? = null,
    /**
     * 评级流水（按发生顺序，含 Again 后的本轮重问），只用来生成收尾小结。
     * 单独记一份而不是回头读队列：队列里的卡片是**评级前**的快照，
     * 算不出「这张卡重问了没有」；也不能读库，写库与状态机是解耦的。
     */
    val events: List<Pair<Long, Grade>> = emptyList(),
) {
    val card: Card? get() = queue.getOrNull(index)
    val total: Int get() = queue.size
    val finished: Boolean get() = card == null

    /** 这一轮做了什么：结束时由调用方展示一次，别让人莫名其妙被送回语料页。 */
    fun summary(): RoundSummary = RoundSummary.of(events)

    /** 评级按钮是否可点：没有卡，或这张卡正在写库，都不能点。 */
    val canGrade: Boolean get() = card != null && busyCardId != card?.id

    fun reveal(): StudyRound = if (card == null) this else copy(revealed = true)

    /** 发起评级：立刻置灰，直到 [applyGrade] / [unlock] 才恢复，防连点评两次。 */
    fun beginGrade(): StudyRound {
        val c = card ?: return this
        if (busyCardId == c.id) return this
        return copy(busyCardId = c.id)
    }

    /**
     * 写库完成后推进队列。
     *
     * Again 的卡留在本轮（[ReviewQueue] 负责隔几张再问），队列可能只剩它自己、
     * 位置也不变——所以解锁必须走「清掉 busyCardId」而不是「换卡了自然解锁」。
     * 没发起过评级就调用时直接忽略，避免一次点击被算两次分。
     */
    fun applyGrade(grade: Grade): StudyRound {
        val c = card ?: return copy(busyCardId = null)
        if (busyCardId != c.id) return this
        val step = ReviewQueue.afterGrade(queue, index, grade)
        return copy(
            queue = step.queue,
            index = step.index,
            revealed = false,
            busyCardId = null,
            events = events + (c.id to grade),
        )
    }

    /** 写库失败等异常路径：只解锁、不推进，让用户能对同一张卡重评。 */
    fun unlock(): StudyRound = copy(busyCardId = null)

    /** 删除当前卡：从队列里移走，索引夹回合法范围，删空即结束。 */
    fun discard(): StudyRound {
        val c = card ?: return copy(busyCardId = null)
        val rest = queue.filterNot { it.id == c.id }
        // 删空也要带着评级流水结束，否则「删掉最后一张」会把本轮小结抹成空。
        if (rest.isEmpty()) return copy(queue = emptyList(), index = 0, revealed = false, busyCardId = null)
        return copy(
            queue = rest,
            index = index.coerceAtMost(rest.lastIndex),
            revealed = false,
            busyCardId = null,
        )
    }

    companion object {
        val EMPTY = StudyRound()

        fun start(cards: List<Card>): StudyRound = StudyRound(queue = cards)
    }
}
