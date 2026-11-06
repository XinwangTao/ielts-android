package com.txw.ielts.llm

import org.json.JSONObject

/**
 * 写作提纲 / 口语范例的本地快照。
 *
 * 端侧 4B 生成一次要 30-60 秒；原来结果只存在 Compose 的 remember 里，
 * 用户返回上一级再进来就得重新等一遍（写作尤其明显：查个词、回个消息回来就没了）。
 * 这里按「输入指纹」缓存模型的原始输出，配合 [StudyAi.parseOutline] / [StudyAi.parseSample]
 * 还原成结构化结果 —— 只存 raw，不另做一套序列化，解析器改了也不会读到旧结构。
 *
 * 与 AiReviewCache 的区别：复盘要判断「练习数据变没变」，这里输入是题目本身，
 * 同一个题目的提纲/范例与时间无关，所以 TTL 放宽到 7 天，并且只做 LRU 上限。
 */
object StudyAiCache {
    const val TTL_MS = 7L * 24 * 60 * 60 * 1000
    private const val MAX_ENTRIES = 30

    /** 缓存键：kind + 输入指纹。输入完全一致才复用，题目改一个字都会 miss。 */
    fun key(kind: String, vararg parts: String): String =
        "$kind:${AiReviewCache.fingerprint(parts.joinToString("\u0001"))}"

    /** 读缓存；过期或不存在返回 null。 */
    fun get(store: String, key: String, now: Long = System.currentTimeMillis()): String? {
        val all = parse(store)
        val entry = all.optJSONObject(key) ?: return null
        val at = entry.optLong("at", 0L)
        val raw = entry.optString("raw", "")
        if (raw.isBlank() || at <= 0L || now - at > TTL_MS) return null
        return raw
    }

    /** 写缓存并做 LRU 淘汰。返回新的存储串，调用方负责落盘。 */
    @Synchronized
    fun put(
        store: String,
        key: String,
        raw: String,
        now: Long = System.currentTimeMillis(),
    ): String {
        if (raw.isBlank()) return store
        val all = parse(store)
        all.put(key, JSONObject().put("at", now).put("raw", raw.take(MAX_RAW)))
        // 超过上限按时间淘汰最老的，避免 SharedPreferences 无限膨胀。
        if (all.length() > MAX_ENTRIES) {
            val keys = all.keys().asSequence().sortedBy { all.optJSONObject(it)?.optLong("at", 0L) ?: 0L }
            keys.take(all.length() - MAX_ENTRIES).forEach { all.remove(it) }
        }
        val out = all.toString()
        // 让记忆与新的存储串保持一致，避免紧接着的 get 读到旧解析结果。
        cachedStore = out
        cachedObj = all
        return out
    }

    /** 删除某个语料（设置页清空数据时用得上）。 */
    fun clear(): String = "{}"

    /**
     * 解析结果按「存储串内容」记忆：一次 put 会换新串，之后连续多次 get 不必重复解析整串 JSON
     * （缓存串最大可达两百多 KB）。调用方都在 IO 线程，但仍加锁，避免并发 put/get 交错。
     */
    private var cachedStore: String? = null
    private var cachedObj: JSONObject? = null

    @Synchronized
    private fun parse(store: String): JSONObject {
        val s = store.ifBlank { "{}" }
        cachedObj?.let { if (cachedStore == s) return it }
        val o = runCatching { JSONObject(s) }.getOrDefault(JSONObject())
        cachedStore = s
        cachedObj = o
        return o
    }

    private const val MAX_RAW = 8000
}
