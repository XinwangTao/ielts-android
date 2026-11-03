package com.txw.ielts.data

import android.content.Context
import org.json.JSONObject

/** 口语 Part 2 话题素材：一个话题卡 + 可复用表达句 + 中文结构提示。 */
data class SpeakingTopic(
    val id: String,
    val title: String,
    val prompt: String,
    val sentences: List<String>,
    val tips: String,
)

/** 从 assets/topics.json 加载素材库（离线，无需网络）。 */
object TopicRepo {
    fun load(context: Context): List<SpeakingTopic> = runCatching {
        val text = context.assets.open("topics.json").bufferedReader().use { it.readText() }
        val arr = JSONObject(text).getJSONArray("topics")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            SpeakingTopic(
                id = o.getString("id"),
                title = o.getString("title"),
                prompt = o.getString("prompt"),
                sentences = o.getJSONArray("sentences").let { a ->
                    (0 until a.length()).map { a.getString(it) }
                },
                tips = o.optString("tips", ""),
            )
        }
    }.getOrDefault(emptyList())
}
