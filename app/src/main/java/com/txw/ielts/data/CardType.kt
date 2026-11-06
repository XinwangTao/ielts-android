package com.txw.ielts.data

/**
 * 复习卡类型的展示文案。
 *
 * 为什么单独抽出来：卡片的 `type` 是英文标识（word / collocation / topic / error /
 * cloze / vocab），而学习页右上角与语料页「内容构成」直接把它印了出来 —— 后来的
 * `cloze`（逐句改法）和 `vocab`（查词）两个类型从没补过映射，用户看到的就是英文单词。
 * 另外 `type=error` 太粗：练习批注、听读错题、跟读漏词、内置错题卡在库里都是 error，
 * 只有 `source` 能区分，学习时告诉用户「这张卡从哪来」比只写「错题」有用。
 */
object CardType {

    /** 类型未知/为空时的兜底：不要让界面出现空标签。 */
    const val FALLBACK = "卡片"

    private fun norm(raw: String): String = raw.trim().lowercase()

    /** 按 `type` 聚合时的文案（语料页「内容构成」用它，那里只有 type 没有 source）。 */
    fun typeLabel(type: String): String = when (norm(type)) {
        "word" -> "词汇"
        "collocation" -> "搭配句型"
        "topic" -> "话题语料"
        "error" -> "错题"
        "cloze" -> "逐句改错"
        "vocab" -> "查词卡"
        "" -> FALLBACK
        // 未知类型原样显示：不猜，也不吞掉，方便发现新写入的类型没补映射。
        else -> type.trim()
    }

    /**
     * 单张卡片在学习页的标签：`source` 能说清来历时优先用它，否则退回 [typeLabel]。
     * `ai_vocab`/`seed` 这类 source 不改变卡片性质（搭配句型、内置词条），继续按 type 走。
     */
    fun cardLabel(type: String, source: String): String = when (norm(source)) {
        "error" -> "练习批注"
        "fix" -> "逐句改错"
        "bank" -> "听读错题"
        "shadow" -> "跟读错词"
        "lookup" -> "查词卡"
        else -> typeLabel(type)
    }

    fun cardLabel(card: Card): String = cardLabel(card.type, card.source)
}
