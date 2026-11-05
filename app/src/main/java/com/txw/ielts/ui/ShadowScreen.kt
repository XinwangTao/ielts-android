package com.txw.ielts.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.txw.ielts.WavRecorder
import com.txw.ielts.data.ShadowDiff
import com.txw.ielts.data.ShadowPassage
import kotlinx.coroutines.delay
import java.io.File

@Composable
fun ShadowHomeScreen(
    passages: List<ShadowPassage>,
    lastScores: Map<String, String>,
    onOpen: (String) -> Unit,
    onBack: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("返回") }
            Text("口语跟读", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        }
        Text(
            "系统朗读参考句 → 你复述 → 端侧转写对比。全程零 API，练发音和流利度。",
            style = MaterialTheme.typography.bodySmall,
        )

        passages.forEach { p ->
            val last = lastScores[p.id]
            BookCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(p.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        if (!last.isNullOrBlank()) {
                            Text("上次 $last", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Text("${p.topic} · ${p.sentences.size} 句", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onOpen(p.id) },
                    ) { Text("开始跟读") }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ShadowStudyScreen(
    passage: ShadowPassage,
    index: Int,
    stage: String?,
    error: String?,
    lastResult: ShadowDiff?,
    lastTranscript: String?,
    ttsAvailable: Boolean,
    speak: (String) -> Unit,
    onSubmit: (audioPath: String, seconds: Int) -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val sentence = passage.sentences[index]

    var recording by remember { mutableStateOf(false) }
    var seconds by remember { mutableIntStateOf(0) }
    var audioFile by remember { mutableStateOf<File?>(null) }
    var recorder by remember { mutableStateOf<WavRecorder?>(null) }
    var localError by remember { mutableStateOf<String?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (!granted) localError = "没有麦克风权限" }

    LaunchedEffect(recording) {
        while (recording) {
            delay(1_000)
            seconds += 1
        }
    }
    DisposableEffect(Unit) { onDispose { recorder?.stop() } }

    fun startRecording() {
        val f = File(context.filesDir, "shadow_${System.currentTimeMillis()}.wav")
        runCatching { WavRecorder(f).also { it.start() } }
            .onSuccess { recorder = it; audioFile = f; recording = true; seconds = 0; localError = null }
            .onFailure { localError = "录音启动失败：${it.message}" }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, enabled = !recording) { Text("退出") }
            Text(passage.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text("第 ${index + 1} / ${passage.sentences.size} 句", style = MaterialTheme.typography.labelLarge)
        }

        BookCard(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("参考句", style = MaterialTheme.typography.labelLarge)
                Text(sentence, style = MaterialTheme.typography.titleMedium)
                if (ttsAvailable) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { speak(sentence) }) { Text("朗读") }
                    }
                } else {
                    Text("（无 TTS 引擎，请照着文字读）", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        BookCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    when {
                        recording -> "跟读中…第 $seconds 秒"
                        audioFile != null && lastResult == null -> "已录 ${"%.1f".format((audioFile?.length() ?: 0L) / 32000.0)} 秒，点「转写对比」"
                        else -> "先听参考句，再开始跟读"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        enabled = !recording && stage == null,
                        onClick = {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                                != PackageManager.PERMISSION_GRANTED
                            ) permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            else startRecording()
                        },
                    ) { Text("开始跟读") }
                    OutlinedButton(enabled = recording, onClick = {
                        recorder?.stop(); recorder = null; recording = false
                    }) { Text("停止") }
                    OutlinedButton(
                        enabled = !recording && audioFile != null && stage == null && lastResult == null,
                        onClick = { audioFile?.let { onSubmit(it.absolutePath, seconds) } },
                    ) { Text("转写对比") }
                }
            }
        }

        stage?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        (error ?: localError)?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        if (lastResult != null) {
            BookCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("准确率 ${"%.0f".format(lastResult.accuracy * 100)}%",
                            style = MaterialTheme.typography.titleLarge,
                            color = if (lastResult.accuracy >= 0.8) MaterialTheme.colorScheme.tertiary
                            else MaterialTheme.colorScheme.primary)
                        Text("  读对 ${lastResult.matchedRef.size} / ${lastResult.refWords.size} 词",
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    }
                    if (lastResult.wpm > 0) {
                        val rateText = when {
                            lastResult.wpm < 90 -> "语速 ${lastResult.wpm} 词/分 · 偏慢，注意连贯性"
                            lastResult.wpm > 170 -> "语速 ${lastResult.wpm} 词/分 · 偏快，注意发音清晰"
                            else -> "语速 ${lastResult.wpm} 词/分 · 适中"
                        }
                        Text(rateText, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider()
                    Text("参考句（红=漏读）：", style = MaterialTheme.typography.labelMedium)
                    Text(buildAnnotatedString {
                        lastResult.refWords.forEachIndexed { i, w ->
                            if (i in lastResult.missedRef) {
                                withStyle(SpanStyle(color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)) { append(w) }
                            } else append(w)
                            append(' ')
                        }
                    }, style = MaterialTheme.typography.bodyMedium)
                    Text("你的转写：", style = MaterialTheme.typography.labelMedium)
                    Text(lastTranscript ?: "", style = MaterialTheme.typography.bodyMedium)
                    if (lastResult.extraHyp.isNotEmpty()) {
                        Text("多读/读错的词：${lastResult.extraHyp.joinToString(", ")}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = onNext,
            ) { Text(if (index + 1 >= passage.sentences.size) "完成，看汇总" else "下一句") }
        }
    }
}

data class ShadowResultItem(
    val ref: String,
    val hyp: String,
    val accuracy: Double,
    val missed: List<String>,
)

@Composable
fun ShadowResultScreen(
    title: String,
    accuracy: Int,
    items: List<ShadowResultItem>,
    onAddWrong: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    val missedAll = items.flatMap { it.missed }.distinct()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("返回") }
            Text("跟读结果", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        }

        BookCard(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Serif)
                Text(
                    "$accuracy%",
                    style = MaterialTheme.typography.displaySmall,
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    when {
                        accuracy >= 85 -> "很稳，发音清晰、基本无漏读。"
                        accuracy >= 70 -> "不错，注意下面这些漏读的词。"
                        else -> "多跟读几遍，先盯住漏读最多的词。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        items.forEachIndexed { i, item ->
            BookCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${i + 1}. ${item.ref}", style = MaterialTheme.typography.bodyMedium)
                    Text("你的转写：${item.hyp.ifBlank { "（空）" }}", style = MaterialTheme.typography.bodySmall)
                    Text("准确率 ${"%.0f".format(item.accuracy * 100)}%", style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        if (missedAll.isNotEmpty()) {
            BookCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("漏读/读错词（${missedAll.size}）", style = MaterialTheme.typography.labelLarge)
                    Text(missedAll.joinToString("  "), style = MaterialTheme.typography.bodyMedium)
                    Button(modifier = Modifier.fillMaxWidth(), onClick = onAddWrong) {
                        Text("把这些词加入背诵")
                    }
                }
            }
        }

        OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = onRetry) { Text("再跟读一遍") }
    }
}
