package com.txw.ielts.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.txw.ielts.data.LocalWait
import com.txw.ielts.data.ModelEssay
import com.txw.ielts.data.ModelEssayStore
import com.txw.ielts.data.Session
import org.json.JSONObject

@Composable
fun ResultScreen(
    session: Session,
    busy: String?,
    error: String? = null,
    staleLocal: Boolean = false,
    waitStartedAt: Long = 0L,
    scoreTookMs: Long = 0L,
    sameTopicHistory: List<Session> = emptyList(),
    onRetry: () -> Unit,
    onAddToCorpus: (List<String>, List<Triple<String, String, String>>) -> Unit,
    onPracticeAgain: () -> Unit,
    onBack: () -> Unit,
    // 本地 AI 生词卡：从本次正文 + 逐句改法里提搭配，勾选后进 SRS。
    vocabResult: com.txw.ielts.llm.VocabResult? = null,
    vocabSessionId: Long? = null,
    vocabBusy: Boolean = false,
    vocabError: String? = null,
    onRequestVocab: (text: String, fixes: List<Triple<String, String, String>>) -> Unit = { _, _ -> },
    onAddVocab: (List<com.txw.ielts.llm.VocabItem>) -> Unit = {},
    // AI 批注逐条「忽略 / 恢复」：只影响本会话的背诵与生词输入，按会话存盘由调用方负责。
    mutedFixes: Set<String> = emptySet(),
    onToggleMute: (String) -> Unit = {},
    // AI 生词卡手动编辑：模型给错释义/例句时入库前改掉，可逐条或全部恢复原文。
    vocabEdits: Map<String, com.txw.ielts.llm.VocabDraft> = emptyMap(),
    onEditVocab: (com.txw.ielts.llm.VocabItem, com.txw.ielts.llm.VocabDraft) -> Unit = { _, _ -> },
    onRevertVocab: (com.txw.ielts.llm.VocabItem) -> Unit = {},
    onRevertAllVocab: () -> Unit = {},
) {
    val speaking = session.module == "speaking"
    val context = LocalContext.current
    var addedToCorpus by remember { mutableStateOf(false) }
    // 生词卡勾选：默认全选，用户可去掉已经会的；换了结果就重置。
    var vocabChecked by remember(session.id, vocabResult) {
        mutableStateOf(vocabResult?.items?.indices?.toSet() ?: emptySet())
    }
    var vocabAdded by remember(session.id) { mutableStateOf(false) }
    // 展示真实已等待时间：本地评分 1-4 分钟，用户需要知道还要等多久。
    var waitedSec by remember { mutableLongStateOf(0L) }
    // 开始时间缺失（旧数据 / 进程重启 / 调用方没写）时从进入结果页开始数，
    // 不能回退到 session.startedAt —— 那是录音起点，会显示「已等待」几十分钟。
    var fallbackBase by remember(session.id) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(session.status, waitStartedAt) {
        if (session.status != "pending_local") return@LaunchedEffect
        val base = waitStartedAt.takeIf { it > 0 } ?: fallbackBase
        while (true) {
            waitedSec = ((System.currentTimeMillis() - base) / 1000).coerceAtLeast(0)
            kotlinx.coroutines.delay(1_000)
        }
    }
    val scores = runCatching { JSONObject(session.scoresJson) }.getOrNull()
    // 历史数据里有模型早期返回的 ["", "，应改为"] 这类残缺条目（sanitizer 已修，旧数据仍在）：
    // 展示层按「非空白 + 不以标点开头 + 至少 2 个有效字符」过滤，避免结果页出现「1. ，」。
    fun meaningfulFix(s: String): Boolean {
        val t = s.trim()
        // 正常条目以文字/数字开头；坏数据形如「，」「，应改为」，被首字符规则挡掉。
        return t.length >= 2 && t.first().isLetterOrDigit() && t.count { it.isLetterOrDigit() } >= 2
    }
    val allFixes = runCatching {
        val o = JSONObject(session.fixesJson)
        o.optJSONArray("fixes")?.let { a ->
            (0 until a.length()).map { a.get(it).toString() }.filter(::meaningfulFix)
        } ?: emptyList()
    }.getOrDefault(emptyList())
    val allSentenceFixes = runCatching {
        val o = JSONObject(session.fixesJson)
        o.optJSONArray("sentence_fixes")?.let { a ->
            (0 until a.length()).mapNotNull { i ->
                runCatching {
                    val x = a.getJSONObject(i)
                    Triple(x.optString("sentence"), x.optString("issue"), x.optString("better"))
                }.getOrNull()
            }.filter {
                // 旧会话里可能存着「原句 → 原句」的无效改法（只差大小写/标点），展示层也挡掉。
                it.first.isNotBlank() && it.third.isNotBlank() &&
                    com.txw.ielts.data.CardText.key(it.first) != com.txw.ielts.data.CardText.key(it.third)
            }
        } ?: emptyList()
    }.getOrDefault(emptyList())
    // 用户忽略的批注只从「可采纳」列表里移除，单独列在下面并可一键恢复：
    // 端侧模型偶尔把对的句子改错，直接塞进 SRS 会让复习卡整个污染掉。
    val fixes = com.txw.ielts.llm.FixTriage.filterFixes(allFixes, mutedFixes)
    val sentenceFixes = com.txw.ielts.llm.FixTriage.filterSentenceFixes(allSentenceFixes, mutedFixes)
    val mutedFixItems = allFixes.filter { com.txw.ielts.llm.FixTriage.fixKey(it) in mutedFixes }
    val mutedSentenceItems = allSentenceFixes.filter {
        com.txw.ielts.llm.FixTriage.sentenceKey(it.first) in mutedFixes
    }
    // 忽略/恢复后允许再次「加入背诵」：库里按 CardText.key 判重，重复点击不会重复建卡。
    LaunchedEffect(mutedFixes) { addedToCorpus = false }
    // 改了生词卡内容也允许重新入库：搭配改过之后是一张新卡，按钮不该一直停在「已加入」。
    LaunchedEffect(vocabEdits) { vocabAdded = false }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("返回") }
            Text("练习结果", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Text("${session.durationSec / 60} 分 ${session.durationSec % 60} 秒",
                style = MaterialTheme.typography.bodyMedium)
        }

        Text(session.topic, style = MaterialTheme.typography.bodyMedium)

        if (session.status == "done" && scoreTookMs > 0) {
            Text(
                "本地评分耗时约 ${(scoreTookMs / 60_000L).coerceAtLeast(1)} 分钟",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (session.status == "pending_local") {
            BookCard {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        androidx.compose.material3.CircularProgressIndicator(
                            Modifier.size(20.dp)
                        )
                        Text(
                            if (waitedSec > 0) "本地评分中… 已等待 ${waitedSec / 60} 分 ${waitedSec % 60} 秒"
                            else "本地评分中…",
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                    Text(
                        "端侧 Qwen3.5-4B 正在离线评分，约 1-3 分钟。完成后会通知，可先返回继续练习，结果页会自动刷新。" +
                            "评分期间请勿同时使用语音助手等其他本地大模型 App（内存竞争会导致变慢或中断）。" +
                            "若超过 10 分钟仍无结果，可在这里重新评分。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!error.isNullOrBlank()) {
                        Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    // staleLocal 是父级组合期算的快照，Room Flow 不变它就永远不变；
                    // waitedSec 每秒刷新，才是判定的真实驱动源（否则过了 10 分钟出口仍不激活）。
                    val staleNow = LocalWait.isStale(waitedSec, staleLocal)
                    TextButton(onClick = onRetry, enabled = staleNow && busy == null) {
                        Text(LocalWait.retryLabel(busy, staleNow))
                    }
                }
            }
        } else if (session.status != "done") {
            Text(
                error ?: when (session.status) {
                    "pending_eval" -> "这篇还没评分成功（云端失败或评分被中断），可以点下面重新评分。"
                    "asr_empty" -> "没有识别到有效内容（录音太短或环境太吵），请重录。"
                    else -> "这篇还没评分成功（${session.status}）"
                },
                color = MaterialTheme.colorScheme.error,
            )
            Button(onClick = onRetry, enabled = busy == null) {
                Text(busy ?: "重新评分")
            }
        } else if (scores != null) {
            val speakingDims = listOf("FC" to "流利", "LR" to "词汇", "GRA" to "语法", "P" to "发音*")
            val writingDims = listOf(
                "TR" to "任务", "TA" to "任务",
                "CC" to "衔接", "LR" to "词汇", "GRA" to "语法",
            )
            val dims: List<Pair<String, String>> = if (speaking) {
                speakingDims.map { (k, l) -> k to l }
            } else {
                val isTask1 = scores.has("TA")
                writingDims.filter { (k, _) -> if (isTask1) k != "TR" else k != "TA" }
                    .map { (k, l) -> k to l }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Band " + "%.1f".format(scores.optDouble("overall", 0.0)),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    if (speaking) "口语评分" else if (scores.has("TA")) "Task 1 图表" else "Task 2 议论文",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                dims.forEach { (key, label) ->
                    ScoreBar(label, scores.optDouble(key, 0.0), MaterialTheme.colorScheme.primary)
                }
            }
            if (speaking) {
                val metrics = runCatching { JSONObject(session.fixesJson).optJSONObject("metrics") }.getOrNull()
                if (metrics != null) {
                    Text(
                        "客观指标：语速 ${metrics.optInt("wpm")} 词/分 · 填充词 ${metrics.optInt("fillers")} 次 · 共 ${metrics.optInt("words")} 词",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    if (metrics?.optBoolean("p_estimated", false) == true) {
                        "* 发音为估计值：本次模型未单独给出 P，已按流利/词汇/语法均值估算"
                    } else {
                        "* 发音为估计值：只看转写文本与语速/填充词，不是声学评分"
                    },
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        if (sameTopicHistory.isNotEmpty()) {
            BookCard {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("同一题的历史", style = MaterialTheme.typography.titleSmall)
                    sameTopicHistory.takeLast(4).forEach { s ->
                        val b = runCatching { JSONObject(s.scoresJson).optDouble("overall", 0.0) }.getOrDefault(0.0)
                        val day = java.time.Instant.ofEpochMilli(s.startedAt)
                            .atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString().takeLast(5)
                        Text("$day · Band %.1f".format(b), style = MaterialTheme.typography.bodySmall)
                    }
                    // 上次这题的改进点：重写后照着对照，才知道有没有改掉。
                    val prevFixes = runCatching {
                        val o = JSONObject(sameTopicHistory.last().fixesJson)
                        o.optJSONArray("fixes")?.let { a ->
                            (0 until a.length()).map { a.get(it).toString() }.filter(::meaningfulFix)
                        } ?: emptyList()
                    }.getOrDefault(emptyList())
                    if (prevFixes.isNotEmpty()) {
                        Text(
                            "上次的改进点（这次对照）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        prevFixes.take(3).forEach { Text("· $it", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }

        if (allFixes.isNotEmpty()) {
            BookCard {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "最该改的三点",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        if (mutedFixItems.isNotEmpty()) {
                            TextButton(onClick = {
                                mutedFixItems.forEach { onToggleMute(com.txw.ielts.llm.FixTriage.fixKey(it)) }
                            }) { Text("全部恢复", style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                    if (fixes.isEmpty()) {
                        Text(
                            "这组批注已全部忽略，不会进入背诵与生词。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    fixes.forEachIndexed { i, f ->
                        Row(verticalAlignment = Alignment.Top) {
                            Text(
                                "${i + 1}. $f",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = {
                                onToggleMute(com.txw.ielts.llm.FixTriage.fixKey(f))
                            }) { Text("忽略", style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                    // 忽略不是删除：条目照常列出来，点「恢复」立刻回到上面，避免误点后找不回来。
                    mutedFixItems.forEach { f ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "已忽略：$f",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = {
                                onToggleMute(com.txw.ielts.llm.FixTriage.fixKey(f))
                            }) { Text("恢复", style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                    TextButton(
                        enabled = !addedToCorpus && (fixes.isNotEmpty() || sentenceFixes.isNotEmpty()),
                        onClick = { onAddToCorpus(fixes, sentenceFixes); addedToCorpus = true },
                    ) { Text(if (addedToCorpus) "已加入背诵" else "把这几点加入背诵") }
                    Button(
                        onClick = onPracticeAgain,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (speaking) "带着这三点重录一段" else "带着这三点重写一篇") }
                }
            }
        } else if (session.status == "done") {
            // 评分没有 fixes 时也给一个重练入口，别让「反馈 → 重练」的闭环断在这里。
            Button(onClick = onPracticeAgain, modifier = Modifier.fillMaxWidth()) {
                Text(if (speaking) "再练一次同一题" else "重写同一题目")
            }
        }

        if (allSentenceFixes.isNotEmpty()) {
            BookCard {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (speaking) "逐句打磨（说错的句子逐条改）" else "逐句打磨（写错的句子逐条改）",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        if (mutedSentenceItems.isNotEmpty()) {
                            TextButton(onClick = {
                                mutedSentenceItems.forEach {
                                    onToggleMute(com.txw.ielts.llm.FixTriage.sentenceKey(it.first))
                                }
                            }) { Text("全部恢复", style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                    if (sentenceFixes.isEmpty()) {
                        Text(
                            "逐句改法已全部忽略，不会进入改错卡与生词。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    sentenceFixes.forEachIndexed { i, (sentence, issue, better) ->
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Row(verticalAlignment = Alignment.Top) {
                                Text(
                                    "${i + 1}. 原句：$sentence",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = {
                                    onToggleMute(com.txw.ielts.llm.FixTriage.sentenceKey(sentence))
                                }) { Text("忽略", style = MaterialTheme.typography.labelSmall) }
                            }
                            Text(
                                "问题：$issue",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Text("更好：$better", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    mutedSentenceItems.forEach { (sentence, _, _) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "已忽略：$sentence",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = {
                                onToggleMute(com.txw.ielts.llm.FixTriage.sentenceKey(sentence))
                            }) { Text("恢复", style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                }
            }
        }

        if (session.status == "done" && (session.essayText.isNotBlank() || session.transcript.isNotBlank())) {
            BookCard {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("AI 生词卡（本地模型）", style = MaterialTheme.typography.titleSmall)
                    // 状态按 session 隔离：切到另一条结果时，不要把上一条的「提取中/失败」带过来。
                    val vocabHere = vocabSessionId == session.id
                    val busyHere = vocabBusy && vocabHere
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            enabled = !vocabBusy,
                            onClick = {
                                onRequestVocab(
                                    session.essayText.ifBlank { session.transcript },
                                    sentenceFixes,
                                )
                            },
                        ) { Text(if (busyHere) "提取中…" else "提取生词 / 搭配") }
                    }
                    if (busyHere) {
                        LocalAiBusy("本地模型提取生词中（请留在 App 内）")
                    }
                    if (vocabHere) {
                        vocabError?.let {
                            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    val result = vocabResult?.takeIf { vocabSessionId == session.id }
                    // 输入超预算时直说省了多少：生词偏少时用户能分清是「原文被裁」还是「模型不行」。
                    if (result != null && result.trimmedChars > 0) {
                        Text(
                            "原文较长，已省略中间 ${result.trimmedChars} 字（保留开头与结尾）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (result != null) {
                        if (result.items.isEmpty()) {
                            Text("模型没有返回可用的生词。原文：", style = MaterialTheme.typography.bodySmall)
                            Text(
                                result.raw.take(800),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "已选 ${vocabChecked.size} / ${result.items.size}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = {
                                    vocabChecked = if (vocabChecked.size == result.items.size) {
                                        emptySet()
                                    } else {
                                        result.items.indices.toSet()
                                    }
                                }) {
                                    Text(if (vocabChecked.size == result.items.size) "取消全选" else "全选")
                                }
                            }
                            val editedCount = com.txw.ielts.llm.VocabEdits.editedCount(result.items, vocabEdits)
                            if (editedCount > 0) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "已手动修改 $editedCount 条",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.weight(1f),
                                    )
                                    TextButton(onClick = onRevertAllVocab) {
                                        Text("全部恢复原文", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                            val duplicateAt = com.txw.ielts.llm.VocabEdits.duplicates(result.items, vocabEdits)
                            result.items.forEachIndexed { i, item ->
                                val editKey = com.txw.ielts.llm.VocabEdits.key(item)
                                val draft = com.txw.ielts.llm.VocabEdits.draftOf(item, vocabEdits)
                                var editing by remember(session.id, editKey) { mutableStateOf(false) }
                                Row(verticalAlignment = Alignment.Top) {
                                    Checkbox(
                                        checked = i in vocabChecked,
                                        onCheckedChange = { on ->
                                            vocabChecked = if (on) vocabChecked + i else vocabChecked - i
                                        },
                                    )
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        if (editing) {
                                            IosTextField(
                                                value = draft.phrase,
                                                onValueChange = { onEditVocab(item, draft.copy(phrase = it)) },
                                                modifier = Modifier.fillMaxWidth(),
                                                label = "搭配",
                                                singleLine = true,
                                            )
                                            IosTextField(
                                                value = draft.meaning,
                                                onValueChange = { onEditVocab(item, draft.copy(meaning = it)) },
                                                modifier = Modifier.fillMaxWidth(),
                                                label = "中文释义",
                                            )
                                            IosTextField(
                                                value = draft.example,
                                                onValueChange = { onEditVocab(item, draft.copy(example = it)) },
                                                modifier = Modifier.fillMaxWidth(),
                                                label = "例句",
                                            )
                                            Text(
                                                "搭配/释义留空会保留模型原文；例句留空表示不要例句。",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        } else {
                                            Text(draft.phrase, style = MaterialTheme.typography.titleSmall)
                                            Text(
                                                draft.meaning.ifBlank { "（无释义）" },
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                            if (draft.example.isNotBlank()) {
                                                Text(
                                                    draft.example,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                        duplicateAt[i]?.let { first ->
                                            Text(
                                                "与第 ${first + 1} 条重复，入库时只保留一条",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.error,
                                            )
                                        }
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            TextButton(onClick = { editing = !editing }) {
                                                Text(
                                                    if (editing) "完成" else "编辑",
                                                    style = MaterialTheme.typography.labelSmall,
                                                )
                                            }
                                            if (editKey in vocabEdits) {
                                                TextButton(onClick = {
                                                    onRevertVocab(item)
                                                    editing = false
                                                }) {
                                                    Text("恢复原文", style = MaterialTheme.typography.labelSmall)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            Button(
                                modifier = Modifier.fillMaxWidth(),
                                enabled = vocabChecked.isNotEmpty() && !vocabAdded,
                                onClick = {
                                    onAddVocab(result.items.filterIndexed { i, _ -> i in vocabChecked })
                                    vocabAdded = true
                                },
                            ) {
                                Text(if (vocabAdded) "已加入词汇积累" else "加入词汇积累（${vocabChecked.size}）")
                            }
                            TextButton(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    val text = result.items.joinToString("\n") {
                                        "${it.phrase} — ${it.meaning}"
                                    }
                                    (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                        as android.content.ClipboardManager)
                                        .setPrimaryClip(android.content.ClipData.newPlainText("IELTS vocab", text))
                                },
                            ) { Text("复制生词列表") }
                        }
                    }
                }
            }
        }

        if (!speaking && session.status == "done" && session.polished.isNotBlank()) {
            val appContext = LocalContext.current
            var savedEssay by remember {
                mutableStateOf(ModelEssayStore.contains(appContext, session.id))
            }
            TextButton(onClick = {
                ModelEssayStore.save(
                    appContext,
                    ModelEssay(
                        sessionId = session.id,
                        topic = session.topic,
                        polished = session.polished,
                        band = scores?.optDouble("overall", 0.0) ?: 0.0,
                    ),
                )
                savedEssay = true
            }) { Text(if (savedEssay) "已收藏范文" else "收藏这篇范文") }
        }

        // 一键复制改写结果：用户常把 Band 7 版本贴到笔记或聊天里对照。
        if (session.status == "done" && session.polished.isNotBlank()) {
            val clipboard = LocalContext.current
                .getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            TextButton(onClick = {
                clipboard.setPrimaryClip(
                    android.content.ClipData.newPlainText(
                        if (speaking) "IELTS speaking band 7" else "IELTS writing band 7",
                        session.polished,
                    )
                )
            }) { Text("复制改写（Band 7）") }
        }

        BoxWithConstraints {
            val wide = maxWidth > 640.dp
            if (wide) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Pane(if (speaking) "我的回答（转写）" else "我的初稿", session.essayText.ifBlank { session.transcript }, Modifier.weight(1f))
                    PaneDiff(
                        if (speaking) "参考回答（Band 7，高亮为改写处）" else "AI 润色（Band 7，高亮为改写处）",
                        session.essayText.ifBlank { session.transcript },
                        session.polished,
                        Modifier.weight(1f),
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Pane(if (speaking) "我的回答（转写）" else "我的初稿", session.essayText.ifBlank { session.transcript }, Modifier.fillMaxWidth())
                    PaneDiff(
                        if (speaking) "参考回答（Band 7，高亮为改写处）" else "AI 润色（Band 7，高亮为改写处）",
                        session.essayText.ifBlank { session.transcript },
                        session.polished,
                        Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun ScoreChip(label: String, value: Double, modifier: Modifier = Modifier, highlight: Boolean = false) {
    BookCard(modifier) {
        Column(Modifier.padding(vertical = 10.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.labelSmall)
            Text(
                if (value == 0.0) "暂无" else value.toString(),
                style = if (highlight) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@Composable
private fun Pane(title: String, body: String, modifier: Modifier = Modifier, serifBody: Boolean = false) {
    BookCard(modifier) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                body.ifBlank { "（空）" },
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = if (serifBody) FontFamily.Serif else FontFamily.Default,
                lineHeight = if (serifBody) 24.sp else 21.sp,
            )
            Text("${wordCount(body)} 词", style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** 改写面板：与原文做词级 diff，改动的词用主题色标出。 */
@Composable
private fun PaneDiff(title: String, original: String, polished: String, modifier: Modifier = Modifier, serifBody: Boolean = true) {
    BookCard(modifier) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                diffAnnotated(original, polished, MaterialTheme.colorScheme.primary),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = if (serifBody) FontFamily.Serif else FontFamily.Default,
                lineHeight = if (serifBody) 24.sp else 21.sp,
            )
            Text("${wordCount(polished)} 词 · 高亮为改写处", style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** 词级 LCS diff：只保留改写文本，改动的词用 accent 标出。 */
private fun diffAnnotated(original: String, polished: String, accent: Color): AnnotatedString {
    val a = original.split(Regex("\\s+")).filter { it.isNotBlank() }
    val b = polished.split(Regex("\\s+")).filter { it.isNotBlank() }
    if (a.isEmpty() || b.isEmpty()) return AnnotatedString(polished)
    val n = a.size
    val m = b.size
    val dp = Array(n + 1) { IntArray(m + 1) }
    for (i in n - 1 downTo 0) {
        for (j in m - 1 downTo 0) {
            dp[i][j] = if (a[i].equals(b[j], ignoreCase = true)) dp[i + 1][j + 1] + 1
            else maxOf(dp[i + 1][j], dp[i][j + 1])
        }
    }
    return buildAnnotatedString {
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                a[i].equals(b[j], ignoreCase = true) -> { append(b[j]); append(' '); i++; j++ }
                dp[i + 1][j] >= dp[i][j + 1] -> i++
                else -> {
                    withStyle(SpanStyle(color = accent, fontWeight = FontWeight.SemiBold)) {
                        append(b[j]); append(' ')
                    }
                    j++
                }
            }
        }
        while (j < m) {
            withStyle(SpanStyle(color = accent, fontWeight = FontWeight.SemiBold)) {
                append(b[j]); append(' ')
            }
            j++
        }
    }
}

/** 四维分数条：label + 分值 + 0-9 刻度横条。 */@Composable
private fun ScoreBar(label: String, score: Double, color: Color) {
    val frac = (score / 9.0).coerceIn(0.0, 1.0).toFloat()
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row {
            Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
            Text(
                if (score == 0.0) "暂无" else "%.1f".format(score),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Box(Modifier.fillMaxWidth().height(6.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)) {
            Box(
                Modifier.fillMaxWidth(frac)
                    .height(6.dp)
                    .background(color, CircleShape),
            )
        }
    }
}
