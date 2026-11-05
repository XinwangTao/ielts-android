package com.txw.ielts

import android.os.Bundle
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.txw.ielts.data.AppDb
import com.txw.ielts.data.BankQuestion
import com.txw.ielts.data.BankRepo
import com.txw.ielts.data.BankSet
import com.txw.ielts.data.BankWrong
import com.txw.ielts.data.CardRepo
import com.txw.ielts.data.Prefs
import com.txw.ielts.data.ReviewQueue
import com.txw.ielts.data.Session
import com.txw.ielts.data.ShadowDiff
import com.txw.ielts.data.ShadowPassage
import com.txw.ielts.data.ShadowRepo
import com.txw.ielts.llm.BankExplain
import com.txw.ielts.llm.ChannelPick
import com.txw.ielts.llm.LocalLlmEngine
import com.txw.ielts.llm.LlmClient
import com.txw.ielts.llm.ScoreSanitizer
import com.txw.ielts.llm.ScoreGate
import com.txw.ielts.llm.routeLlm
import com.txw.ielts.llm.SpeakingEval
import com.txw.ielts.llm.WritingEval
import com.txw.ielts.ui.AiGenScreen
import com.txw.ielts.ui.ArgumentScreen
import com.txw.ielts.ui.BankResultItem
import com.txw.ielts.ui.topErrors
import com.txw.ielts.ui.BankResultScreen
import com.txw.ielts.ui.BankScreen
import com.txw.ielts.ui.ChartGenScreen
import com.txw.ielts.ui.HomeScreen
import com.txw.ielts.ui.CorpusHomeScreen
import com.txw.ielts.ui.IeltsTheme
import com.txw.ielts.ui.ModelEssayScreen
import com.txw.ielts.ui.MockResultScreen
import com.txw.ielts.ui.QuizScreen
import com.txw.ielts.ui.ShadowHomeScreen
import com.txw.ielts.ui.ShadowResultItem
import com.txw.ielts.ui.ShadowResultScreen
import com.txw.ielts.ui.ShadowStudyScreen
import com.txw.ielts.ui.StudyScreen
import com.txw.ielts.ui.TopicScreen
import com.txw.ielts.ui.ResultScreen
import com.txw.ielts.ui.ReviewScreen
import com.txw.ielts.ui.SettingsScreen
import com.txw.ielts.ui.SkillTargets
import com.txw.ielts.ui.SpeakingScreen
import com.txw.ielts.ui.SpeakingMockScreen
import com.txw.ielts.ui.SpeakingPartnerScreen
import com.txw.ielts.ui.WritingScreen
import com.txw.ielts.ui.WritingMockScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

private const val TAG = "E2E"

sealed interface Route {
    data object Home : Route
    data object Writing : Route
    data object Speaking : Route
    data object Settings : Route
    data object Review : Route
    data object Corpus : Route
    data object Study : Route
    data object Bank : Route
    data class Quiz(val setId: String) : Route
    data class BankResult(val sessionId: Long, val setId: String) : Route
    data object AiGen : Route
    data object Shadow : Route
    data object Topics : Route
    data object Arguments : Route
    data object ModelEssays : Route
    data object WritingMock : Route
    data object ChartGen : Route
    data object SpeakingPartner : Route
    data object SpeakingMock : Route
    data class MockResult(val ids: List<Long>) : Route
    data class ShadowStudy(val passageId: String) : Route
    data class ShadowResult(val sessionId: Long) : Route
    data class Result(val id: Long) : Route
}

/**
 * Route 的字符串编解码：用于 rememberSaveable（旋转屏 / 进程被回收后恢复页面）。
 * 没有它，`remember` 会在 Activity 重建时回到首页——用户写作到一半转个屏就丢页。
 * 未知/损坏的串一律回首页，不抛异常。
 */
internal fun Route.encode(): String = when (this) {
    Route.Home -> "home"
    Route.Writing -> "writing"
    Route.Speaking -> "speaking"
    Route.Settings -> "settings"
    Route.Review -> "review"
    Route.Corpus -> "corpus"
    Route.Study -> "study"
    Route.Bank -> "bank"
    Route.AiGen -> "aigen"
    Route.Shadow -> "shadow"
    Route.Topics -> "topics"
    Route.Arguments -> "arguments"
    Route.ModelEssays -> "modelessays"
    Route.WritingMock -> "writingmock"
    Route.ChartGen -> "chartgen"
    Route.SpeakingPartner -> "speakingpartner"
    Route.SpeakingMock -> "speakingmock"
    is Route.Quiz -> "quiz:$setId"
    // setId 理论上不含冒号，取最后一个分隔符之前的全部内容即可
    is Route.BankResult -> "bankresult:$sessionId:$setId"
    is Route.MockResult -> "mockresult:${ids.joinToString(",")}"
    is Route.ShadowStudy -> "shadowstudy:$passageId"
    is Route.ShadowResult -> "shadowresult:$sessionId"
    is Route.Result -> "result:$id"
}

internal fun decodeRoute(s: String): Route {
    if (!s.contains(':')) {
        return when (s) {
            "writing" -> Route.Writing
            "speaking" -> Route.Speaking
            "settings" -> Route.Settings
            "review" -> Route.Review
            "corpus" -> Route.Corpus
            "study" -> Route.Study
            "bank" -> Route.Bank
            "aigen" -> Route.AiGen
            "shadow" -> Route.Shadow
            "topics" -> Route.Topics
            "arguments" -> Route.Arguments
            "modelessays" -> Route.ModelEssays
            "writingmock" -> Route.WritingMock
            "chartgen" -> Route.ChartGen
            "speakingpartner" -> Route.SpeakingPartner
            "speakingmock" -> Route.SpeakingMock
            else -> Route.Home
        }
    }
    val prefix = s.substringBefore(':')
    val rest = s.substringAfter(':')
    return when (prefix) {
        "quiz" -> Route.Quiz(rest)
        "bankresult" -> {
            val id = rest.substringBefore(':').toLongOrNull()
            val setId = rest.substringAfter(':', "")
            if (id != null) Route.BankResult(id, setId) else Route.Home
        }
        "mockresult" -> {
            val ids = rest.split(',').mapNotNull { it.trim().toLongOrNull() }
            if (ids.isEmpty()) Route.Home else Route.MockResult(ids)
        }
        "shadowstudy" -> rest.takeIf { it.isNotBlank() }?.let { Route.ShadowStudy(it) } ?: Route.Home
        "shadowresult" -> rest.toLongOrNull()?.let { Route.ShadowResult(it) } ?: Route.Home
        "result" -> rest.toLongOrNull()?.let { Route.Result(it) } ?: Route.Home
        else -> Route.Home
    }
}

private val RouteSaver: Saver<Route, String> = Saver(
    save = { it.encode() },
    restore = { decodeRoute(it) },
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)
        intent?.getStringExtra("apikey")?.let { prefs.apiKey = it }
        intent?.getStringExtra("baseurl")?.let { prefs.baseUrl = it }
        intent?.getStringExtra("model")?.let { prefs.model = it }
        intent?.getStringExtra("asr")?.let { prefs.asrModel = it }

        val e2eWav = intent?.getStringExtra("e2e_wav")
        val e2eSpeaking = intent?.getBooleanExtra("e2e_speaking", false) == true
        // 调试直达：route=bank / route=quiz:<setId>；bank_auto=true 时自动作答并交卷
        // 评分完成通知点击：result_session_id → 直达结果页
        val resultSessionId = intent?.getLongExtra("result_session_id", -1L) ?: -1L
        val startRoute = intent?.getStringExtra("route")
            ?: if (resultSessionId > 0) "result:$resultSessionId" else null
        // 只有全新启动才应用深链（通知点击/调试直达）；旋转屏或进程重建时交给 rememberSaveable 恢复原页面，
        // 否则同一个 intent 会在每次重建时把用户从当前页踢回结果页。
        val freshStart = savedInstanceState == null
        Log.i(TAG, "start_route=$startRoute result_session_id=$resultSessionId")
        val bankAuto = intent?.getBooleanExtra("bank_auto", false) == true
        val e2eTts = intent?.getBooleanExtra("e2e_tts", false) == true
        // 诊断：只加载本地模型不生成，用于测「加载后 RSS / release 是否真释放」。
        val e2eLoadModel = intent?.getBooleanExtra("e2e_loadmodel", false) == true

        // 本地评分完成通知（Android 13+ 需要运行时授权；拒绝只影响通知，不影响评分）
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            runCatching {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
            }
        }

        setContent {
            IeltsTheme {
                Surface(Modifier.fillMaxSize()) {
                    App(
                        prefs,
                        if (e2eSpeaking) e2eWav else null,
                        if (e2eSpeaking) null else e2eWav,
                        startRoute,
                        bankAuto,
                        e2eTts,
                        freshStart,
                        e2eLoadModel,
                    )
                }
            }
        }
    }
}

/** 评分后的「带着改进点重练」上下文：结果页 → 写作/口语页。 */
private data class PracticeFocus(
    val module: String,        // writing / speaking
    val topic: String,
    val fixes: List<String>,
    val task: String = "task2",
    val part: String = "Part 2",
)

/** 交卷判分后的结果快照：不依赖 Room Flow 异步刷新，交卷即可见。 */
private data class BankResultData(    val setId: String,
    val title: String,
    val module: String,
    val passage: String,
    val transcript: String,
    val correct: Int,
    val total: Int,
    val items: List<BankResultItem>,
)

/** 离线时 auto 通道直接走本地，避免先白等云端重试（每次约 50 秒才降级）。 */
private fun hasNetwork(context: Context): Boolean = runCatching {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return@runCatching true
    val net = cm.activeNetwork ?: return@runCatching false
    val caps = cm.getNetworkCapabilities(net) ?: return@runCatching false
    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}.getOrDefault(true)

/**
 * 额度类失败（403/429/11200/quota/额度）→ 云端冷却 1 小时，auto 直接走本地。
 * 这类失败不会自愈，不冷却的话每条云端路径都会先白试一次再回退。
 */
private fun markCloudDown(prefs: Prefs, e: Throwable?) {
    if (ChannelPick.isQuotaFailure(e?.message)) {
        prefs.cloudDownUntil = System.currentTimeMillis() + 3_600_000
    }
}

/** 无法从空 scoresJson 判断题型时的启发式：图表/流程图题干 → task1（Task2 议论文题干一般含 discuss）。 */
internal fun guessTask(topic: String): String {
    val t = topic.lowercase()
    val looksChart = Regex("\\b(chart|graph|table|diagram|maps?)\\b").containsMatchIn(t)
    return if (looksChart && !t.contains("discuss")) "task1" else "task2"
}

/** 题型优先取字段（v3 起入库），旧数据回退到启发式猜题。 */
internal fun Session.resolvedTask(): String = task.ifBlank { guessTask(topic) }

