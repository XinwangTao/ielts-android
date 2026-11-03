package com.txw.ielts.data

import android.content.Context
import org.json.JSONObject

data class ShadowPassage(
    val id: String,
    val title: String,
    val topic: String,
    val sentences: List<String>,
)

/** 单句跟读对比结果。准确率 = 读对词数 / 参考词数（词级文本代理指标，非音素级）。 */
data class ShadowDiff(
    val refWords: List<String>,
    val matchedRef: Set<Int>,      // refWords 中读对的下标
    val missedRef: Set<Int>,       // 漏读的 ref 下标
    val extraHyp: List<String>,    // 多读/读错的转写词
    val accuracy: Double,
    val wpm: Int = 0,              // 跟读语速（词/分钟），0 表示未记录
)

class ShadowRepo(private val context: Context) {

    private var cache: List<ShadowPassage>? = null

    fun passages(): List<ShadowPassage> = cache ?: load().also { cache = it }

    fun byId(id: String): ShadowPassage? = passages().firstOrNull { it.id == id }

    private fun load(): List<ShadowPassage> {
        val text = context.assets.open("shadow.json").bufferedReader().use { it.readText() }
        val arr = JSONObject(text).getJSONArray("passages")
        val out = ArrayList<ShadowPassage>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val s = o.getJSONArray("sentences")
            out.add(
                ShadowPassage(
                    id = o.getString("id"),
                    title = o.getString("title"),
                    topic = o.optString("topic", ""),
                    sentences = (0 until s.length()).map { s.getString(it) },
                )
            )
        }
        return out
    }

    /** 分词：小写、去首尾标点、按空格切。 */
    private fun tokenize(s: String): List<String> =
        s.lowercase().trim()
            .replace(Regex("[^a-z0-9' ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .split(' ')
            .filter { it.isNotBlank() }

    /** 词级对比：用 LCS 对齐，matchedRef 为读对下标，extraHyp 为多读词。 */
    fun compare(reference: String, hypothesis: String): ShadowDiff {
        val ref = tokenize(reference)
        val hyp = tokenize(hypothesis)
        val matchedRef = HashSet<Int>()
        val matchedHyp = HashSet<Int>()

        if (ref.isEmpty()) return ShadowDiff(ref, emptySet(), ref.indices.toSet(), hyp, 0.0)
        if (hyp.isEmpty()) return ShadowDiff(ref, emptySet(), ref.indices.toSet(), emptyList(), 0.0)

        // LCS 长度表
        val n = ref.size
        val m = hyp.size
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                dp[i][j] = if (ref[i] == hyp[j]) dp[i + 1][j + 1] + 1
                else maxOf(dp[i + 1][j], dp[i][j + 1])
            }
        }
        // 回溯标记匹配
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
