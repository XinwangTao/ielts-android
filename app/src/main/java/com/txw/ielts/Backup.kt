package com.txw.ielts

import android.content.Context
import com.txw.ielts.data.AppDb
import com.txw.ielts.data.ModelEssayStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 数据导出（图：学习闭环扩展）：练习记录 + 卡片 + 范文库写成 JSON，
 * 存到应用外部目录 backup/，换机或重装前可拷贝。不含 API Key。
 */
object Backup {

    suspend fun export(context: Context): File? = withContext(Dispatchers.IO) {
        runCatching {
            val db = AppDb.get(context)
            val sessions = db.sessions().recent(100_000)
            val cards = db.cards().all()
            val essays = ModelEssayStore.load(context)
            val o = JSONObject().apply {
                put("version", 1)
                put("exportedAt", System.currentTimeMillis())
                put("sessions", JSONArray().apply {
                    sessions.forEach { s ->
                        put(JSONObject().apply {
                            put("id", s.id); put("module", s.module); put("topic", s.topic)
                            put("startedAt", s.startedAt); put("durationSec", s.durationSec)
                            put("transcript", s.transcript); put("essayText", s.essayText)
                            put("scoresJson", s.scoresJson); put("fixesJson", s.fixesJson)
                            put("polished", s.polished); put("status", s.status)
                        })
                    }
                })
                put("cards", JSONArray().apply {
                    cards.forEach { c ->
                        put(JSONObject().apply {
                            put("id", c.id); put("type", c.type); put("front", c.front); put("back", c.back)
                            put("example", c.example); put("exampleCn", c.exampleCn); put("tags", c.tags)
                            put("source", c.source); put("createdAt", c.createdAt)
                            put("ease", c.ease); put("intervalDays", c.intervalDays); put("reps", c.reps)
                            put("lapses", c.lapses); put("dueAt", c.dueAt); put("lastReviewed", c.lastReviewed)
                        })
                    }
                })
                put("modelEssays", JSONArray().apply {
                    essays.forEach { e ->
                        put(JSONObject().apply {
                            put("sessionId", e.sessionId); put("topic", e.topic)
                            put("polished", e.polished); put("band", e.band); put("savedAt", e.savedAt)
                        })
                    }
                })
            }
            val dir = File(context.getExternalFilesDir(null), "backup").apply { mkdirs() }
            val name = "ielts_backup_${SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())}.json"
            File(dir, name).apply { writeText(o.toString()) }
        }.getOrNull()
    }
}
