package com.txw.ielts.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import com.txw.ielts.data.SpeakingTopic

/**
 * 口语素材库：Part 2 高频话题 + 可复用表达 + 结构提示 + 朗读。
 * 图：口语 Part 2 准备闭环（素材 → 跟读 → 用自己的话练 → 评分）。
 */
@Composable
fun TopicScreen(
    topics: List<SpeakingTopic>,
    onPractice: (String) -> Unit,
    onBack: () -> Unit,
) {
    val (ttsAvailable, speak) = rememberTts()
    var expanded by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IosNavBar("口语素材", onBack = onBack)
        Text(
            "Part 2 高频话题 · 每个话题 5 句可复用表达：先点 ▶ 跟读，再用自己的话复述，最后去练习评分。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (topics.isEmpty()) {
            Text(
                "素材库没有加载到内容（assets/topics.json 缺失或解析失败）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        topics.forEach { t ->
            val open = expanded == t.id
            BookCard(
                Modifier.fillMaxWidth().clickable { expanded = if (open) null else t.id },
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            t.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontFamily = FontFamily.Serif,
                            modifier = Modifier.weight(1f),
                        )
                        Text(if (open) "▾" else "▸", style = MaterialTheme.typography.labelLarge)
                    }
                    if (!open) {
                        Text(
                            t.prompt,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                        )
                    } else {
                        Text(t.prompt, style = MaterialTheme.typography.bodyMedium)
                        if (t.tips.isNotBlank()) {
                            Text(
                                t.tips,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        t.sentences.forEachIndexed { i, s ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "${i + 1}. $s",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                if (ttsAvailable) {
                                    TextButton(onClick = { speak(s) }) { Text("▶") }
                                }
                            }
                        }
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { onPractice(t.prompt) },
                        ) { Text("用这个素材练习") }
                    }
                }
            }
        }
    }
}
