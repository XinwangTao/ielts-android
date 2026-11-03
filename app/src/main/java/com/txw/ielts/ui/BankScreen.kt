package com.txw.ielts.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.txw.ielts.data.BankSet

@Composable
fun BankScreen(
    sets: List<BankSet>,
    lastScores: Map<String, String>,
    progress: Map<String, Int>,
    onOpen: (String) -> Unit,
    onOpenTimed: (String, Int) -> Unit = { _, _ -> },
    onBack: () -> Unit,
) {
    var filter by remember { mutableStateOf("all") }
    val shown = sets.filter { filter == "all" || it.module == filter }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IosNavBar("听力 / 阅读", onBack = onBack)
        Text(
            "题库全部离线：判分不走网络、不消耗 API 额度。听力可调倍速与重播。",
            style = MaterialTheme.typography.bodySmall,
        )
        IosSegmented(
            options = listOf("全部", "阅读", "听力"),
            selected = when (filter) { "reading" -> 1; "listening" -> 2; else -> 0 },
            onSelect = { filter = when (it) { 1 -> "reading"; 2 -> "listening"; else -> "all" } },
            modifier = Modifier.fillMaxWidth(),
        )

        if (shown.isEmpty()) BankEmptyHint()

        shown.forEach { set ->
            val answered = progress[set.id] ?: 0
            val last = lastScores[set.id]
            BookCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AssistChip(
                            onClick = {},
                            label = { Text(if (set.module == "listening") "听力" else "阅读") },
                        )
                        Text(
                            "  ${set.questions.size} 题",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (!last.isNullOrBlank()) {
                            Text(
                                "  ·  上次 $last",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    Text(set.title, style = MaterialTheme.typography.titleMedium)
                    Text(set.intro, style = MaterialTheme.typography.bodySmall)
                    if (answered > 0) {
                        Text(
                            "已答 $answered 题，可继续",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            onClick = { onOpen(set.id) },
                        ) { Text(if (answered > 0) "继续做题" else "开始做题") }
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            onClick = {
                                onOpenTimed(set.id, if (set.module == "listening") 10 else 15)
                            },
                        ) { Text("限时模考") }
                    }
                }
            }
        }

        BookCard(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("做错了怎么办", style = MaterialTheme.typography.labelLarge)
                Text(
                    "交卷后结果页可以把错题一键加入背诵，和写作/口语批注生成的卡片进同一个复习队列。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
fun BankEmptyHint() {
    Text("题库加载失败", style = MaterialTheme.typography.bodyMedium)
}
