package com.txw.ielts.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 收藏的高分范文（来自评分后的 polished，按 session 去重）。 */
data class ModelEssay(
    val sessionId: Long,
    val topic: String,
    val polished: String,
    val band: Double,
    val savedAt: Long = System.currentTimeMillis(),
)

/** 范文库：存应用私有目录 model_essays.json，离线可读。 */
object ModelEssayStore {

    private fun file(context: Context): File = File(context.filesDir, "model_essays.json")

    fun load(context: Context): List<ModelEssay> = runCatching {
        val f = file(context)
        if (!f.isFile) return emptyList()
        val arr = JSONArray(f.readText())
        (0 until arr.length()).mapNotNull { i ->
            runCatching {
                val o = arr.getJSONObject(i)
                ModelEssay(
                    sessionId = o.getLong("sessionId"),
                    topic = o.optString("topic", ""),
                    polished = o.optString("polished", ""),
                    band = o.optDouble("band", 0.0),
                    savedAt = o.optLong("savedAt", 0L),
                )
            }.getOrNull()
        }.sortedByDescending { it.savedAt }
    }.getOrElse { e ->
        // 文件损坏时先备份原文件再返回空，避免下一次保存把旧收藏直接覆盖掉。
        android.util.Log.e("ModelEssay", "load fail: ${e.message}")
        runCatching {
            val f = file(context)
            f.renameTo(File(f.parentFile, "${f.name}.corrupt"))
        }
        emptyList()
    }

    /** 保存一篇；已收藏过同一 session 则更新内容。返回是否新建。 */
    fun save(context: Context, essay: ModelEssay): Boolean {
        val list = load(context).toMutableList()
        val idx = list.indexOfFirst { it.sessionId == essay.sessionId }
        var created = false
        if (idx >= 0) list[idx] = essay else { list.add(essay); created = true }
        write(context, list)
        return created
    }

    fun remove(context: Context, sessionId: Long) {
        write(context, load(context).filterNot { it.sessionId == sessionId })
    }

    fun contains(context: Context, sessionId: Long): Boolean =
        load(context).any { it.sessionId == sessionId }

    /** 范文去重键：同题目 + 同润色正文（忽略大小写与首尾空白）。 */
    fun essayKey(e: ModelEssay): String =
        e.topic.trim().lowercase() + "\u0001" + e.polished.trim().lowercase()

    /**
     * 纯合并（不碰文件）：按 [essayKey] 去重、给新条目发负数 id。
     *
     * 已有条目原样保留（新增不覆盖）；负数 id 从 -1 往下取，跳过库里已占用的 id，
     * 不会与之后的真实会话 id（恒为正）或此前导入的负数 id 撞键。
     *
     * @return 合并后的列表 + 实际新增篇数（正文为空或重复的不计）。
     */
    fun mergeList(
        existing: List<ModelEssay>,
        incoming: List<ModelEssay>,
    ): Pair<List<ModelEssay>, Int> {
        val list = existing.toMutableList()
        val seen = list.map { essayKey(it) }.toMutableSet()
        val usedIds = list.map { it.sessionId }.toMutableSet()
        var added = 0
        var nextId = -1L
        incoming.forEach { e ->
            if (e.polished.isBlank()) return@forEach
            if (!seen.add(essayKey(e))) return@forEach
            while (nextId in usedIds) nextId -= 1
            usedIds += nextId
            list.add(e.copy(sessionId = nextId))
            added += 1
        }
        return list to added
    }

    /**
     * 备份导入：把别机的范文并进来（新增不覆盖）。
     *
     * 备份里的 sessionId 指向另一台手机的练习记录，导入后练习已被重新分配主键，直接沿用会
     * 关联到本机不相干的记录。合并规则见 [mergeList]。
     *
     * @return 实际新增篇数（已在库里或正文为空的跳过）；文件写入失败会抛出。
     */
    fun mergeImported(context: Context, incoming: List<ModelEssay>): Int {
        if (incoming.isEmpty()) return 0
        val (merged, added) = mergeList(load(context), incoming)
        if (added > 0) write(context, merged)
        return added
    }

    private fun write(context: Context, list: List<ModelEssay>) {
        val arr = JSONArray()
        list.forEach { e ->
            arr.put(JSONObject().apply {
                put("sessionId", e.sessionId)
                put("topic", e.topic)
                put("polished", e.polished)
                put("band", e.band)
                put("savedAt", e.savedAt)
            })
        }
        // 先写临时文件再 rename：进程在写一半被杀时，原文件仍然完整。
        val f = file(context)
        val tmp = File(f.parentFile, "${f.name}.tmp")
        tmp.writeText(arr.toString())
        if (!tmp.renameTo(f)) {
            f.delete()
            if (!tmp.renameTo(f)) {
                tmp.copyTo(f, overwrite = true)
                tmp.delete()
            }
        }
    }
}
