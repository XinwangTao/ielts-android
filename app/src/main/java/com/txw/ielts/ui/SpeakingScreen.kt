package com.txw.ielts.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.txw.ielts.WavRecorder
import kotlinx.coroutines.delay
import java.io.File

internal val PART1 = listOf(
    "Where do you live at the moment, and what do you like about it?",
    "Do you prefer studying in the morning or in the evening? Why?",
    "How often do you use public transport?",
    "What kind of music do you enjoy, and when do you usually listen to it?",
    "Do you cook at home? Why or why not?",
    "What was your favourite subject at school, and why?",
    "How do you usually spend your weekends?",
    "Do you use any apps to learn languages?",
    "What kind of weather do you like best?",
    "Are you good at remembering names? Why do you think that is?",
    "Do you prefer reading paper books or reading on a screen?",
    "Is there a hobby you would like to take up in the future?",
    "How often do you meet your friends in person?",
    "What do you usually do when you have a few minutes of free time?",
    "Do you think you get enough sleep? Is your sleep pattern regular?",
    "Are there any sports you like watching but would never play?",
    "How important is it for you to keep your desk or room tidy?",
    "Do you like taking photos? What do you usually take photos of?",
    "Would you rather spend a holiday at the beach or in the mountains?",
    "Do you drink tea or coffee? Has that changed over the years?",
)
internal val PART2 = listOf(
    "Describe a skill you learned that was difficult at first. You should say what it was, how you learned it, " +
        "why it was difficult, and explain how you feel about it now.",
    "Describe a place you go to that helps you relax. You should say where it is, how often you go, " +
        "what you do there, and why it helps you relax.",
    "Describe a time you had to make a decision quickly. You should say what the situation was, " +
        "what you decided, why you decided quickly, and how you felt afterwards.",
    "Describe a person who has influenced you. You should say who they are, how you know them, " +
        "what they did, and why they influenced you.",
    "Describe an important object you own. You should say what it is, how long you have had it, " +
        "how you use it, and why it matters to you.",
    "Describe a memorable journey. You should say where you went, who you went with, " +
        "what happened during the trip, and why it was memorable.",
    "Describe a rule you think is unfair. You should say what the rule is, where it applies, " +
        "who it affects, and why you think it is unfair.",
    "Describe a piece of technology you find useful. You should say what it is, when you started using it, " +
        "what you use it for, and why it is useful to you.",
    "Describe a time you helped someone. You should say who you helped, what the situation was, " +
        "what you did, and how you felt about helping.",
    "Describe a goal you are working towards. You should say what the goal is, when you started, " +
        "what you are doing to achieve it, and why it matters to you.",
    "Describe a time you were late for something important. You should say what the occasion was, " +
        "why you were late, what you did about it, and explain how you felt.",
    "Describe a shop or market you visit often. You should say where it is, what it sells, " +
        "how often you go, and why you keep going back.",
    "Describe a piece of news that interested you. You should say what it was, how you heard about it, " +
        "why it interested you, and how you felt about it.",
    "Describe a family member you spend a lot of time with. You should say who they are, " +
        "what you do together, what you have in common, and why you enjoy their company.",
    "Describe a time you changed your mind about something. You should say what it was, " +
        "what made you change it, who influenced you, and how you feel about the change now.",
    "Describe a public place that needs improvement. You should say where it is, what problems it has, " +
        "who uses it, and how you think it could be improved.",
    "Describe an occasion when you received good service. You should say where it was, " +
        "what the service was, why it was good, and whether you would go back.",
    "Describe a photo you like. You should say what is in it, when it was taken, " +
        "who took it, and why you like it.",
    "Describe a subject you would like to learn more about. You should say what it is, " +
        "why it interests you, how you would learn it, and how it might help you.",
    "Describe a habit you would like to change. You should say what the habit is, " +
        "how long you have had it, why you want to change it, and what you might do instead.",
)
internal val PART3 = listOf(
    "Why do you think some skills are harder to learn than others?",
    "Should schools teach practical skills instead of academic subjects? Why?",
    "How has technology changed the way people learn new things?",
    "Do you think people today are more or less patient than in the past?",
    "What are the advantages and disadvantages of working from home?",
    "Should governments limit the amount of time children spend online?",
    "Why do some people prefer living in cities while others prefer the countryside?",
    "How important is it for companies to protect employees' private time?",
    "Do you think exams are a fair way to measure ability?",
    "What role should family play in a person's education?",
    "Is it important to keep traditional skills alive in a modern economy?",
    "How does advertising influence the way young people spend money?",
    "Should university education be free for everyone? What are the trade-offs?",
    "Why do some people resist change even when it would benefit them?",
    "What responsibilities do individuals have towards the environment, compared with governments?",
    "Do you think remote work will change the way cities are designed?",
    "How can employers support the mental health of their staff?",
    "Is it possible to be truly successful without taking risks?",
    "Why do so many people enjoy watching others compete in sport?",
    "Should children be given pocket money? What does it teach them?",
)

