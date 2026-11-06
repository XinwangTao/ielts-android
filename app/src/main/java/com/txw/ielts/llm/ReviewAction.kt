package com.txw.ielts.llm

/**
 * 把复盘建议映射到 App 里对应的练习入口。
 *
 * 复盘给的是「今天做这三件事」，但用户看到文字还得自己去首页找入口。
 * 这里按建议里的关键词推断该去哪里：命中不了就返回 null，界面不显示按钮，
 * 宁可少给一个入口，也不要把「练口语」的建议跳到写作业。
 */
object ReviewAction {

    /** 越靠前优先级越高：同为 0 命中时不会误判，同分时按此顺序取。 */
    private val RULES: List<Pair<String, List<String>>> = listOf(
        "vocab" to listOf("词汇", "单词", "搭配", "生词", "背词", "collocation", "vocabulary", "vocab"),
        "speaking" to listOf("口语", "录音", "口述", "说一段", "复述", "speaking", "spoken", "record"),
        "writing" to listOf("写作", "作文", "段落", "论点", "重写", "essay", "writing", "paragraph", "thesis"),
        "listening" to listOf("听力", "listening", "精听"),
        "reading" to listOf("阅读", "reading", "passage", "文章"),
    )

    /**
     * @return vocab / speaking / writing / listening / reading，命中不了返回 null。
     *
     * 「怎么做」权重是标题/理由的两倍：真正决定用户去哪一页的是动作
     * （「在口语练习里描述一次经历」），而不是症状描述（「搭配错误频繁」）。
     */
    fun forTask(title: String, why: String, how: String): String? {
        val head = "$title\n$why".lowercase()
        val tail = how.lowercase()
        var best: String? = null
        var bestScore = 0
        for ((action, words) in RULES) {
            val score = words.count { head.contains(it, ignoreCase = true) } +
                words.count { tail.contains(it, ignoreCase = true) } * 2
            if (score > bestScore) {
                best = action
                bestScore = score
            }
        }
        return best
    }
}
