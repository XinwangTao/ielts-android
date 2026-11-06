package com.txw.ielts.data

/**
 * 跟读的词级对比（纯函数，可直接单测）。
 *
 * 从 [ShadowRepo] 抽出来：算法本身不依赖 Context，而准确率是用户唯一能看到的
 * 「跟读得分」，分词/对齐一错就会冤枉人或放水，必须有回归测试锁住。
 */
object ShadowCompare {

    /**
     * 分词：小写、统一弯引号、去掉标点、按空白切。
     *
     * 用 `\p{L}\p{N}` 而不是 `a-z0-9`：参考文本里的 café、naïve 若被切成 caf/nave，
     * 转写再正确也会被判漏读。弯引号 `’` 统一成 `'`，否则 don’t 会变成 don + t 两个词。
     */
    fun tokenize(s: String): List<String> =
        s.lowercase()
            .replace('’', '\'')
            .replace(Regex("""[^\p{L}\p{N}' ]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .split(' ')
            .filter { it.isNotBlank() }

    /** 词级对比：LCS 对齐；匹配上的参考词算「读对」，未匹配的转写词算多读/读错。 */
    fun compare(reference: String, hypothesis: String): ShadowDiff {
        val ref = tokenize(reference)
        val hyp = tokenize(hypothesis)
        val matchedRef = HashSet<Int>()
        val matchedHyp = HashSet<Int>()

        if (ref.isEmpty()) return ShadowDiff(ref, emptySet(), ref.indices.toSet(), hyp, 0.0)
        if (hyp.isEmpty()) return ShadowDiff(ref, emptySet(), ref.indices.toSet(), emptyList(), 0.0)

        val n = ref.size
        val m = hyp.size
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                dp[i][j] = if (ref[i] == hyp[j]) dp[i + 1][j + 1] + 1
                else maxOf(dp[i + 1][j], dp[i][j + 1])
            }
        }
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                ref[i] == hyp[j] -> { matchedRef.add(i); matchedHyp.add(j); i++; j++ }
                dp[i + 1][j] >= dp[i][j + 1] -> i++
                else -> j++
            }
        }
        val missed = ref.indices.filter { it !in matchedRef }.toSet()
        val extra = hyp.indices.filter { it !in matchedHyp }.map { hyp[it] }
        val accuracy = matchedRef.size.toDouble() / n
        return ShadowDiff(ref, matchedRef, missed, extra, accuracy)
    }
}
