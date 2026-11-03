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
