package com.txw.ielts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.txw.ielts.data.AppDb
import com.txw.ielts.data.Prefs
import com.txw.ielts.llm.LocalLlmEngine
import com.txw.ielts.llm.ScoreGate
import com.txw.ielts.llm.SpeakingEval
import com.txw.ielts.llm.WritingEval
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * 本地评分前台 Service（图：ielts-local-llm_workflow 的「后台评分」边）。
 *
 * 为什么不是普通协程：MNN 推理 1-3 分钟 + 大内存占用，普通后台协程在系统内存紧张时
 * 会被直接杀死（实测被语音助手大模型挤掉），状态永远停在 pending_local。
 * 前台 Service 带常驻通知，优先级更高，能显著降低被杀概率。
 */
class ScoreService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 并发 job 计数：只有最后一个 job 结束时才撤前台通知 / stopSelf，避免先完成的 job 把还在跑的那个踢出前台。 */
    private val runningJobs = java.util.concurrent.atomic.AtomicInteger(0)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            // START_STICKY 重启：系统回收进程后重新创建服务（intent 为空），从数据库恢复未完成的评分。
            startForeground(NOTIF_ID, buildNotification("恢复本地评分…"))
            resumePending()
            return START_STICKY
        }
        val sessionId = intent.getLongExtra("sessionId", -1L)
        val module = intent.getStringExtra("module") ?: ""
        val text = intent.getStringExtra("text") ?: ""
        val task = intent.getStringExtra("task") ?: ""
        if (sessionId <= 0) { stopSelf(); return START_NOT_STICKY }

        startForeground(NOTIF_ID, buildNotification(if (module == "speaking") "本地口语评分中…" else "本地写作评分中…"))
        runningJobs.incrementAndGet()
        // CPU 推理 1-8 分钟：亮屏熄灭/Doze 可能冻结进程，持一个带超时的 partial wake lock。
        val wake = (getSystemService(Context.POWER_SERVICE) as android.os.PowerManager)
            .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "ielts:local-score")
            .apply { setReferenceCounted(false); acquire(12 * 60_000L) }
        scope.launch {
            try {
                runScore(sessionId, module, text, task)
            } catch (e: Exception) {
                Log.e("ScoreService", "score fail: ${e.message}")
                AppDb.get(this@ScoreService).sessions().updateResult(
                    sessionId, text, "", "", "", "", "pending_eval",
                )
                Notify.scoreFailed(this@ScoreService, e.message ?: "未知错误", sessionId)
            } finally {
                runCatching { if (wake.isHeld) wake.release() }
                if (runningJobs.decrementAndGet() == 0) {
                    // 可选：评分结束后释放 2.7GB 模型内存，避免与语音助手等竞争（下次评分重新加载）。
                    if (Prefs(this@ScoreService).releaseAfterScore) runCatching { LocalLlmEngine.release() }
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    /** 系统回收后重启：找回最近 10 分钟内启动、仍未完成的本地评分并继续跑。 */
    private fun resumePending() {
        val db = AppDb.get(this)
        val prefs = Prefs(this)
        val started = runCatching { JSONObject(prefs.localScoreStarted) }.getOrDefault(JSONObject())
        scope.launch {
            val now = System.currentTimeMillis()
            val s = db.sessions().recent(20).firstOrNull {
                if (it.status != "pending_local") return@firstOrNull false
                val at = started.optLong(it.id.toString(), 0L)
                at > 0 && now - at < 10 * 60_000L
            }
            val text = s?.transcript?.ifBlank { s.essayText } ?: ""
            if (s == null || text.isBlank()) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return@launch
            }
            runningJobs.incrementAndGet()
            val wake = (getSystemService(Context.POWER_SERVICE) as android.os.PowerManager)
                .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "ielts:local-score")
                .apply { setReferenceCounted(false); acquire(12 * 60_000L) }
            try {
                // 题型优先取入库字段（v3），旧数据回退到启发式猜题
                val task = if (s.module == "speaking") "" else s.task.ifBlank { guessTaskForService(s.topic) }
                runScore(s.id, s.module, text, task)
            } catch (e: Exception) {
                Log.e("ScoreService", "resume fail: ${e.message}")
            } finally {
                runCatching { if (wake.isHeld) wake.release() }
                if (runningJobs.decrementAndGet() == 0) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    private suspend fun runScore(sessionId: Long, module: String, text: String, taskFromIntent: String) {
        val app = applicationContext
        val prefs = Prefs(app)
        val db = AppDb.get(app)
        val session = db.sessions().byId(sessionId) ?: return
        val weaknesses = com.txw.ielts.ui.topErrors(db.sessions().recent(100))
            .take(3).map { it.label }

        // 空文本不送模型：端侧推理要 3-4 分钟，且必然产出 0 分的假成绩（2026-10-31 遗留过这种记录）。
        if (!ScoreGate.shouldScore(text)) {
            Log.w("ScoreService", "skip scoring blank text id=$sessionId module=$module")
            db.sessions().updateResult(
                sessionId, session.transcript, session.essayText,
                session.scoresJson, session.fixesJson, session.polished, "asr_empty",
            )
            return
        }

        if (module == "speaking") {
            // topic 形如「Part 2 · question」；模考是「Mock Test · Part 1-3」。
            // 之前恒传字面量 "Part"，模型不知道考的是哪一部分。
            val part = when {
                session.topic.startsWith("Mock Test") -> "Part 1-3"
                session.topic.contains(" · ") -> session.topic.substringBefore(" · ").trim()
                else -> "Part"
            }
            var eval: SpeakingEval = LocalLlmEngine.scoreSpeaking(
                app, prefs.localModel, part, session.topic, text, session.durationSec, weaknesses,
            )
            if (eval.fixes.none { it.isNotBlank() } && eval.sentenceFixes.isNotEmpty()) {
                eval = eval.copy(fixes = eval.sentenceFixes.take(3).map { "${it.sentence} → ${it.better}（${it.issue}）" })
            }
            if (eval.fixes.none { it.isNotBlank() }) {
                val retry = runCatching {
                    LocalLlmEngine.scoreSpeaking(app, prefs.localModel, part, session.topic, text, session.durationSec, weaknesses)
                }.getOrNull()
                if (retry != null) {
                    eval = retry
                    if (eval.fixes.none { it.isNotBlank() } && eval.sentenceFixes.isNotEmpty()) {
                        eval = eval.copy(fixes = eval.sentenceFixes.take(3).map { "${it.sentence} → ${it.better}（${it.issue}）" })
                    }
                }
            }
            val words = text.split(Regex("\\s+")).count { it.isNotBlank() }
            val fillers = Regex("\\b(um+|uh+|er+m?|hmm+|ah+)\\b", RegexOption.IGNORE_CASE)
                .findAll(text).count()
            val wpm = if (session.durationSec > 0) (words * 60 / session.durationSec) else 0
            val fixesJson = JSONObject().apply {
                put("fixes", JSONArray(eval.fixes))
                // 客观指标单独存，结果页展示；也让用户知道 P 维度不是凭空评分。
                put("metrics", JSONObject().put("wpm", wpm).put("fillers", fillers).put("words", words))
                if (eval.sentenceFixes.isNotEmpty()) {
                    put("sentence_fixes", JSONArray().apply {
                        eval.sentenceFixes.forEach { sf ->
                            put(
                                JSONObject()
                                    .put("sentence", sf.sentence)
                                    .put("issue", sf.issue)
                                    .put("better", sf.better)
                            )
                        }
                    })
                }
            }.toString()
            db.sessions().updateResult(
                sessionId, text, "",
                JSONObject().apply {
                    put("FC", eval.fluency); put("LR", eval.lexical); put("GRA", eval.grammar)
                    put("P", eval.pronunciation); put("overall", eval.overall)
                }.toString(),
                fixesJson, eval.improved, ScoreGate.statusFor(eval.overall),
            )
            markScoreFinished(app, sessionId)
            if (ScoreGate.isValidBand(eval.overall)) Notify.scoreDone(app, eval.overall, sessionId)
        } else {
            val task = taskFromIntent.ifBlank {
                if (runCatching { JSONObject(session.scoresJson).has("TA") }.getOrDefault(false)) "task1" else "task2"
            }
            var eval: WritingEval = LocalLlmEngine.scoreWriting(
                app, prefs.localModel, session.topic, text, weaknesses, task,
            )
            // 模型偶尔不写 top_fixes：先用逐句改法兜底；两者都空再重试一次，避免结果页只有分数没有可执行反馈。
            if (eval.fixes.none { it.isNotBlank() } && eval.sentenceFixes.isNotEmpty()) {
                eval = eval.copy(fixes = eval.sentenceFixes.take(3).map { "${it.sentence} → ${it.better}（${it.issue}）" })
            }
            if (eval.fixes.none { it.isNotBlank() }) {
                val retry = runCatching {
                    LocalLlmEngine.scoreWriting(app, prefs.localModel, session.topic, text, weaknesses, task)
                }.getOrNull()
                if (retry != null) {
                    eval = retry
                    if (eval.fixes.none { it.isNotBlank() } && eval.sentenceFixes.isNotEmpty()) {
                        eval = eval.copy(fixes = eval.sentenceFixes.take(3).map { "${it.sentence} → ${it.better}（${it.issue}）" })
                    }
                }
            }
            // Task 1 的任务分项叫 TA，Task 2 叫 TR；写成同一个 key 会让结果页误判题型。
            val taskKey = if (task == "task1") "TA" else "TR"
            db.sessions().updateResult(
                sessionId, text, session.essayText.ifBlank { text },
                JSONObject().apply {
                    put(taskKey, eval.tr); put("CC", eval.cc); put("LR", eval.lr)
                    put("GRA", eval.gra); put("overall", eval.overall)
                }.toString(),
                JSONObject().apply {
                    put("fixes", JSONArray(eval.fixes))
                    if (eval.sentenceFixes.isNotEmpty()) {
                        put("sentence_fixes", JSONArray().apply {
                            eval.sentenceFixes.forEach { sf ->
                                put(
                                    JSONObject()
                                        .put("sentence", sf.sentence)
                                        .put("issue", sf.issue)
                                        .put("better", sf.better)
                                )
                            }
                        })
                    }
                }.toString(),
                eval.polished, ScoreGate.statusFor(eval.overall),
            )
            markScoreFinished(app, sessionId)
            if (ScoreGate.isValidBand(eval.overall)) Notify.scoreDone(app, eval.overall, sessionId)
        }
    }

    /** 记录评分完成时间，结果页据此显示「本次评分耗时」。 */
    private fun markScoreFinished(context: Context, id: Long) {
        val p = Prefs(context)
        val o = runCatching { JSONObject(p.localScoreFinished) }.getOrDefault(JSONObject())
        o.put(id.toString(), System.currentTimeMillis())
        p.localScoreFinished = o.toString()
    }

    private fun buildNotification(content: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "本地评分", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("雅思随身练")
            .setContentText(content)
            .setOngoing(true)
            // 通知上直接显示已用时，用户下拉就能知道评分跑了多久。
            .setShowWhen(true)
            .setWhen(System.currentTimeMillis())
            .setUsesChronometer(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "score_service"
        private const val NOTIF_ID = 2001

        fun start(context: Context, sessionId: Long, module: String, text: String, task: String = "task2"): Boolean {
            val i = Intent(context, ScoreService::class.java).apply {
                putExtra("sessionId", sessionId)
                putExtra("module", module)
                putExtra("text", text)
                putExtra("task", task)
            }
            // Android 12+ 在后台启动前台服务会抛 ForegroundServiceStartNotAllowedException；
            // 返回 false 让调用方把状态退回可重试，而不是静默卡在 pending_local。
            return runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(i)
                } else {
                    context.startService(i)
                }
                true
            }.getOrDefault(false)
        }
    }
}

/** 恢复任务时无法从空 scoresJson 判断题型：题干含图表词且不含 discuss → task1。 */
private fun guessTaskForService(topic: String): String {
    val t = topic.lowercase()
    return if (Regex("\\b(chart|graph|table|diagram|maps?)\\b").containsMatchIn(t) && !t.contains("discuss")) "task1" else "task2"
}
