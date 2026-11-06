package com.txw.ielts.data

/**
 * 一轮复习结束后的收尾小结（纯逻辑，界面只负责渲染）。
 *
 * 为什么要有：评完最后一张卡，学习页直接把人送回语料页，这一轮做了多少张、
 * 哪些还不稳，全靠用户自己回忆；语料页顶部只有「今日已学 N 张」这种累计数字，
 * 分不清「刚刚这一轮」发生了什么。中途退出更是连一句交代都没有。
 *
 * 只统计发生过的事，不预测下次到期时间：排期已经由 [Sm2] 写进卡片 dueAt，
 * 这里再推一遍容易和真实调度对不上，也会和评级按钮上的小字打架。
 *
 * @param cards 本轮评过级的卡数（按卡去重；「不认识」重问不重复计数）
 * @param ratings 评级次数；「不认识」后本轮重问会让它大于 [cards]
 * @param solid 最后评为「认识 / 太简单」的卡数
 * @param shaky 最后评为「模糊」的卡数
 * @param unfinished 最后仍停在「不认识」的卡数（只有中途退出才会出现）
 * @param relearned 被评过两次以上的卡数，即本轮真正重问过的张数
 */
data class RoundSummary(
    val cards: Int,
    val ratings: Int,
    val solid: Int,
    val shaky: Int,
    val unfinished: Int,
    val relearned: Int,
) {
    val empty: Boolean get() = cards == 0

    /** 标题行：一轮没评级时说人话，别显示「0 张卡 · 0 次」。 */
    val headline: String
        get() = if (empty) "这一轮没有评级记录" else "本轮 $cards 张卡 · 评级 $ratings 次"

    /** 明细行：只列真的发生过的档位，避免「不认识 0」这种噪声。 */
    val detail: String
        get() = if (empty) "" else buildList {
            if (solid > 0) add("认识 $solid")
            if (shaky > 0) add("还需巩固 $shaky")
            if (unfinished > 0) add("停在「不认识」$unfinished")
            if (relearned > 0) add("$relearned 张本轮重问过")
        }.joinToString(" · ")

    /** 收尾说明：讲清重问与排期，别让用户以为「不认识」的卡凭空消失。 */
    val note: String
        get() = when {
            empty -> ""
            unfinished > 0 -> "停在「不认识」的卡已排到明天，下次打开会先见到它们。"
            relearned > 0 -> "「不认识」的卡当时隔几张又问了；这里按每张卡最后的结果统计。"
            else -> "每张卡都是一次评过，下次到期时间就是评级按钮上写的间隔。"
        }

    companion object {
        val EMPTY = RoundSummary(0, 0, 0, 0, 0, 0)

        /**
         * 从评级流水构建小结。
         *
         * @param events 按发生顺序排列的「卡片 id → 评级」；同一张卡重问会出现多次，
         *   以最后一次为准判断它最终算不算记住。
         */
        fun of(events: List<Pair<Long, Grade>>): RoundSummary {
            if (events.isEmpty()) return EMPTY
            val last = LinkedHashMap<Long, Grade>()
            val times = HashMap<Long, Int>()
            events.forEach { (id, grade) ->
                last[id] = grade
                times[id] = (times[id] ?: 0) + 1
            }
            return RoundSummary(
                cards = last.size,
                ratings = events.size,
                solid = last.values.count { it == Grade.GOOD || it == Grade.EASY },
                shaky = last.values.count { it == Grade.HARD },
                unfinished = last.values.count { it == Grade.AGAIN },
                relearned = times.count { it.value > 1 },
            )
        }
    }
}
