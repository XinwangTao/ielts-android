package com.txw.ielts.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.txw.ielts.data.BankSet

private val DIFFICULTIES = listOf("6.0", "6.5", "7.0", "7.5", "8.0", "8.5", "9.0")
private val TYPES = listOf("choice" to "单选", "tfng" to "判断", "gap" to "填空")
private val COUNTS = listOf(4, 6, 8)

@Composable
fun AiGenScreen(
    aiSets: List<BankSet>,
    busy: Boolean,
    error: String?,
    stage: String? = null,
    onGenerate: (String, String, List<String>, Int) -> Unit,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
    onBack: () -> Unit,
) {
    var difficulty by remember { mutableStateOf("7.0") }
    var module by remember { mutableStateOf("reading") }
    var selectedTypes by remember { mutableStateOf(setOf("choice", "tfng")) }
    var count by remember { mutableStateOf(6) }
    var deleting by remember { mutableStateOf<BankSet?>(null) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IosNavBar("AI 出题", onBack = onBack)

        Text(
            "让 AI 现生成一套更难的原创题（文章/听力稿 + 题目 + 中英双语解析）。听力题会自动用系统美音 TTS 合成音频（需已装 Kokoro 引擎）。每次生成消耗 1 次额度，判分仍走本地。",
            style = MaterialTheme.typography.bodySmall,
        )

        IosSegmented(
            options = listOf("阅读题", "听力题"),
            selected = if (module == "listening") 1 else 0,
            onSelect = { module = if (it == 1) "listening" else "reading" },
            modifier = Modifier.fillMaxWidth(),
        )

        BookCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("目标难度（Band）", style = MaterialTheme.typography.titleMedium)
                IosSegmented(
                    options = DIFFICULTIES,
                    selected = DIFFICULTIES.indexOf(difficulty).coerceAtLeast(0),
                    onSelect = { difficulty = DIFFICULTIES[it] },
                )
            }
        }

        BookCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("题型（可多选）", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TYPES.forEach { (key, label) ->
                        FilterChip(
                            selected = key in selectedTypes,
                            label = { Text(label) },
                            onClick = {
                                selectedTypes = if (key in selectedTypes) {
                                    selectedTypes - key
                                } else {
                                    selectedTypes + key
                                }
                            },
                        )
                    }
                }
            }
        }

        BookCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("题数", style = MaterialTheme.typography.titleMedium)
                IosSegmented(
                    options = COUNTS.map { "$it 题" },
                    selected = COUNTS.indexOf(count).coerceAtLeast(0),
                    onSelect = { count = COUNTS[it] },
                )
            }
        }

        if (selectedTypes.isEmpty()) {
            Text("至少选一种题型", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy && selectedTypes.isNotEmpty(),
            onClick = { onGenerate(module, "Band $difficulty", selectedTypes.toList(), count) },
        ) {
            Text(if (busy) "生成中…" else "生成一套题")
        }

        if (busy) {
            Text(
                stage ?: "正在请求模型…网络慢会自动重试，最长约 3 分钟",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (aiSets.isNotEmpty()) {
            Text("已生成的题目", style = MaterialTheme.typography.titleMedium)
            aiSets.forEach { s ->
                BookCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(s.title, style = MaterialTheme.typography.titleMedium)
                        Text(s.intro, style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { onOpen(s.id) }) { Text("做题") }
                            TextButton(onClick = { deleting = s }) { Text("删除") }
                        }
                    }
                }
            }
        }
    }

    deleting?.let { target ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除这套题？") },
            text = { Text("「${target.title}」将被永久删除，无法恢复。") },
            confirmButton = {
                TextButton(onClick = { onDelete(target.id); deleting = null }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
        )
    }
}
