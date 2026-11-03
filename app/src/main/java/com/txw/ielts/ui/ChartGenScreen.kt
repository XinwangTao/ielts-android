package com.txw.ielts.ui

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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.txw.ielts.data.ChartSpec
import kotlinx.coroutines.launch

/**
 * AI 出图题：随机生成数据规格 → Canvas 渲染 → 用户可换一组 → 带去写作页。
 * 图：ielts-ai-question_workflow（数据与题干同源，纯本地）。
 */
@Composable
fun ChartGenScreen(
    onUse: (ChartSpec) -> Unit,
    onBack: () -> Unit,
    /** 本地 LLM 润色题干：只改措辞，不改数据；返回 null 保持模板。 */
    polishPrompt: (suspend (ChartSpec) -> String?)? = null,
) {
    val kinds = listOf(
        "line" to "折线", "bar" to "柱状", "pie" to "饼图",
        "table" to "表格", "map" to "地图", "process" to "流程",
    )
    var kind by remember { mutableStateOf("line") }
    var spec by remember { mutableStateOf(ChartSpec.random("line")) }
    var polishing by remember { mutableStateOf(false) }
    var polishError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IosNavBar("AI 出图题", onBack = onBack)
        Text(
            "随机生成雅思 Task 1 数据题：图表和题干来自同一份数据，评分会按数据清单核对。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        IosSegmented(
            options = kinds.map { it.second },
            selected = kinds.indexOfFirst { it.first == kind }.coerceAtLeast(0),
            onSelect = {
                kind = kinds[it].first
                spec = ChartSpec.random(kind)
            },
            modifier = Modifier.fillMaxWidth(),
        )
        BookCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ChartView(spec)
                Text(spec.prompt(), style = MaterialTheme.typography.bodyMedium)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { spec = ChartSpec.random(kind) }, modifier = Modifier.weight(1f)) {
                Text("换一组")
            }
            Button(onClick = { onUse(spec) }, modifier = Modifier.weight(1f)) {
                Text("用这题写作")
            }
        }
        if (polishPrompt != null) {
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                enabled = !polishing,
                onClick = {
                    scope.launch {
                        polishing = true
                        polishError = null
                        val p = runCatching { polishPrompt(spec) }.getOrNull()
                        if (!p.isNullOrBlank()) {
                            spec = spec.copy(promptOverride = cleanPrompt(p).take(400))
                        } else {
                            polishError = "润色失败，保持模板题干"
                        }
                        polishing = false
                    }
                },
            ) { Text(if (polishing) "AI 润色中…（约 10-30 秒）" else "AI 润色题干（只改措辞，不改数据）") }
            polishError?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
        Text(
            "数据由本地随机生成，不消耗 API；同样的图表数字会出现在评分 prompt 里。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 模型偶尔把题干包成 {"prompt": "..."}；这里剥掉 JSON 外壳，只留句子。 */
private fun cleanPrompt(raw: String): String {
    val t = raw.trim()
    val start = t.indexOf('{')
    val end = t.lastIndexOf('}')
    if (start >= 0 && end > start) {
        runCatching {
            val o = org.json.JSONObject(t.substring(start, end + 1))
            for (k in listOf("prompt", "task", "sentence", "text")) {
                val v = o.optString(k)
                if (v.isNotBlank()) return v.trim()
            }
        }
    }
    return t.trim('"').trim()
}
