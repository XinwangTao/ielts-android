package com.txw.ielts.data

/**
 * 填空/选择判分的答案归一化。
 *
 * 原来只做 trim + 小写 + 去标点，中文输入法打出的全角数字/字母（２５、ＡＢＣ）
 * 会被当成标点整段删掉，正确答案直接判错。这里先把全角 ASCII 折回半角再清洗。
 */
object AnswerKey {
    private val nonAnswer = Regex("[^a-z0-9' ]")
    private val spaces = Regex("\\s+")

    fun normalize(s: String): String {
        val folded = StringBuilder(s.length)
        for (c in s) {
            // 全角 ！..～ 与 ASCII 0x21..0x7E 一一对应；全角空格 U+3000 也折成空格。
            folded.append(if (c in '\uFF01'..'\uFF5E' || c == '\u3000') (c.code - 0xFEE0).toChar() else c)
        }
        return folded.toString().trim().lowercase()
            .replace(nonAnswer, " ")
            .replace(spaces, " ")
            .trim()
    }
}