/** 从题库里随机抽一题，尽量避开当前这题（题库 >1 时保证换题）。 */
private fun pickRandom(list: List<String>, avoid: String?): String {
    if (list.isEmpty()) return ""
    if (list.size == 1) return list[0]
    var pick = list.random()
    while (pick == avoid) pick = list.random()
    return pick
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SpeakingScreen(
    stage: String?,
    error: String?,
    onSubmit: (part: String, question: String, audioPath: String, seconds: Int) -> Unit,
    onBack: () -> Unit,
    focusFixes: List<String> = emptyList(),   // 上次评分的改进点：带着这几点重说
    presetPart: String? = null,
    presetQuestion: String? = null,
    onOpenTopics: () -> Unit = {},
    onOpenMock: () -> Unit = {},
    onOpenPartner: () -> Unit = {},
    // 本地 AI 范例答案：给「不知道怎么说」的题一个 Band 7 参考。
    sample: com.txw.ielts.llm.SpeakingSample? = null,
    sampleQuestion: String? = null,
    sampleBusy: Boolean = false,
    sampleError: String? = null,
    onRequestSample: (part: String, question: String) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val (ttsAvailable, speakSample) = rememberTts()
    var part by remember { mutableStateOf(presetPart ?: "Part 2") }
    val questions = when (part) {
        "Part 1" -> PART1
        "Part 2" -> PART2
        else -> PART3
    }
    var question by remember(part) {
        mutableStateOf(
            if (presetQuestion != null && presetPart == part) presetQuestion else questions[0]
        )
    }

    var recording by remember { mutableStateOf(false) }
    var seconds by remember { mutableIntStateOf(0) }
    var countdown by remember { mutableIntStateOf(0) }
    var audioFile by remember { mutableStateOf<File?>(null) }
    var recorder by remember { mutableStateOf<WavRecorder?>(null) }
    var localError by remember { mutableStateOf<String?>(null) }

    val limit = if (part == "Part 2") 120 else 90

    LaunchedEffect(recording) {
        while (recording) {
            delay(1_000)
            seconds += 1
            if (seconds >= limit) {
                recorder?.stop(); recorder = null; recording = false
            }
        }
    }
    LaunchedEffect(countdown) {
        while (countdown > 0) {
            delay(1_000)
            countdown -= 1
        }
        if (countdown == 0 && audioFile == null && !recording) {
            // 准备结束，进入录音
        }
    }
    DisposableEffect(Unit) { onDispose { recorder?.stop() } }

    fun startRecording() {
        val f = File(context.filesDir, "speaking_${System.currentTimeMillis()}.wav")
        runCatching { WavRecorder(f).also { it.start() } }
            .onSuccess { recorder = it; audioFile = f; recording = true; seconds = 0; countdown = 0; localError = null }
            .onFailure { localError = "录音启动失败：${it.message}" }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        // 授权成功后直接开始录音。
        if (granted) startRecording() else localError = "没有麦克风权限"
    }

    val busy = stage != null
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        IosNavBar("口语练习室", onBack = onBack) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onOpenMock, enabled = !busy && !recording) {
                    Text("模考")
                }
                TextButton(onClick = onOpenTopics, enabled = !busy && !recording) {
                    Text("素材")
                }
                TextButton(onClick = onOpenPartner, enabled = !busy && !recording) {
                    Text("对练")
                }
                Text(
                    if (countdown > 0) "准备 ${countdown}s" else "%02d:%02d".format(seconds / 60, seconds % 60),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }

        IosSegmented(
            options = listOf("Part 1", "Part 2", "Part 3"),
            selected = when (part) { "Part 1" -> 0; "Part 2" -> 1; else -> 2 },
            onSelect = { i ->
                part = listOf("Part 1", "Part 2", "Part 3")[i]
                question = when (part) {
                    "Part 1" -> PART1[0]
                    "Part 2" -> PART2[0]
                    else -> PART3[0]
                }
                audioFile = null
                seconds = 0
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy && !recording && countdown == 0,
        )

        if (focusFixes.isNotEmpty()) {
            BookCard(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("上次评分的改进点（说的时候对照检查）", style = MaterialTheme.typography.labelLarge)
                    focusFixes.take(3).forEachIndexed { i, f ->
                        Text("${i + 1}. $f", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        BookCard(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    when (part) {
                        "Part 1" -> "Part 1 · 日常问答（每题 20–40 秒）"
                        "Part 2" -> "Part 2 · 事件卡（准备 1 分钟，陈述 2 分钟）"
                        else -> "Part 3 · 深入讨论（每题 1–1.5 分钟）"
                    },
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(question, style = MaterialTheme.typography.bodyLarge, fontFamily = FontFamily.Serif)
            }
        }

        val sampleBusyHere = sampleBusy && sampleQuestion == question
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                enabled = !busy && !recording && countdown == 0 && !sampleBusy,
                onClick = { onRequestSample(part, question) },
            ) { Text(if (sampleBusyHere) "生成范例中…" else "AI 范例答案") }
        }
        if (sampleBusyHere) {
            LocalAiBusy("本地模型生成范例中（请留在 App 内）")
        }
        if (sampleQuestion == question) {
            sampleError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
        // 题目换了就隐藏旧范例，避免拿上一题的答案去准备这一题。
        if (!sampleBusy && sample != null && sampleQuestion == question && !sample.isEmpty) {
            SpeakingSampleCard(sample, ttsAvailable, speakSample)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(
                enabled = !busy && !recording && countdown == 0,
                onClick = {
                    question = pickRandom(questions, question)
                    audioFile = null
                    seconds = 0
                },
            ) { Text("随机抽一题") }
            val qIndex = questions.indexOf(question)
            Text(
                if (qIndex >= 0) "第 ${qIndex + 1} 题 / 共 ${questions.size} 题"
                else "自定义题目（来自素材库或模考结果）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        ) {
            questions.forEachIndexed { i, q ->
                FilterChip(
                    selected = question == q,
                    enabled = !busy && !recording && countdown == 0,
                    onClick = { question = q; audioFile = null; seconds = 0 },
                    label = { Text("题 ${i + 1}") },
                )
            }
        }

        if (countdown > 0) {
            ProgressRing(
                progress = countdown / 60f,
                valueText = "${countdown}s",
                labelText = "准备中",
                color = MaterialTheme.colorScheme.secondary,
            )
        }
        if (recording) {
            ProgressRing(
                progress = seconds.toFloat() / limit,
                valueText = "${seconds}s",
                labelText = "陈述中 · 上限 ${limit} 秒",
                color = MaterialTheme.colorScheme.error,
            )
        }

        BookCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (recording) {
                        val pulse = rememberInfiniteTransition(label = "rec")
                        val scale by pulse.animateFloat(
                            initialValue = 0.8f,
                            targetValue = 1.4f,
                            animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
                            label = "pulse",
                        )
                        Box(
                            Modifier.size(12.dp)
                                .scale(scale)
                                .background(MaterialTheme.colorScheme.error, CircleShape),
                        )
                    }
                    Text(
                        when {
                            recording -> "录音中，第 ${seconds} 秒 / 上限 ${limit} 秒"
                            audioFile != null -> "已录 ${"%.1f".format((audioFile?.length() ?: 0L) / 32000.0)} 秒，可以提交"
                            else -> if (part == "Part 2") "先点「准备 1 分钟」，再开始陈述" else "点开始录音直接作答"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (recording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (part == "Part 2" && !recording && countdown == 0 && audioFile == null) {
                        OutlinedButton(
                            enabled = !busy,
                            onClick = { countdown = 60 },
                        ) { Text("准备 1 分钟") }
                    }
                    Button(
                        enabled = !busy && !recording && countdown == 0,
                        onClick = {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                                != PackageManager.PERMISSION_GRANTED
                            ) permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            else startRecording()
                        },
                    ) { Text("开始录音") }
                    OutlinedButton(enabled = recording, onClick = {
                        recorder?.stop(); recorder = null; recording = false
                    }) { Text("停止") }
                    OutlinedButton(
                        enabled = !busy && !recording && audioFile != null,
                        onClick = { audioFile = null; seconds = 0 },
                    ) { Text("重录") }
                }
            }
        }

        stage?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        (error ?: localError)?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy && !recording && audioFile != null && seconds >= 5,
            onClick = { onSubmit(part, question, audioFile!!.absolutePath, seconds) },
        ) { Text(if (busy) "处理中…" else "提交评分") }
    }
}

/** 环形进度：中央大数字秒数 + 下方标签（Part 2 准备倒计时 / 陈述计时）。 */
@Composable
private fun ProgressRing(progress: Float, valueText: String, labelText: String, color: Color) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        androidx.compose.foundation.Canvas(Modifier.size(130.dp)) {            drawArc(
                color = track,
                startAngle = 0f, sweepAngle = 360f, useCenter = false,
                style = Stroke(width = 14f, cap = StrokeCap.Round),
            )
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = 360f * progress.coerceIn(0f, 1f),
                useCenter = false,
                style = Stroke(width = 14f, cap = StrokeCap.Round),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                valueText,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Serif,
                color = color,
            )
            Text(labelText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 口语范例卡：Band 7 答案 + 关键词 + 中文结构提示；可朗读。 */
@Composable
private fun SpeakingSampleCard(
    sample: com.txw.ielts.llm.SpeakingSample,
    ttsAvailable: Boolean,
    speak: (String) -> Unit,
) {
    val context = LocalContext.current
    // Part 2 范例 180-220 词，整段铺开会把录音按钮顶到屏幕外；默认只显示前 8 行。
    var expanded by remember(sample) { mutableStateOf(false) }
    BookCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("AI 范例答案 · 本地模型", style = MaterialTheme.typography.labelLarge)
            Text(
                sample.answer,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Serif,
                maxLines = if (expanded) Int.MAX_VALUE else 8,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            if (sample.answer.length > 260) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "收起" else "展开全文")
                }
            }
            if (sample.keywords.isNotEmpty()) {
                Text("可复用表达", style = MaterialTheme.typography.labelLarge)
                sample.keywords.forEach { Text("· $it", style = MaterialTheme.typography.bodySmall) }
            }
            if (sample.structure.isNotEmpty()) {
                Text("结构提示", style = MaterialTheme.typography.labelLarge)
                sample.structure.forEach {
                    Text(
                        "· $it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (ttsAvailable && sample.answer.isNotBlank()) {
                    TextButton(onClick = { speak(sample.answer) }) { Text("朗读范例") }
                }
                TextButton(onClick = {
                    val text = buildString {
                        append(sample.answer)
                        if (sample.keywords.isNotEmpty()) {
                            append("\n\n可复用表达：\n")
                            sample.keywords.forEach { append("· ").append(it).append("\n") }
                        }
                        if (sample.structure.isNotEmpty()) {
                            append("\n结构提示：\n")
                            sample.structure.forEach { append("· ").append(it).append("\n") }
                        }
                    }
                    (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                        .setPrimaryClip(android.content.ClipData.newPlainText("IELTS sample", text))
                }) { Text("复制范例") }
            }
            Text(
                "仅供模仿，不要背成模板",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
