package com.txw.ielts.data

import android.content.Context
import org.json.JSONObject

/** 写作论点素材：一个话题 + 2-3 组「论点 / 解释 / 例子」（解决「展开不足」）。 */
data class Argument(
    val point: String,
    val explain: String,
    val example: String,
)

data class WritingArgumentTopic(
    val id: String,
    val title: String,
    val prompt: String,
    val arguments: List<Argument>,
    val tips: String,
)

/** 从 assets/arguments.json 加载写作论点库（离线）。 */
object ArgumentRepo {
    fun load(context: Context): List<WritingArgumentTopic> = runCatching {
        val text = context.assets.open("arguments.json").bufferedReader().use { it.readText() }
        val arr = JSONObject(text).getJSONArray("topics")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val args = o.getJSONArray("arguments")
            WritingArgumentTopic(
                id = o.getString("id"),
                title = o.getString("title"),
                prompt = o.getString("prompt"),
                arguments = (0 until args.length()).map { j ->
                    val a = args.getJSONObject(j)
                    Argument(
                        point = a.getString("point"),
                        explain = a.getString("explain"),
                        example = a.getString("example"),
                    )
                },
                tips = o.optString("tips", ""),
            )
        }
    }.getOrDefault(emptyList())
}
