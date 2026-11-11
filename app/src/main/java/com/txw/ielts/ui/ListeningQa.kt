package com.txw.ielts.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.txw.ielts.WavRecorder
import com.txw.ielts.llm.ListeningVerdict
import kotlinx.coroutines.launch
import java.io.File

/**
 * 听力口语追问卡：AI 按原文出一道能口头回答的理解题，用户听完口述、转写、由 AI 判要点。
 *
 * 和题库里的选择题互补：选择题考细节定位，口述题考真听懂没有。
 */
@Composable
fun ListeningQaCard(
    question: String?,
    reference: String?,
    busy: Boolean,
    error: String?,
    verdict: ListeningVerdict?,
    verdictBusy: Boolean,
    verdictError: String?,
    onRequestQa: () -> Unit,
    onJudge: (question: String, reference: String, answer: String) -> Unit,
    transcribe: suspend (File) -> String,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val (ttsAvailable, speak) = rememberTts()
    var recording by remember { mutableStateOf(false) }
    var recorder by remember { mutableStateOf<WavRecorder?>(null) }
    var audioFile by remember { mutableStateOf<File?>(null) }
    var transcribing by remember { mutableStateOf(false) }
    var transcript by remember { mutableStateOf<String?>(null) }
    var localError by remember { mutableStateOf<String?>(null) }
    var showReference by remember { mutableStateOf(false) }

    fun startRecording() {
        val f = File(context.filesDir, "listen_qa_" + System.currentTimeMillis() + ".wav")
        runCatching { WavRecorder(f, context).also { it.start() } }
            .onSuccess { recorder = it; audioFile = f; recording = true; localError = null; transcript = null }
            .onFailure { localError = "录音启动失败：" + (it.message ?: "") }
    }

    fun stopAndTranscribe() {
        val rec = recorder ?: return
        runCatching { rec.stop() }
        recorder = null
        recording = false
        val f = audioFile ?: return
        scope.launch {
            transcribing = true
            localError = null
            runCatching { transcribe(f) }
                .onSuccess { transcript = it.ifBlank { "" } }
                .onFailure { localError = "转写失败：" + (it.message ?: "") }
            transcribing = false
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startRecording() else localError = "没有麦克风权限，无法口述"
    }

    DisposableEffect(Unit) { onDispose { runCatching { recorder?.stop() } } }

    BookCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("听题口述", style = MaterialTheme.typography.titleSmall)
            Text(
                "AI 按这段听力出一道能口头回答的理解题：听完题直接说答案，转写后让 AI 判你有没有答到点上。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onRequestQa, enabled = !busy) {
                    Text(if (busy) "出题中…" else if (question != null) "换一题" else "AI 出题")
                }
                if (busy) CircularProgressIndicator(Modifier.padding(4.dp), strokeWidth = 2.dp)
            }
            if (error != null) {
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            question?.let { q ->
                Text(q, style = MaterialTheme.typography.bodyLarge, fontFamily = FontFamily.Serif)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { speak("Question. " + q) }, enabled = ttsAvailable) { Text("听题") }
                    if (!recording) {
                        TextButton(
                            onClick = {
                                val granted = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                                    android.content.pm.PackageManager.PERMISSION_GRANTED
                                if (granted) startRecording() else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            },
                            enabled = !transcribing,
                        ) { Text("口述回答") }
                    } else {
                        TextButton(onClick = { stopAndTranscribe() }) { Text("停止并转写") }
                    }
                    if (transcribing) CircularProgressIndicator(Modifier.padding(4.dp), strokeWidth = 2.dp)
                }
                if (recording) {
                    Text("正在录音…说完点「停止并转写」", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                transcript?.let { t ->
                    Text("你说：" + if (t.isBlank()) "（没识别到内容）" else t, style = MaterialTheme.typography.bodyMedium)
                    if (t.isNotBlank() && !reference.isNullOrBlank()) {
                        Button(onClick = { onJudge(q, reference, t) }, enabled = !verdictBusy) {
                            Text(if (verdictBusy) "判分中…" else "AI 判一判")
                        }
                    }
                }
                verdictError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                verdict?.let { v ->
                    Text(
                        v.verdict.ifBlank { "点评" } + (if (v.comment.isBlank()) "" else "：" + v.comment),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (v.verdict.contains("不对")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                }
                if (!reference.isNullOrBlank()) {
                    TextButton(onClick = { showReference = !showReference }) {
                        Text(if (showReference) "收起参考答案" else "看参考答案")
                    }
                    if (showReference) {
                        Text(reference, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            localError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
