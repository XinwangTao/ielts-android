package com.txw.ielts.llm

/**
 * 提示词的输入预算：把「多少 token 能塞进模型」变成可计算、可测试的规则。
 *
 * 之前几处都是 `text.take(4000)`：只砍尾巴。对作文和复盘这两类输入是错的——
 * 作文的结论段、复盘的「目标分 / 剩余天数」都在末尾，砍掉之后模型给的建议会跑偏
 * （例如不知道 14 天就考试，仍然按三个月排计划）。这里改成「开头 + 结尾、砍中间」，
 * 并在多段输入时按优先级决定先砍谁：短且关键的部分（逐句改法）尽量完整保留。
 *
 * 估算口径故意保守简单：中日韩字符 ≈ 1 token/字，其余字符 ≈ 4 字符/token。
 * 用途是**设上限、防止超长**，不是精确计费。
 */
object PromptBudget {

    private const val SEPARATOR = "\n\n"

    /** 评分类请求的输入上限：正常作文/转写远低于它，只在粘贴超长文本或 ASR 复读时兜底。 */
    const val SCORE_INPUT_TOKENS = 6000

    /** 生词提取 / 复盘的输入上限：端侧 4B 模型上下文小，长输入会明显变慢且开始漏字段。 */
    const val STUDY_INPUT_TOKENS = 3000

    /** 一段输入。priority 越大越晚被砍；pinned 段排在同优先级之后（最后才动）。 */
    data class Part(
        val name: String,
        val text: String,
        val priority: Int = 1,
        val pinned: Boolean = false,
    )

    data class Clipped(val text: String, val removedChars: Int)

    data class Result(
        val text: String,
        /** 被裁过的段名，按输入顺序；空表示原样返回。 */
        val trimmed: List<String>,
        val originalTokens: Int,
        val tokens: Int,
        /** 一共从原文里省掉多少字符（不含省略标记本身）。 */
        val removedChars: Int,
    ) {
        val changed: Boolean get() = trimmed.isNotEmpty()
    }

    fun estimateTokens(text: String): Int {
        if (text.isEmpty()) return 0
        var wide = 0
        var narrow = 0
        for (ch in text) {
            if (isWide(ch)) wide++ else narrow++
        }
        return wide + (narrow + 3) / 4
    }

    /**
     * 超预算时保留开头与结尾、中部替换成一行说明。
     * 英文按空格退到完整单词，中文没有空格就原样保留（不会把汉字砍成半截字节）。
     */
    fun clip(text: String, maxTokens: Int): Clipped {
        if (text.isEmpty()) return Clipped("", 0)
        if (maxTokens <= 0) return Clipped("", text.length)
        if (estimateTokens(text) <= maxTokens) return Clipped(text, 0)

        var marker = ""
        var headBusy = 0
        var tailBusy = 0
        // 标记本身也占 token（含被省略的字数），迭代几次让数字稳定下来。
        repeat(4) {
            val keep = (maxTokens - estimateTokens(marker)).coerceAtLeast(0).toDouble()
            val headBudget = keep * 0.6
            headBusy = headChars(text, headBudget)
            tailBusy = tailChars(text, keep - headBudget)
            val removed = text.length - headBusy - tailBusy
            marker = " [… middle trimmed: $removed characters …] "
        }

        var head = trimToWordEnd(text.substring(0, headBusy))
        var tail = trimToWordStart(text.substring(text.length - tailBusy))
        var out = head + marker + tail
        var guard = 0
        // 估算有取整误差：超一点点就继续缩，直到真的在预算内。
        while (estimateTokens(out) > maxTokens && (head.isNotEmpty() || tail.isNotEmpty()) && guard++ < 40) {
            head = head.dropLast((head.length / 8).coerceAtLeast(1))
            tail = tail.drop((tail.length / 8).coerceAtLeast(1))
            out = head + marker + tail
        }
        return Clipped(out, text.length - head.length - tail.length)
    }

    /** 单段输入的便捷版。 */
    fun clipText(text: String, maxTokens: Int): String = clip(text, maxTokens).text

    /**
     * 多段输入按预算装配：先砍低优先级段，短而关键的高优先级段尽量不动；
     * 每段都是「砍中间、留两头」，所以不会出现「作文只留前半篇」。
     */
    fun fit(parts: List<Part>, maxTokens: Int): Result {
        val source = parts.map { it.text.trim() }
        val joined = source.joinToString(SEPARATOR)
        val original = estimateTokens(joined)
        if (original <= maxTokens) return Result(joined, emptyList(), original, original, 0)

        val out = source.toMutableList()
        val removed = IntArray(parts.size)
        // 先砍非 pinned、priority 小的；pinned 段留到最后（仍可能被砍，只是最后才动）。
        val order = parts.indices.sortedWith(compareBy({ parts[it].pinned }, { parts[it].priority }))

        fun currentTokens(): Int = estimateTokens(out.joinToString(SEPARATOR))

        var round = 0
        while (currentTokens() > maxTokens && round++ < 6) {
            var progressed = false
            for (i in order) {
                val total = currentTokens()
                if (total <= maxTokens) break
                val target = (estimateTokens(out[i]) - (total - maxTokens)).coerceAtLeast(0)
                val clipped = clip(source[i], target)
                if (clipped.text.length < out[i].length) {
                    removed[i] += clipped.removedChars
                    out[i] = clipped.text
                    progressed = true
                }
            }
            if (!progressed) break
        }
        return Result(
            text = out.joinToString(SEPARATOR),
            trimmed = parts.indices.filter { removed[it] > 0 }.map { parts[it].name },
            originalTokens = original,
            tokens = estimateTokens(out.joinToString(SEPARATOR)),
            removedChars = removed.sum(),
        )
    }

    // ---- 估算与断句细节 ----

    private fun cost(ch: Char): Double = if (isWide(ch)) 1.0 else 0.25

    private fun headChars(text: String, budget: Double): Int {
        var used = 0.0
        var i = 0
        while (i < text.length) {
            val c = cost(text[i])
            if (used + c > budget + 1e-9) break
            used += c
            i++
        }
        return i
    }

    private fun tailChars(text: String, budget: Double): Int {
        var used = 0.0
        var i = 0
        while (i < text.length) {
            val c = cost(text[text.length - 1 - i])
            if (used + c > budget + 1e-9) break
            used += c
            i++
        }
        return i
    }

    /** 头段末尾若是半个单词就退到上一个空白；中文没有空格，返回原样。 */
    private fun trimToWordEnd(s: String): String {
        if (s.isEmpty() || s.last().isWhitespace()) return s
        val cut = s.indexOfLast { it.isWhitespace() }
        return if (cut >= 0) s.substring(0, cut + 1) else s
    }

    /** 尾段开头若是半个单词就前进到下一个空白。 */
    private fun trimToWordStart(s: String): String {
        if (s.isEmpty() || s.first().isWhitespace()) return s
        val cut = s.indexOfFirst { it.isWhitespace() }
        return if (cut >= 0) s.substring(cut) else s
    }

    /** CJK / 全角 / 假名 / 谚文：按 1 token 估算，与拉丁文分开计数。 */
    private fun isWide(ch: Char): Boolean {
        val c = ch.code
        return c in 0x2E80..0x303F || c in 0x3040..0x30FF || c in 0x3400..0x4DBF ||
            c in 0x4E00..0x9FFF || c in 0xAC00..0xD7AF || c in 0xF900..0xFAFF ||
            c in 0xFF00..0xFFEF
    }
}
