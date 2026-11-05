package com.txw.ielts.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
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
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.txw.ielts.data.Card
import com.txw.ielts.data.Grade

private fun typeLabel(t: String) = when (t) {
    "word" -> "词汇"
    "collocation" -> "搭配句型"
    "topic" -> "话题语料"
    "error" -> "错题"
    else -> t
}

@Composable
fun CorpusHomeScreen(
    due: Int,
    fresh: Int,
    total: Int,
    mastered: Int,
    dailyLimit: Int,
    generated: Int,
    generatedCap: Int,
    typeCounts: List<Pair<String, Int>>,
    wrongCount: Int,
    reviewedToday: Int = 0,
    onStart: () -> Unit,
    onWrongStart: () -> Unit,
    onBack: () -> Unit,
    error: String? = null,
) {
    val todayNew = minOf(fresh, (dailyLimit).coerceAtLeast(0))
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        IosNavBar("语料积累", onBack = onBack)
        if (!error.isNullOrBlank()) {
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        BookCard(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("今天", style = MaterialTheme.typography.labelLarge)
                Text(
                    if (due + todayNew == 0) "今天没有待学卡片" else "待复习 $due 张 · 新卡 $todayNew 张",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Serif,
                )
                Text("新卡每日上限 $dailyLimit 张，到期复习优先", style = MaterialTheme.typography.bodySmall)
                if (reviewedToday > 0) {
                    Text(
                        "今日已学 $reviewedToday 张",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = due + todayNew > 0,
            onClick = onStart,
        ) { Text("开始学习") }

        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            enabled = wrongCount > 0,
            onClick = onWrongStart,
        ) { Text(if (wrongCount > 0) "错题专项复习（$wrongCount 张）" else "错题专项复习（暂无错题）") }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            MiniStat("总卡片", "$total", Modifier.weight(1f))
            MiniStat("已掌握", "$mastered", Modifier.weight(1f))
            MiniStat("待复习", "$due", Modifier.weight(1f))
        }

        Text("内容构成", style = MaterialTheme.typography.titleMedium)
        BookCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                typeCounts.sortedByDescending { it.second }.forEach { (t, n) ->
                    Row(Modifier.fillMaxWidth()) {
                        Text(typeLabel(t), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Text("$n 张", style = MaterialTheme.typography.labelLarge)
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("AI 生成池", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text("$generated / $generatedCap", style = MaterialTheme.typography.labelLarge)
                }
                Text(
                    "超出上限时，先淘汰从未复习的卡，再淘汰复习次数最少的；内置卡不动",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun MiniStat(label: String, value: String, modifier: Modifier) {
    BookCard(modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall)
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun StudyScreen(
    card: Card,
    index: Int,
    total: Int,
    revealed: Boolean,
    ttsAvailable: Boolean,
    onReveal: () -> Unit,
    onSpeak: () -> Unit,
    onGrade: (Grade) -> Unit,
    onDelete: () -> Unit = {},
    onExit: () -> Unit,
) {
    var grading by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    Column(
        // 学习页必须可滚动：横屏或系统大字号时，评级按钮会掉出屏幕且无法到达。
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onExit) { Text("退出") }
            Text(
                "${index + 1} / $total",
                style = MaterialTheme.typography.labelLarge,
                fontFamily = FontFamily.Serif,
                modifier = Modifier.weight(1f),
            )
            Text(typeLabel(card.type), style = MaterialTheme.typography.labelMedium)
            TextButton(onClick = { confirmDelete = true }) {
                Text("删除", color = MaterialTheme.colorScheme.error)
            }
        }
        LinearProgressIndicator(
            progress = { (index.toFloat() / total).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
        )

        BookCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(card.front, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Serif)
                if (card.tags.isNotBlank()) {
                    Text(card.tags, style = MaterialTheme.typography.labelSmall)
                }
                if (ttsAvailable) {
                    OutlinedButton(onClick = onSpeak) { Text("朗读") }
                }
            }
        }

        AnimatedContent(
            targetState = revealed,
            transitionSpec = {
                (scaleIn(initialScale = 0.92f) + fadeIn()) togetherWith fadeOut()
            },
            label = "card",
        ) { rv ->
            if (!rv) {
                Button(onClick = onReveal, modifier = Modifier.fillMaxWidth()) { Text("显示答案") }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    BookCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(card.back, style = MaterialTheme.typography.bodyLarge)
                            if (card.example.isNotBlank()) {
                                Text(card.example, style = MaterialTheme.typography.bodyMedium)
                                if (card.exampleCn.isNotBlank()) {
                                    Text(card.exampleCn, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            enabled = !grading,
                            onClick = { if (!grading) { grading = true; onGrade(Grade.AGAIN) } },
                            modifier = Modifier.weight(1f),
                        ) { Text("不认识") }
                        OutlinedButton(
                            enabled = !grading,
                            onClick = { if (!grading) { grading = true; onGrade(Grade.HARD) } },
                            modifier = Modifier.weight(1f),
                        ) { Text("模糊") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(
                            enabled = !grading,
                            onClick = { if (!grading) { grading = true; onGrade(Grade.GOOD) } },
                            modifier = Modifier.weight(1f),
                        ) { Text("认识") }
                        OutlinedButton(
                            enabled = !grading,
                            onClick = { if (!grading) { grading = true; onGrade(Grade.EASY) } },
                            modifier = Modifier.weight(1f),
                        ) { Text("太简单") }
                    }
                    Text("不认识的卡明天再出现；认识按 1→3→7 天递进", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除这张卡？") },
            text = { Text("「${card.front}」将从词汇库永久删除，不影响练习记录。") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            },
        )
    }
}
