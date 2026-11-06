package com.txw.ielts.data

import org.json.JSONObject

/** 跟读进度：当前句索引 + 已读句的转写。 */
data class ShadowProgressState(val index: Int, val transcripts: Map<Int, String>)

/**
 * 跟读进度的 JSON 编解码（纯函数）。
 *
 * 只存索引与转写；diff 由转写与原文重新比对得出，不落盘也不怕结构变更。
 * 容错：坏键忽略、索引越界夹到合法范围、空对象视为没有进度。
 */
object ShadowProgressCodec {

    fun encode(state: ShadowProgressState): JSONObject = JSONObject()
        .put("i", state.index)
        .put("t", JSONObject().apply {
            state.transcripts.forEach { (k, v) -> put(k.toString(), v) }
        })

    fun decode(obj: JSONObject?, maxIndex: Int): ShadowProgressState? {
        if (obj == null) return null
        val rawIndex = obj.optInt("i", 0)
        val index = rawIndex.coerceIn(0, maxIndex.coerceAtLeast(0))
        val t = obj.optJSONObject("t") ?: JSONObject()
        val map = t.keys().asSequence()
            .mapNotNull { k -> k.toIntOrNull()?.let { it to t.optString(k) } }
            .toMap()
        if (map.isEmpty() && index == 0) return null
        return ShadowProgressState(index, map)
    }
}
