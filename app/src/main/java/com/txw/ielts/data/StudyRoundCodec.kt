package com.txw.ielts.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * 学习页「一轮复习」快照的编解码（纯函数），供 `rememberSaveable` 用。
 *
 * 为什么需要：Route 本身是 saveable 的，但队列/位置/翻面只在内存——旋转或进程重建后
 * 学习页会退回语料页，或者从第 1 张卡重来。这里把整轮队列（卡片快照）一起存进 SavedState。
 * 卡片数量在上限 50 张左右，JSON 体积远小于 Bundle 的 1MB 事务上限。
 */
object StudyRoundCodec {

    fun encode(round: StudyRound): String {
        val q = JSONArray()
        round.queue.forEach { c ->
            q.put(JSONObject().apply {
                put("id", c.id); put("type", c.type); put("front", c.front); put("back", c.back)
                put("ex", c.example); put("exCn", c.exampleCn); put("tags", c.tags)
                put("src", c.source); put("ca", c.createdAt); put("ease", c.ease)
                put("iv", c.intervalDays); put("reps", c.reps); put("lapses", c.lapses)
                put("due", c.dueAt); put("lr", c.lastReviewed)
            })
        }
        val ev = JSONArray()
        round.events.forEach { (id, g) -> ev.put(JSONArray().put(id).put(g.name)) }
        return JSONObject().apply {
            put("i", round.index)
            put("r", round.revealed)
            put("q", q)
            put("e", ev)
        }.toString()
    }

    /** 解析失败/空串返回 null（调用方回退到空状态，按「本轮没开始」处理）。 */
    fun decode(raw: String?): StudyRound? {
        if (raw.isNullOrBlank()) return null
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val q = o.optJSONArray("q") ?: return null
        val cards = (0 until q.length()).mapNotNull { i ->
            q.optJSONObject(i)?.let { x ->
                val id = x.optLong("id", 0L)
                if (id <= 0L) null else Card(
                    id = id,
                    type = x.optString("type", "word"),
                    front = x.optString("front"),
                    back = x.optString("back"),
                    example = x.optString("ex"),
                    exampleCn = x.optString("exCn"),
                    tags = x.optString("tags"),
                    source = x.optString("src", "seed"),
                    createdAt = x.optLong("ca", 0L),
                    ease = x.optDouble("ease", 2.5),
                    intervalDays = x.optInt("iv", 0),
                    reps = x.optInt("reps", 0),
                    lapses = x.optInt("lapses", 0),
                    dueAt = x.optLong("due", 0L),
                    lastReviewed = x.optLong("lr", 0L),
                )
            }
        }
        if (cards.isEmpty()) return null
        val events = o.optJSONArray("e")?.let { a ->
            (0 until a.length()).mapNotNull { i ->
                val pair = a.optJSONArray(i) ?: return@mapNotNull null
                val id = pair.optLong(0, 0L)
                val grade = Grade.values().firstOrNull { it.name == pair.optString(1) } ?: return@mapNotNull null
                if (id <= 0L) null else id to grade
            }
        }.orEmpty()
        return StudyRound(
            queue = cards,
            index = o.optInt("i", 0).coerceIn(0, cards.lastIndex),
            revealed = o.optBoolean("r"),
            events = events,
        )
    }
}
