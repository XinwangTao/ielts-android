package com.txw.ielts.data

/**
 * 填空/选择判分的答案归一化。
 *
 * 原来只做 trim + 小写 + 去标点，中文输入法打出的全角数字/字母（２５、ＡＢＣ）
 * 会被当成标点整段删掉，正确答案直接判错。这里先把全角 ASCII 折回半角再清洗。
 */
object AnswerKey {
    private val nonAnswer = Regex("[^a-z0-9 ]")
    private val spaces = Regex("\\s+")
    private val digitGroup = Regex("(?<=\\d),(?=\\d)")
    private val marks = Regex("\\p{M}+")

    fun normalize(s: String): String {
        val folded = StringBuilder(s.length)
        for (c in s) {
            // 全角 ！..～ 与 ASCII 0x21..0x7E 一一对应；全角空格 U+3000 也折成空格。
            folded.append(if (c in '！'..'～' || c == '　') (c.code - 0xFEE0).toChar() else c)
        }
        // 去掉重音（café → cafe）：用户输入法不一定打得出重音符，读对不该判错。
        val plain = java.text.Normalizer.normalize(folded, java.text.Normalizer.Form.NFD)
            .replace(marks, "")
        return plain.trim().lowercase()
            // 1,000 与 1000 应判对；千分位逗号先去掉，其余标点仍折算成空格。
            .replace(digitGroup, "")
            // 弯引号先统一；撇号整体去掉（don’t 与 dont 等价），避免输入法差异误判。
            .replace('’', '\'').replace('‘', '\'')
            .replace("'", "")
            .replace(nonAnswer, " ")
            .replace(spaces, " ")
            .trim()
    }
}
