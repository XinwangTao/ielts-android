package com.txw.ielts.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.txw.ielts.llm.BankExplain

data class BankResultItem(
    val id: Int,
    val prompt: String,
    val yours: String,
    val right: String,
    val explain: String,
    val explainEn: String = "",
    val keys: List<String> = emptyList(),
    val ok: Boolean,
)

@Composable
fun BankResultScreen(
    title: String,
    module: String,
    correct: Int,
    total: Int,
    items: List<BankResultItem>,
    passage: String = "",
    transcript: String,
    readerFontSize: Int = 16,
    readerLineHeight: Float = 1.4f,
    aiExplains: Map<Int, BankExplain> = emptyMap(),
    explainingIds: Set<Int> = emptySet(),
    onAiExplain: (BankResultItem) -> Unit = {},
    onAddWrong: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    val wrong = items.count { !it.ok }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IosNavBar(if (module == "listening") "听力结果" else "阅读结果", onBack = onBack)

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    "$correct / $total",
                    style = MaterialTheme.typography.displaySmall,
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    when {
                        correct == total -> "全对，这套可以过了。"
                        correct * 10 >= total * 7 -> "正确率 70% 以上，接近目标分数了。"
                        correct * 10 >= total * 5 -> "一半以上，重点看错题解析。"
                        else -> "先把错题吃透，再重做一遍这一套。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        if (passage.isNotBlank()) {
            val allKeys = items.flatMap { it.keys }.distinct()
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("原文对照（答案定位处已高亮）", style = MaterialTheme.typography.labelLarge)
                    Text(
                        highlightKeys(passage, allKeys, MaterialTheme.colorScheme.primary),
                        fontSize = readerFontSize.sp,
                        lineHeight = (readerFontSize * readerLineHeight).sp,
                    )
                }
            }
        }

        items.forEach { item ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                        Box(
                            Modifier.size(26.dp)
                                .background(
                                    if (item.ok) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                                    CircleShape,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(if (item.ok) "✓" else "✕", color = Color.White, style = MaterialTheme.typography.labelLarge)
                        }
                        Text(
                            highlightKeys("${item.id}. ${item.prompt}", item.keys, MaterialTheme.colorScheme.primary),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (!item.ok) {
                        Text(
                            "你的答案：${item.yours.ifBlank { "（未作答）" }}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Text("正确答案：${item.right}", style = MaterialTheme.typography.bodySmall)
                    if (item.explain.isNotBlank()) {
                        Text(
                            highlightKeys(item.explain, item.keys, MaterialTheme.colorScheme.primary),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (item.explainEn.isNotBlank()) {
                        Text(
                            highlightKeys(item.explainEn, item.keys, MaterialTheme.colorScheme.primary),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (!item.ok) {
                        val exp = aiExplains[item.id]
                        if (exp == null) {
                            OutlinedButton(
                                onClick = { onAiExplain(item) },
                                enabled = item.id !in explainingIds,
                            ) {
                                Text(if (item.id in explainingIds) "精讲生成中…" else "AI 精讲（消耗 1 次额度）")
                            }
                        } else {
                            Text("AI 精讲", style = MaterialTheme.typography.labelLarge)
                            Text(
                                highlightKeys(exp.cn, exp.keys, MaterialTheme.colorScheme.primary),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (exp.en.isNotBlank()) {
                                Text(
                                    highlightKeys(exp.en, exp.keys, MaterialTheme.colorScheme.primary),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }

        if (module == "listening" && transcript.isNotBlank()) {
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("听力原文", style = MaterialTheme.typography.labelLarge)
                    Text(transcript, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        if (wrong > 0) {
            Button(modifier = Modifier.fillMaxWidth(), onClick = onAddWrong) {
                Text("把 $wrong 道错题加入背诵")
            }
        }
        OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = onRetry) { Text("重做这一套") }
    }
}

/** 把 [keys] 中的关键短语在 [text] 里高亮（不区分大小写），用于结果页定位答案依据。 */
private fun highlightKeys(text: String, keys: List<String>, color: Color): AnnotatedString {
    if (text.isBlank() || keys.isEmpty()) return AnnotatedString(text)
    val lower = text.lowercase()
    return buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            var matched: String? = null
            for (k in keys) {
                val kl = k.lowercase()
                if (kl.isNotBlank() && lower.startsWith(kl, i)) {
                    matched = k
                    break
                }
            }
            if (matched != null) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = color)) {
                    append(text.substring(i, i + matched.length))
                }
                i += matched.length
            } else {
                append(text[i])
                i++
            }
        }
    }
}
