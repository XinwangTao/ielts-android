package com.txw.ielts.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.txw.ielts.data.Session
import com.txw.ielts.data.StatusLabel
import org.json.JSONObject

/**
 * 模考结果聚合：写作模考（Task1+Task2）或口语模考（Part1-3）完成后统一展示。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MockResultScreen(
    sessions: List<Session>,
    onOpen: (Long) -> Unit,
    onBack: () -> Unit,
) {
    val done = sessions.filter { it.status == "done" }
    val bands = done.mapNotNull { s -> bandOf(s)?.let { s to it } }
    // 写作总分 = (Task1 + 2×Task2) / 3（Task 2 权重更高），口语就是该段的 overall；
    // 之前对两篇直接算术平均，会显示 6.3 这种不在 0.5 步长上的分数。
    val overall: Double? = when {
        bands.isEmpty() -> null
        done.all { it.module != "speaking" } && bands.size >= 2 -> {
            val t1 = bands.first().second
            val t2 = bands.last().second
            Math.round((t1 + 2 * t2) / 3.0 * 2.0) / 2.0
        }
        else -> Math.round(bands.map { it.second }.average() * 2.0) / 2.0
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IosNavBar("模考结果", onBack = onBack)

        if (overall != null) {
            // 窄屏下「总分 + 权重说明 + 完成段数」挤一行会把右侧压成一字一行；
            // 总分单独一行，说明与段数放第二行小字。
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "总分 Band %.1f".format(overall),
                    style = MaterialTheme.typography.headlineSmall,
                    fontFamily = FontFamily.Serif,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    if (done.all { it.module != "speaking" }) "Task1:Task2 = 1:2 · ${done.size} 段已完成"
                    else "${done.size} 段已完成",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        sessions.forEach { s ->
            BookCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            moduleLabel(s.module) + " · " + s.topic,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            when (s.status) {
                                StatusLabel.DONE -> "Band %.1f".format(bandOf(s) ?: 0.0)
                                // 不再用 else -> "待评分" 把 asr_empty（没听清、要重录）也扮成待评分。
                                else -> StatusLabel.label(s.status)
                            },
                            style = MaterialTheme.typography.titleMedium,
                            color = if (s.status == "done") MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (s.status == "done") {
                        val dims = runCatching {
                            val o = JSONObject(s.scoresJson)
                            val keys = if (s.module == "speaking") {
                                listOf("FC" to "流利", "LR" to "词汇", "GRA" to "语法", "P" to "发音")
                            } else {
                                listOf("TR" to "任务", "TA" to "任务", "CC" to "衔接", "LR" to "词汇", "GRA" to "语法")
                            }
                            keys.filter { o.has(it.first) }
                        }.getOrDefault(emptyList())
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            dims.forEach { (k, label) ->
                                Text(
                                    "$label ${scoresOf(s, k)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Button(onClick = { onOpen(s.id) }) { Text("查看详情") }
                    }
                }
            }
        }
    }
}

private fun bandOf(s: Session): Double? = runCatching {
    val o = JSONObject(s.scoresJson)
    if (o.has("overall")) o.optDouble("overall", 0.0) else null
}.getOrNull()

private fun scoresOf(s: Session, key: String): String = runCatching {
    JSONObject(s.scoresJson).optDouble(key, 0.0).toString()
}.getOrDefault("暂无")
