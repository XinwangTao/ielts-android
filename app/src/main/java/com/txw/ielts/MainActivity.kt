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
import androidx.lifecycle.lifecycleScope
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
import com.txw.ielts.data.Card
import com.txw.ielts.data.CardRepo
import com.txw.ielts.data.FirstRun
import com.txw.ielts.data.LocalModelState
import com.txw.ielts.data.Prefs
import com.txw.ielts.data.Session
import com.txw.ielts.data.ShadowDiff
import com.txw.ielts.data.ShadowPassage
import com.txw.ielts.data.ShadowRepo
import com.txw.ielts.data.StudyRound
import com.txw.ielts.data.WrongTrendCalc
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
import com.txw.ielts.ui.FirstRunScreen
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
import com.txw.ielts.ui.StudyPlanCalc
import com.txw.ielts.ui.StudyPlanScreen
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
    /** 首启引导：只在首次启动自动进入，也可从设置页重新打开。 */
    data object FirstRun : Route
    data object Writing : Route
    data object Speaking : Route
    data object Settings : Route
    data object Review : Route
    data object Corpus : Route
    data object Study : Route
    /** 7 天备考课表：按考期倒排、可逐日换科目。 */
    data object Plan : Route
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
    Route.FirstRun -> "firstrun"
    Route.Writing -> "writing"
    Route.Speaking -> "speaking"
    Route.Settings -> "settings"
    Route.Review -> "review"
    Route.Corpus -> "corpus"
    Route.Study -> "study"
    Route.Plan -> "plan"
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
            "firstrun" -> Route.FirstRun
            "writing" -> Route.Writing
            "speaking" -> Route.Speaking
            "settings" -> Route.Settings
            "review" -> Route.Review
            "corpus" -> Route.Corpus
            "study" -> Route.Study
            "plan" -> Route.Plan
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
        // 本地 AI 无点击自测：e2e_ai=outline|sample|vocab，生成结果打到 E2E 日志。
        val e2eAi = intent?.getStringExtra("e2e_ai")

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
                        e2eAi,
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 后台长评分被 ColorOS 冻结/杀死后，回前台自动续跑；服务还在跑或引擎忙时不重复启动。
        if (ScoreService.active || LocalLlmEngine.state != "idle") return
        lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDb.get(this@MainActivity)
            val s = db.sessions().recent(20).firstOrNull {
                it.status == "pending_local" &&
                    (it.transcript.isNotBlank() || it.essayText.isNotBlank())
            } ?: return@launch
            val text = s.transcript.ifBlank { s.essayText }
            if (s.module == "speaking") {
                ScoreService.start(this@MainActivity, s.id, "speaking", text)
            } else {
                val task = s.task.ifBlank { guessTask(s.topic) }
                ScoreService.start(this@MainActivity, s.id, "writing", text, task)
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

/**
 * 从会话重建题库结果页数据（旋转/进程恢复时用）。
 * setId 用路由里保存的那个，保证与内存快照的 key 一致。
 */
private fun bankResultFromSession(session: Session, setId: String): BankResultData? {
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
    if (items.isEmpty()) return null
    return BankResultData(
        setId = setId,
        title = session.topic,
        module = session.module,
        passage = "",
        transcript = "",
        correct = sum?.optInt("correct") ?: 0,
        total = sum?.optInt("total") ?: items.size,
        items = items,
    )
}

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
    e2eAi: String? = null,
) {
    val context = LocalContext.current
    val isWide = LocalConfiguration.current.screenWidthDp >= 600
    val db = remember { AppDb.get(context) }
    val scope = rememberCoroutineScope()
    // null = Room Flow 还没吐出第一帧。结果页直达要在「加载完成」后才判断记录不存在，
    // 否则 App 冷启动时会话列表为空，会被误判为「记录已删除」瞬间弹回首页。
    val sessionsOrNull by db.sessions().observeAll().collectAsState(initial = null)
    val sessions = sessionsOrNull ?: emptyList()

    // 首次启动先进引导：新装机默认「云端优先」，而云端额度可能已死、本地模型也可能没投递，
    // 直接落在首页会让人不知道现在能不能出分。
    var route by rememberSaveable(stateSaver = RouteSaver) {
        mutableStateOf<Route>(if (prefs.firstRunDone) Route.Home else Route.FirstRun)
    }
    // 页面跳转诊断：旋转屏 / 进程重建 / 深链时用它确认 route 是否符合预期。
    LaunchedEffect(route) { Log.i(TAG, "route=${route.encode()} (freshStart=$freshStart)") }
    // 网络状态决定 AI 走云端还是本地。首页需要说明降级原因，不能让用户等到云端超时才发现没网；
    // 只在首页轮询，离开首页立即取消，不常驻耗电。
    var networkOnline by remember { mutableStateOf(com.txw.ielts.llm.NetworkStatus.hasNetwork(context)) }
    LaunchedEffect(route) {
        networkOnline = com.txw.ielts.llm.NetworkStatus.hasNetwork(context)
        if (route == Route.Home) {
            while (true) {
                delay(15_000L)
                networkOnline = com.txw.ielts.llm.NetworkStatus.hasNetwork(context)
            }
        }
    }
    var stage by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    // stage/error 是 Activity 级：记录它们属于哪一场。结果页只显示自己的，
    // 否则 A 篇评分时打开 B 篇会看到 A 的「评分中/转写中」，按钮还被禁用。
    var activeSessionId by remember { mutableStateOf<Long?>(null) }
    var practiceFocus by remember { mutableStateOf<PracticeFocus?>(null) }
    var speakingPresetQuestion by remember { mutableStateOf<String?>(null) }
    // 预设题目的所属 Part：追问生成的是 Part 3 问题，不能当 Part 2 练。为空时按原来的 Part 2 处理。
    var speakingPresetPart by remember { mutableStateOf<String?>(null) }
    var writingPresetTopic by remember { mutableStateOf<String?>(null) }
    // 预设题目的 Task：换题生成的是同类型题目，回填时不能一律当 Task 2。
    var writingPresetTask by remember { mutableStateOf<String?>(null) }
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
    // 「加入背诵」的实际入库账目（按会话存内存）：结果页据此说清加了几张、几条重复。
    var corpusAdd by remember { mutableStateOf<Pair<Long, com.txw.ielts.data.IngestResult>?>(null) }
    var vocabBusy by remember { mutableStateOf(false) }
    var vocabError by remember { mutableStateOf<String?>(null) }
    // Part 3 追问：真实考试 Part 2 之后考官会追着问，这里让本地模型扮演考官。
    var followUpQuestion by remember { mutableStateOf<String?>(null) }
    var followUpSessionId by remember { mutableStateOf<Long?>(null) }
    var followUpBusy by remember { mutableStateOf(false) }
    var followUpError by remember { mutableStateOf<String?>(null) }
    // 写作：换一个同类型新题（结果页按钮）。
    var newTopicBusy by remember { mutableStateOf(false) }
    var newTopicError by remember { mutableStateOf<String?>(null) }
    // 出题归属：生成中用户可能已经离开这个结果页，忙/错提示和跳转都要按会话判断。
    var newTopicSessionId by remember { mutableStateOf<Long?>(null) }
    // 冷启动先恢复上次 AI 复盘：不只省一次 30-60 秒端侧推理，也避免为同样的输入再花云端额度。
    val cachedAiReview = remember { com.txw.ielts.llm.AiReviewCache.fromJson(prefs.aiReviewCache) }
    var aiReviewCache by remember { mutableStateOf(cachedAiReview) }
    var aiReview by remember { mutableStateOf(cachedAiReview?.review) }
    var aiReviewBusy by remember { mutableStateOf(false) }
    var aiReviewError by remember { mutableStateOf<String?>(null) }
    var recoveryNotice by remember { mutableStateOf<String?>(null) }

    // 本地评分或本地 AI 生成在跑时钉住屏幕：熄屏会让 ColorOS 冻结进程，
    // 端侧推理（评分 / 提纲 / 范例 / 生词卡 / 复盘）必然中断（docs/oppo-background-scoring-findings.md §九）。
    com.txw.ielts.ui.KeepScreenOn(
        sessionsOrNull?.any { it.status == "pending_local" } == true ||
            writingOutlineBusy || speakingSampleBusy || vocabBusy || aiReviewBusy || followUpBusy || newTopicBusy ||
            !e2eAi.isNullOrBlank(),
    )

    // 恢复完成后撤掉首页提示：不再有 pending_local 就说明评分已出结果或已转可重试。
    LaunchedEffect(sessions) {
        if (recoveryNotice != null && sessions.none { it.status == "pending_local" }) recoveryNotice = null
    }

    // 进 AI 相关页面停留 5 秒后预热本地模型：用户写完/说完/点生成时少等一次 60 秒冷加载。
    // 只是路过（5 秒内离开）不会触发，避免白占 2.7GB；模型未投递时静默跳过。
    // 判定交给 LocalWarmup：内部已就绪（文件已拷贝）同样需要加载，不能因为「已就绪」就跳过。
    LaunchedEffect(route) {
        val likelyNeedsAi = route is Route.Writing || route is Route.Speaking || route is Route.AiGen
        if (!likelyNeedsAi) return@LaunchedEffect
        kotlinx.coroutines.delay(5_000)
        val ready = LocalLlmEngine.isReady(context, prefs.localModel)
        val delivered = LocalLlmEngine.hasExternalCopy(context, prefs.localModel)
        if (!com.txw.ielts.llm.LocalWarmup.shouldPreload(prefs.preloadOnPractice, ready, delivered)) {
            return@LaunchedEffect
        }
        runCatching { LocalLlmEngine.preload(context, prefs.localModel) }
    }

    // 三个入口共用本地引擎闸门；生成期间界面显示「约 30-60 秒」，失败只影响当前卡片。
    fun localModelMissing(): Boolean =
        !LocalLlmEngine.isReady(context, prefs.localModel) &&
            !LocalLlmEngine.hasExternalCopy(context, prefs.localModel)

    suspend fun requestWritingOutline(topic: String, task: String) {
        // 缓存读在 IO 上，期间按钮仍可能被再点一次：重复请求直接忽略。
        if (writingOutlineBusy) return
        // 同一题目 + 同一 Task 的提纲不会变：命中缓存直接展示，省掉一次 30-60 秒端侧推理。
        // 但如果界面上已经有这道题的提纲，这次点击就是「想换一份」，跳过缓存重新生成。
        val cacheKey = com.txw.ielts.llm.StudyAiCache.key("outline", prefs.localModel, task, topic)
        if (writingOutline == null || writingOutlineTopic != topic) {
            // 缓存串最大几十到两百多 KB：解析放到 IO，别在主线程做。
            val cachedRaw = withContext(Dispatchers.IO) {
                com.txw.ielts.llm.StudyAiCache.get(prefs.studyAiCache, cacheKey)
            }
            if (cachedRaw != null) {
                val cached = com.txw.ielts.llm.StudyAi.parseOutline(cachedRaw)
                if (!cached.isEmpty) {
                    writingOutline = cached
                    writingOutlineTopic = topic
                    writingOutlineError = null
                    return
                }
            }
        }
        // 归属先写、旧载荷要清：否则上一题的提纲会被当成这一题的结果展示。
        writingOutline = null
        writingOutlineBusy = true
        writingOutlineError = null
        writingOutlineTopic = topic
        if (LocalLlmEngine.isGenerating) {
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
            // 生成期间用户可能改了题目：只有仍是这道题才写入，避免旧结果覆盖新请求。
            if (writingOutlineTopic == topic) {
                if (r.isEmpty) {
                    writingOutlineError = "本地模型没有输出可用的提纲，请重试"
                } else {
                    writingOutline = r
                    withContext(Dispatchers.IO) {
                        // 读改写要串行：两次生成同时落缓存时，后写的不覆盖先写的条目。
                        prefs.editJson { prefs.studyAiCache = com.txw.ielts.llm.StudyAiCache.put(prefs.studyAiCache, cacheKey, r.raw) }
                    }
                }
            }
        } catch (e: Exception) {
            if (writingOutlineTopic == topic) writingOutlineError = "提纲生成失败：${e.message}"
        } finally {
            if (writingOutlineTopic == topic) writingOutlineBusy = false
        }
    }

    suspend fun requestSpeakingSample(part: String, question: String) {
        if (speakingSampleBusy) return
        // 同一道题的范例可以复用：切走再回来不必重跑 30-60 秒端侧推理。
        // 界面上已有这题的范例时，这次点击是想换一份，跳过缓存重新生成。
        val cacheKey = com.txw.ielts.llm.StudyAiCache.key("sample", prefs.localModel, part, question)
        if (speakingSample == null || speakingSampleQuestion != question) {
            val cachedRaw = withContext(Dispatchers.IO) {
                com.txw.ielts.llm.StudyAiCache.get(prefs.studyAiCache, cacheKey)
            }
            if (cachedRaw != null) {
                val cached = com.txw.ielts.llm.StudyAi.parseSample(cachedRaw)
                if (!cached.isEmpty) {
                    speakingSample = cached
                    speakingSampleQuestion = question
                    speakingSampleError = null
                    return
                }
            }
        }
        speakingSample = null
        speakingSampleBusy = true
        speakingSampleError = null
        speakingSampleQuestion = question
        if (LocalLlmEngine.isGenerating) {
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
            // 只有仍是这道题才写入：生成期间用户可能换了题目。
            if (speakingSampleQuestion == question) {
                if (r.isEmpty) {
                    speakingSampleError = "本地模型没有输出可用的范例，请重试"
                } else {
                    speakingSample = r
                    withContext(Dispatchers.IO) {
                        prefs.editJson { prefs.studyAiCache = com.txw.ielts.llm.StudyAiCache.put(prefs.studyAiCache, cacheKey, r.raw) }
                    }
                }
            }
        } catch (e: Exception) {
            if (speakingSampleQuestion == question) speakingSampleError = "范例生成失败：${e.message}"
        } finally {
            if (speakingSampleQuestion == question) speakingSampleBusy = false
        }
    }

    suspend fun requestVocab(sessionId: Long, text: String, fixes: List<Triple<String, String, String>>) {
        if (vocabBusy) return
        // 同一篇作文/同一段转写的生词卡不会变：重开结果页直接展示，省一次 30-60 秒推理。
        // 界面上已有本次会话的生词卡时，这次点击是想换一批，跳过缓存重新提取。
        val cacheKey = com.txw.ielts.llm.StudyAiCache.key(
            "vocab", prefs.localModel, sessionId.toString(), text, fixes.joinToString("\u0001") { it.first },
        )
        if (vocabResult == null || vocabSessionId != sessionId) {
            val cachedRaw = withContext(Dispatchers.IO) {
                com.txw.ielts.llm.StudyAiCache.get(prefs.studyAiCache, cacheKey)
            }
            if (cachedRaw != null) {
                val cached = com.txw.ielts.llm.StudyAi.parseVocab(cachedRaw)
                if (cached.items.isNotEmpty()) {
                    vocabResult = cached
                    vocabSessionId = sessionId
                    vocabError = null
                    return
                }
            }
        }
        vocabResult = null
        vocabBusy = true
        vocabError = null
        vocabSessionId = sessionId
        if (LocalLlmEngine.isGenerating) {
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
            // 只有仍是同一场会话才写入：生成期间用户可能切到别场并重新提取。
            if (vocabSessionId == sessionId) {
                vocabResult = r
                if (r.items.isNotEmpty()) {
                    withContext(Dispatchers.IO) {
                        prefs.editJson { prefs.studyAiCache = com.txw.ielts.llm.StudyAiCache.put(prefs.studyAiCache, cacheKey, r.raw) }
                    }
                }
            }
        } catch (e: Exception) {
            if (vocabSessionId == sessionId) vocabError = "生词提取失败：${e.message}"
        } finally {
            if (vocabSessionId == sessionId) vocabBusy = false
        }
    }

    /** 口语 Part 3 追问：把本场题目与转写交给本地模型，扮演考官问一个更抽象的问题。 */
    suspend fun requestFollowUp(sessionId: Long, question: String, answer: String) {
        // 已有请求在跑：再点会把 sessionId 改写成另一场，旧请求的结果被静默丢弃、新请求还得排队。
        if (followUpBusy) return
        followUpQuestion = null
        followUpBusy = true
        followUpError = null
        followUpSessionId = sessionId
        if (LocalLlmEngine.isGenerating) {
            followUpError = "本地模型正在忙（评分或另一项生成中），请等它完成后再试"
            followUpBusy = false
            return
        }
        if (localModelMissing()) {
            followUpError = "本地模型未就绪：请到设置页确认模型已投递"
            followUpBusy = false
            return
        }
        try {
            val q = withContext(Dispatchers.IO) {
                com.txw.ielts.llm.StudyAi.followUp(context, prefs.localModel, question, answer)
            }
            // 只有仍是同一场会话才写入：A 场生成中切到 B 场再点，A 的结果不能出现在 B 页。
            if (followUpSessionId == sessionId) {
                if (q.isBlank()) followUpError = "模型没有返回可用问题，请重试" else followUpQuestion = q
            }
        } catch (e: Exception) {
            if (followUpSessionId == sessionId) followUpError = "追问生成失败：${e.message}"
        } finally {
            if (followUpSessionId == sessionId) followUpBusy = false
        }
    }

    /** 写作换题：让本地模型出一道同类型新题，生成完直接进写作页预填。 */
    suspend fun requestNewWritingTopic(session: Session, task: String) {
        if (newTopicBusy) return
        newTopicBusy = true
        newTopicError = null
        newTopicSessionId = session.id
        if (LocalLlmEngine.isGenerating) {
            newTopicError = "本地模型正在忙（评分或另一项生成中），请等它完成后再试"
            newTopicBusy = false
            return
        }
        if (localModelMissing()) {
            newTopicError = "本地模型未就绪：请到设置页确认模型已投递"
            newTopicBusy = false
            return
        }
        try {
            val topic = withContext(Dispatchers.IO) {
                com.txw.ielts.llm.StudyAi.writingTopic(context, prefs.localModel, session.topic, task)
            }
            // 只有仍是这次请求才写入：生成期间用户可能在另一场点了换题。
            if (newTopicSessionId == session.id) {
                if (topic.isBlank()) {
                    newTopicError = "模型没有返回可用题目，请重试"
                } else {
                    practiceFocus = null
                    writingPresetChart = null
                    writingPresetTopic = topic
                    writingPresetTask = task
                    // 生成期间用户可能已经离开这个结果页：只有仍在看它时才自动跳过去，别把人拽走。
                    if (route == Route.Result(session.id)) route = Route.Writing
                }
            }
        } catch (e: Exception) {
            if (newTopicSessionId == session.id) newTopicError = "出新题失败：${e.message}"
        } finally {
            if (newTopicSessionId == session.id) newTopicBusy = false
        }
    }

    /** 复盘输入：最近 7 天成绩 + 高频问题；纯函数在 ReviewSummary，听读显示 correct/total 而不是 Band 0。 */
    fun buildReviewSummary(): String = com.txw.ielts.llm.ReviewSummary.build(
        sessions = sessions,
        targetBand = prefs.targetBand,
        weaknesses = com.txw.ielts.ui.topErrors(sessions).map { it.label to it.count },
        examDate = prefs.examDate,
    )

    suspend fun requestAiReview() {
        aiReviewBusy = true
        aiReviewError = null
        if (LocalLlmEngine.isGenerating) {
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
            val summary = buildReviewSummary()
            val r = withContext(Dispatchers.IO) {
                com.txw.ielts.llm.StudyAi.review(context, prefs.localModel, summary)
            }
            if (r.isEmpty) {
                aiReviewError = "本地模型没有输出可用的复盘，请重试"
            } else {
                // 落盘时记下输入指纹与练习条数：下次启动能判断这份复盘是否还对得上当前数据。
                val cache = com.txw.ielts.llm.AiReviewCache(
                    review = r,
                    createdAt = System.currentTimeMillis(),
                    fingerprint = com.txw.ielts.llm.AiReviewCache.fingerprint(summary + "\nmodel=" + prefs.localModel),
                    sessionsCount = sessions.size,
                )
                aiReview = r
                aiReviewCache = cache
                prefs.aiReviewCache = cache.toJson()
            }
        } catch (e: Exception) {
            aiReviewError = "复盘生成失败：${e.message}"
        } finally {
            aiReviewBusy = false
        }
    }

    // 复盘卡的新鲜度提示：数据没变/新增了几条/已过期，都写清楚，别让用户盲目重跑模型。
    val aiReviewHint = remember(aiReviewCache, sessions, prefs.targetBand, prefs.examDate, prefs.localModel) {
        aiReviewCache?.hint(
            currentFingerprint = com.txw.ielts.llm.AiReviewCache.fingerprint(buildReviewSummary() + "\nmodel=" + prefs.localModel),
            currentSessionsCount = sessions.size,
            now = System.currentTimeMillis(),
        )
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
            prefs.editJson {
                val m = runCatching { JSONObject(prefs.localScoreStarted) }.getOrDefault(JSONObject())
                m.put(s.id.toString(), System.currentTimeMillis())
                prefs.localScoreStarted = m.toString()
            }
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
            val referenced = db.sessions().audioPaths().toSet()
            val files = context.filesDir
                .listFiles { f -> f.isFile && f.name.endsWith(".wav", ignoreCase = true) }
                ?.map { it.absolutePath to it.lastModified() }
                .orEmpty()
            // 对练逐轮录音只在 ielts_partner 里，不并入引用集会被当成无主删掉。
            val partner = com.txw.ielts.data.RecordingCleanup.partnerRefs(
                context.getSharedPreferences(
                    com.txw.ielts.data.RecordingCleanup.PARTNER_PREFS, Context.MODE_PRIVATE,
                ).getString(com.txw.ielts.data.RecordingCleanup.PARTNER_TURNS_KEY, "[]")
            )
            com.txw.ielts.data.RecordingCleanup.pickDeletable(
                files, referenced + partner, System.currentTimeMillis(),
                com.txw.ielts.data.RecordingCleanup.KEEP_STARTUP_MS,
            ).forEach { path ->
                if (java.io.File(path).delete()) Log.i(TAG, "cleanup recording ${java.io.File(path).name}")
            }
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
    var wrongCards by remember { mutableStateOf<List<Card>>(emptyList()) }
    var reviewedToday by remember { mutableIntStateOf(0) }
    // 未复习的练习反馈卡（批注/逐句改法）：队列里会优先安排，语料页负责说清这件事。
    var feedbackPending by remember { mutableIntStateOf(0) }

    // —— 今日任务：基于数据的推荐（错题/到期卡/未练模块），碎片时间不用自己想练什么 ——
    val nowMs = System.currentTimeMillis()
    // App 跨午夜常驻时，依赖「今天」的首页任务/倒计时需要一次重组才会更新；
    // 每分钟对一次日期，跨天时 +1 触发重组（nowMs 在同一帧重新取）。
    var dayTick by remember { mutableStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        var last = java.time.LocalDate.now().toString()
        while (true) {
            kotlinx.coroutines.delay(60_000)
            val d = java.time.LocalDate.now().toString()
            if (d != last) { last = d; dayTick++ }
        }
    }
    // 一轮复习的状态（当前卡 / 翻面 / 哪张卡正在写库）统一交给 StudyRound，
    // 评级按钮的置灰跟着它走：换卡或 Again 就地重问都会解锁，不会只评得动第一张。
    // 整轮复习快照 saveable：旋转/进程重建后接着评，不退回语料页也不从头开始。
    var round by rememberSaveable(
        stateSaver = androidx.compose.runtime.saveable.Saver<StudyRound, String>(
            save = { com.txw.ielts.data.StudyRoundCodec.encode(it) },
            restore = { com.txw.ielts.data.StudyRoundCodec.decode(it) ?: StudyRound.EMPTY },
        ),
    ) { mutableStateOf(StudyRound()) }
    // 上一轮复习的小结：评完（或中途退出）后回到语料页先给一句交代，开新一轮时清掉。
    var lastRoundSummary by remember { mutableStateOf<com.txw.ielts.data.RoundSummary?>(null) }
    // 队列为空时的交代：在首页/语料页都显示出来，点击不再像没反应。
    var queueNotice by remember { mutableStateOf<com.txw.ielts.data.QueueNotice?>(null) }
    // 页面切走就撤掉提示：它只解释「刚才那一下」，不该跨页面或跨天留着误导人。
    LaunchedEffect(route) { queueNotice = null }
    fun beginRound(cards: List<com.txw.ielts.data.Card>): StudyRound {
        lastRoundSummary = null
        return StudyRound.start(cards)
    }

    // 今日任务：点一下就直接开始，不再先跳语料首页再点第二轮。
    // 队列为空时保留上一轮小结（空入口不该抹掉刚看到的交代），只给一句可读的提示。
    fun startWrongQueue() {
        scope.launch {
            val cards = repo.buildWrongQueue()
            if (cards.isEmpty()) {
                queueNotice = com.txw.ielts.data.QueueNotice.emptyWrong()
                return@launch
            }
            queueNotice = null
            error = null
            round = beginRound(cards)
            route = Route.Study
        }
    }
    fun startDueQueue() {
        scope.launch {
            val cards = repo.buildQueue(prefs.dailyNewCards)
            if (cards.isEmpty()) {
                queueNotice = com.txw.ielts.data.QueueNotice.emptyReview(wrongCount)
                return@launch
            }
            queueNotice = null
            error = null
            round = beginRound(cards)
            route = Route.Study
        }
    }
    // 临考收紧：考前两周口语/写作的复练间隔自动缩短（14 天内减半、7 天内每天），
    // 平时不必天天练的项目，临近考试就不能再等 3 天了。间隔判断统一在 DailyPlanCalc 里做。
    val daysLeft = remember(prefs.examDate, dayTick) {
        com.txw.ielts.data.ExamTarget.daysUntil(prefs.examDate, nowMs)
    }
    // 四科目标与最弱科：目标和首页共用一份，今日任务按「差得最多」排，而不是只看隔了几天。
    val skillTargets = SkillTargets(
        listening = prefs.targetListening.toDouble(),
        reading = prefs.targetReading.toDouble(),
        writing = prefs.targetWriting.toDouble(),
        speaking = prefs.targetSpeaking.toDouble(),
    )
    // 错题趋势只用已有数据（练习正确率 + 错题卡），进语料页时随 refreshCorpus 更新；
    // 按天做 key，App 跨天常驻时窗口也会跟着滚动。
    val wrongTrend = remember(sessions, wrongCards, dayTick) {
        WrongTrendCalc.build(sessions, wrongCards, nowMs)
    }
    var bankFilter by remember { mutableStateOf("all") }
    fun routeFor(module: String): Route = when (module) {
        "writing" -> Route.Writing
        "speaking" -> Route.Speaking
        else -> Route.Bank // 听力/阅读共用题库页，进去再按模块筛
    }
    // 只把 status=done 的练习算作「练过」，最弱科与到点复练合并，按优先级截断；
    // 首页每行显示推荐依据和预计时长，点一下直接开始。
    val skillProfile = remember(sessions) { com.txw.ielts.ui.skillProfile(sessions) }
    val dailyTasks = com.txw.ielts.ui.DailyPlanCalc.build(
        sessions = sessions,
        wrongCount = wrongCount,
        dueCards = due,
        targets = skillTargets,
        now = nowMs,
        daysLeft = daysLeft,
        profile = skillProfile,
    )
    val dailyPlan: List<com.txw.ielts.ui.PlanEntry> = dailyTasks.map { t ->
        com.txw.ielts.ui.PlanEntry(t.title, t.detail, t.minutes) {
            when (t.kind) {
                com.txw.ielts.ui.DailyTaskKind.WRONG -> startWrongQueue()
                com.txw.ielts.ui.DailyTaskKind.CARDS -> startDueQueue()
                com.txw.ielts.ui.DailyTaskKind.FREE -> {
                    bankFilter = "all"
                    route = Route.Bank
                }
                com.txw.ielts.ui.DailyTaskKind.GAP,
                com.txw.ielts.ui.DailyTaskKind.SPEAKING,
                com.txw.ielts.ui.DailyTaskKind.WRITING -> {
                    if (t.module == "listening" || t.module == "reading") bankFilter = t.module
                    route = routeFor(t.module)
                }
            }
        }
    }
    // 7 天课表：与今日任务共用考期/目标/四科估值，但回答的是「这周怎么排」。
    // 手动换过的日子按日期存 SharedPreferences，跨天重算后旧日期的修改自然失效；
    // 换科目只是覆盖一层，课表本体仍然随练习数据每天重算。
    var planOverrides by remember {
        mutableStateOf(com.txw.ielts.ui.PlanOverrides.decode(prefs.studyPlanOverrides))
    }
    fun savePlanOverrides(next: Map<String, String>) {
        planOverrides = next
        prefs.studyPlanOverrides = com.txw.ielts.ui.PlanOverrides.encode(next)
    }
    val studyPlan = StudyPlanCalc.build(
        now = nowMs,
        examDate = prefs.examDate,
        targets = skillTargets,
        profile = skillProfile,
        dueCards = due,
        wrongCount = wrongCount,
        overrides = planOverrides,
        // 课表本身已经会随练习数据重算；这里再把练习记录交给它，
        // 才好在「今天」那张卡上打勾、在页头给出「计划 N 次 / 已完成 M 次」。
        sessions = sessions,
    )
    // 未加手动覆盖的课表：换科目轮回到这一天的原科目时用它判定「已经等于原样」，
    // 免得 SharedPreferences 里留下一条看起来没生效、过几天却突然生效的旧修改。
    val baseStudyPlan = StudyPlanCalc.build(
        now = nowMs,
        examDate = prefs.examDate,
        targets = skillTargets,
        profile = skillProfile,
        dueCards = due,
        wrongCount = wrongCount,
    )
    // 首启引导：把「目标 / AI 通道 / 第一道题」三步算成可渲染状态，缺哪步给按钮。
    // remember 的 key 用 route：从设置页回到首页时就地重算，用户配完不用重启 App。
    val firstRunPlan = remember(route, networkOnline, sessions, prefs.targetBand, prefs.examDate, prefs.localModel, prefs.apiKey) {
        val localState = runCatching {
            when {
                LocalLlmEngine.isReady(context, prefs.localModel) -> LocalModelState.READY
                LocalLlmEngine.hasExternalCopy(context, prefs.localModel) -> LocalModelState.DELIVERED
                else -> LocalModelState.MISSING
            }
        }.getOrDefault(LocalModelState.MISSING)
        FirstRun.plan(
            targetBand = prefs.targetBand,
            examDate = prefs.examDate,
            localModel = prefs.localModel,
            localModelState = localState,
            cloudKeyReady = prefs.apiKey.isNotBlank(),
            online = networkOnline,
            practiceCount = sessions.count { it.status == com.txw.ielts.data.StatusLabel.DONE },
        )
    }
    val (ttsAvailable, speak) = com.txw.ielts.ui.rememberTts()

    // ---- 听力/阅读题库（全离线，判分不调用任何接口）----
    val bankRepo = remember { BankRepo(context, prefs) }
    val bankSets = remember { runCatching { bankRepo.sets() }.getOrDefault(emptyList()) }
    var bankAnswers by remember { mutableStateOf<Map<Int, String>>(emptyMap()) }
    // 交卷失败（DB 写失败/套题被删）留在答题页提示，答案不丢。
    var quizError by remember { mutableStateOf<String?>(null) }
    // 用 rememberSaveable：旋转/进程重建后计时与限时不能归零。
    var quizStartAt by rememberSaveable { mutableStateOf(0L) }
    var quizLimitMin by rememberSaveable { mutableStateOf(0) }
    var bankResult by remember { mutableStateOf<BankResultData?>(null) }
    var aiSets by remember { mutableStateOf(bankRepo.aiSets()) }
    var aiGenBusy by remember { mutableStateOf(false) }
    var aiGenError by remember { mutableStateOf<String?>(null) }
    var aiGenStage by remember { mutableStateOf<String?>(null) }
    var aiGenReady by remember { mutableStateOf<String?>(null) }   // 后台生成完成待查看的套题 id
    // 精讲按「作用域#题号」存，跨套题不串。
    var aiExplains by remember { mutableStateOf<Map<String, BankExplain>>(emptyMap()) }
    var explainingIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    // 精讲失败按「作用域#题号」展示：写进全局 error 会跑到无关作文结果页上，题库页又看不到。
    var explainErrors by remember { mutableStateOf<Map<String, String?>>(emptyMap()) }
    // 键是「结果页作用域#题号」：不同套题/不同会话的同号题不能共用变式。
    var bankVariants by remember { mutableStateOf<Map<String, com.txw.ielts.llm.BankVariant>>(emptyMap()) }
    var variantBusyIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    // 变式题的失败原因按题展示在卡片里：写进全局 error 结果页根本不显示，用户只看到「点了没反应」。
    var variantErrors by remember { mutableStateOf<Map<String, String?>>(emptyMap()) }

    // Route 恢复了但答题 Map 只在内存：从已保存进度回填，避免空 Map 把整套答案覆盖掉。
    LaunchedEffect(route) {
        val r = route
        if (r is Route.Quiz && bankAnswers.isEmpty()) {
            val saved = withContext(Dispatchers.IO) { bankRepo.savedAnswers(r.setId) }
            if (saved.isNotEmpty()) bankAnswers = saved
        }
    }

    fun openSet(setId: String, limitMin: Int = 0) {
        quizError = null
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

    /** 跟读进度落盘：旋转/进程重建后接着读，不用从头再来。 */
    fun saveShadowProgress(id: String) {
        runCatching {
            val o = JSONObject(prefs.shadowProgress)
            o.put(id, com.txw.ielts.data.ShadowProgressCodec.encode(
                com.txw.ielts.data.ShadowProgressState(shadowIndex, shadowTranscripts),
            ))
            prefs.shadowProgress = o.toString()
        }
    }

    fun loadShadowProgress(id: String, passage: ShadowPassage) {
        runCatching {
            val state = com.txw.ielts.data.ShadowProgressCodec.decode(
                JSONObject(prefs.shadowProgress).optJSONObject(id),
                maxIndex = (passage.sentences.size - 1).coerceAtLeast(0),
            ) ?: return@runCatching
            shadowIndex = state.index
            shadowTranscripts = state.transcripts
            // diff 不落盘（结构复杂）：用转写与原文重新比对，保证结果卡能显示。
            shadowDiffs = state.transcripts.mapValues { (i, text) ->
                shadowRepo.compare(passage.sentences.getOrNull(i) ?: "", text)
            }
        }
    }

    fun clearShadowProgress(id: String) {
        runCatching {
            val o = JSONObject(prefs.shadowProgress)
            o.remove(id)
            prefs.shadowProgress = o.toString()
        }
    }

    fun openShadow(id: String) {
        val passage = shadowRepo.byId(id)
        shadowPassage = passage
        shadowIndex = 0
        shadowDiffs = emptyMap()
        shadowTranscripts = emptyMap()
        if (passage != null) loadShadowProgress(id, passage)
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
        wrongCards = repo.wrongBook()
        reviewedToday = repo.reviewedToday()
        feedbackPending = repo.feedbackPending()
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

    /**
     * 本地通道是否真的可用：内部已就绪，或已投递到外部目录（首次使用会自动拷贝）。
     * 通道提示/预检都靠它区分「降级到本地」与「降级到必然失败」——
     * 后者不能再让用户去等本地模型，而要直接说清先投递模型。
     */
    fun localAiAvailable(): Boolean = runCatching {
        LocalLlmEngine.isReady(context, prefs.localModel) ||
            LocalLlmEngine.hasExternalCopy(context, prefs.localModel)
    }.getOrDefault(false)

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
        prefs.editJson {
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
    }

    fun localScoreStartedAt(id: Long): Long =
        runCatching { JSONObject(prefs.localScoreStarted).optLong(id.toString(), 0L) }
            .getOrDefault(0L)

    /** 评分失败原因按会话存盘：结果页重开/重启后仍能说清「为什么没出分、下一步改什么」。 */
    fun recordScoreFailure(id: Long, reason: String?) {
        prefs.editJson {
            prefs.localScoreErrors =
                com.txw.ielts.llm.ScoreFailures.record(prefs.localScoreErrors, id, reason)
        }
    }

    fun clearScoreFailure(id: Long) {
        prefs.editJson {
            prefs.localScoreErrors = com.txw.ielts.llm.ScoreFailures.clear(prefs.localScoreErrors, id)
        }
    }

    /** 后台本地写作评分（不阻塞继续练习）。 */
    suspend fun startLocalWriting(session: Session, text: String, task: String) {
        activeSessionId = session.id
        // 已出分的记录不能被迟到的重试降级成待评分。
        if (db.sessions().byId(session.id)?.status == "done") return
        stage = null
        error = null
        clearScoreFailure(session.id)
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
            recordScoreFailure(session.id, error)
        }
    }

    /** 后台本地口语评分（不阻塞继续练习）。 */
    suspend fun startLocalSpeaking(session: Session, text: String) {
        activeSessionId = session.id
        if (db.sessions().byId(session.id)?.status == "done") return
        stage = null
        error = null
        clearScoreFailure(session.id)
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
            recordScoreFailure(session.id, error)
        }
    }

    /**
     * 评分通道（图：ielts-local-llm_workflow 的 LlmRouter）：
     * auto 云端优先、失败自动降级本地；local 仅本地；cloud 仅云端。
     * 本地评分转后台：立刻进结果页（pending_local），完成通知 + Room 自动刷新。
     */
    suspend fun evaluateWriting(session: Session, text: String, task: String = "task2") {
        activeSessionId = session.id
        val mode = prefs.scoreChannel
        val cloudDown = System.currentTimeMillis() < prefs.cloudDownUntil
        val online = hasNetwork(context)
        // 新一轮评分先清掉上一轮的原因：这次如果成功，结果页不该再显示「上次失败」。
        clearScoreFailure(session.id)
        // 显式选云端又断网：这一路必然失败，直接给出改设置的提示，不白等 HTTP 超时。
        ChannelPick.cloudOfflineError(mode, online, localAiAvailable())?.let { throw IOException(it) }
        if (ChannelPick.effective(
                mode, cloudDown, online, prefs.remainingCalls()
            ) == "local"
        ) {
            startLocalWriting(session, text, task)
            return
        }
        stage = "评分中（${prefs.model}）…"
        val t0 = System.currentTimeMillis()
        // 云端请求也要扛住切后台：ColorOS 在窗口不可见约 7 秒后就会冻进程，HTTP 等待会被一起冻掉。
        com.txw.ielts.Guard.start(context)
        try {
            val eval: WritingEval = withContext(Dispatchers.IO) {
                val client = LlmClient(prefs.baseUrl, prefs.apiKey, prefs.model, guard, onRetry)
                // 模型偶发把 JSON 写坏：格式类失败重发一次，别直接掉到本地白等几分钟。
                com.txw.ielts.llm.retryOnceOnFormat {
                    if (task == "task1") client.evaluateWritingTask1(session.topic, text)
                    else client.evaluateWriting(session.topic, text, myWeaknesses())
                }
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
            clearScoreFailure(session.id)
            stage = null
            error = null
            // 评分完成不强行导航：用户已离开时只更新 Room 并靠通知告知，避免把人从别的页面拉回结果页。
            if (route is Route.Writing || route is Route.Speaking || route == Route.Result(session.id)) {
                route = Route.Result(session.id)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (mode == "cloud") {
                recordScoreFailure(session.id, e.message)
                throw e
            }
            val msg = e.message ?: ""
            // 额度耗尽/限流：冷却 1 小时，auto 直接走本地，避免每次评分都先白试云端。
            markCloudDown(prefs, e)
            Log.w(TAG, "cloud writing eval failed, fallback local: $msg")
            startLocalWriting(session, text, task)
        } finally {
            com.txw.ielts.Guard.stop(context)
        }
    }

    suspend fun evaluateSpeaking(session: Session, text: String) {
        activeSessionId = session.id
        val mode = prefs.scoreChannel
        val part = when {
            session.topic.startsWith("Mock Test") -> "Part 1-3"
            session.topic.contains(" · ") -> session.topic.substringBefore(" · ").trim()
            else -> "Part"
        }
        val cloudDown = System.currentTimeMillis() < prefs.cloudDownUntil
        val online = hasNetwork(context)
        clearScoreFailure(session.id)
        ChannelPick.cloudOfflineError(mode, online, localAiAvailable())?.let { throw IOException(it) }
        if (ChannelPick.effective(
                mode, cloudDown, online, prefs.remainingCalls()
            ) == "local"
        ) {
            startLocalSpeaking(session, text)
            return
        }
        stage = "评分中（${prefs.model}）…"
        val t0 = System.currentTimeMillis()
        com.txw.ielts.Guard.start(context)
        try {
            val eval: SpeakingEval = withContext(Dispatchers.IO) {
                val client = LlmClient(prefs.baseUrl, prefs.apiKey, prefs.model, guard, onRetry)
                // 同上：格式类失败重发一次，避免直接掉本地。
                com.txw.ielts.llm.retryOnceOnFormat {
                    client.evaluateSpeaking(part, session.topic, text, session.durationSec, myWeaknesses())
                }
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
            clearScoreFailure(session.id)
            stage = null
            error = null
            // 评分完成不强行导航：用户已离开时只更新 Room 并靠通知告知，避免把人从别的页面拉回结果页。
            if (route is Route.Writing || route is Route.Speaking || route == Route.Result(session.id)) {
                route = Route.Result(session.id)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (mode == "cloud") {
                recordScoreFailure(session.id, e.message)
                throw e
            }
            val msg = e.message ?: ""
            markCloudDown(prefs, e)
            Log.w(TAG, "cloud speaking eval failed, fallback local: $msg")
            startLocalSpeaking(session, text)
        } finally {
            com.txw.ielts.Guard.stop(context)
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
            recordScoreFailure(id, e.message)
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
            // 留在模考页：错误文案只有该页会显示，跳回首页等于「点了提交然后什么都没发生」。
            route = Route.WritingMock
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
            recordScoreFailure(id, e.message)
            db.sessions().updateResult(id, text, "", "", "", "", "pending_eval")
            error = "评分失败：${e.message}（录音已保存，可重试）"; stage = null; route = Route.Result(id)
        }
    }

    suspend fun submitQuiz(setId: String) {
        val set = bankRepo.byId(setId) ?: run {
            quizError = "这套题已被删除，无法判分"
            return
        }
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
        // 进程重建后 startAt 异常时不要写入荒唐用时：限制在 0..24h。
        val seconds = (((System.currentTimeMillis() - quizStartAt) / 1000).toInt()).coerceIn(0, 24 * 3600)
        val id = try {
            db.sessions().insert(
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
        } catch (e: Exception) {
            // 写库失败（磁盘满/DB 损坏）不能让协程异常直接杀进程；答案还在，可重试。
            Log.e(TAG, "submitQuiz fail: ${e.message}")
            quizError = "交卷失败：${e.message}（答案已保留，可重试）"
            return
        }
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
                    // 超时/取消时协程直接结束，但 TTS 引擎绑定还在：必须在这里 shutdown，
                    // 否则每次超时都留下一个常驻的合成引擎（对比重试 3 次就是 3 个）。
                    val closed = java.util.concurrent.atomic.AtomicBoolean(false)
                    fun closeTts() {
                        if (closed.compareAndSet(false, true)) runCatching { tts?.stop(); tts?.shutdown() }
                    }
                    cont.invokeOnCancellation { closeTts() }
                    fun finish(done: Boolean) {
                        if (settled) return
                        settled = true
                        Log.i(TAG, "tts one finish=$done size=${outFile.length()}")
                        closeTts()
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
        val online = hasNetwork(context)
        ChannelPick.cloudOfflineError(prefs.scoreChannel, online, localAiAvailable())?.let {
            aiGenError = it
            aiGenBusy = false
            return
        }
        // 本地模型要几分钟才出题，先说清楚，否则用户以为卡死；auto 模式也提示会自动兜底。
        // 云端处于冷却期时这里直接落成 local，否则每次出题都要先白试一次云端。
        val channelNow = ChannelPick.effective(
            prefs.scoreChannel,
            System.currentTimeMillis() < prefs.cloudDownUntil,
            online,
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
        // 云端出题是长 HTTP 等待：窗口不可见约 7 秒进程就被冻，请求会一起停住。
        // 本地分支内部已包 Guard（LocalLlmEngine），这里再包一层靠引用计数，不会重复占资源。
        Guard.start(context)
        try {
            val setId = "ai-${System.currentTimeMillis()}"
            val set: BankSet
            if (module == "listening") {
                val gen = withContext(Dispatchers.IO) {
                    // client 建在 retry 之外：格式类重试复用同一实例，额度只扣一次。
                    val client = LlmClient(prefs.baseUrl, prefs.apiKey, prefs.model, guard, retryFeedback)
                    routeLlm(
                        channel = channelNow,
                        label = "AI 出题（听力）",
                        onCloudFail = { markCloudDown(prefs, it) },
                        cloud = { client.generateListening(difficulty, types, count) },
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
                    intro = "$difficulty · AI 生成 · ${gen.questions.size} 题" +
                        // 模型偶尔漏题（残题会被过滤）：说清「请求 N 题、实际 M 题」，别让用户以为本来就这么多。
                        (if (count > 0 && gen.questions.size < count) " · 模型少给 ${count - gen.questions.size} 题" else "") +
                        (if (ok) " · 含音频" else ""),
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
                    val client = LlmClient(prefs.baseUrl, prefs.apiKey, prefs.model, guard, retryFeedback)
                    routeLlm(
                        channel = channelNow,
                        label = "AI 出题（阅读）",
                        onCloudFail = { markCloudDown(prefs, it) },
                        cloud = { client.generateReading(difficulty, types, count) },
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
                    intro = "$difficulty · AI 生成 · ${gen.questions.size} 题" +
                        (if (count > 0 && gen.questions.size < count) " · 模型少给 ${count - gen.questions.size} 题" else ""),
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
            Guard.stop(context)
        }
    }

    suspend fun runAiExplain(item: BankResultItem, resultScope: String) {
        val ek = "$resultScope#${item.id}"
        val mode = prefs.scoreChannel
        val online = hasNetwork(context)
        ChannelPick.cloudOfflineError(mode, online, localAiAvailable())?.let {
            explainErrors = explainErrors + (ek to it)
            return
        }
        // 与评分/出题共用选路：auto 在断网/冷却/无额度时直接走本地，不再先白试一次云端。
        val channel = ChannelPick.effective(
            mode,
            System.currentTimeMillis() < prefs.cloudDownUntil,
            online,
            prefs.remainingCalls(),
        )
        explainErrors = explainErrors - ek
        explainingIds = explainingIds + ek
        Guard.start(context)
        try {
            val exp = withContext(Dispatchers.IO) {
                val client = LlmClient(prefs.baseUrl, prefs.apiKey, prefs.model, guard, onRetry)
                routeLlm(
                    channel = channel,
                    label = "错题精讲",
                    onCloudFail = { markCloudDown(prefs, it) },
                    cloud = { client.explainBankWrong(item.prompt, item.right, item.yours, item.explain) },
                    local = {
                        LocalLlmEngine.explainBankWrong(
                            context, prefs.localModel,
                            item.prompt, item.right, item.yours, item.explain,
                        )
                    },
                )
            }
            aiExplains = aiExplains.toMutableMap().apply { put(ek, exp) }
        } catch (e: Exception) {
            Log.e(TAG, "ai explain fail: ${e.message}")
            explainErrors = explainErrors + (ek to "精讲失败：${e.message}")
        } finally {
            explainingIds = explainingIds - ek
            Guard.stop(context)
        }
    }

    /** 错题变式题：同考点换新内容，避免重做原题变成背答案。 */
    suspend fun runVariant(item: BankResultItem, resultScope: String) {
        val vk = "$resultScope#${item.id}"
        if (vk in variantBusyIds) return
        // 本次请求一开始就清掉旧失败提示：缓存命中直接出题时也不该还挂着上一次的错误。
        variantErrors = variantErrors - vk
        // 同一道错题 + 同一份作答的变式可复用：重开结果页/重启后不必再跑一次模型。
        // 界面上已经有这道题的变式时，本次点击就是「换一道」，跳过缓存重新生成。
        val cacheKey = com.txw.ielts.llm.StudyAiCache.key(
            "variant", prefs.localModel, resultScope, item.prompt, item.right, item.yours, item.explain,
        )
        if (bankVariants[vk] == null) {
            val cachedRaw = withContext(Dispatchers.IO) {
                com.txw.ielts.llm.StudyAiCache.get(prefs.studyAiCache, cacheKey)
            }
            if (cachedRaw != null) {
                val cached = com.txw.ielts.llm.StudyAi.parseVariant(cachedRaw)
                if (!cached.isEmpty) {
                    bankVariants = bankVariants + (vk to cached)
                    return
                }
            }
        }
        variantBusyIds = variantBusyIds + vk
        try {
            val v = withContext(Dispatchers.IO) {
                com.txw.ielts.llm.StudyAi.variant(
                    context, prefs.localModel,
                    item.prompt, item.right, item.yours, item.explain,
                )
            }
            if (v.isEmpty) {
                variantErrors = variantErrors + (vk to "变式题生成失败，请重试")
            } else {
                bankVariants = bankVariants + (vk to v)
                withContext(Dispatchers.IO) {
                    prefs.editJson { prefs.studyAiCache = com.txw.ielts.llm.StudyAiCache.put(prefs.studyAiCache, cacheKey, v.raw) }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "variant fail: ${e.message}")
            variantErrors = variantErrors + (vk to "变式题生成失败：${e.message}")
        } finally {
            variantBusyIds = variantBusyIds - vk
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
        // 从引导按返回 = 跳过：标记看过后下次启动不再自动弹，缺的设置项随时可补。
        if (route is Route.FirstRun) prefs.firstRunDone = true
        // 系统返回与左上角返回走同一套清理：否则重练的 fixes/预设题目会残留到下一次练习。
        practiceFocus = null
        writingPresetTopic = null
        writingPresetTask = null
        speakingPresetQuestion = null
        speakingPresetPart = null
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

    LaunchedEffect(e2eAi, freshStart) {
        val mode = e2eAi?.trim()?.lowercase().orEmpty()
        if (mode.isEmpty() || !freshStart) return@LaunchedEffect
        val model = prefs.localModel
        try {
            // 本地 submit 是阻塞 native 调用；直接在 Main 协程里跑会触发 5 秒 ANR。
            withContext(Dispatchers.IO) {
            when (mode) {
                "outline" -> {
                    val r = com.txw.ielts.llm.StudyAi.outline(
                        context, model,
                        "Some people believe university students should study whatever they like. " +
                            "Others think they should only study subjects that will be useful. " +
                            "Discuss both views and give your own opinion.",
                        "task2",
                    )
                    Log.i(TAG, "E2E: RESULT ai outline stance=${r.stance.length} args=${r.arguments.size} " +
                        "phrases=${r.phrases.size} raw=${r.raw.isNotBlank()}")
                }
                "sample" -> {
                    val r = com.txw.ielts.llm.StudyAi.speakingSample(
                        context, model, "Part 2",
                        "Describe a skill you learned that was difficult at first. You should say what it was, " +
                            "how you learned it, why it was difficult, and explain how you feel about it now.",
                    )
                    val words = r.answer.split(Regex("\\s+")).count { it.isNotBlank() }
                    Log.i(TAG, "E2E: RESULT ai sample words=$words keywords=${r.keywords.size} " +
                        "structure=${r.structure.size} raw=${r.raw.isNotBlank()}")
                }
                "vocab" -> {
                    val essay = "Many people believe that universities should only offer courses that lead directly to jobs. " +
                        "I disagree with this view. First, education is not only about employment; " +
                        "it also helps people become responsible citizens."
                    val r = com.txw.ielts.llm.StudyAi.vocab(context, model, essay, emptyList())
                    Log.i(TAG, "E2E: RESULT ai vocab items=${r.items.size} raw=${r.raw.isNotBlank()}")
                }
                "review" -> {
                    // 用真实历史生成复盘摘要，和首页「AI 复盘」走同一条输入链路。
                    val summary = buildReviewSummary()
                    val r = com.txw.ielts.llm.StudyAi.review(context, model, summary)
                    Log.i(TAG, "E2E: RESULT ai review tasks=${r.tasks.size} " +
                        "summary=${r.summary.isNotBlank()} encourage=${r.encouragement.isNotBlank()}")
                }
                "followup" -> {
                    val q = com.txw.ielts.llm.StudyAi.followUp(
                        context, model,
                        "Do you think schools should teach practical skills?",
                        "Yes, I think schools focus too much on exams. Students should learn cooking, " +
                            "personal finance and basic repair because these skills are useful in daily life.",
                    )
                    Log.i(TAG, "E2E: RESULT ai followup chars=${q.length} q=$q")
                }
                "variant" -> {
                    val v = com.txw.ielts.llm.StudyAi.variant(
                        context, model,
                        "The writer says the museum was originally built to store private collections.",
                        "NOT GIVEN",
                        "TRUE",
                        "原文只提到 19 世纪建造，没有说明最初用途。",
                    )
                    val answerOk = v.options.isEmpty() || v.options.any { it.equals(v.answer, ignoreCase = true) }
                    Log.i(TAG, "E2E: RESULT ai variant empty=${v.isEmpty} options=${v.options.size} " +
                        "answerOk=$answerOk q=${v.question.take(60)}")
                }
                "newtopic" -> {
                    val t = com.txw.ielts.llm.StudyAi.writingTopic(
                        context, model,
                        "Some people believe university students should study whatever they like. " +
                            "Others think they should only study subjects that will be useful. " +
                            "Discuss both views and give your own opinion.",
                        "task2",
                    )
                    val words = t.split(Regex("\\s+")).count { it.isNotBlank() }
                    Log.i(TAG, "E2E: RESULT ai newtopic words=$words t=${t.take(80)}")
                }
                "score_writing" -> {
                    val topic = "Some people believe university students should study whatever they like. " +
                        "Others think they should only study subjects that will be useful. " +
                        "Discuss both views and give your own opinion."
                    val essay = """
                        Some people believe that university students should be free to study whatever they are passionate about, while others argue that they should only choose subjects with clear employment value. In my opinion, both perspectives have merit, but a balanced approach is the most sensible.

                        On the one hand, allowing students to follow their interests can lead to greater motivation and academic success. When people study a subject they genuinely enjoy, they are more likely to engage deeply with the material, persist through difficulties, and produce original work. For example, a student who loves history may spend extra hours in the library and eventually become a researcher who contributes to public understanding of the past. If universities force everyone into a narrow set of useful courses, this kind of curiosity may be lost.

                        On the other hand, higher education is expensive, and graduates need to repay loans and find work. Subjects such as medicine, engineering and computer science provide clear routes into employment, while some arts courses may not. Governments and universities therefore have a responsibility to offer guidance about labour-market trends and to ensure that students understand the financial consequences of their choices.

                        However, the idea that only vocational subjects are valuable is too simplistic. Critical thinking, communication and cultural awareness are also important for society, and these skills are developed through a wide range of disciplines. A better solution is to combine flexibility with support: students should be able to choose their major, but universities should provide career counselling, internships and opportunities to combine subjects.

                        In conclusion, I believe students should be allowed to study what they love, provided that they receive honest information about employment prospects. A system that respects both personal interest and practical responsibility is likely to produce happier graduates and a more adaptable workforce.
                    """.trimIndent()
                    val ev = com.txw.ielts.llm.LocalLlmEngine.scoreWriting(
                        context, model, topic, essay, listOf("字数与展开"), "task2",
                    )
                    Log.i(TAG, "E2E: RESULT ai score_writing overall=${ev.overall} tr=${ev.tr} " +
                        "cc=${ev.cc} lr=${ev.lr} gra=${ev.gra} fixes=${ev.fixes.size} " +
                        "sentences=${ev.sentenceFixes.size} raw=${ev.rawJson.length}")
                }
                "score_speaking" -> {
                    val q = "Describe a skill you learned that was difficult at first. You should say what it was, " +
                        "how you learned it, why it was difficult, and explain how you feel about it now."
                    val transcript = """
                        I would like to talk about learning to swim, which was really difficult for me at the beginning. I learned it when I was 22 years old, during a summer holiday at the coast. My friend Tom taught me the basics in a shallow pool before we moved to the sea. At first I was terrified of putting my face under the water, and I could not float at all. The main reason it was difficult is that I had a bad experience as a child, so I panicked easily. I practiced for about half an hour every morning for two weeks, and I watched short videos to copy the arm movements. Slowly I became confident, and by the end of the holiday I could swim 50 meters without stopping. I feel proud of it now, because it taught me that patience and small daily steps can overcome fear.
                    """.trimIndent()
                    val ev = com.txw.ielts.llm.LocalLlmEngine.scoreSpeaking(
                        context, model, "Part 2", q, transcript, 95,
                    )
                    Log.i(TAG, "E2E: RESULT ai score_speaking overall=${ev.overall} fc=${ev.fluency} " +
                        "lr=${ev.lexical} gra=${ev.grammar} p=${ev.pronunciation} fixes=${ev.fixes.size} " +
                        "sentences=${ev.sentenceFixes.size} raw=${ev.rawJson.length}")
                }
                "gen_reading" -> {
                    val r = com.txw.ielts.llm.LocalLlmEngine.generateReading(
                        context, model, "7.0", listOf("choice", "tfng"), 4,
                    )
                    val words = r.passage.split(Regex("\\s+")).count { it.isNotBlank() }
                    Log.i(TAG, "E2E: RESULT ai gen_reading questions=${r.questions.size} " +
                        "passage_words=$words title_chars=${r.title.length}")
                }
                "gen_listening" -> {
                    val r = com.txw.ielts.llm.LocalLlmEngine.generateListening(
                        context, model, "6.5", listOf("choice", "gap"), 4,
                    )
                    val words = r.transcript.split(Regex("\\s+")).count { it.isNotBlank() }
                    Log.i(TAG, "E2E: RESULT ai gen_listening questions=${r.questions.size} " +
                        "transcript_words=$words title_chars=${r.title.length}")
                }
                else -> Log.w(TAG, "E2E: RESULT ai unknown=$mode")
            }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 页面/组合离开时的正常取消，不是生成失败；吞掉会误报一条 FAIL。
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "E2E: RESULT ai mode=$mode fail=${e.javaClass.simpleName}:${e.message}")
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
                is Route.FirstRun -> FirstRunScreen(
                    plan = firstRunPlan,
                    // 「去设置」不算看完引导：下次启动还能补，配置项本身不因引导而丢。
                    onOpenSettings = { route = Route.Settings },
                    onStartPractice = {
                        prefs.firstRunDone = true
                        bankFilter = "all"
                        route = Route.Bank
                    },
                    onDone = { prefs.firstRunDone = true; route = Route.Home },
                    onBack = { prefs.firstRunDone = true; route = Route.Home },
                )
                is Route.Home -> {
                    // 「已改用本地模型」只有在模型确实可用时才成立：
                    // 没投递时提示要说清「现在评分会失败、先推模型」，而不是让用户白等一轮。
                    val localModelReady = remember(route, networkOnline, prefs.localModel) {
                        localAiAvailable()
                    }
                    HomeScreen(
                        sessions = sessions,
                        onStartWriting = { route = Route.Writing; error = null },
                        onStartSpeaking = { route = Route.Speaking; error = null },
                        onReview = { route = Route.Review },
                        onCorpus = { route = Route.Corpus },
                        onBank = { bankFilter = "all"; route = Route.Bank },
                        onAiGen = { route = Route.AiGen; aiGenError = null },
                        onShadow = { route = Route.Shadow },
                        // 跟读记录不是写作/口语：直接进跟读结果页，别落到通用结果页显示 Band 0.0。
                        onOpen = { id ->
                            val s = sessions.firstOrNull { it.id == id }
                            route = if (s?.module == "shadow") Route.ShadowResult(id) else Route.Result(id)
                        },
                        onSettings = { route = Route.Settings },
                        onModelEssays = { route = Route.ModelEssays },
                        onOpenPlan = { route = Route.Plan },
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
                        queueNotice = queueNotice,
                        onDismissQueueNotice = { queueNotice = null },
                        channelNotice = ChannelPick.notice(
                            mode = prefs.scoreChannel,
                            cloudDown = System.currentTimeMillis() < prefs.cloudDownUntil,
                            hasNetwork = networkOnline,
                            remainingCalls = prefs.remainingCalls(),
                            localAvailable = localModelReady,
                        ),
                    )
                }

                is Route.Plan -> StudyPlanScreen(
                    plan = studyPlan,
                    // 「换一科」在当天现有的科目上往后轮一科：连续点几次就能走完四科，
                    // 比弹一层科目选择器轻，且不会误改到别的日子。
                    onSwapDay = { date ->
                        val day = studyPlan.days.firstOrNull { it.date == date }
                        if (day != null && day.editable) {
                            val next = com.txw.ielts.ui.PlanOverrides.cycle(day.module)
                            val original = baseStudyPlan.days.firstOrNull { it.date == date }?.module
                            savePlanOverrides(
                                if (next == original) planOverrides - date
                                else planOverrides + (date to next)
                            )
                        }
                    },
                    onResetDay = { date -> savePlanOverrides(planOverrides - date) },
                    onResetAll = { savePlanOverrides(emptyMap()) },
                    onOpenModule = { module ->
                        when (module) {
                            "writing" -> route = Route.Writing
                            "speaking" -> route = Route.Speaking
                            "mock" -> {
                                // 整套模考就是听读连做：进题库不预筛模块
                                bankFilter = "all"
                                route = Route.Bank
                            }
                            "listening", "reading" -> {
                                bankFilter = module
                                route = Route.Bank
                            }
                            // 考前一天只看错题：进语料页，错题专项入口在那里
                            "light" -> route = Route.Corpus
                            // 休息/考试日不跳转，留在课表页看说明
                            else -> Unit
                        }
                    },
                    onOpenSettings = { route = Route.Settings },
                    onBack = { route = Route.Home },
                )

                is Route.Writing -> WritingScreen(
                    stage = stage, error = error,
                    onSubmit = { task, topic, audio, typed, secs ->
                        practiceFocus = null
                        writingPresetTopic = null
                        writingPresetTask = null
                        writingPresetChart = null
                        scope.launch { runWriting(task, topic, audio, typed, secs) }
                    },
                    onBack = {
                        practiceFocus = null
                        writingPresetTopic = null
                        writingPresetTask = null
                        writingPresetChart = null
                        route = Route.Home
                    },
                    focusFixes = practiceFocus?.takeIf { it.module == "writing" }?.fixes ?: emptyList(),
                    presetTopic = writingPresetTopic
                        ?: practiceFocus?.takeIf { it.module == "writing" }?.topic,
                    presetTask = writingPresetTask
                        ?: practiceFocus?.takeIf { it.module == "writing" }?.task ?: "task2",
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
                        speakingPresetPart = null
        speakingPresetPart = null
                        scope.launch { runSpeaking(part, q, audio, secs) }
                    },
                    onBack = {
                        practiceFocus = null
                        speakingPresetQuestion = null
                        speakingPresetPart = null
        speakingPresetPart = null
                        route = Route.Home
                    },
                    focusFixes = practiceFocus?.takeIf { it.module == "speaking" }?.fixes ?: emptyList(),
                    presetPart = speakingPresetPart
                        ?: if (speakingPresetQuestion != null) "Part 2"
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
                            aiExplains = aiExplains, explainingIds = explainingIds, explainErrors = explainErrors,
                            onAiExplain = { item -> scope.launch { runAiExplain(item, "s${session.id}") } },
                            variants = bankVariants, variantBusyIds = variantBusyIds, variantErrors = variantErrors,
                            variantScope = "s${session.id}",
                            onRequestVariant = { item -> scope.launch { runVariant(item, "s${session.id}") } },
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
                        // 批注忽略状态按会话存盘：结果页来回切换、App 重启后都还认得哪些被忽略。
                        var mutedFixes by remember(session.id) {
                            mutableStateOf(com.txw.ielts.llm.FixTriage.sessionKeys(prefs.mutedFixes, session.id))
                        }
                        val toggleMute: (String) -> Unit = { key ->
                            mutedFixes = com.txw.ielts.llm.FixTriage.toggle(mutedFixes, key)
                            prefs.editJson {
                                prefs.mutedFixes = com.txw.ielts.llm.FixTriage.withSession(
                                    prefs.mutedFixes, session.id, mutedFixes,
                                )
                            }
                        }
                        // 生词卡手动修改同样按会话存盘：改完再入 SRS，重新打开结果页仍是改过的内容。
                        var vocabEdits by remember(session.id) {
                            mutableStateOf(com.txw.ielts.llm.VocabEdits.load(prefs.vocabEdits, session.id))
                        }
                        val applyVocabEdits: (Map<String, com.txw.ielts.llm.VocabDraft>) -> Unit = { next ->
                            vocabEdits = next
                            prefs.editJson {
                                prefs.vocabEdits =
                                    com.txw.ielts.llm.VocabEdits.save(prefs.vocabEdits, session.id, next)
                            }
                        }
                        ResultScreen(
                            session = session,
                            busy = stage.takeIf { activeSessionId == session.id },
                            error = error.takeIf { activeSessionId == session.id },
                            // 失败原因按会话存盘：通知被划掉/App 重启后，结果页仍能说清上次为什么没出分。
                            lastFailure = com.txw.ielts.llm.ScoreFailures.reasonFor(
                                prefs.localScoreErrors, session.id, System.currentTimeMillis(),
                            ),
                            // 开始时间缺失时不回退到 session.startedAt（录音起点），
                            // 结果页会从进入时开始计时，避免显示「已等待」几十分钟。
                            staleLocal = session.status == "pending_local" &&
                                localScoreStartedAt(session.id) > 0L &&
                                System.currentTimeMillis() - localScoreStartedAt(session.id) > 10 * 60_000L,
                            waitStartedAt = localScoreStartedAt(session.id),
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
                                        recordScoreFailure(session.id, e.message)
                                    }
                                }
                            },
                            onAddToCorpus = { fixes, sentences ->
                                // 先清掉上一次的账目：新一轮点击期间不显示旧结果。
                                corpusAdd = null
                                scope.launch {
                                    // 批注与逐句改法分开入库（后者要等前者的卡先落库才能跨类型判重），
                                    // 两笔账合并后再告诉结果页，别让界面只看到其中一半。
                                    val result = repo.addFixesAsCards(fixes, session.module) +
                                        repo.addSentenceFixesAsCards(sentences, session.module)
                                    corpusAdd = session.id to result
                                    refreshCorpus()
                                }
                            },
                            corpusAdd = corpusAdd?.takeIf { it.first == session.id }?.second,
                            onPracticeAgain = {
                                val rawFixes = runCatching {
                                    val o = JSONObject(session.fixesJson)
                                    o.optJSONArray("fixes")?.let { a ->
                                        (0 until a.length()).map { a.get(it).toString() }
                                    } ?: emptyList()
                                }.getOrDefault(emptyList())
                                // 重练只带用户认可的批注：被忽略的条目不进下一轮 focus。
                                val fixes = com.txw.ielts.llm.FixTriage.filterFixes(rawFixes, mutedFixes)
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
                            newTopicBusy = newTopicBusy,
                            newTopicHere = newTopicSessionId == session.id,
                            newTopicError = newTopicError.takeIf { newTopicSessionId == session.id },
                            onRequestNewTopic = {
                                scope.launch { requestNewWritingTopic(session, session.resolvedTask()) }
                            },
                            vocabResult = vocabResult,
                            vocabSessionId = vocabSessionId,
                            vocabBusy = vocabBusy,
                            vocabHere = vocabSessionId == session.id,
                            vocabError = vocabError,
                            onRequestVocab = { text, fixes ->
                                scope.launch { requestVocab(session.id, text, fixes) }
                            },
                            followUpQuestion = followUpQuestion.takeIf { followUpSessionId == session.id },
                            followUpBusy = followUpBusy,
                            followUpHere = followUpSessionId == session.id,
                            followUpError = followUpError.takeIf { followUpSessionId == session.id },
                            onRequestFollowUp = {
                                scope.launch {
                                    requestFollowUp(
                                        session.id,
                                        session.topic.substringAfter(" · ", session.topic),
                                        session.transcript,
                                    )
                                }
                            },
                            onPracticeFollowUp = { q ->
                                // 追问是 Part 3 问题：带上它进入口语练习，答完会走同一套评分链路。
                                practiceFocus = null
                                speakingPresetQuestion = q
                                speakingPresetPart = "Part 3"
                                route = Route.Speaking
                            },
                            onAddVocab = { items ->
                                // 入库前套用用户的修改：模型释义/例句未必合身，改过就以改的为准。
                                val resolved = com.txw.ielts.llm.VocabEdits.resolve(items, vocabEdits)
                                scope.launch { repo.addVocabCards(resolved) }
                            },
                            mutedFixes = mutedFixes,
                            onToggleMute = toggleMute,
                            vocabEdits = vocabEdits,
                            onEditVocab = { item, draft ->
                                applyVocabEdits(com.txw.ielts.llm.VocabEdits.edit(vocabEdits, item, draft))
                            },
                            onRevertVocab = { item ->
                                applyVocabEdits(com.txw.ielts.llm.VocabEdits.revert(vocabEdits, item))
                            },
                            onRevertAllVocab = { applyVocabEdits(emptyMap()) },
                            onBack = { route = Route.Home },
                        )
                    }
                }

                is Route.Settings -> SettingsScreen(
                    prefs,
                    onBack = { route = Route.Home },
                    onReplayGuide = { route = Route.FirstRun },
                )

                is Route.Corpus -> CorpusHomeScreen(
                    due = due, fresh = fresh, total = totalCards, mastered = mastered,
                    dailyLimit = prefs.dailyNewCards,
                    generated = generated, generatedCap = generatedCap,
                    typeCounts = typeCounts,
                    wrongCount = wrongCount,
                    reviewedToday = reviewedToday,
                    feedbackPending = feedbackPending,
                    wrongTrend = wrongTrend,
                    roundSummary = lastRoundSummary,
                    onDismissRound = { lastRoundSummary = null },
                    error = error,
                    queueNotice = queueNotice,
                    onDismissQueueNotice = { queueNotice = null },
                    // 与首页「今日任务」共用同一套队列构建与空队列提示，避免两处口径/文案分叉。
                    onStart = { startDueQueue() },
                    onWrongStart = { startWrongQueue() },
                    onBack = { route = Route.Home },
                )

                is Route.Study -> {
                    val card = round.card
                    if (card == null) {
                        LaunchedEffect(round) { route = Route.Corpus }
                    } else {
                        StudyScreen(
                            card = card,
                            index = round.index,
                            total = round.total,
                            revealed = round.revealed,
                            // 只有「这张卡正在写库」时才置灰；换卡或 Again 重问会立刻恢复。
                            busy = !round.canGrade,
                            error = error,
                            ttsAvailable = ttsAvailable,
                            onReveal = { round = round.reveal() },
                            onSpeak = { speak(card.example.ifBlank { card.front }) },
                            onGrade = { grade ->
                                // 先上锁再写库：连点只会记一次分；写库失败就解锁，让用户重评。
                                round = round.beginGrade()
                                error = null
                                scope.launch {
                                    val target = round.card ?: return@launch
                                    val saved = runCatching { repo.grade(target, grade) }
                                    if (saved.isFailure) {
                                        round = round.unlock()
                                        error = "这张卡没存上，请再评一次"
                                        return@launch
                                    }
                                    // Again 不丢到明天：留在本轮隔几张再问，答对才前进。
                                    round = round.applyGrade(grade)
                                    if (round.finished) {
                                        // 收尾小结先取出来再清状态：否则语料页只会看到「今天没有待学卡片」。
                                        lastRoundSummary = round.summary()
                                        round = StudyRound.EMPTY
                                        refreshCorpus()
                                        route = Route.Corpus
                                    }
                                }
                            },
                            onDelete = {
                                scope.launch {
                                    repo.delete(card)
                                    round = round.discard()
                                    if (round.finished) {
                                        lastRoundSummary = round.summary()
                                        round = StudyRound.EMPTY
                                        refreshCorpus()
                                        route = Route.Corpus
                                    }
                                }
                            },
                            onExit = {
                                // 中途退出也留一份小结：用户已经评过的卡不该被无声丢掉。
                                round.summary().takeIf { !it.empty }?.let { lastRoundSummary = it }
                                round = StudyRound.EMPTY
                                route = Route.Corpus
                            },
                        )
                    }
                }

                is Route.Review -> ReviewScreen(
                    sessions = sessions,
                    onOpen = { route = Route.Result(it) },
                    onBack = { route = Route.Home },
                    aiReview = aiReview,
                    aiReviewHint = aiReviewHint,
                    aiBusy = aiReviewBusy,
                    aiError = aiReviewError,
                    onRequestReview = { scope.launch { requestAiReview() } },
                    onOpenAction = { action ->
                        route = when (action) {
                            "writing" -> Route.Writing
                            "speaking" -> Route.Speaking
                            "vocab" -> Route.Corpus
                            "listening", "reading" -> Route.Bank
                            else -> Route.Home
                        }
                    },
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
                    if (session == null && sessionsOrNull != null) {
                        // 数据已加载完仍找不到 → 记录确实不存在。
                        LaunchedEffect(r.sessionId) { route = Route.Shadow }
                    } else if (session == null) {
                        // 进程恢复时 Room 首帧还没到：别把「加载中」当成「记录不存在」弹回去。
                        Text("载入中…", style = MaterialTheme.typography.bodyMedium)
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
                            audioPath = session.audioPath,
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
                                                    if (bankAnswers.isNotEmpty()) bankRepo.saveAnswers(set.id, bankAnswers)
                                                },
                                                onSubmit = { scope.launch { submitQuiz(set.id) } },
                                                error = quizError,
                                                onBack = {
                                                    if (bankAnswers.isNotEmpty()) bankRepo.saveAnswers(set.id, bankAnswers)
                                                    route = if (isAi) Route.AiGen else Route.Bank
                                                },
                                            )
                                        }
                                    }
                                    is Route.BankResult -> {
                                        // 旋转/进程恢复后 Route 还在，但载荷（bankResult）只在内存：
                                        // 先按 sessionId 从 fixesJson 重建，不能把「载荷丢失」当成「记录不存在」。
                                        val live = bankResult?.takeIf { it.setId == r.setId }
                                        var restored by remember(r.sessionId, r.setId) {
                                            mutableStateOf<BankResultData?>(null)
                                        }
                                        LaunchedEffect(r.sessionId, r.setId, live) {
                                            if (live == null && sessionsOrNull != null) {
                                                restored = db.sessions().byId(r.sessionId)
                                                    ?.let { bankResultFromSession(it, r.setId) }
                                            }
                                        }
                                        val data = live ?: restored
                                        if (data == null && sessionsOrNull != null) {
                                            LaunchedEffect(r.sessionId) { route = Route.Bank }
                                        } else if (data == null) {
                                            Text("载入中…", style = MaterialTheme.typography.bodyMedium)
                                        } else {
                                            BankResultScreen(
                                                title = data.title, module = data.module,
                                                correct = data.correct, total = data.total,
                                                items = data.items, passage = data.passage,
                                                transcript = data.transcript,
                                                readerFontSize = prefs.readerFontSize, readerLineHeight = prefs.readerLineHeight,
                                                aiExplains = aiExplains, explainingIds = explainingIds, explainErrors = explainErrors,
                                                onAiExplain = { item -> scope.launch { runAiExplain(item, data.setId) } },
                                                variants = bankVariants, variantBusyIds = variantBusyIds, variantErrors = variantErrors,
                                                variantScope = data.setId,
                                                onRequestVariant = { item -> scope.launch { runVariant(item, data.setId) } },
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
                                            if (bankAnswers.isNotEmpty()) bankRepo.saveAnswers(set.id, bankAnswers)
                                        },
                                        onSubmit = { scope.launch { submitQuiz(set.id) } },
                                        error = quizError,
                                        onBack = {
                                            if (bankAnswers.isNotEmpty()) bankRepo.saveAnswers(set.id, bankAnswers)
                                            route = if (isAi) Route.AiGen else Route.Bank
                                        },
                                    )
                                }
                            }
                            is Route.BankResult -> {
                                // 与宽屏同一套恢复逻辑：旋转/进程重建后 Route 还在、载荷只在内存，
                                // 窄屏不能把「载荷丢失」当成「记录不存在」直接踢回题库。
                                val live = bankResult?.takeIf { it.setId == r.setId }
                                var restored by remember(r.sessionId, r.setId) {
                                    mutableStateOf<BankResultData?>(null)
                                }
                                LaunchedEffect(r.sessionId, r.setId, live) {
                                    if (live == null && sessionsOrNull != null) {
                                        restored = db.sessions().byId(r.sessionId)
                                            ?.let { bankResultFromSession(it, r.setId) }
                                    }
                                }
                                val data = live ?: restored
                                if (data == null && sessionsOrNull != null) {
                                    LaunchedEffect(r.sessionId) { route = Route.Bank }
                                } else if (data == null) {
                                    Text("载入中…", style = MaterialTheme.typography.bodyMedium)
                                } else {
                                    BankResultScreen(
                                        title = data.title, module = data.module,
                                        correct = data.correct, total = data.total,
                                        items = data.items, passage = data.passage,
                                        transcript = data.transcript,
                                        readerFontSize = prefs.readerFontSize, readerLineHeight = prefs.readerLineHeight,
                                        aiExplains = aiExplains, explainingIds = explainingIds, explainErrors = explainErrors,
                                        onAiExplain = { item -> scope.launch { runAiExplain(item, data.setId) } },
                                        variants = bankVariants, variantBusyIds = variantBusyIds,
                                        variantErrors = variantErrors,
                                        variantScope = data.setId,
                                        onRequestVariant = { item -> scope.launch { runVariant(item, data.setId) } },
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
