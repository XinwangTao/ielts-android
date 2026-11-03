package com.txw.ielts.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.txw.ielts.WavRecorder
import kotlinx.coroutines.delay
import java.io.File

/**
 * 口语模考：Part 1 日常问答 → Part 2 话题卡 → Part 3 深入讨论，三段连续录音。
 * 提交后依次转写并合并评分，模拟真实考试节奏。
 */
@Composable
fun SpeakingMockScreen(
    stage: String?,
    error: String?,
    onSubmit: (files: List<String>, seconds: List<Int>) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val q1 = remember { PART1.random() }
    val q2 = remember { PART2.random() }
    val q3 = remember { PART3.random() }
    val questions = listOf("Part 1" to q1, "Part 2" to q2, "Part 3" to q3)
    val limits = listOf(40, 120, 90)

    var files by remember { mutableStateOf<List<File?>>(listOf(null, null, null)) }
    var secs by remember { mutableStateOf(listOf(0, 0, 0)) }
    var active by remember { mutableIntStateOf(-1) }
    var recorder by remember { mutableStateOf<WavRecorder?>(null) }
    var localError by remember { mutableStateOf<String?>(null) }
    var confirmExit by remember { mutableStateOf(false) }
    val busy = stage != null

    // 已录内容（或正在录音）时返回会丢三段录音，先确认。
    val hasContent = files.any { it != null } || active >= 0
    androidx.activity.compose.BackHandler(enabled = hasContent && !busy) { confirmExit = true }
    if (confirmExit) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text("退出口语模考？") },
            text = { Text("已录制的段落会被丢弃，本次不会保存。") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { confirmExit = false; onBack() }) { Text("退出") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { confirmExit = false }) { Text("继续") }
            },
        )
    }

    var pendingPart by remember { mutableIntStateOf(-1) }
    fun startPart(i: Int) {
        val f = File(context.filesDir, "mock_${i}_${System.currentTimeMillis()}.wav")
        runCatching { WavRecorder(f).also { it.start() } }
            .onSuccess {
                recorder = it
                files = files.toMutableList().also { l -> l[i] = f }
                secs = secs.toMutableList().also { l -> l[i] = 0 }
                active = i
                localError = null
            }
            .onFailure { localError = "录音失败：${it.message}" }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        // 授权成功后直接录这一段，省掉「再点一次」。
        if (granted) {
            if (pendingPart >= 0) startPart(pendingPart)
        } else localError = "没有麦克风权限"
        pendingPart = -1
    }

    LaunchedEffect(active) {
        while (active >= 0) {
            delay(1_000)
            val cur = active
            if (cur >= 0) {
                secs = secs.toMutableList().also { it[cur] += 1 }
                if (secs[cur] >= limits[cur]) {
                    recorder?.stop(); recorder = null; active = -1
                }
            }
        }
    }
    DisposableEffect(Unit) { onDispose { recorder?.stop() } }

    fun stopActive() {
        recorder?.stop()
        recorder = null
        active = -1
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IosNavBar("口语模考", onBack = { if (hasContent && !busy) confirmExit = true else onBack() })
        Text(
            "模拟真实考试：Part 1 简短问答、Part 2 话题卡、Part 3 深入讨论，三段连续录音后合并评分。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        questions.forEachIndexed { i, pair ->
            val (part, q) = pair
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "$part · ${if (i == 1) "准备 1 分钟，陈述 2 分钟" else if (i == 0) "每题 20-40 秒" else "每题 1-1.5 分钟"}",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(q, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Serif)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        when {
                            active == i -> {
                                Text("录音中 ${secs[i]} 秒", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                OutlinedButton(onClick = { stopActive() }) { Text("停止") }
                            }
                            files[i] != null -> {
                                Text("已录 ${secs[i]} 秒", style = MaterialTheme.typography.bodySmall)
                                TextButton(
                                    enabled = !busy && active < 0,
                                    onClick = {
                                        files = files.toMutableList().also { it[i] = null }
                                        secs = secs.toMutableList().also { it[i] = 0 }
                                    },
                                ) { Text("重录") }
                            }
                            else -> {
                                Button(
                                    enabled = !busy && active < 0,
                                    onClick = {
                                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                                            != PackageManager.PERMISSION_GRANTED
                                        ) {
                                            pendingPart = i
                                            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                        } else {
                                            startPart(i)
                                        }
                                    },
                                ) { Text(if (i == 1) "开始陈述" else "开始回答") }
                            }
                        }
                    }
                }
            }
        }

        (error ?: localError)?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy && files.all { it != null },
            onClick = { onSubmit(files.map { it!!.absolutePath }, secs) },
        ) {
            Text(
                when {
                    busy -> stage ?: "提交中…"
                    files.any { it == null } -> "三段都录完后提交"
                    else -> "提交模考"
                }
            )
        }
    }
}
