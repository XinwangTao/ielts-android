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
import androidx.compose.material3.Card
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
import com.txw.ielts.data.ModelEssay

/**
 * 范文库：收藏自己评分后的 Band 7 润色，随时回看与朗读。
 * 图：写作积累闭环（评分 → 润色 → 收藏 → 复习）。
 */
@Composable
fun ModelEssayScreen(
    essays: List<ModelEssay>,
    onRemove: (Long) -> Unit,
    onBack: () -> Unit,
) {
    val (ttsAvailable, speak) = rememberTts()
    var expanded by remember { mutableStateOf<Long?>(null) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IosNavBar("范文库", onBack = onBack)
        Text(
            "收藏自己写过的作文经 AI 润色后的 Band 7 版本。回看结构、朗读表达，比背模板更有效。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (essays.isEmpty()) {
            Text(
                "还没有收藏范文。写作评分后，在结果页点「收藏这篇范文」。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 24.dp),
            )
        }

        essays.forEach { e ->
            val open = expanded == e.sessionId
            Card(Modifier.fillMaxWidth().clickable { expanded = if (open) null else e.sessionId }) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Band %.1f".format(e.band),
                            style = MaterialTheme.typography.titleLarge,
                            fontFamily = FontFamily.Serif,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            "  ·  ${e.topic.take(40)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Text(if (open) "▾" else "▸", style = MaterialTheme.typography.labelLarge)
                    }
                    if (open) {
                        Text(
                            e.polished,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Serif,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (ttsAvailable) {
                                TextButton(onClick = { speak(e.polished) }) { Text("朗读全文") }
                            }
                            TextButton(onClick = { onRemove(e.sessionId) }) {
                                Text("删除", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    } else {
                        Text(
                            e.polished.take(80) + "…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                        )
                    }
                }
            }
        }
    }
}
