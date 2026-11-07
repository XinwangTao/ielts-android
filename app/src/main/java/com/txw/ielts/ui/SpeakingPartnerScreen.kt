package com.txw.ielts.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager
import com.txw.ielts.WavRecorder
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

private data class PartnerTurn(val question: String, val answer: String, val audioPath: String)

/**
 * 口语 AI 对练（全真考官模式，图：ielts-speaking-partner_workflow）。
 * 核心约束：录音期间不播放 TTS、不显示新问题、不转写——只有用户停止或到时限才处理。
 */
@Composable
fun SpeakingPartnerScreen(
    stage: String?,
    error: String?,
    onFinish: (transcript: String, seconds: Int) -> Unit,
    onBack: () -> Unit,
    transcribe: suspend (File) -> String,
    /** Part 3 追问：基于上一轮问答生成考官追问；返回 null 时回退题库。 */
    askFollowUp: (suspend (question: String, answer: String) -> String?)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val (ttsAvailable, speak) = rememberTts()
    // 对练进度持久化：退出后回来可继续，不用重头录。
    val prefs = remember { context.getSharedPreferences(
        com.txw.ielts.data.RecordingCleanup.PARTNER_PREFS,
        android.content.Context.MODE_PRIVATE,
    ) }

    var part by remember { mutableStateOf(prefs.getString("part", "Part 1") ?: "Part 1") }
    val limit = when (part) {
        "Part 1" -> 40
        "Part 2" -> 120
        else -> 90
    }
    val maxRounds = if (part == "Part 2") 1 else 4

    var round by remember { mutableIntStateOf(prefs.getInt("round", 0)) }
    var currentQuestion by remember {
        mutableStateOf(prefs.getString("question", null)?.takeIf { it.isNotBlank() })
    }
    var turns by remember {
        mutableStateOf(
            runCatching {
                val arr = org.json.JSONArray(prefs.getString(com.txw.ielts.data.RecordingCleanup.PARTNER_TURNS_KEY, "[]") ?: "[]")
                (0 until arr.length()).map {
                    val o = arr.getJSONObject(it)
                    PartnerTurn(o.optString("q"), o.optString("a"), o.optString("p"))
                }
            }.getOrDefault(emptyList())
        )
    }
    var phase by remember { mutableStateOf("idle") }   // idle / asking / answering / transcribing / done
    var recording by remember { mutableStateOf(false) }
    var seconds by remember { mutableIntStateOf(0) }
    var totalSeconds by remember { mutableIntStateOf(0) }
    var recorder by remember { mutableStateOf<WavRecorder?>(null) }
    var audioFile by remember { mutableStateOf<File?>(null) }
    var prepLeft by remember { mutableIntStateOf(0) }
    var localError by remember { mutableStateOf<String?>(null) }
    // 交评分后立刻锁住：onFinish 是异步的，连点会插入两条会话。
    var finishing by remember { mutableStateOf(false) }
    // 保存失败时解锁，让用户能重试（错误由上层写入 error）。
    LaunchedEffect(error) { if (error != null) finishing = false }
    // 忙状态只看本页 phase：全局 stage 由转写/评分函数设置且不会自动清空，
    // 之前用 stage != null 会让「结束并评分」永久禁用。
    val busy = phase == "transcribing"

    DisposableEffect(Unit) { onDispose { recorder?.stop() } }

    fun askNext() {
        // Part 3：基于上一轮回答让本地 LLM 生成追问；失败或没有上一轮时回退题库。
        if (part == "Part 3" && askFollowUp != null && turns.isNotEmpty()) {
            val prev = turns.last()
            phase = "thinking"
            currentQuestion = null
            scope.launch {
                val q = runCatching { askFollowUp(prev.question, prev.answer) }.getOrNull()
                val text = q?.trim()?.takeIf { it.isNotBlank() } ?: PART3.random()
                currentQuestion = text
                phase = "asking"
                if (ttsAvailable) speak(text)
            }
            return
        }
        val pool = when (part) {
            "Part 1" -> PART1
            "Part 2" -> PART2
            else -> PART3
        }
        val q = pool.random()
        currentQuestion = q
        phase = if (part == "Part 2") "preparing" else "asking"
        if (part == "Part 2") prepLeft = 60
        // TTS 只在出题时播放一次；录音期间不再发声（不打断用户）
        if (ttsAvailable) speak(q)
    }

    fun startRecording() {
        val f = File(context.filesDir, "partner_${System.currentTimeMillis()}.wav")
        runCatching { WavRecorder(f, context).also { it.start() } }
            .onSuccess {
                recorder = it; audioFile = f; recording = true; seconds = 0
                phase = "answering"; localError = null
            }
            .onFailure { localError = "录音启动失败：${it.message}" }
    }

    fun stopRecording() {
        recorder?.stop(); recorder = null; recording = false
        val f = audioFile ?: return
        phase = "transcribing"
        scope.launch {
            val text = runCatching { transcribe(f) }.getOrElse {
                localError = "转写失败：${it.message}"; "" }
            if (text.isBlank()) {
                localError = "没识别到内容，请重录这一段"
                phase = "asking"
                return@launch
            }
            turns = turns + PartnerTurn(currentQuestion ?: "", text, f.absolutePath)
            round += 1
            if (round >= maxRounds) phase = "done" else askNext()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) startRecording() else localError = "没有麦克风权限" }

    fun beginWithPermission() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            startRecording()
        }
    }

    fun begin() {
        round = 0
        turns = emptyList()
        totalSeconds = 0
        localError = null
        finishing = false
        askNext()
    }

    // 进度落盘：退出页面后回来可继续上次对练。
    LaunchedEffect(turns, round, currentQuestion, part) {
        val arr = org.json.JSONArray()
        turns.forEach { t ->
            arr.put(org.json.JSONObject().put("q", t.question).put("a", t.answer).put("p", t.audioPath))
        }
        prefs.edit()
            .putString("part", part)
            .putString(com.txw.ielts.data.RecordingCleanup.PARTNER_TURNS_KEY, arr.toString())
            .putInt("round", round)
            .putString("question", currentQuestion ?: "")
            .apply()
    }

    // 录音计时：到时限自动停止（不打断，但也不无限录）
    LaunchedEffect(recording) {
        while (recording) {
            delay(1_000)
            seconds += 1
            totalSeconds += 1
            if (seconds >= limit) stopRecording()
        }
    }
    // Part 2 准备倒计时：结束后自动开始录音
    LaunchedEffect(prepLeft) {
        while (prepLeft > 0) {
            delay(1_000)
            prepLeft -= 1
        }
        if (prepLeft == 0 && phase == "preparing") startRecording()
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IosNavBar("AI 口语对练", onBack = onBack) {
            Text(
                "第 $round/$maxRounds 轮",
                style = MaterialTheme.typography.labelLarge,
            )
        }
        Text(
            "全真考官模式：AI 提问并朗读，你回答完再进入下一题；录音期间 AI 不会插话。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (phase == "idle" || phase == "done") {
            IosSegmented(
                options = listOf("Part 1", "Part 2", "Part 3"),
                selected = when (part) { "Part 1" -> 0; "Part 2" -> 1; else -> 2 },
                onSelect = { part = listOf("Part 1", "Part 2", "Part 3")[it] },
                modifier = Modifier.fillMaxWidth(),
            )
            if (phase == "done") {
                BookCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("本轮对练完成", style = MaterialTheme.typography.titleSmall)
                        Text("共 ${turns.size} 轮，累计回答 $totalSeconds 秒。", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy && !finishing && turns.isNotEmpty(),
                    onClick = {
                        if (finishing) return@Button
                        finishing = true
                        // 过滤 whisper 的 [BLANK_AUDIO]/[Music] 占位，避免噪音词混入评分文本。
                        val merged = turns.mapNotNull { t ->
                            val ans = t.answer
                                .replace(Regex("\\[[^\\]]*\\]"), " ")
                                .replace(Regex("\\s+"), " ")
                                .trim()
                            if (ans.isBlank()) null else "[${t.question}]\n$ans"
                        }.joinToString("\n\n")
                        if (merged.isBlank()) {
                            localError = "没有识别到有效回答，请重试或换一段真实作答"
                        } else {
                            onFinish(merged, totalSeconds)
                        }
                    },
                ) { Text(if (busy || finishing) "评分中…" else "结束并评分") }
            }
            val hasSaved = turns.isNotEmpty() && phase == "idle"
            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
                onClick = { if (hasSaved) askNext() else begin() },
            ) {
                Text(
                    when {
                        phase == "done" -> "再练一轮"
                        hasSaved -> "继续上次对练（已完成 ${turns.size} 轮）"
                        else -> "开始对练"
                    }
                )
            }
            if (hasSaved) {
                TextButton(onClick = { begin() }) { Text("重新开始") }
            }
        }

        currentQuestion?.let { q ->
            BookCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("考官问题", style = MaterialTheme.typography.labelLarge)
                    Text(q, style = MaterialTheme.typography.bodyLarge, fontFamily = FontFamily.Serif)
                    if (ttsAvailable && phase == "asking") {
                        TextButton(onClick = { speak(q) }) { Text("再听一遍") }
                    }
                }
            }
        }

        if (phase == "preparing") {
            Text("准备时间：${prepLeft}s（结束后自动开始录音）", color = MaterialTheme.colorScheme.tertiary)
            LinearProgressIndicator(progress = { 1f - prepLeft / 60f }, modifier = Modifier.fillMaxWidth())
        }
        if (recording) {
            LinearProgressIndicator(progress = { seconds.toFloat() / limit }, modifier = Modifier.fillMaxWidth())
            Text("回答中 ${seconds}s / ${limit}s（说完点停止；到时限自动停）",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (phase == "transcribing") {
            Text(stage ?: "转写中…", style = MaterialTheme.typography.bodySmall)
        }
        if (phase == "thinking") {
            Text("考官正在根据你的回答想下一问…", style = MaterialTheme.typography.bodySmall)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                modifier = Modifier.weight(1f),
                enabled = phase == "asking" && !recording && !busy,
                onClick = { beginWithPermission() },
            ) { Text("开始回答") }
            OutlinedButton(
                modifier = Modifier.weight(1f),
                enabled = recording,
                onClick = { stopRecording() },
            ) { Text("说完了") }
        }

        (error ?: localError)?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        if (turns.isNotEmpty()) {
            Text("对话记录", style = MaterialTheme.typography.labelLarge)
            turns.forEachIndexed { i, t ->
                BookCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Q${i + 1} ${t.question}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("A: ${t.answer}", style = MaterialTheme.typography.bodyMedium)
                        // 每一轮都能回放：对练里最容易发现的是卡顿和口头禅。
                        if (t.audioPath.isNotBlank()) RecordingPlayer(t.audioPath, compact = true)
                    }
                }
            }
        }
    }
}
