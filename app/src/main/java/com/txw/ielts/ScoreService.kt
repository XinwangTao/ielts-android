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
import com.txw.ielts.llm.ScoreFailures
import com.txw.ielts.llm.ScoreGate
import com.txw.ielts.llm.SpeakingEval
import com.txw.ielts.llm.WritingEval
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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

    /** 同一会话只允许一个评分 job：重复点击「重新评分」不会再起第二个任务。 */
    private val runningIds: MutableSet<Long> = java.util.Collections.synchronizedSet(HashSet())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        active = false
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        active = true
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
        launchScoring(sessionId, module, text, task)
        return START_STICKY
    }

    /**
     * 启动一个评分 job（正常启动与 START_STICKY 恢复共用）。
     *
     * 同一 session 单飞：迟到的重复请求直接忽略——否则后失败的 job 会把先成功的结果覆盖掉。
     */
    private fun launchScoring(sessionId: Long, module: String, text: String, task: String) {
        if (!runningIds.add(sessionId)) {
            Log.w("ScoreService", "已有同一会话的评分在跑，忽略重复启动 id=$sessionId")
            if (runningJobs.get() == 0) stopSelf()
            return
        }
        runningJobs.incrementAndGet()
        val jobDone = java.util.concurrent.atomic.AtomicBoolean(false)
        // CPU 推理 1-8 分钟：亮屏熄灭/Doze 可能冻结进程，持一个带超时的 partial wake lock。
        val wake = (getSystemService(Context.POWER_SERVICE) as android.os.PowerManager)
            .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "ielts:local-score")
            .apply { setReferenceCounted(false); acquire(20 * 60_000L) }
        scope.launch {
            val watchdog = launch { watchdogLoop(sessionId, text, jobDone) }
            try {
                runScore(sessionId, module, text, task)
            } catch (e: Exception) {
                handleFailure(sessionId, text, e.message)
            } finally {
                watchdog.cancel()
                runCatching { if (wake.isHeld) wake.release() }
                finishJob(sessionId, jobDone)
            }
        }
    }

    /** job 收尾（正常结束/异常/看门狗判死共用）：CAS 保证只结算一次，最后一个 job 才停服务。 */
    private fun finishJob(sessionId: Long, jobDone: java.util.concurrent.atomic.AtomicBoolean) {
        if (!jobDone.compareAndSet(false, true)) return
        runningIds.remove(sessionId)
        if (runningJobs.decrementAndGet() == 0) {
            // 可选：评分结束后释放 2.7GB 模型内存，避免与语音助手等竞争（下次评分重新加载）。
            if (Prefs(this).releaseAfterScore) runCatching { LocalLlmEngine.release() }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /**
     * 失败收尾：只在记录仍是 pending_local 时回写——迟到的失败不能清掉另一个 job 已出的分。
     */
    private suspend fun handleFailure(sessionId: Long, text: String, message: String?) {
        Log.e("ScoreService", "score fail: ${message}")
        recordFailure(sessionId, message)
        runCatching {
            AppDb.get(this).sessions().updateResultIfPending(
                sessionId, text, "", "", "", "", "pending_eval",
            )
        }
        Notify.scoreFailed(this, message ?: "未知错误", sessionId)
    }

    /**
     * 盯引擎心跳。为什么必须有：
     * 「加载」和「推理」都是阻塞的 native 调用。App 被 ColorOS 冻结后，协程会停在 s.load()/s.submit()
     * 里永不返回 —— runScore 的 try/catch 根本没机会执行，session 就永远停在 pending_local，
     * 结果页也只能无限数「已等待 N 分」。这里用心跳判死，把结果退成可重试，并把卡死的引擎强拆。
     */
    private suspend fun CoroutineScope.watchdogLoop(
        sessionId: Long,
        text: String,
        jobDone: java.util.concurrent.atomic.AtomicBoolean,
    ) {
        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        var lastBeatSeen = 0L
        // 熄屏期间累计的「系统暂停」时长：只从当前这段心跳间隔里扣，不能跨心跳累加。
        var offAccumMs = 0L
        while (isActive) {
            delay(WATCH_TICK_MS)
            val now = System.currentTimeMillis()
            val beat = LocalLlmEngine.lastBeatAt
            if (beat != lastBeatSeen) {
                lastBeatSeen = beat
                offAccumMs = 0
            }
            // 熄屏时 ColorOS 会把进程冻结一段再自动解冻（2026-11-06 实测：熄屏后 freeze=1 约 40s，
            // 随后继续出 token 并正常评分完成）。熄屏期间的停滞是系统暂停，不是引擎卡死，
            // 不计入判定；亮屏后只按「熄屏之外的真实停滞」决定是否强拆。
            if (!pm.isInteractive) {
                offAccumMs += WATCH_TICK_MS
                continue
            }
            val state = LocalLlmEngine.state
            if (beat <= 0 || state == "idle") continue
            val activeIdleMs = (now - beat - offAccumMs).coerceAtLeast(0)
            // 加载阶段没有 token 心跳：实测后台冷加载最长约 5 分钟（14:16 那次 429s 全程）。
            // 用统一的 180s 会把「正在正常加载」误判成卡死，白等一次加载又重来。
            val stallMs = if (state == "loading") LOAD_STALL_AFTER_MS else STALL_AFTER_MS
            if (activeIdleMs <= stallMs) continue
            Log.w("ScoreService", "watchdog: engine stalled ${activeIdleMs}ms state=$state, forceReset id=$sessionId")
            LocalLlmEngine.forceReset()
            val reason = "系统把后台评分中断了（后台限速/冻结），内容已保存，请重新评分"
            recordFailure(sessionId, reason)
            runCatching {
                AppDb.get(this@ScoreService).sessions().updateResult(
                    sessionId, text, "", "", "", "", "pending_eval",
                )
            }
            Notify.scoreFailed(
                this@ScoreService, reason, sessionId,
            )
            // 卡死的协程永远不会走到 finally，这里替它收尾；其它仍在跑的 job 不受影响。
            finishJob(sessionId, jobDone)
            return
        }
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
                if (runningJobs.get() == 0) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                return@launch
            }
            // 题型优先取入库字段（v3），旧数据回退到启发式猜题
            val task = if (s.module == "speaking") "" else s.task.ifBlank { guessTaskForService(s.topic) }
            // 复用统一入口：带看门狗、同一会话单飞、失败只回写 pending_local。
            launchScoring(s.id, s.module, text, task)
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
            clearFailure(sessionId)
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
                put("metrics", JSONObject()
                    .put("wpm", wpm).put("fillers", fillers).put("words", words)
                    .put("p_estimated", eval.estimatedP))
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
        // 这次已经出分：把上一轮的失败原因清掉，结果页不再显示「上次失败」。
        p.localScoreErrors = ScoreFailures.clear(p.localScoreErrors, id)
    }

    /** 失败原因按会话存下：通知划掉、重开结果页甚至重启 App 后都还能看到为什么没出分。 */
    private fun recordFailure(id: Long, reason: String?) {
        val p = Prefs(this)
        p.localScoreErrors = ScoreFailures.record(p.localScoreErrors, id, reason)
    }

    private fun clearFailure(id: Long) {
        val p = Prefs(this)
        p.localScoreErrors = ScoreFailures.clear(p.localScoreErrors, id)
    }

    private fun buildNotification(content: String): Notification {
        // minSdk 26：通知渠道无需再判断版本。
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "本地评分", NotificationManager.IMPORTANCE_LOW)
        )
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

        /** 是否有评分服务实例存活：回前台自动续跑时避免重复启动。 */
        @Volatile
        var active: Boolean = false

        /** 心跳断多久算卡死。留得比一次「加载+一个 token 批次」的长尾大，避免误判正常慢评分。 */
        private const val STALL_AFTER_MS = 180_000L

        /** 加载阶段没有 token 心跳，实测后台冷加载可达 5 分钟；阈值必须显著大于最慢的正常加载。 */
        private const val LOAD_STALL_AFTER_MS = 8 * 60_000L

        /** 看门狗轮询间隔；熄屏暂停时长也按这个步长累计，避免依赖真实时间差。 */
        private const val WATCH_TICK_MS = 15_000L

        fun start(context: Context, sessionId: Long, module: String, text: String, task: String = "task2"): Boolean {
            // 结果页用这个时间算「已等待 N 分」；只有 MainActivity 的部分入口写过，
            // onResume 续跑/Service 重启路径没写，结果页就会退化成从录音时刻开始数。
            // 统一在这里幂等补齐：已有值不覆盖（避免续跑把等待时间重置）。
            runCatching {
                val prefs = Prefs(context)
                val o = JSONObject(prefs.localScoreStarted)
                if (o.optLong(sessionId.toString(), 0L) <= 0L) {
                    o.put(sessionId.toString(), System.currentTimeMillis())
                    prefs.localScoreStarted = o.toString()
                }
            }
            val i = Intent(context, ScoreService::class.java).apply {
                putExtra("sessionId", sessionId)
                putExtra("module", module)
                putExtra("text", text)
                putExtra("task", task)
            }
            // Android 12+ 在后台启动前台服务会抛 ForegroundServiceStartNotAllowedException；
            // 返回 false 让调用方把状态退回可重试，而不是静默卡在 pending_local。
            // minSdk 26：startForegroundService 恒可用，无需判断版本。
            return runCatching {
                context.startForegroundService(i)
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
