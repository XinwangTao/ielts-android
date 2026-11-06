package com.txw.ielts.llm

import org.json.JSONObject

/**
 * 评分失败原因（按会话存盘）。
 *
 * 为什么要有：本地评分跑在前台 Service 里，失败时只发一条会被划掉的通知；
 * 结果页则只剩「这篇还没评分成功」这类通用文案——用户既不知道是模型没投递、
 * 额度用完，还是系统把后台任务冻住了，也就不知道该改哪一项。
 * 把原因按 sessionId 存下来，结果页重新打开、甚至 App 重启后仍能照着重试。
 *
 * 只存最近 30 条、7 天内有效：这是排障线索，不是练习记录，没必要无限增长。
 */
object ScoreFailures {

    /** 一条原因的长度上限：异常消息可能带整段模型输出，压到一屏可读即可。 */
    const val MAX_REASON = 240

    /** 最多保留多少条（跨会话）。 */
    const val KEEP = 30

    /** 超过这个时间的失败原因不再展示：环境可能已经变了，旧原因会误导。 */
    const val EXPIRE_MS = 7L * 24 * 60 * 60 * 1000

    /**
     * 单行化并截断原因。空白（或字面量 "null"）视为「没有原因」返回 null，
     * 让调用方能顺手用它表达「清掉旧原因」。
     */
    fun normalize(reason: String?): String? {
        val flat = reason?.trim()?.replace(Regex("\\s+"), " ") ?: return null
        if (flat.isBlank() || flat.equals("null", ignoreCase = true)) return null
        return if (flat.length <= MAX_REASON) flat else flat.take(MAX_REASON - 1).trimEnd() + "…"
    }

    /**
     * 写入/清除一条失败原因。
     * @param reason 空白表示这次评分已经成功、或用户主动重试：删除该会话的旧条目。
     * @return 新的存储串，直接写回 Prefs。
     */
    fun record(
        store: String?,
        sessionId: Long,
        reason: String?,
        nowMs: Long = System.currentTimeMillis(),
    ): String {
        // 会话 id 非法时不动原存储：宁可保留旧数据，也不要写进 "0"/"-1" 这种脏键。
        if (sessionId <= 0L) return store ?: "{}"
        val root = parse(store)
        val key = sessionId.toString()
        val msg = normalize(reason)
        if (msg == null) {
            root.remove(key)
        } else {
            root.put(key, JSONObject().put("at", nowMs).put("msg", msg))
        }
        prune(root, nowMs)
        return root.toString()
    }

    /** 读取某次会话的失败原因；没有、已过期或存储损坏一律返回 null。 */
    fun reasonFor(
        store: String?,
        sessionId: Long,
        nowMs: Long = System.currentTimeMillis(),
    ): String? {
        if (sessionId <= 0L) return null
        val entry = parse(store).optJSONObject(sessionId.toString()) ?: return null
        val at = entry.optLong("at", 0L)
        if (at <= 0L || nowMs - at > EXPIRE_MS) return null
        return normalize(entry.optString("msg", ""))
    }

    /** 评分成功时清掉该会话的旧原因。 */
    fun clear(store: String?, sessionId: Long): String = record(store, sessionId, null)

    private fun parse(store: String?): JSONObject =
        runCatching { JSONObject(store ?: "") }.getOrNull() ?: JSONObject()

    /** 丢掉过期条目；仍超量时只留最新的 [KEEP] 条（按写入时间）。 */
    private fun prune(root: JSONObject, nowMs: Long) {
        val entries = root.keys().asSequence().mapNotNull { key ->
            root.optJSONObject(key)?.let { key to it.optLong("at", 0L) }
        }.toList()
        entries.forEach { (key, at) ->
            if (at <= 0L || nowMs - at > EXPIRE_MS) root.remove(key)
        }
        root.keys().asSequence().mapNotNull { key ->
            root.optJSONObject(key)?.let { key to it.optLong("at", 0L) }
        }.sortedByDescending { it.second }
            .drop(KEEP)
            .forEach { (key, _) -> root.remove(key) }
    }
}
