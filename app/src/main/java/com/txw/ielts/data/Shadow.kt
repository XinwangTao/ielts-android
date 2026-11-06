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

    /** 词级对比：算法在 [ShadowCompare]，这里只保留对外的仓储入口。 */
    fun compare(reference: String, hypothesis: String): ShadowDiff =
        ShadowCompare.compare(reference, hypothesis)

}
