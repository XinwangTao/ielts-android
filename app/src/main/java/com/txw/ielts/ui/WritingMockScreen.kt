package com.txw.ielts.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * 写作模考：Task 1（20 分钟建议）+ Task 2（40 分钟建议），共 60 分钟连续计时。
 * 训练真实考试的时间分配，而不是单篇无压力练习。
 */
@Composable
fun WritingMockScreen(
    stage: String?,
    error: String?,
    onSubmit: (t1Topic: String, t1Text: String, t2Topic: String, t2Text: String) -> Unit,
    onBack: () -> Unit,
) {
    var started by remember { mutableStateOf(false) }
    var remaining by remember { mutableIntStateOf(60 * 60) }
    var task by remember { mutableStateOf("task2") }
    var t1Item by remember { mutableStateOf(TASK1_ITEMS[0]) }
    var t2Topic by remember { mutableStateOf(TASK2_TOPICS[0]) }
    var t1Text by remember { mutableStateOf("") }
    var t2Text by remember { mutableStateOf("") }
    var confirmExit by remember { mutableStateOf(false) }
    val busy = stage != null
    val ctx = LocalContext.current

    // 模考进行中返回会丢掉两篇正文和计时，先确认。
    androidx.activity.compose.BackHandler(enabled = started && !busy) { confirmExit = true }
    if (confirmExit) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text("退出写作模考？") },
            text = { Text("两篇作文和已用时间都会丢失，本次不会保存。") },
            confirmButton = {
                TextButton(onClick = { confirmExit = false; onBack() }) { Text("退出") }
            },
            dismissButton = {
                TextButton(onClick = { confirmExit = false }) { Text("继续写") }
            },
        )
    }

    LaunchedEffect(started) {
        while (started && remaining > 0) {
            delay(1_000)
            remaining -= 1
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IosNavBar("写作模考", onBack = { if (started && !busy) confirmExit = true else onBack() }) {
            Text(
                "%02d:%02d".format(remaining / 60, remaining % 60),
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Serif,
                color = if (remaining <= 5 * 60) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface,
            )
        }

        if (!started) {
            BookCard(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("考试说明", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Task 1（图表）建议 20 分钟，Task 2（议论文）建议 40 分钟，共 60 分钟。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "开始后计时不会暂停。时间到后仍可提交，但请按考试节奏练习。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "两篇都提交后依次评分（云端优先，失败自动本地），结果在首页练习批注查看。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
                onClick = { started = true },
            ) { Text("开始模考") }
        } else {
            IosSegmented(
                options = listOf("Task 1 图表", "Task 2 议论文"),
                selected = if (task == "task1") 0 else 1,
                onSelect = { task = if (it == 0) "task1" else "task2" },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
            )

            if (task == "task1") {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                ) {
                    TASK1_ITEMS.forEachIndexed { i, item ->
                        FilterChip(
                            selected = t1Item == item,
                            enabled = !busy,
                            onClick = { t1Item = item },
                            label = { Text(item.label) },
                        )
                    }
                }
                BookCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Task 1 · 图表描述（150 词以上）", style = MaterialTheme.typography.labelLarge)
                        t1Item.image?.let { path ->
                            val bmp = rememberAssetImage(ctx.assets, path)
                            if (bmp != null) {
                                Image(
                                    bitmap = bmp,
                                    contentDescription = "Task 1 图表",
                                    modifier = Modifier.fillMaxWidth(),
                                    contentScale = androidx.compose.ui.layout.ContentScale.FillWidth,
                                )
                            }
                        }
                        Text(t1Item.fullText(), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            } else {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                ) {
                    TASK2_TOPICS.forEachIndexed { i, t ->
                        FilterChip(
                            selected = t2Topic == t,
                            enabled = !busy,
                            onClick = { t2Topic = t },
                            label = { Text("题 ${i + 1}") },
                        )
                    }
                }
                BookCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Task 2 · 议论文（250 词以上）", style = MaterialTheme.typography.labelLarge)
                        Text(t2Topic, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            IosTextField(
                value = if (task == "task1") t1Text else t2Text,
                onValueChange = { if (task == "task1") t1Text = it else t2Text = it },
                enabled = !busy,
                label = if (task == "task1") "Task 1 写在这里" else "Task 2 写在这里",
                modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp),
            )
            Text(
                "已写 ${
                    (if (task == "task1") t1Text else t2Text).split(Regex("\\s+"))
                        .count { it.isNotBlank() }
                } 词",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy && t1Text.trim().length > 30 && t2Text.trim().length > 30,
                onClick = { onSubmit(t1Item.fullText(), t1Text.trim(), t2Topic, t2Text.trim()) },
            ) {
                Text(
                    when {
                        busy -> stage ?: "提交中…"
                        t1Text.trim().length <= 30 || t2Text.trim().length <= 30 -> "两篇都写完后提交"
                        else -> "提交两篇评分"
                    }
                )
            }
            (error)?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
