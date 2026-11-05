package com.txw.ielts.data

/**
 * 生成卡（AI 批注 / 听读错题 / 查词 / 跟读 / 生词）的正面清洗与去重键。
 *
 * 模型输出的近重复很常见：大小写、连续空白、句末标点不同，都会让同一条反馈
 * 反复入库，把 AI 卡池（上限 200）挤满。清洗和判重放在这里，保证各入口一致。
 */
object CardText {
    /** 练习批注正面长度上限（与历史行为一致）。 */
    const val FIX_MAX = 70

    /** 听读错题题干正面长度上限。 */
    const val BANK_MAX = 80

    /** 单词/搭配类正面长度上限。 */
    const val SHORT_MAX = 64

    private val ws = Regex("\\s+")
    private val trailing = charArrayOf(
        '.', '!', '?', '。', '！', '？', ',', '，', ';', '；', ':', '：',
        '"', '\'', '“', '”', '…',
    )

    /** 压平空白并按上限截断；截断后补省略号，保证卡片正面可读。 */
    fun front(raw: String, maxLen: Int): String {
        val flat = raw.trim().replace(ws, " ")
        if (flat.isEmpty() || flat.length <= maxLen) return flat
        return flat.take(maxLen).trimEnd() + "…"
    }

    /** 去重键：忽略大小写、连续空白与句末标点，避免同一反馈换个标点又建一张卡。 */
    fun key(front: String): String =
        front.trim().replace(ws, " ").lowercase().trimEnd(*trailing)
}