@Composable
private fun App(
    prefs: Prefs,
    e2eSpeakingWav: String?,
    e2eWritingWav: String?,
    initialRoute: String? = null,
    bankAuto: Boolean = false,
    e2eTts: Boolean = false,
    freshStart: Boolean = true,
    e2eLoadModel: Boolean = false,
) {
    val context = LocalContext.current
    val isWide = LocalConfiguration.current.screenWidthDp >= 600
    val db = remember { AppDb.get(context) }
    val scope = rememberCoroutineScope()
    // null = Room Flow 还没吐出第一帧。结果页直达要在「加载完成」后才判断记录不存在，
    // 否则 App 冷启动时会话列表为空，会被误判为「记录已删除」瞬间弹回首页。
    val sessionsOrNull by db.sessions().observeAll().collectAsState(initial = null)
    val sessions = sessionsOrNull ?: emptyList()

    var route by rememberSaveable(stateSaver = RouteSaver) { mutableStateOf<Route>(Route.Home) }
    // 页面跳转诊断：旋转屏 / 进程重建 / 深链时用它确认 route 是否符合预期。
    LaunchedEffect(route) { Log.i(TAG, "route=${route.encode()} (freshStart=$freshStart)") }
    var stage by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var practiceFocus by remember { mutableStateOf<PracticeFocus?>(null) }
    var speakingPresetQuestion by remember { mutableStateOf<String?>(null) }
    var writingPresetTopic by remember { mutableStateOf<String?>(null) }
    var writingPresetChart by remember { mutableStateOf<com.txw.ielts.data.ChartSpec?>(null) }

    // 本地 AI 学习助手（图：docs/archify/ielts-local-ai_workflow）：
    // 提纲/范例/生词卡都只在用户点击时生成，不自动跑，避免和评分抢引擎。
    var writingOutline by remember { mutableStateOf<com.txw.ielts.llm.StudyOutline?>(null) }
    var writingOutlineTopic by remember { mutableStateOf<String?>(null) }
    var writingOutlineBusy by remember { mutableStateOf(false) }
    var writingOutlineError by remember { mutableStateOf<String?>(null) }
    var speakingSample by remember { mutableStateOf<com.txw.ielts.llm.SpeakingSample?>(null) }
    var speakingSampleQuestion by remember { mutableStateOf<String?>(null) }
    var speakingSampleBusy by remember { mutableStateOf(false) }
    var speakingSampleError by remember { mutableStateOf<String?>(null) }
    var vocabResult by remember { mutableStateOf<com.txw.ielts.llm.VocabResult?>(null) }
    var vocabSessionId by remember { mutableStateOf<Long?>(null) }
    var vocabBusy by remember { mutableStateOf(false) }
    var vocabError by remember { mutableStateOf<String?>(null) }
    var aiReview by remember { mutableStateOf<com.txw.ielts.llm.AiReview?>(null) }
    var aiReviewBusy by remember { mutableStateOf(false) }
    var aiReviewError by remember { mutableStateOf<String?>(null) }
    var recoveryNotice by remember { mutableStateOf<String?>(null) }

    // 本地评分或本地 AI 生成在跑时钉住屏幕：熄屏会让 ColorOS 冻结进程，
    // 端侧推理（评分 / 提纲 / 范例 / 生词卡 / 复盘）必然中断（docs/oppo-background-scoring-findings.md §九）。
    com.txw.ielts.ui.KeepScreenOn(
        sessionsOrNull?.any { it.status == "pending_local" } == true ||
            writingOutlineBusy || speakingSampleBusy || vocabBusy || aiReviewBusy,
    )

    // 恢复完成后撤掉首页提示：不再有 pending_local 就说明评分已出结果或已转可重试。
    LaunchedEffect(sessions) {
        if (recoveryNotice != null && sessions.none { it.status == "pending_local" }) recoveryNotice = null
    }

    // 三个入口共用本地引擎闸门；生成期间界面显示「约 30-60 秒」，失败只影响当前卡片。
    fun localModelMissing(): Boolean =
        !LocalLlmEngine.isReady(context, prefs.localModel) &&
            !LocalLlmEngine.hasExternalCopy(context, prefs.localModel)

    suspend fun requestWritingOutline(topic: String, task: String) {
        writingOutlineBusy = true
        writingOutlineError = null
        writingOutlineTopic = topic
        if (LocalLlmEngine.isBusy) {
            writingOutlineError = "本地模型正在忙（评分或另一项生成中），请等它完成后再试"
            writingOutlineBusy = false
            return
        }
        if (localModelMissing()) {
            writingOutlineError = "本地模型未就绪：请到设置页确认模型已投递"
            writingOutlineBusy = false
            return
        }
        try {
            val r = withContext(Dispatchers.IO) {
                com.txw.ielts.llm.StudyAi.outline(context, prefs.localModel, topic, task)
            }
            if (r.isEmpty) writingOutlineError = "本地模型没有输出可用的提纲，请重试" else writingOutline = r
        } catch (e: Exception) {
            writingOutlineError = "提纲生成失败：${e.message}"
        } finally {
            writingOutlineBusy = false
        }
    }

    suspend fun requestSpeakingSample(part: String, question: String) {
        speakingSampleBusy = true
        speakingSampleError = null
        speakingSampleQuestion = question
        if (LocalLlmEngine.isBusy) {
            speakingSampleError = "本地模型正在忙（评分或另一项生成中），请等它完成后再试"
            speakingSampleBusy = false
            return
        }
        if (localModelMissing()) {
            speakingSampleError = "本地模型未就绪：请到设置页确认模型已投递"
            speakingSampleBusy = false
            return
        }
        try {
            val r = withContext(Dispatchers.IO) {
                com.txw.ielts.llm.StudyAi.speakingSample(context, prefs.localModel, part, question)
            }
            if (r.isEmpty) speakingSampleError = "本地模型没有输出可用的范例，请重试" else speakingSample = r
        } catch (e: Exception) {
            speakingSampleError = "范例生成失败：${e.message}"
        } finally {
            speakingSampleBusy = false
        }
    }

    suspend fun requestVocab(sessionId: Long, text: String, fixes: List<Triple<String, String, String>>) {
        vocabBusy = true
        vocabError = null
        vocabSessionId = sessionId
        if (LocalLlmEngine.isBusy) {
            vocabError = "本地模型正在忙（评分或另一项生成中），请等它完成后再试"
            vocabBusy = false
            return
        }
        if (localModelMissing()) {
            vocabError = "本地模型未就绪：请到设置页确认模型已投递"
            vocabBusy = false
            return
        }
        try {
            val r = withContext(Dispatchers.IO) {
                com.txw.ielts.llm.StudyAi.vocab(context, prefs.localModel, text, fixes)
            }
            vocabResult = r
        } catch (e: Exception) {
            vocabError = "生词提取失败：${e.message}"
        } finally {
            vocabBusy = false
        }
    }

    /** 复盘输入：最近 7 天成绩 + 高频问题；纯函数在 ReviewSummary，听读显示 correct/total 而不是 Band 0。 */
    fun buildReviewSummary(): String = com.txw.ielts.llm.ReviewSummary.build(
        sessions = sessions,
        targetBand = prefs.targetBand,
        weaknesses = com.txw.ielts.ui.topErrors(sessions).map { it.label to it.count },
    )

    suspend fun requestAiReview() {
        aiReviewBusy = true
        aiReviewError = null
        if (LocalLlmEngine.isBusy) {
            aiReviewError = "本地模型正在忙（评分或另一项生成中），请等它完成后再试"
            aiReviewBusy = false
            return
        }
        if (localModelMissing()) {
            aiReviewError = "本地模型未就绪：请到设置页确认模型已投递"
            aiReviewBusy = false
            return
        }
        try {
            val r = withContext(Dispatchers.IO) {
                com.txw.ielts.llm.StudyAi.review(context, prefs.localModel, buildReviewSummary())
            }
            if (r.isEmpty) aiReviewError = "本地模型没有输出可用的复盘，请重试" else aiReview = r
        } catch (e: Exception) {
            aiReviewError = "复盘生成失败：${e.message}"
        } finally {
            aiReviewBusy = false
        }
    }

    // 启动时清理「卡死的本地评分」：进程被系统杀死后 pending_local 会永远停在评分中，超过 10 分钟重置为可重试。
    LaunchedEffect(Unit) {
        delay(1500)
        // 引擎仍在生成/加载时不要动手：8 分钟上限 + 模型加载可能刚好超过 10 分钟阈值。
        if (LocalLlmEngine.state != "idle") return@LaunchedEffect
        val startedMap = runCatching { JSONObject(prefs.localScoreStarted) }.getOrDefault(JSONObject())
        val stale = db.sessions().recent(50).filter {
            if (it.status != "pending_local") return@filter false
            // 评分任务的启动时间优先；旧数据没有记录时回退到练习开始时间。
            val started = startedMap.optLong(it.id.toString(), 0L).takeIf { v -> v > 0 } ?: it.startedAt
            System.currentTimeMillis() - started > 10 * 60_000L
        }
        // 超过 10 分钟仍 pending_local：有文本的自动重新排队（最多 2 条），没有文本的退回可重试。
        var requeued = false
        stale.take(2).forEach { s ->
            val text = s.transcript.ifBlank { s.essayText }
            if (text.isBlank()) {
                db.sessions().updateResult(
                    s.id, s.transcript, s.essayText, s.scoresJson, s.fixesJson, s.polished, "pending_eval",
                )
                return@forEach
            }
            val task = s.task.ifBlank { guessTask(s.topic) }
            val m = runCatching { JSONObject(prefs.localScoreStarted) }.getOrDefault(JSONObject())
            m.put(s.id.toString(), System.currentTimeMillis())
            prefs.localScoreStarted = m.toString()
            db.sessions().updateResult(
                s.id, s.transcript, s.essayText, s.scoresJson, s.fixesJson, s.polished, "pending_local",
            )
            if (s.module == "speaking") ScoreService.start(context, s.id, "speaking", text)
            else ScoreService.start(context, s.id, "writing", text, task)
            requeued = true
        }
        if (requeued) {
            recoveryNotice = "上次评分被系统中断，正在恢复（约 1-3 分钟）。可以先去练习，完成后会通知。"
        }
        stale.drop(2).forEach {
            db.sessions().updateResult(
                it.id, it.transcript, it.essayText, it.scoresJson, it.fixesJson, it.polished, "pending_eval",
            )
        }

        // 清理「无可救药」记录：既无文本、又无法重新转写（无音频或音频文件已丢失）的未完成记录
        // 永远不可能评分成功，会一直卡在「学习统计 → 待处理」里（实测有 2026-10-29 遗留的记录挂了 5 天）。
        // 只判 audioPath 是否为空还不够：session 10 指向的 /sdcard/retry_test.wav 早已不存在，
        // 而 session 5 的 essay16k.wav 还在、重试就能救回来，两者不能混为一谈。
        // 30 分钟阈值：正常评分/转写在几分钟内就会写入文本，超时即视为中断。
        db.sessions().recent(50)
            .filter { s ->
                if (s.status !in setOf("pending_eval", "pending_local", "asr_empty")) return@filter false
                if (s.transcript.isNotBlank() || s.essayText.isNotBlank()) return@filter false
                if (System.currentTimeMillis() - s.startedAt < 30 * 60_000L) return@filter false
                s.audioPath.isNullOrBlank() || !File(s.audioPath).exists()
            }
            .forEach { s ->
                Log.i(TAG, "archive unrecoverable record id=${s.id} status=${s.status} audio=${s.audioPath}")
                db.sessions().updateResult(
                    s.id, s.transcript, s.essayText, s.scoresJson, s.fixesJson, s.polished, "abandoned",
                )
            }

        // 录音清理：转写/评分后的 wav 只在结果页回放时用，长期堆积会吃满空间
        // （实测 files/ 下残留 2.3MB 的 partner_*.wav 十余个）。保留 session 仍引用的文件与 7 天内新文件。
        runCatching {
            val referenced = db.sessions().recent(200).mapNotNull { it.audioPath }.toSet()
            val cutoff = System.currentTimeMillis() - 7 * 86_400_000L
            context.filesDir.listFiles { f -> f.isFile && f.name.endsWith(".wav", ignoreCase = true) }
                ?.filter { it.absolutePath !in referenced && it.lastModified() < cutoff }
                ?.forEach { f -> if (f.delete()) Log.i(TAG, "cleanup recording ${f.name}") }
        }
    }

    // ---- 语料积累 ----
    val repo = remember { CardRepo(context, db) }
    var due by remember { mutableIntStateOf(0) }
    var fresh by remember { mutableIntStateOf(0) }
    var totalCards by remember { mutableIntStateOf(0) }
    var mastered by remember { mutableIntStateOf(0) }
    var typeCounts by remember { mutableStateOf<List<Pair<String, Int>>>(emptyList()) }
    var generated by remember { mutableIntStateOf(0) }
    var generatedCap by remember { mutableIntStateOf(CardRepo.AI_CARD_CAP) }
    var wrongCount by remember { mutableIntStateOf(0) }
    var reviewedToday by remember { mutableIntStateOf(0) }

    // —— 今日任务：基于数据的推荐（错题/到期卡/未练模块），碎片时间不用自己想练什么 ——
    val dayMs = 86_400_000L
    val lastSpeaking = sessions.filter { it.module == "speaking" }.maxOfOrNull { it.startedAt }
    val lastWriting = sessions.filter { it.module == "writing" }.maxOfOrNull { it.startedAt }
    val nowMs = System.currentTimeMillis()
    var queue by remember { mutableStateOf<List<com.txw.ielts.data.Card>>(emptyList()) }
    var cardIndex by remember { mutableIntStateOf(0) }
    var revealed by remember { mutableStateOf(false) }

    // 今日任务：点一下就直接开始，不再先跳语料首页再点第二轮。
    fun startWrongQueue() {
        scope.launch {
            queue = repo.buildWrongQueue()
            cardIndex = 0
            revealed = false
            if (queue.isEmpty()) error = "暂无到期错题" else route = Route.Study
        }
    }
    fun startDueQueue() {
        scope.launch {
            queue = repo.buildQueue(prefs.dailyNewCards)
            cardIndex = 0
            revealed = false
            if (queue.isEmpty()) error = "今天没有待学卡片" else route = Route.Study
        }
    }
    // 临考收紧：考前两周口语/写作的复练间隔自动缩短（14 天内减半、7 天内每天），
    // 平时不必天天练的项目，临近考试就不能再等 3 天了。
    val daysLeft = remember(prefs.examDate) { com.txw.ielts.data.ExamTarget.daysUntil(prefs.examDate, nowMs) }
    val speakEvery = com.txw.ielts.data.ExamTarget.reviewInterval(2, daysLeft)
    val writeEvery = com.txw.ielts.data.ExamTarget.reviewInterval(3, daysLeft)
    // 四科目标与最弱科：目标和首页共用一份，今日任务按「差得最多」排，而不是只看隔了几天。
    val skillTargets = SkillTargets(
        listening = prefs.targetListening.toDouble(),
        reading = prefs.targetReading.toDouble(),
        writing = prefs.targetWriting.toDouble(),
        speaking = prefs.targetSpeaking.toDouble(),
    )
    val skillGap = remember(sessions, prefs.targetBand, skillTargets) {
        com.txw.ielts.ui.weakestGap(com.txw.ielts.ui.skillProfile(sessions), skillTargets)
    }
    var bankFilter by remember { mutableStateOf("all") }
    fun routeFor(module: String): Route = when (module) {
        "writing" -> Route.Writing
        "speaking" -> Route.Speaking
        else -> Route.Bank // 听力/阅读共用题库页，进去再按模块筛
    }
    val speakingDue = lastSpeaking == null || nowMs - lastSpeaking >= speakEvery * dayMs
    val writingDue = lastWriting == null || nowMs - lastWriting >= writeEvery * dayMs
    val dailyPlan: List<Pair<String, () -> Unit>> = buildList {
        if (wrongCount > 0) add("错题专项 · 重刷 $wrongCount 张" to { startWrongQueue() })
        // 最弱科单列一条；如果它正好也到点该练了，就把差距写进那条，别占两个位置。
        if (skillGap != null && !(skillGap.module == "speaking" && speakingDue) &&
            !(skillGap.module == "writing" && writingDue)
        ) {
            val g = skillGap
            add("${g.label}差 %.1f 分 · 优先补".format(g.gap) to {
                if (g.module == "listening" || g.module == "reading") bankFilter = g.module
                route = routeFor(g.module)
            })
        }
        if (speakingDue) {
            val ago = if (lastSpeaking == null) "还没开口过" else "距上次 ${(nowMs - lastSpeaking) / dayMs} 天"
            val note = if (skillGap?.module == "speaking") "，差 %.1f".format(skillGap.gap) else ""
            add("口语该练了 · $ago$note" to { route = Route.Speaking })
        }
        if (due > 0) add("复习 $due 张到期卡片" to { startDueQueue() })
        if (writingDue) {
            val ago = if (lastWriting == null) "还没写过" else "距上次 ${(nowMs - lastWriting) / dayMs} 天"
            val note = if (skillGap?.module == "writing") "，差 %.1f".format(skillGap.gap) else ""
            add("写作该练了 · $ago$note" to { route = Route.Writing })
        }
    }.take(3)
    val (ttsAvailable, speak) = com.txw.ielts.ui.rememberTts()

    // ---- 听力/阅读题库（全离线，判分不调用任何接口）----
    val bankRepo = remember { BankRepo(context, prefs) }
    val bankSets = remember { runCatching { bankRepo.sets() }.getOrDefault(emptyList()) }
    var bankAnswers by remember { mutableStateOf<Map<Int, String>>(emptyMap()) }
    var quizStartAt by remember { mutableStateOf(0L) }
    var quizLimitMin by remember { mutableIntStateOf(0) }
    var bankResult by remember { mutableStateOf<BankResultData?>(null) }
    var aiSets by remember { mutableStateOf(bankRepo.aiSets()) }
    var aiGenBusy by remember { mutableStateOf(false) }
    var aiGenError by remember { mutableStateOf<String?>(null) }
    var aiGenStage by remember { mutableStateOf<String?>(null) }
    var aiGenReady by remember { mutableStateOf<String?>(null) }   // 后台生成完成待查看的套题 id
    var aiExplains by remember { mutableStateOf<Map<Int, BankExplain>>(emptyMap()) }
    var explainingIds by remember { mutableStateOf<Set<Int>>(emptySet()) }

    fun openSet(setId: String, limitMin: Int = 0) {
        bankAnswers = bankRepo.savedAnswers(setId)
        quizStartAt = System.currentTimeMillis()
        quizLimitMin = limitMin
        aiExplains = emptyMap()
        explainingIds = emptySet()
        route = Route.Quiz(setId)
    }

    // ---- 口语跟读（全程零 LLM：端侧转写 + 系统 TTS + 本地词级对比）----
    val shadowRepo = remember { ShadowRepo(context) }
    val shadowPassages = remember { runCatching { shadowRepo.passages() }.getOrDefault(emptyList()) }
    var shadowPassage by remember { mutableStateOf<ShadowPassage?>(null) }
    var shadowIndex by remember { mutableIntStateOf(0) }
    var shadowDiffs by remember { mutableStateOf<Map<Int, ShadowDiff>>(emptyMap()) }
    var shadowTranscripts by remember { mutableStateOf<Map<Int, String>>(emptyMap()) }

    fun openShadow(id: String) {
        shadowPassage = shadowRepo.byId(id)
        shadowIndex = 0
        shadowDiffs = emptyMap()
        shadowTranscripts = emptyMap()
        route = Route.ShadowStudy(id)
    }

    suspend fun refreshCorpus() {
        val (d, f, t) = repo.stats()
        due = d; fresh = f; totalCards = t
        mastered = db.cards().countMastered()
        typeCounts = db.cards().countByType().map { it.type to it.n }
        fresh = repo.todayNewAvailable(prefs.dailyNewCards)
        val (g, cap) = repo.generatedStats()
        generated = g; generatedCap = cap
        wrongCount = repo.wrongCount()
        reviewedToday = repo.reviewedToday()
    }

    LaunchedEffect(Unit) {
        runCatching { repo.seedIfEmpty() }
        refreshCorpus()
    }
    LaunchedEffect(route) {
        if (route is Route.Corpus) refreshCorpus()
    }

    /**
     * 端侧 LLM（qwen3-4b 约 2.5GB）评分后是常驻缓存的，此时再加载 Whisper 会让两个模型
     * 同时驻留，被 lmkd 直接杀进程（表现：转写中途闪退，记录停在 pending_eval）。
     * 转写前先把 LLM 内存腾出来；下次评分会按需重新加载。
     */
    /**
     * 端侧 LLM（qwen3-4b 约 2.5GB）评分后是常驻缓存的，转写前必须先把它让出来，否则只用 Whisper 就会
     * 撞到 lmkd。两条实测结论决定了这里的策略：
     * 1) 空闲时 release() 后 RSS 不会立刻降（权重是 mmap 的干净页，仍计入 RSS），但内核在 ASR 分配时
     *    直接回收——实测 2909MB → 转写中 768MB，安全。
     * 2) 正在生成时硬加载 Whisper 会被杀：权重是活跃脏页，回收不及时（实测峰值 3.67GB，thrashing 38%
     *    后 lmkd 杀掉本进程）。所以宁可不转写，也不能在生成中加载 ASR。
     *
     * @return true 表示可以安全加载 ASR。
     */
    suspend fun freeLlmMemoryForAsr(): Boolean {
        val before = Diag.rssMb()
        if (LocalLlmEngine.release()) {
            Log.i(TAG, "asr mem: llm released rss_before=${before}MB rss_now=${Diag.rssMb()}MB")
            return true
        }
        // 本地评分正在生成：给它 60 秒收尾的机会（用户此时本来也在等着），等不到就报错而不是冒险。
        val saved = stage
        stage = "本地评分收尾中，稍后自动转写…"
        val t0 = System.currentTimeMillis()
        val deadline = t0 + 60_000L
        while (System.currentTimeMillis() < deadline) {
            delay(1_000)
            if (LocalLlmEngine.release()) {
                Log.i(TAG, "asr mem: llm released after ${System.currentTimeMillis() - t0}ms wait")
                stage = saved
                return true
            }
        }
        stage = saved
        Log.w(TAG, "asr mem: local scoring still running after 60s (rss=${before}MB), refuse to load whisper")
        return false
    }

    suspend fun transcribe(audio: File): String {
        stage = "转写中（端侧 ${prefs.asrModel}）…"
        if (!freeLlmMemoryForAsr()) {
            stage = null
            throw IOException("本地评分正在使用端侧模型，请等它结束后再转写（一次评分约 1-4 分钟）")
        }
        val dir = withContext(Dispatchers.IO) { ModelStore.ensure(context, prefs.asrModel) }
        val asr = withContext(Dispatchers.Default) { WhisperAsr(context, dir, prefs.asrModel) }
        try {
            val loadMs = withContext(Dispatchers.Default) { asr.load() }
            val rssAfterLoad = Diag.rssMb()
            val (text, ms) = withContext(Dispatchers.Default) { asr.transcribe(audio) }
            Log.i(
                TAG,
                "asr load_ms=$loadMs transcribe_ms=$ms chars=${text.length} " +
                    "rss_after_asr_load=${rssAfterLoad}MB rss_peak=${Diag.rssMb()}MB",
            )
            return text
        } finally {
            // 失败路径也必须释放 native recognizer，否则反复重试会泄漏内存。
            withContext(Dispatchers.Default) { asr.release() }
            Log.i(TAG, "asr mem: rss_after_asr_release=${Diag.rssMb()}MB")
            // 转写结束清掉 stage，避免调用方（如口语对练）被残留的「转写中」锁住按钮。
            stage = null
        }
    }

    suspend fun finishShadow(passage: ShadowPassage) {
        val diffs = (0 until passage.sentences.size).map { i -> shadowDiffs[i] }
        val transcripts = (0 until passage.sentences.size).map { i -> shadowTranscripts[i] ?: "" }
        val totalRef = diffs.sumOf { it?.refWords?.size ?: 0 }
        val totalMatched = diffs.sumOf { it?.matchedRef?.size ?: 0 }
        val accuracy = if (totalRef > 0) (totalMatched * 100 / totalRef) else 0
        val items = JSONArray()
        diffs.forEachIndexed { i, d ->
            val missed = d?.missedRef?.map { d.refWords[it] } ?: emptyList()
            items.put(
                JSONObject().apply {
                    put("i", i)
                    put("ref", passage.sentences[i])
                    put("hyp", transcripts[i])
                    put("acc", d?.accuracy ?: 0.0)
                    put("missed", JSONArray(missed))
                }
            )
        }
        val id = db.sessions().insert(
            Session(
                module = "shadow", topic = passage.title, durationSec = 0,
                scoresJson = JSONObject().put("accuracy", accuracy).toString(),
                fixesJson = JSONObject().put("items", items).toString(),
                status = "done",
            )
        )
        shadowPassage = null; shadowIndex = 0; shadowDiffs = emptyMap(); shadowTranscripts = emptyMap()
        route = Route.ShadowResult(id)
    }

    fun nextShadowSentence() {
        val passage = shadowPassage ?: return
        if (shadowIndex + 1 >= passage.sentences.size) {
            scope.launch { finishShadow(passage) }
        } else {
            shadowIndex += 1
        }
    }

    suspend fun submitShadowSentence(audioPath: String, seconds: Int) {
        val passage = shadowPassage ?: return
        val ref = passage.sentences.getOrNull(shadowIndex) ?: return
        stage = "转写中（端侧）…"
        val text = try {
            transcribe(File(audioPath))
        } catch (e: Exception) {
            error = "转写失败：${e.message}"; stage = null; return
        }
        if (text.isBlank()) {
            error = "没识别到有效内容，请重录"; stage = null; return
        }
        val base = shadowRepo.compare(ref, text)
        val wpm = if (seconds > 0) (base.refWords.size * 60 / seconds) else 0
        val diff = base.copy(wpm = wpm)
        shadowDiffs = shadowDiffs.toMutableMap().apply { put(shadowIndex, diff) }
        shadowTranscripts = shadowTranscripts.toMutableMap().apply { put(shadowIndex, text) }
        stage = null
    }

    val guard: () -> Unit = {
        if (prefs.remainingCalls() <= 0) {
            throw java.io.IOException("今日 API 调用已达上限 ${prefs.dailyCallLimit} 次，可到设置页调整")
        }
        prefs.consumeCall()
    }

    /** 重试时把原因反馈到界面（Compose 快照状态允许后台线程写入）。 */
    val onRetry: (Int, Int, String) -> Unit = { attempt, max, reason ->
        stage = "请求失败（$reason），正在重试 $attempt/$max…"
        Log.w(TAG, "llm retry $attempt/$max reason=$reason")
    }

    /** 历史高频弱点 top3：注入评分 prompt，实现「反馈针对你的老毛病」闭环。 */
    fun myWeaknesses(): List<String> = topErrors(sessions).take(3).map { it.label }

    /** 口语评分落库 JSON：fixes + 逐句打磨（sentence_fixes）。 */
    fun speakingFixesJson(eval: SpeakingEval): String = JSONObject().apply {
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
    }.toString()

    /** 记录本地评分任务真正启动的时间；卡死判断必须用它，不能用练习开始时间。 */
    fun markLocalScoreStarted(id: Long) {
        val o = runCatching { JSONObject(prefs.localScoreStarted) }.getOrDefault(JSONObject())
        o.put(id.toString(), System.currentTimeMillis())
        if (o.length() > 30) {
            val keep = o.keys().asSequence()
                .map { k -> k to o.optLong(k, 0L) }
                .sortedByDescending { p -> p.second }
                .take(20)
                .map { p -> p.first }
                .toSet()
            o.keys().asSequence().toList().filterNot { k -> k in keep }.forEach { k -> o.remove(k) }
        }
        prefs.localScoreStarted = o.toString()
    }

    fun localScoreStartedAt(id: Long): Long =
        runCatching { JSONObject(prefs.localScoreStarted).optLong(id.toString(), 0L) }
            .getOrDefault(0L)

    /** 后台本地写作评分（不阻塞继续练习）。 */
    suspend fun startLocalWriting(session: Session, text: String, task: String) {
        stage = null
        error = null
        db.sessions().updateResult(
            id = session.id, t = text, e = session.essayText.ifBlank { text },
            s = session.scoresJson, f = session.fixesJson, p = session.polished, st = "pending_local",
        )
        route = Route.Result(session.id)
        markLocalScoreStarted(session.id)
        // 前台 Service 执行（普通协程在系统内存紧张时会被杀，状态会卡在 pending_local）
        if (!ScoreService.start(context, session.id, "writing", text, task)) {
            db.sessions().updateResult(
                id = session.id, t = text, e = session.essayText.ifBlank { text },
                s = session.scoresJson, f = session.fixesJson, p = session.polished, st = "pending_eval",
            )
            error = "系统不允许后台启动评分服务，请停在结果页点「重新评分」"
        }
    }

    /** 后台本地口语评分（不阻塞继续练习）。 */
    suspend fun startLocalSpeaking(session: Session, text: String) {
        stage = null
        error = null
        db.sessions().updateResult(
            id = session.id, t = text, e = "",
            s = session.scoresJson, f = session.fixesJson, p = session.polished, st = "pending_local",
        )
        route = Route.Result(session.id)
        markLocalScoreStarted(session.id)
        if (!ScoreService.start(context, session.id, "speaking", text)) {
            db.sessions().updateResult(
                id = session.id, t = text, e = "",
                s = session.scoresJson, f = session.fixesJson, p = session.polished, st = "pending_eval",
            )
            error = "系统不允许后台启动评分服务，请停在结果页点「重新评分」"
        }
    }

    /**
     * 评分通道（图：ielts-local-llm_workflow 的 LlmRouter）：
     * auto 云端优先、失败自动降级本地；local 仅本地；cloud 仅云端。
     * 本地评分转后台：立刻进结果页（pending_local），完成通知 + Room 自动刷新。
     */
    suspend fun evaluateWriting(session: Session, text: String, task: String = "task2") {
        val mode = prefs.scoreChannel
        val cloudDown = System.currentTimeMillis() < prefs.cloudDownUntil
        if (ChannelPick.effective(
                mode, cloudDown, hasNetwork(context), prefs.remainingCalls()
            ) == "local"
        ) {
            startLocalWriting(session, text, task)
            return
        }
        stage = "评分中（${prefs.model}）…"
        val t0 = System.currentTimeMillis()
        try {
            val eval: WritingEval = withContext(Dispatchers.IO) {
                val client = LlmClient(prefs.baseUrl, prefs.apiKey, prefs.model, guard, onRetry)
                if (task == "task1") client.evaluateWritingTask1(session.topic, text)
                else client.evaluateWriting(session.topic, text, myWeaknesses())
            }
            Log.i(TAG, "writing eval ${System.currentTimeMillis() - t0}ms raw=${eval.rawJson.take(200)}")
            // 云端与本地统一后处理：0.5 步长、overall 由分项重算、按题型套字数封顶。
            // 缺项直接判失败走降级，不能把 0 当有效分落库。
            val words = text.split(Regex("\\s+")).count { it.isNotBlank() }
            if (eval.tr <= 0 || eval.cc <= 0 || eval.lr <= 0 || eval.gra <= 0) {
                throw IllegalStateException("云端评分缺项：${eval.rawJson.take(120)}")
            }
            val scores = ScoreSanitizer.sanitizeWriting(
                eval.tr, eval.cc, eval.lr, eval.gra, eval.overall, words, task,
                essayText = text,
                sentenceFixCount = eval.sentenceFixes.size,
                grammarIssueCount = ScoreSanitizer.grammarIssueCount(eval.sentenceFixes),
                hasFixes = eval.fixes.isNotEmpty(),
            )
            db.sessions().updateResult(
                id = session.id, t = text,
                e = session.essayText.ifBlank { text },
                s = JSONObject().apply {
                    put(if (task == "task1") "TA" else "TR", scores.tr)
                    put("CC", scores.cc); put("LR", scores.lr)
                    put("GRA", scores.gra); put("overall", scores.overall)
                }.toString(),
                f = JSONObject().apply {
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
                p = eval.polished, st = ScoreGate.statusFor(scores.overall),
            )
            stage = null
            error = null
            // 评分完成不强行导航：用户已离开时只更新 Room 并靠通知告知，避免把人从别的页面拉回结果页。
            if (route is Route.Writing || route is Route.Speaking || route == Route.Result(session.id)) {
                route = Route.Result(session.id)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (mode == "cloud") throw e
            val msg = e.message ?: ""
            // 额度耗尽/限流：冷却 1 小时，auto 直接走本地，避免每次评分都先白试云端。
            markCloudDown(prefs, e)
            Log.w(TAG, "cloud writing eval failed, fallback local: $msg")
            startLocalWriting(session, text, task)
        }
    }

    suspend fun evaluateSpeaking(session: Session, text: String) {
        val mode = prefs.scoreChannel
        val part = when {
            session.topic.startsWith("Mock Test") -> "Part 1-3"
            session.topic.contains(" · ") -> session.topic.substringBefore(" · ").trim()
            else -> "Part"
        }
        val cloudDown = System.currentTimeMillis() < prefs.cloudDownUntil
        if (ChannelPick.effective(
                mode, cloudDown, hasNetwork(context), prefs.remainingCalls()
            ) == "local"
        ) {
            startLocalSpeaking(session, text)
            return
        }
        stage = "评分中（${prefs.model}）…"
        val t0 = System.currentTimeMillis()
        try {
            val eval: SpeakingEval = withContext(Dispatchers.IO) {
                LlmClient(prefs.baseUrl, prefs.apiKey, prefs.model, guard, onRetry)
                    .evaluateSpeaking(part, session.topic, text, session.durationSec, myWeaknesses())
            }
            Log.i(TAG, "speaking eval ${System.currentTimeMillis() - t0}ms raw=${eval.rawJson.take(200)}")
            val words = text.split(Regex("\\s+")).count { it.isNotBlank() }
            if (eval.fluency <= 0 || eval.lexical <= 0 || eval.grammar <= 0 || eval.pronunciation <= 0) {
                throw IllegalStateException("云端口语评分缺项：${eval.rawJson.take(120)}")
            }
            val scores = ScoreSanitizer.sanitizeSpeaking(
                eval.fluency, eval.lexical, eval.grammar, eval.pronunciation, eval.overall, words,
            )
            db.sessions().updateResult(
                id = session.id, t = text, e = "",
                s = JSONObject().apply {
                    put("FC", scores.fluency); put("LR", scores.lexical); put("GRA", scores.grammar)
                    put("P", scores.pronunciation); put("overall", scores.overall)
                }.toString(),
                f = speakingFixesJson(eval),
                p = eval.improved, st = ScoreGate.statusFor(scores.overall),
            )
            stage = null
            error = null
            // 评分完成不强行导航：用户已离开时只更新 Room 并靠通知告知，避免把人从别的页面拉回结果页。
            if (route is Route.Writing || route is Route.Speaking || route == Route.Result(session.id)) {
                route = Route.Result(session.id)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (mode == "cloud") throw e
            val msg = e.message ?: ""
            markCloudDown(prefs, e)
            Log.w(TAG, "cloud speaking eval failed, fallback local: $msg")
            startLocalSpeaking(session, text)
        }
    }

    suspend fun runWriting(task: String, topic: String, audioPath: String?, typedText: String, seconds: Int = 0) {
        error = null
        val audio = audioPath?.let { File(it) }
        // 键盘写作没有音频，用页面计时作为时长；音频模式仍按 WAV 长度算。
        val duration = audio?.let { (it.length() / 32000).toInt() }?.takeIf { it > 0 } ?: seconds
        val id = db.sessions().insert(
            Session(topic = topic, durationSec = duration, audioPath = audioPath,
                essayText = typedText, status = "pending_eval", task = task)
        )
        val session = db.sessions().byId(id) ?: return
        val text = try {
            if (audio != null) transcribe(audio) else typedText
        } catch (e: Exception) {
            Log.e(TAG, "asr fail: ${e.message}")
            error = "转写失败：${e.message}"; stage = null; route = Route.Result(id); return
        }
        if (text.isBlank()) {
            db.sessions().updateResult(id, "", "", "", "", "", "asr_empty")
            error = "没识别到有效内容，请重录"; stage = null; route = Route.Result(id); return
        }
        try {
            evaluateWriting(session, text, task)
        } catch (e: Exception) {
            Log.e(TAG, "llm fail: ${e.message}")
            db.sessions().updateResult(id, text, text, "", "", "", "pending_eval")
            error = "评分失败：${e.message}（内容已保存，可重试）"; stage = null; route = Route.Result(id)
        }
    }

    /** 写作模考：两篇依次评分（各自独立记录），完成后回首页。 */
    suspend fun runWritingMock(t1Topic: String, t1Text: String, t2Topic: String, t2Text: String) {
        error = null
        try {
            stage = "模考评分中：Task 1…"
            val id1 = db.sessions().insert(
                Session(topic = t1Topic, essayText = t1Text, status = "pending_eval", task = "task1")
            )
            db.sessions().byId(id1)?.let { evaluateWriting(it, t1Text, "task1") }

            stage = "模考评分中：Task 2…"
            val id2 = db.sessions().insert(
                Session(topic = t2Topic, essayText = t2Text, status = "pending_eval", task = "task2")
            )
            db.sessions().byId(id2)?.let { evaluateWriting(it, t2Text, "task2") }

            stage = null
            route = Route.MockResult(listOf(id1, id2))
            error = null
        } catch (e: Exception) {
            Log.e(TAG, "mock fail: ${e.message}")
            stage = null
            route = Route.Home
            error = "模考评分中断：${e.message}（已完成的部分已保存）"
        }
    }

    /** 口语模考：三段录音依次转写，合并后评分。 */
    suspend fun runSpeakingMock(files: List<String>, secs: List<Int>) {
        error = null
        stage = "模考转写中…"
        try {
            // 三段转写复用同一个 recognizer：每次 new 会重复加载模型，首段之后两段白等。
            if (!freeLlmMemoryForAsr()) {
                error = "本地评分正在使用端侧模型，请等它结束后再做口语模考"
                stage = null
                route = Route.Speaking
                return
            }
            val dir = withContext(Dispatchers.IO) { ModelStore.ensure(context, prefs.asrModel) }
            val asr = withContext(Dispatchers.Default) { WhisperAsr(context, dir, prefs.asrModel) }
            val text = try {
                withContext(Dispatchers.Default) { asr.load() }
                files.mapIndexed { i, f ->
                    "[Part ${i + 1}]\n" + withContext(Dispatchers.Default) { asr.transcribe(File(f)).first }
                }.joinToString("\n\n")
            } finally {
                withContext(Dispatchers.Default) { asr.release() }
            }
            if (text.replace(Regex("\\[Part \\d]"), "").isBlank()) {
                error = "没有识别到有效内容，请重录"
                stage = null
                route = Route.Speaking
                return
            }
            val id = db.sessions().insert(
                Session(
                    module = "speaking",
                    topic = "Mock Test · Part 1-3",
                    durationSec = secs.sum(),
                    status = "pending_eval",
                )
            )
            val session = db.sessions().byId(id) ?: return
            // 先把转写落库：评分前进程被杀/旋转取消时，重试仍能拿到文本（此前只存在内存里）。
            db.sessions().updateResult(id, text, "", "", "", "", "pending_eval")
            stage = null
            evaluateSpeaking(session, text)
            route = Route.MockResult(listOf(id))
        } catch (e: Exception) {
            Log.e(TAG, "speaking mock fail: ${e.message}")
            error = "口语模考失败：${e.message}"
            stage = null
            route = Route.Speaking
        }
    }

    suspend fun runSpeaking(part: String, question: String, audioPath: String, seconds: Int) {
        error = null
        val id = db.sessions().insert(
            Session(module = "speaking", topic = "$part · $question", durationSec = seconds,
                audioPath = audioPath, status = "pending_eval")
        )
        val session = db.sessions().byId(id) ?: return
        val text = try {
            transcribe(File(audioPath))
        } catch (e: Exception) {
            error = "转写失败：${e.message}"; stage = null; route = Route.Result(id); return
        }
        if (text.isBlank()) {
            db.sessions().updateResult(id, "", "", "", "", "", "asr_empty")
            error = "没识别到有效内容，请重录"; stage = null; route = Route.Result(id); return
        }
        try {
            evaluateSpeaking(session, text)
        } catch (e: Exception) {
            Log.e(TAG, "llm fail: ${e.message}")
            db.sessions().updateResult(id, text, "", "", "", "", "pending_eval")
            error = "评分失败：${e.message}（录音已保存，可重试）"; stage = null; route = Route.Result(id)
        }
    }

    suspend fun submitQuiz(setId: String) {
        val set = bankRepo.byId(setId) ?: return
        var correct = 0
        val items = JSONArray()
        val resultItems = ArrayList<BankResultItem>(set.questions.size)
        set.questions.forEach { q ->
            val yours = bankAnswers[q.id] ?: ""
            val ok = bankRepo.isCorrect(q, yours)
            val right = bankRepo.correctAnswerText(q)
            if (ok) correct++
            items.put(
                JSONObject().apply {
                    put("id", q.id)
                    put("prompt", q.prompt)
                    put("yours", yours)
                    put("right", right)
                    put("explain", q.explain)
                    put("explain_en", q.explainEn)
                    put("keys", JSONArray(q.keys))
                    put("ok", ok)
                }
            )
            resultItems.add(BankResultItem(q.id, q.prompt, yours, right, q.explain, q.explainEn, q.keys, ok))
        }
        val seconds = ((System.currentTimeMillis() - quizStartAt) / 1000).toInt()
        val id = db.sessions().insert(
            Session(
                module = set.module,
                topic = set.title,
                durationSec = seconds,
                scoresJson = JSONObject().apply {
                    put("correct", correct)
                    put("total", set.questions.size)
                }.toString(),
                fixesJson = JSONObject().put("items", items).toString(),
                status = "done",
            )
        )
        bankRepo.clearAnswers(setId)
        bankAnswers = emptyMap()
        // 直接缓存结果快照，交卷立刻可见，不等 Room Flow 异步刷新
        bankResult = BankResultData(setId, set.title, set.module, set.passage, set.transcript, correct, set.questions.size, resultItems)
        route = Route.BankResult(id, setId)
    }

    /** 端侧系统 TTS 单段合成；数据量 >10KB 才算成功（系统引擎对长文本 phonemize 失败时只产 44 字节空 WAV 头）。 */
    suspend fun synthesizeOne(text: String, outFile: File): Boolean {
        // 引擎冷启动时系统会并发做 zho 探测 + eng 请求，双线程同时 createTts 偶发卡死 init 回调；
        // 引擎进程通常在后台完成模型加载，失败后隔几秒重试即可恢复。
        repeat(3) { attempt ->
            if (attempt > 0) {
                Log.w(TAG, "tts retry attempt=$attempt")
                delay(3000)
                runCatching { outFile.delete() }
            }
            val ok = withTimeoutOrNull(60_000L) {
                suspendCancellableCoroutine { cont ->
                    var settled = false
                    var tts: TextToSpeech? = null
                    fun finish(done: Boolean) {
                        if (settled) return
                        settled = true
                        Log.i(TAG, "tts one finish=$done size=${outFile.length()}")
                        runCatching { tts?.stop(); tts?.shutdown() }
                        if (cont.isActive) cont.resume(done)
                    }
                    tts = TextToSpeech(context) { status ->
                        Log.i(TAG, "tts init status=$status")
                        if (status != TextToSpeech.SUCCESS) { finish(false); return@TextToSpeech }
                        tts?.language = java.util.Locale.US
                        tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                            override fun onStart(id: String?) { Log.i(TAG, "tts onStart") }
                            override fun onDone(id: String?) { Log.i(TAG, "tts onDone"); finish(true) }
                            @Deprecated("Deprecated in Java")
                            override fun onError(id: String?) { Log.i(TAG, "tts onError id=$id"); finish(false) }
                            override fun onError(id: String?, errorCode: Int) { Log.i(TAG, "tts onError code=$errorCode"); finish(false) }
                        })
                        val r = tts?.synthesizeToFile(text, android.os.Bundle(), outFile, "ai_listen")
                        Log.i(TAG, "tts synthesizeToFile ret=$r len=${text.length}")
                        if (r != TextToSpeech.SUCCESS) finish(false)
                    }
                }
            } ?: false
            if (ok && outFile.length() > 10_000L) {
                Log.i(TAG, "tts one ok attempt=$attempt size=${outFile.length()}")
                return true
            }
            Log.w(TAG, "tts one failed attempt=$attempt size=${outFile.length()}")
        }
        return false
    }

    fun wavHeader(dataLen: Int, sampleRate: Int): ByteArray {
        val b = java.io.ByteArrayOutputStream()
        fun le32(v: Int) { repeat(4) { b.write((v ushr (8 * it)) and 0xFF) } }
        fun le16(v: Int) { b.write(v and 0xFF); b.write((v ushr 8) and 0xFF) }
        b.write("RIFF".toByteArray()); le32(36 + dataLen); b.write("WAVE".toByteArray())
        b.write("fmt ".toByteArray()); le32(16); le16(1); le16(1)
        le32(sampleRate); le32(sampleRate * 2); le16(2); le16(16)
        b.write("data".toByteArray()); le32(dataLen)
        return b.toByteArray()
    }

    fun mergeWavs(parts: List<File>, out: File): Boolean = runCatching {
        val data = java.io.ByteArrayOutputStream()
        var sampleRate = 22050
        parts.forEachIndexed { i, f ->
            val bytes = f.readBytes()
            if (bytes.size <= 44) return false
            if (i == 0) {
                // fmt 块 offset 24-27 = sampleRate（小端）
                sampleRate = (bytes[24].toInt() and 0xFF) or
                    ((bytes[25].toInt() and 0xFF) shl 8) or
                    ((bytes[26].toInt() and 0xFF) shl 16) or
                    ((bytes[27].toInt() and 0xFF) shl 24)
            }
            data.write(bytes, 44, bytes.size - 44)
        }
        out.outputStream().use {
            it.write(wavHeader(data.size(), sampleRate))
            it.write(data.toByteArray())
        }
        out.length() > 10_000L
    }.getOrDefault(false)

    /**
     * 分段合成 + 本地拼接：系统 TTS 引擎对长多段文本的 synthesizeToFile 只产空文件，
     * 故按段落逐段合成（去说话人前缀）再拼 WAV。任一段失败返回 false（题目降级文字版）。
     */
    suspend fun synthesizeWav(transcript: String, outFile: File, onStage: (String) -> Unit): Boolean {
        val paragraphs = transcript.split("\n\n")
            .map {
                it.replace(
                    Regex("^(Caller|Agent|Speaker\\s*[A-Za-z0-9]+|Interviewer|Host|Man|Woman)\\s*:\\s*", RegexOption.IGNORE_CASE),
                    "",
                ).trim()
            }
            .filter { it.isNotBlank() }
        if (paragraphs.isEmpty()) return false
        Log.i(TAG, "tts wav paragraphs=${paragraphs.size}")
        val tmp = File(context.cacheDir, "tts_parts").apply { mkdirs() }
        val parts = mutableListOf<File>()
        try {
            paragraphs.forEachIndexed { i, p ->
                onStage("正在合成第 ${i + 1}/${paragraphs.size} 段…")
                val f = File(tmp, "part_$i.wav")
                if (!synthesizeOne(p, f)) { runCatching { f.delete() }; return false }
                parts.add(f)
            }
            onStage("正在拼接音频…")
            return withContext(Dispatchers.IO) { mergeWavs(parts, outFile) }
        } finally {
            parts.forEach { runCatching { it.delete() } }
            runCatching { tmp.delete() }
        }
    }

    suspend fun runAiGen(module: String, difficulty: String, types: List<String>, count: Int) {
        aiGenBusy = true
        aiGenError = null
        // 本地模型要几分钟才出题，先说清楚，否则用户以为卡死；auto 模式也提示会自动兜底。
        // 云端处于冷却期时这里直接落成 local，否则每次出题都要先白试一次云端。
        val channelNow = ChannelPick.effective(
            prefs.scoreChannel,
            System.currentTimeMillis() < prefs.cloudDownUntil,
            hasNetwork(context),
            prefs.remainingCalls(),
        )
        aiGenStage = if (channelNow == "local") "本地模型生成中（可能要几分钟）…"
        else if (channelNow == "cloud") "正在请求模型…"
        else "正在请求模型（云端失败会自动转本地）…"
        val retryFeedback: (Int, Int, String) -> Unit = { a, m, r ->
            aiGenStage = "请求失败（${r.take(50)}），正在重试 $a/$m…"
            Log.w(TAG, "llm retry $a/$m reason=$r")
        }
        fun toQuestions(qs: List<com.txw.ielts.llm.GeneratedQuestion>) = qs.mapIndexed { i, gq ->
            BankQuestion(
                id = i + 1,
                type = gq.type,
                prompt = gq.prompt,
                options = gq.options,
                answers = gq.answers,
                explain = gq.explain,
                explainEn = gq.explainEn,
                keys = gq.keys,
            )
        }
        try {
            val setId = "ai-${System.currentTimeMillis()}"
            val set: BankSet
            if (module == "listening") {
                val gen = withContext(Dispatchers.IO) {
                    routeLlm(
                        channel = channelNow,
                        label = "AI 出题（听力）",
                        onCloudFail = { markCloudDown(prefs, it) },
                        cloud = {
                            LlmClient(prefs.baseUrl, prefs.apiKey, prefs.model, guard, retryFeedback)
                                .generateListening(difficulty, types, count)
                        },
                        local = {
                            LocalLlmEngine.generateListening(
                                context, prefs.localModel, difficulty, types, count,
                            )
                        },
                    )
                }
                if (gen.questions.isEmpty() || gen.transcript.isBlank()) {
                    aiGenError = "生成结果为空，请重试"
                    return
                }
                aiGenStage = "正在用系统美音 TTS 分段合成听力音频…"
                val audioName = "$setId.wav"
                val outFile = File(File(context.filesDir, "bank").apply { mkdirs() }, audioName)
                val ok = synthesizeWav(gen.transcript, outFile) { s -> aiGenStage = s }
                set = BankSet(
                    id = setId,
                    module = "listening",
                    title = gen.title,
                    intro = "$difficulty · AI 生成 · ${gen.questions.size} 题" + if (ok) " · 含音频" else "",
                    passage = "",
                    audio = if (ok) audioName else "",
                    transcript = gen.transcript,
                    questions = toQuestions(gen.questions),
                )
                if (!ok) {
                    runCatching { outFile.delete() }
                    aiGenError = "音频合成失败（检查系统 TTS 引擎），题目已按文字版保留"
                }
            } else {
                val gen = withContext(Dispatchers.IO) {
                    routeLlm(
                        channel = channelNow,
                        label = "AI 出题（阅读）",
                        onCloudFail = { markCloudDown(prefs, it) },
                        cloud = {
                            LlmClient(prefs.baseUrl, prefs.apiKey, prefs.model, guard, retryFeedback)
                                .generateReading(difficulty, types, count)
                        },
                        local = {
                            LocalLlmEngine.generateReading(
                                context, prefs.localModel, difficulty, types, count,
                            )
                        },
                    )
                }
                if (gen.questions.isEmpty() || gen.passage.isBlank()) {
                    aiGenError = "生成结果为空，请重试"
                    return
                }
                set = BankSet(
                    id = setId,
                    module = "reading",
                    title = gen.title,
                    intro = "$difficulty · AI 生成 · ${gen.questions.size} 题",
                    passage = gen.passage,
                    audio = "",
                    transcript = "",
                    questions = toQuestions(gen.questions),
                )
            }
            bankRepo.saveAiSet(set)
            aiSets = bankRepo.aiSets()
            // 用户还在 AI 出题页就直接进题；已返回首页则只在首页提示，不打断
            if (route is Route.AiGen) {
                openSet(setId)
            } else {
                aiGenReady = setId
            }
        } catch (e: Exception) {
            Log.e(TAG, "ai gen fail: ${e.message}")
            aiGenError = "生成失败：${e.message}"
        } finally {
            aiGenBusy = false
            aiGenStage = null
        }
    }

    suspend fun runAiExplain(item: BankResultItem) {
        explainingIds = explainingIds + item.id
        try {
            val exp = withContext(Dispatchers.IO) {
                routeLlm(
                    channel = prefs.scoreChannel,
                    label = "错题精讲",
                    onCloudFail = { markCloudDown(prefs, it) },
                    cloud = {
                        LlmClient(prefs.baseUrl, prefs.apiKey, prefs.model, guard, onRetry)
                            .explainBankWrong(item.prompt, item.right, item.yours, item.explain)
                    },
                    local = {
                        LocalLlmEngine.explainBankWrong(
                            context, prefs.localModel,
                            item.prompt, item.right, item.yours, item.explain,
                        )
                    },
                )
            }
            aiExplains = aiExplains.toMutableMap().apply { put(item.id, exp) }
        } catch (e: Exception) {
            Log.e(TAG, "ai explain fail: ${e.message}")
            error = "精讲失败：${e.message}"
        } finally {
            explainingIds = explainingIds - item.id
        }
    }

    // 全面屏侧滑 / 返回键：回到上一级页面，而不是退出 App
    val backTarget: Route? = when (val r = route) {
        Route.Home -> null
        is Route.Writing -> Route.Home   // 评分中也可回首页：本地评分在 Service 里继续
        is Route.Quiz ->
            if ((bankSets + aiSets).firstOrNull { it.id == r.setId }?.id?.startsWith("ai-") == true) Route.AiGen
            else Route.Bank
        is Route.BankResult -> if (r.setId.startsWith("ai-")) Route.AiGen else Route.Bank
        is Route.Study -> Route.Corpus
        is Route.ShadowStudy, is Route.ShadowResult -> Route.Shadow
        is Route.Topics -> Route.Speaking
        is Route.Arguments -> Route.Writing
        is Route.ModelEssays -> Route.Home
        is Route.WritingMock -> Route.Writing
        is Route.ChartGen -> Route.Writing
        is Route.SpeakingPartner -> Route.Speaking
        is Route.SpeakingMock -> Route.Speaking
        is Route.MockResult -> Route.Home
        else -> Route.Home
    }
    BackHandler(enabled = backTarget != null) {
        // 系统返回与左上角返回走同一套清理：否则重练的 fixes/预设题目会残留到下一次练习。
        practiceFocus = null
        writingPresetTopic = null
        speakingPresetQuestion = null
        backTarget?.let { route = it }
    }

    LaunchedEffect(initialRoute) {
        val r = initialRoute ?: return@LaunchedEffect
        if (!freshStart) return@LaunchedEffect
        when {
            r == "bank" -> route = Route.Bank
            r.startsWith("quiz:") -> openSet(r.removePrefix("quiz:"))
            // 评分完成通知点击：直达对应结果页
            r.startsWith("result:") -> r.removePrefix("result:").toLongOrNull()?.let { route = Route.Result(it) }
            // 其余单例页面（settings / review / corpus / shadow / stats / modelessays …）按编码解析，
            // 便于 adb 直达；decodeRoute 对未知串回退 Home，不会崩。
            else -> route = decodeRoute(r)
        }
        if (bankAuto && r.startsWith("quiz:")) {
            val setId = r.removePrefix("quiz:")
            val set = bankRepo.byId(setId) ?: return@LaunchedEffect
            val lastId = set.questions.last().id
            bankAnswers = set.questions.associate { q ->
                q.id to if (q.id == lastId) "zzz-wrong" else bankRepo.correctAnswerText(q)
            }
            submitQuiz(setId)
            val latest = db.sessions().recent(1).firstOrNull()
            val wrong = set.questions.filter { it.id == lastId }
                .map { BankWrong(it.prompt, bankRepo.correctAnswerText(it), it.explain) }
            val added = repo.addBankWrong(wrong, set.module)
            Log.i(TAG, "RESULT bank scores=${latest?.scoresJson} cards_added=$added total_cards=${db.cards().countAll()}")
        }
    }

    LaunchedEffect(e2eLoadModel) {
        if (!e2eLoadModel) return@LaunchedEffect
        try {
            Log.i(TAG, "LOADMODEL before rss=${Diag.rssMb()}MB model=${prefs.localModel}")
            LocalLlmEngine.preload(context, prefs.localModel)
            Log.i(TAG, "LOADMODEL loaded rss=${Diag.rssMb()}MB")
        } catch (e: Exception) {
            Log.e(TAG, "LOADMODEL fail=${e.javaClass.simpleName}:${e.message}")
        }
    }

    LaunchedEffect(e2eTts) {
        if (!e2eTts) return@LaunchedEffect
        var tts: TextToSpeech? = null
        tts = TextToSpeech(context) { status ->
            val us = runCatching { tts?.setLanguage(java.util.Locale.US) }.getOrDefault(-99)
            val langs = runCatching {
                tts?.availableLanguages?.joinToString(",") { it.toLanguageTag() }
            }.getOrDefault("n/a")
            val defaultPkg = runCatching {
                android.provider.Settings.Secure.getString(context.contentResolver, "tts_default_synth")
            }.getOrDefault("n/a")
            Log.i(TAG, "E2E TTS init=$status enUS=$us langs=$langs default=$defaultPkg")
            if (status == TextToSpeech.SUCCESS) {
                tts?.speak("This is a test of the text to speech engine.", TextToSpeech.QUEUE_FLUSH, null, "e2e")
            }
        }
        kotlinx.coroutines.delay(6000)
        runCatching { tts?.shutdown() }
    }

    LaunchedEffect(e2eWritingWav) {
        if (e2eWritingWav.isNullOrBlank()) return@LaunchedEffect
        val topic = "Some people believe university students should study whatever they like. " +
            "Others think they should only be allowed to study subjects that will be useful in the future. " +
            "Discuss both views and give your own opinion."
        try {
            runWriting("task2", topic, e2eWritingWav, "")
            val latest = db.sessions().recent(1).firstOrNull()
            Log.i(TAG, "RESULT writing status=${latest?.status} scores=${latest?.scoresJson}")
        } catch (e: Exception) {
            Log.e(TAG, "RESULT fail=${e.javaClass.simpleName}:${e.message}")
        }
    }

    LaunchedEffect(e2eSpeakingWav) {
        if (e2eSpeakingWav.isNullOrBlank()) return@LaunchedEffect
        val q = "Describe a skill you learned that was difficult at first. You should say what it was, " +
            "how you learned it, why it was difficult, and explain how you feel about it now."
        try {
            runSpeaking("Part 2", q, e2eSpeakingWav, 90)
            val latest = db.sessions().recent(1).firstOrNull()
            Log.i(TAG, "RESULT speaking status=${latest?.status} scores=${latest?.scoresJson}")
        } catch (e: Exception) {
            Log.e(TAG, "RESULT fail=${e.javaClass.simpleName}:${e.message}")
        }
    }

    AnimatedContent(
        targetState = route,
        transitionSpec = {
            // iOS 式转场：新页从右滑入、旧页向左淡出
            (slideInHorizontally { it / 5 } + fadeIn()) togetherWith
                (slideOutHorizontally { -it / 6 } + fadeOut())
        },
        label = "route",
    ) { r ->
        Box(Modifier.fillMaxSize()) {
            when (r) {
                is Route.Home -> HomeScreen(
                    sessions = sessions,
                    onStartWriting = { route = Route.Writing; error = null },
                    onStartSpeaking = { route = Route.Speaking; error = null },
                    onReview = { route = Route.Review },
                    onCorpus = { route = Route.Corpus },
                    onBank = { bankFilter = "all"; route = Route.Bank },
                    onAiGen = { route = Route.AiGen; aiGenError = null },
                    onShadow = { route = Route.Shadow },
                    onOpen = { route = Route.Result(it) },
                    onSettings = { route = Route.Settings },
                    onModelEssays = { route = Route.ModelEssays },
                    aiGenBusy = aiGenBusy,
                    aiGenStage = aiGenStage,
                    aiGenReadyId = aiGenReady,
                    aiGenError = if (aiGenBusy) null else aiGenError,
                    onOpenReadySet = { id -> aiGenReady = null; openSet(id) },
                    onDismissAiGenError = { aiGenError = null },
                    plan = dailyPlan,
                    targetBand = prefs.targetBand,
                    examDate = prefs.examDate,
                    skillTargets = skillTargets,
                    recoveryNotice = recoveryNotice,
                )

                is Route.Writing -> WritingScreen(
                    stage = stage, error = error,
                    onSubmit = { task, topic, audio, typed, secs ->
                        practiceFocus = null
                        writingPresetTopic = null
                        writingPresetChart = null
                        scope.launch { runWriting(task, topic, audio, typed, secs) }
                    },
                    onBack = {
                        practiceFocus = null
                        writingPresetTopic = null
                        writingPresetChart = null
                        route = Route.Home
                    },
                    focusFixes = practiceFocus?.takeIf { it.module == "writing" }?.fixes ?: emptyList(),
                    presetTopic = writingPresetTopic
                        ?: practiceFocus?.takeIf { it.module == "writing" }?.topic,
                    presetTask = practiceFocus?.takeIf { it.module == "writing" }?.task ?: "task2",
                    presetChart = writingPresetChart,
                    onOpenArguments = { route = Route.Arguments },
                    onOpenMock = { route = Route.WritingMock },
                    onOpenChartGen = { route = Route.ChartGen },
                    outline = writingOutline,
                    outlineTopic = writingOutlineTopic,
                    outlineBusy = writingOutlineBusy,
                    outlineError = writingOutlineError,
                    onRequestOutline = { topic, task ->
                        scope.launch { requestWritingOutline(topic, task) }
                    },
                )

                is Route.Arguments -> ArgumentScreen(
                    topics = remember { com.txw.ielts.data.ArgumentRepo.load(context) },
                    onPractice = { q ->
                        writingPresetTopic = q
                        route = Route.Writing
                    },
                    onBack = { route = Route.Writing },
                )

                is Route.ModelEssays -> {
                    var version by remember { mutableIntStateOf(0) }
                    ModelEssayScreen(
                        essays = remember(version) { com.txw.ielts.data.ModelEssayStore.load(context) },
                        onRemove = { id ->
                            com.txw.ielts.data.ModelEssayStore.remove(context, id)
                            version += 1
                        },
                        onBack = { route = Route.Home },
                    )
                }

                is Route.ChartGen -> ChartGenScreen(
                    onUse = { spec ->
                        writingPresetChart = spec
                        writingPresetTopic = spec.prompt() + "\n\n" + spec.dataText()
                        route = Route.Writing
                    },
                    onBack = { route = Route.Writing },
                    polishPrompt = { spec ->
                        runCatching {
                            LocalLlmEngine.generateText(
                                context, prefs.localModel,
                                system = "You are an IELTS Writing Task 1 examiner. Rewrite the given Task 1 prompt in natural exam-authentic English. Keep every number, category and unit exactly as given; do not add or remove data. Output only the prompt sentence(s), no explanation.",
                                user = "Chart type: ${spec.kind}\nTitle: ${spec.title}\nUnit: ${spec.unit}\nData:\n${spec.dataText()}",
                            )
                        }.getOrNull()
                    },
                )

                is Route.WritingMock -> WritingMockScreen(
                    stage = stage, error = error,
                    onSubmit = { t1Topic, t1Text, t2Topic, t2Text ->
                        scope.launch { runWritingMock(t1Topic, t1Text, t2Topic, t2Text) }
                    },
                    onBack = { route = Route.Writing },
                )

                is Route.Speaking -> SpeakingScreen(
                    stage = stage, error = error,
                    onSubmit = { part, q, audio, secs ->
                        practiceFocus = null
                        speakingPresetQuestion = null
                        scope.launch { runSpeaking(part, q, audio, secs) }
                    },
                    onBack = {
                        practiceFocus = null
                        speakingPresetQuestion = null
                        route = Route.Home
                    },
                    focusFixes = practiceFocus?.takeIf { it.module == "speaking" }?.fixes ?: emptyList(),
                    presetPart = if (speakingPresetQuestion != null) "Part 2"
                    else practiceFocus?.takeIf { it.module == "speaking" }?.part,
                    presetQuestion = speakingPresetQuestion
                        ?: practiceFocus?.takeIf { it.module == "speaking" }?.topic,
                    onOpenTopics = { route = Route.Topics },
                    onOpenMock = { route = Route.SpeakingMock },
                    onOpenPartner = { route = Route.SpeakingPartner },
                    sample = speakingSample,
                    sampleQuestion = speakingSampleQuestion,
                    sampleBusy = speakingSampleBusy,
                    sampleError = speakingSampleError,
                    onRequestSample = { part, question ->
                        scope.launch { requestSpeakingSample(part, question) }
                    },
                )

                is Route.Topics -> TopicScreen(
                    topics = remember { com.txw.ielts.data.TopicRepo.load(context) },
                    onPractice = { q ->
                        speakingPresetQuestion = q
                        route = Route.Speaking
                    },
                    onBack = { route = Route.Speaking },
                )

                is Route.SpeakingPartner -> SpeakingPartnerScreen(
                    stage = stage, error = error,
                    onFinish = { text, secs ->
                        scope.launch {
                            val id = db.sessions().insert(
                                Session(
                                    module = "speaking",
                                    topic = "Partner · Part 1-3",
                                    durationSec = secs,
                                    status = "pending_eval",
                                )
                            )
                            // 先落库转写，评分前进程被杀也能重试
                            db.sessions().updateResult(id, text, "", "", "", "", "pending_eval")
                            db.sessions().byId(id)?.let { evaluateSpeaking(it, text) }
                        }
                    },
                    onBack = { route = Route.Speaking },
                    transcribe = { file -> transcribe(file) },
                    askFollowUp = { question, answer ->
                        runCatching {
                            LocalLlmEngine.generateText(
                                context, prefs.localModel,
                                system = "You are an IELTS Speaking Part 3 examiner. Ask ONE natural follow-up question based on the candidate's answer. Output only the question sentence, no quotes, no explanation.",
                                user = "Previous question: $question\nCandidate's answer: $answer",
                            ).trim().take(200)
                        }.getOrNull()
                    },
                )

                is Route.SpeakingMock -> SpeakingMockScreen(
                    stage = stage, error = error,
                    onSubmit = { files, secs -> scope.launch { runSpeakingMock(files, secs) } },
                    onBack = { route = Route.Speaking },
                )

                is Route.MockResult -> MockResultScreen(
                    sessions = sessions.filter { it.id in r.ids },
                    onOpen = { route = Route.Result(it) },
                    onBack = { route = Route.Home },
                )

                is Route.Result -> {
                    val session = sessions.firstOrNull { it.id == r.id }
                    if (session == null && sessionsOrNull != null) {
                        // 数据已加载完仍找不到 → 记录确实不存在，回首页。
                        LaunchedEffect(r.id) { route = Route.Home }
                    } else if (session == null) {
                        Text("载入中…", style = MaterialTheme.typography.bodyMedium)
                    } else if (session.module == "reading" || session.module == "listening") {
                        // 历史听读记录要走套题结果页；用写作模板渲染会显示「Band 0.0 · Task 2」。
                        val sum = runCatching { JSONObject(session.scoresJson) }.getOrNull()
                        val items = runCatching {
                            val arr = JSONObject(session.fixesJson).getJSONArray("items")
                            (0 until arr.length()).mapNotNull { i ->
                                runCatching {
                                    val x = arr.getJSONObject(i)
                                    BankResultItem(
                                        id = x.optInt("id"),
                                        prompt = x.optString("prompt"),
                                        yours = x.optString("yours"),
                                        right = x.optString("right"),
                                        explain = x.optString("explain"),
                                        explainEn = x.optString("explain_en"),
                                        keys = x.optJSONArray("keys")?.let { k ->
                                            (0 until k.length()).map { k.get(it).toString() }
                                        } ?: emptyList(),
                                        ok = x.optBoolean("ok"),
                                    )
                                }.getOrNull()
                            }
                        }.getOrDefault(emptyList())
                        BankResultScreen(
                            title = session.topic,
                            module = session.module,
                            correct = sum?.optInt("correct") ?: 0,
                            total = sum?.optInt("total") ?: items.size,
                            items = items,
                            transcript = "",
                            onAddWrong = {
                                scope.launch {
                                    repo.addBankWrong(
                                        items.filter { !it.ok }.map { BankWrong(it.prompt, it.right, it.explain) },
                                        session.module,
                                    )
                                }
                            },
                            onRetry = { route = Route.Bank },
                            onBack = { route = Route.Home },
                        )
                    } else {
                        ResultScreen(
                            session = session, busy = stage, error = error,
                            staleLocal = session.status == "pending_local" &&
                                System.currentTimeMillis() -
                                (localScoreStartedAt(session.id).takeIf { it > 0 } ?: session.startedAt) > 10 * 60_000L,
                            waitStartedAt = localScoreStartedAt(session.id).takeIf { it > 0 } ?: session.startedAt,
                            scoreTookMs = runCatching {
                                val fin = JSONObject(prefs.localScoreFinished).optLong(session.id.toString(), 0L)
                                val st = localScoreStartedAt(session.id)
                                if (fin > 0 && st > 0 && fin > st) fin - st else 0L
                            }.getOrDefault(0L),
                            sameTopicHistory = sessions.filter {
                                it.id != session.id && it.module == session.module &&
                                    it.topic == session.topic && it.status == "done"
                            }.sortedBy { it.startedAt },
                            onRetry = {
                                scope.launch {
                                    error = null
                                    try {
                                        if (session.module == "speaking") {
                                            var spText = session.transcript
                                            if (spText.isBlank() && session.audioPath != null) {
                                                spText = transcribe(java.io.File(session.audioPath))   // 首次转写失败的录音，重试时补转写
                                            }
                                            evaluateSpeaking(session, spText)
                                        } else {
                                            val isTask1 = session.resolvedTask() == "task1"
                                            var text = session.transcript.ifBlank { session.essayText }
                                            if (text.isBlank() && session.audioPath != null) {
                                                text = transcribe(java.io.File(session.audioPath))   // 同上：补转写
                                            }
                                            evaluateWriting(session, text, if (isTask1) "task1" else "task2")
                                        }
                                    } catch (e: Exception) {
                                        error = "评分失败：${e.message}"; stage = null
                                    }
                                }
                            },
                            onAddToCorpus = { fixes, sentences ->
                                scope.launch {
                                    repo.addFixesAsCards(fixes, session.module)
                                    // 逐句改法也进 SRS：下次复习的是「改这句」，不是只读批评。
                                    repo.addSentenceFixesAsCards(sentences, session.module)
                                }
                            },
                            onPracticeAgain = {
                                val fixes = runCatching {
                                    val o = JSONObject(session.fixesJson)
                                    o.optJSONArray("fixes")?.let { a ->
                                        (0 until a.length()).map { a.get(it).toString() }
                                    } ?: emptyList()
                                }.getOrDefault(emptyList())
                                val isSpeaking = session.module == "speaking"
                                practiceFocus = PracticeFocus(
                                    module = session.module,
                                    topic = if (isSpeaking) session.topic.substringAfter(" · ", session.topic)
                                    else session.topic,
                                    fixes = fixes,
                                    task = if (isSpeaking) "task2" else session.resolvedTask(),
                                    part = if (isSpeaking) session.topic.substringBefore(" · ").trim() else "Part 2",
                                )
                                route = if (isSpeaking) Route.Speaking else Route.Writing
                            },
                            vocabResult = vocabResult,
                            vocabSessionId = vocabSessionId,
                            vocabBusy = vocabBusy,
                            vocabError = vocabError,
                            onRequestVocab = { text, fixes ->
                                scope.launch { requestVocab(session.id, text, fixes) }
                            },
                            onAddVocab = { items ->
                                scope.launch { repo.addVocabCards(items) }
                            },
                            onBack = { route = Route.Home },
                        )
                    }
                }

                is Route.Settings -> SettingsScreen(prefs) { route = Route.Home }

                is Route.Corpus -> CorpusHomeScreen(
                    due = due, fresh = fresh, total = totalCards, mastered = mastered,
                    dailyLimit = prefs.dailyNewCards,
                    generated = generated, generatedCap = generatedCap,
                    typeCounts = typeCounts,
                    wrongCount = wrongCount,
                    reviewedToday = reviewedToday,
                    error = error,
                    onStart = {
                        scope.launch {
                            queue = repo.buildQueue(prefs.dailyNewCards)
                            cardIndex = 0
                            revealed = false
                            if (queue.isEmpty()) error = "今天没有待学卡片" else route = Route.Study
                        }
                    },
                    onWrongStart = {
                        scope.launch {
                            queue = repo.buildWrongQueue()
                            cardIndex = 0
                            revealed = false
                            if (queue.isEmpty()) error = "暂无错题" else route = Route.Study
                        }
                    },
                    onBack = { route = Route.Home },
                )

                is Route.Study -> {
                    val card = queue.getOrNull(cardIndex)
                    if (card == null) {
                        LaunchedEffect(cardIndex) { route = Route.Corpus }
                    } else {
                        StudyScreen(
                            card = card,
                            index = cardIndex,
                            total = queue.size,
                            revealed = revealed,
                            ttsAvailable = ttsAvailable,
                            onReveal = { revealed = true },
                            onSpeak = { speak(card.example.ifBlank { card.front }) },
                            onGrade = { grade ->
                                scope.launch {
                                    repo.grade(card, grade)
                                    // Again 不丢到明天：留在本轮隔几张再问，答对才前进。
                                    val step = ReviewQueue.afterGrade(queue, cardIndex, grade)
                                    queue = step.queue
                                    cardIndex = step.index
                                    revealed = false
                                    if (step.done) {
                                        queue = emptyList(); cardIndex = 0
                                        refreshCorpus()
                                        route = Route.Corpus
                                    }
                                }
                            },
                            onDelete = {
                                scope.launch {
                                    repo.delete(card)
                                    val newQueue = queue.filterNot { it.id == card.id }
                                    queue = newQueue
                                    revealed = false
                                    if (newQueue.isEmpty()) {
                                        cardIndex = 0
                                        refreshCorpus()
                                        route = Route.Corpus
                                    } else if (cardIndex >= newQueue.size) {
                                        cardIndex = newQueue.size - 1
                                    }
                                }
                            },
                            onExit = { queue = emptyList(); cardIndex = 0; revealed = false; route = Route.Corpus },
                        )
                    }
                }

                is Route.Review -> ReviewScreen(
                    sessions = sessions,
                    onOpen = { route = Route.Result(it) },
                    onBack = { route = Route.Home },
                    aiReview = aiReview,
                    aiBusy = aiReviewBusy,
                    aiError = aiReviewError,
                    onRequestReview = { scope.launch { requestAiReview() } },
                )

                is Route.Shadow -> ShadowHomeScreen(
                    passages = shadowPassages,
                    lastScores = shadowPassages.associate { p ->
                        val s = sessions.firstOrNull { it.module == "shadow" && it.topic == p.title }
                        val txt = s?.let {
                            runCatching { "${JSONObject(it.scoresJson).optInt("accuracy")}%" }.getOrDefault("")
                        }.orEmpty()
                        p.id to txt
                    },
                    onOpen = { openShadow(it) },
                    onBack = { route = Route.Home },
                )

                is Route.ShadowStudy -> {
                    val passage = shadowPassages.firstOrNull { it.id == r.passageId }
                    if (passage == null) {
                        LaunchedEffect(r.passageId) { route = Route.Shadow }
                    } else {
                        ShadowStudyScreen(
                            passage = passage,
                            index = shadowIndex,
                            stage = stage,
                            error = error,
                            lastResult = shadowDiffs[shadowIndex],
                            lastTranscript = shadowTranscripts[shadowIndex],
                            ttsAvailable = ttsAvailable,
                            speak = speak,
                            onSubmit = { path, secs -> scope.launch { submitShadowSentence(path, secs) } },
                            onNext = { nextShadowSentence() },
                            onBack = { shadowPassage = null; route = Route.Shadow },
                        )
                    }
                }

                is Route.ShadowResult -> {
                    val session = sessions.firstOrNull { it.id == r.sessionId }
                    if (session == null) {
                        LaunchedEffect(r.sessionId) { route = Route.Shadow }
                    } else {
                        val acc = runCatching { JSONObject(session.scoresJson).optInt("accuracy") }.getOrDefault(0)
                        val items = runCatching {
                            val arr = JSONObject(session.fixesJson).getJSONArray("items")
                            (0 until arr.length()).map { i ->
                                val o = arr.getJSONObject(i)
                                ShadowResultItem(
                                    ref = o.optString("ref"),
                                    hyp = o.optString("hyp"),
                                    accuracy = o.optDouble("acc"),
                                    missed = o.optJSONArray("missed")?.let { a ->
                                        (0 until a.length()).map { a.getString(it) }
                                    } ?: emptyList(),
                                )
                            }
                        }.getOrDefault(emptyList())
                        ShadowResultScreen(
                            title = session.topic,
                            accuracy = acc,
                            items = items,
                            onAddWrong = {
                                scope.launch { repo.addShadowWords(items.flatMap { it.missed }) }
                            },
                            onRetry = {
                                val p = shadowPassages.firstOrNull { it.title == session.topic }
                                if (p != null) openShadow(p.id) else route = Route.Shadow
                            },
                            onBack = { route = Route.Shadow },
                        )
                    }
                }

                is Route.AiGen -> AiGenScreen(
                    aiSets = aiSets,
                    busy = aiGenBusy,
                    error = aiGenError,
                    stage = aiGenStage,
                    onGenerate = { m, d, t, c -> scope.launch { runAiGen(m, d, t, c) } },
                    onOpen = { openSet(it) },
                    onDelete = {
                        bankRepo.deleteAiSet(it)
                        aiSets = bankRepo.aiSets()
                    },
                    onBack = { route = Route.Home },
                )

                is Route.Bank, is Route.Quiz, is Route.BankResult -> {
                    val listPane: @Composable () -> Unit = {
                        BankScreen(
                            sets = bankSets,
                            lastScores = bankSets.associate { set ->
                                val s = sessions.firstOrNull { it.module == set.module && it.topic == set.title }
                                val txt = s?.let {
                                    runCatching {
                                        val o = JSONObject(it.scoresJson)
                                        "${o.optInt("correct")}/${o.optInt("total")}"
                                    }.getOrDefault("")
                                }.orEmpty()
                                set.id to txt
                            },
                            progress = bankSets.associate { it.id to bankRepo.savedAnswers(it.id).size },
                            onOpen = { openSet(it) },
                            onOpenTimed = { id, minutes -> openSet(id, minutes) },
                            onBack = { route = Route.Home },
                            initialFilter = bankFilter,
                        )
                    }
                    if (isWide) {
                        Row(Modifier.fillMaxSize()) {
                            Box(Modifier.fillMaxHeight().weight(0.36f)) { listPane() }
                            HorizontalDivider(Modifier.fillMaxHeight().width(1.dp))
                            Box(Modifier.fillMaxHeight().weight(0.64f)) {
                                when (r) {
                                    is Route.Quiz -> {
                                        val set = (bankSets + aiSets).firstOrNull { it.id == r.setId }
                                        if (set == null) {
                                            LaunchedEffect(r.setId) { route = Route.Home }
                                        } else {
                                            val isAi = set.id.startsWith("ai-")
                                            QuizScreen(
                                                set = set, repo = bankRepo, prefs = prefs, answers = bankAnswers,
                                                startAtMs = quizStartAt,
                                                limitMinutes = quizLimitMin,
                                                onAddWord = { w, ph, tr -> scope.launch { repo.addLookupWord(w, ph, tr) } },
                                                onAnswer = { qid, v ->
                                                    bankAnswers = bankAnswers.toMutableMap().apply { put(qid, v) }
                                                    bankRepo.saveAnswers(set.id, bankAnswers)
                                                },
                                                onSubmit = { scope.launch { submitQuiz(set.id) } },
                                                onBack = {
                                                    bankRepo.saveAnswers(set.id, bankAnswers)
                                                    route = if (isAi) Route.AiGen else Route.Bank
                                                },
                                            )
                                        }
                                    }
                                    is Route.BankResult -> {
                                        val data = bankResult
                                        if (data == null || data.setId != r.setId) {
                                            LaunchedEffect(r.sessionId) { route = Route.Bank }
                                        } else {
                                            BankResultScreen(
                                                title = data.title, module = data.module,
                                                correct = data.correct, total = data.total,
                                                items = data.items, passage = data.passage,
                                                transcript = data.transcript,
                                                readerFontSize = prefs.readerFontSize, readerLineHeight = prefs.readerLineHeight,
                                                aiExplains = aiExplains, explainingIds = explainingIds,
                                                onAiExplain = { item -> scope.launch { runAiExplain(item) } },
                                                onAddWrong = {
                                                    scope.launch {
                                                        val rows = data.items.filter { !it.ok }
                                                            .map { BankWrong(it.prompt, it.right, it.explain) }
                                                        repo.addBankWrong(rows, data.module)
                                                    }
                                                },
                                                onRetry = { openSet(data.setId) },
                                                onBack = { route = if (data.setId.startsWith("ai-")) Route.AiGen else Route.Bank },
                                            )
                                        }
                                    }
                                    else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Text("在左边选一套题开始", style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    } else {
                        when (r) {
                            is Route.Bank -> listPane()
                            is Route.Quiz -> {
                                val set = (bankSets + aiSets).firstOrNull { it.id == r.setId }
                                if (set == null) {
                                    LaunchedEffect(r.setId) { route = Route.Home }
                                } else {
                                    val isAi = set.id.startsWith("ai-")
                                    QuizScreen(
                                        set = set, repo = bankRepo, prefs = prefs, answers = bankAnswers,
                                        startAtMs = quizStartAt,
                                        limitMinutes = quizLimitMin,
                                        onAddWord = { w, ph, tr -> scope.launch { repo.addLookupWord(w, ph, tr) } },
                                        onAnswer = { qid, v ->
                                            bankAnswers = bankAnswers.toMutableMap().apply { put(qid, v) }
                                            bankRepo.saveAnswers(set.id, bankAnswers)
                                        },
                                        onSubmit = { scope.launch { submitQuiz(set.id) } },
                                        onBack = {
                                            bankRepo.saveAnswers(set.id, bankAnswers)
                                            route = if (isAi) Route.AiGen else Route.Bank
                                        },
                                    )
                                }
                            }
                            is Route.BankResult -> {
                                val data = bankResult
                                if (data == null || data.setId != r.setId) {
                                    LaunchedEffect(r.sessionId) { route = Route.Bank }
                                } else {
                                    BankResultScreen(
                                        title = data.title, module = data.module,
                                        correct = data.correct, total = data.total,
                                        items = data.items, passage = data.passage,
                                        transcript = data.transcript,
                                        readerFontSize = prefs.readerFontSize, readerLineHeight = prefs.readerLineHeight,
                                        aiExplains = aiExplains, explainingIds = explainingIds,
                                        onAiExplain = { item -> scope.launch { runAiExplain(item) } },
                                        onAddWrong = {
                                            scope.launch {
                                                val rows = data.items.filter { !it.ok }
                                                    .map { BankWrong(it.prompt, it.right, it.explain) }
                                                repo.addBankWrong(rows, data.module)
                                            }
                                        },
                                        onRetry = { openSet(data.setId) },
                                        onBack = { route = if (data.setId.startsWith("ai-")) Route.AiGen else Route.Bank },
                                    )
                                }
                            }
                            else -> listPane()
                        }
                    }
                }
            }
        }
    }
}
