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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.txw.ielts.data.WritingArgumentTopic

/**
 * 写作论点素材库：每个话题 2-3 组「论点 / 解释 / 例子」，直击「展开不足」。
 * 图：写作 Task 2 论点积累闭环（素材 → 仿写 → 评分）。
 */
@Composable
fun ArgumentScreen(
    topics: List<WritingArgumentTopic>,
    onPractice: (String) -> Unit,
    onBack: () -> Unit,
) {
    val (ttsAvailable, speak) = rememberTts()
    var expanded by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IosNavBar("写作论点", onBack = onBack)
        Text(
            "每个话题 2-3 组「论点 → 解释 → 例子」。写作时每个主体段用一组，就能解决「观点空泛、展开不足」。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (topics.isEmpty()) {
            Text(
                "论点库没有加载到内容（assets/arguments.json 缺失或解析失败）。",
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
                        // 不再 take(60) 硬切：写死的字符数在宽屏上也会白白截掉后半句，窄屏（360dp）还会
                        // 被 maxLines 再砍一次。改成让 Compose 按可用宽度省略，计数放最前保证不被吞。
                        Text(
                            "${t.arguments.size} 组论点 · ${t.prompt}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else {
                        Text(t.prompt, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (t.tips.isNotBlank()) {
                            Text(
                                t.tips,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        t.arguments.forEachIndexed { i, a ->
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "${i + 1}. ${a.point}",
                                        style = MaterialTheme.typography.titleSmall,
                                        modifier = Modifier.weight(1f),
                                    )
                                    if (ttsAvailable) {
                                        TextButton(onClick = { speak("${a.point}. ${a.explain}. ${a.example}") }) {
                                            Text("▶")
                                        }
                                    }
                                }
                                Text(a.explain, style = MaterialTheme.typography.bodySmall)
                                Text(
                                    "e.g. ${a.example}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { onPractice(t.prompt) },
                        ) { Text("用这个题目写作") }
                    }
                }
            }
        }
    }
}
