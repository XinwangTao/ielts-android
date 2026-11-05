package com.txw.ielts.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.txw.ielts.data.Session
import com.txw.ielts.data.StatusLabel
import com.txw.ielts.data.StreakCalc
import org.json.JSONObject
import java.util.Calendar

private data class Point(val index: Int, val band: Double, val speaking: Boolean)

internal data class ErrorBucket(val label: String, val count: Int)

internal val WEAKNESS_RULES = listOf(
    // 中英关键词并存：历史 fixes 是英文，2026-11-02 起评分要求输出中文，聚合两边都要能命中。
    "主谓一致 / 时态" to listOf("agreement", "subject-verb", "tense", "verb form", "conjugation", "主谓", "时态", "动词形式"),
    "冠词与单复数" to listOf("article", "a/an", "plural", "countable", "冠词", "单复数", "可数"),
    "搭配与用词" to listOf("collocation", "word choice", "inaccurate", "imprecise", "phrasing", "vocabulary", "搭配", "用词", "选词", "词汇"),
    "衔接与分段" to listOf("cohesive", "cohesion", "paragraph", "linking", "connective", "衔接", "段落", "连接词", "连贯"),
    "字数与展开" to listOf("250 words", "under-length", "under 250", "develop", "extended example", "expand", "字数", "展开", "论据", "论证"),
    "重复用词" to listOf("repetition", "repetitive", "repeated", "重复"),
    "流利度与停顿" to listOf("hesitat", "pause", "filler", "fluency", "self-correct", "停顿", "填充", "流利"),
    "发音（估计）" to listOf("pronunciation", "stress", "pronounce", "发音", "重音"),
)

@Composable
fun ReviewScreen(
    sessions: List<Session>,
    onOpen: (Long) -> Unit,
    onBack: () -> Unit,
    aiReview: com.txw.ielts.llm.AiReview? = null,
    aiBusy: Boolean = false,
    aiError: String? = null,
    onRequestReview: () -> Unit = {},
) {
    val done = sessions.filter { it.status == "done" && it.scoresJson.isNotBlank() }
    val bands = done.mapNotNull { s ->
        val b = runCatching { JSONObject(s.scoresJson).optDouble("overall", 0.0) }.getOrDefault(0.0)
        if (b > 0) s to b else null
    }.sortedBy { it.first.startedAt }   // Room 返回倒序，画图前必须转成时间正序
    val thisWeek = bands.count { it.first.startedAt >= weekStart() }
    val avg = if (bands.isEmpty()) 0.0 else bands.sumOf { it.second } / bands.size
    val streak = StreakCalc.currentStreak(sessions)
    val buckets = topErrors(sessions)
    val isWide = LocalConfiguration.current.screenWidthDp >= 600

    val statsRow: @Composable () -> Unit = {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            StatTile("本周练习", "$thisWeek", "次", Modifier.weight(1f))
            StatTile("平均 Band", if (bands.isEmpty()) "暂无" else "%.1f".format(avg), "", Modifier.weight(1f), highlight = true)
            StatTile("连续天数", "$streak", "天", Modifier.weight(1f))
        }
    }
    val trendCard: @Composable () -> Unit = {
        BookCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Band 趋势（最近 ${minOf(bands.size, 20)} 次）", style = MaterialTheme.typography.titleSmall)
                if (bands.size < 2) {
                    Text("至少练两次才能画趋势。", style = MaterialTheme.typography.bodySmall)
                } else {
                    BandChart(bands.takeLast(20))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        LegendDot("写作", MaterialTheme.colorScheme.primary)
                        LegendDot("口语", MaterialTheme.colorScheme.secondary)
                    }
                }
            }
        }
    }
    // abandoned = 既无文本也无音频的中断记录，无法评分，不应占用待处理列表。
    val pending = sessions.filter { it.status != "done" && it.status != "abandoned" }
    val dailyCard: @Composable () -> Unit = { DailyBarsCard(sessions) }
    val errorsSection: @Composable () -> Unit = {
        Text("高频问题（按你历史批注统计）", style = MaterialTheme.typography.titleMedium)
        if (buckets.isEmpty()) {
            Text("还没有足够的批注数据。", style = MaterialTheme.typography.bodySmall)
        } else {
            BookCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val max = buckets.maxOf { it.count }.coerceAtLeast(1)
                    buckets.take(6).forEach { b -> ErrorBar(b, max) }
                }
            }
        }
    }
    val pendingSection: @Composable () -> Unit = {
        if (pending.isNotEmpty()) {
            Text("待处理", style = MaterialTheme.typography.titleMedium)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.height(180.dp)) {
                items(pending, key = { it.id }) { s ->
                    BookCard(onClick = { onOpen(s.id) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                s.topic.ifBlank { "（未命名练习）" },
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(StatusLabel.label(s.status), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IosNavBar("复盘看板", onBack = onBack)

        AiReviewSection(aiReview, aiBusy, aiError, onRequestReview)

        if (isWide) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(Modifier.weight(0.55f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    statsRow()
                    trendCard()
                    dailyCard()
                }
                Column(Modifier.weight(0.45f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    errorsSection()
                    pendingSection()
                }
            }
        } else {
            statsRow()
            trendCard()
            dailyCard()
            errorsSection()
            pendingSection()
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, unit: String, modifier: Modifier, highlight: Boolean = false) {
    BookCard(
        modifier,
        colors = CardDefaults.cardColors(
            containerColor = if (highlight) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surface
        )
    ) {
        Column(Modifier.padding(vertical = 14.dp, horizontal = 10.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    value,
                    style = if (highlight) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Serif,
                )
                if (unit.isNotEmpty()) Text(" $unit", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun LegendDot(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Text("  $label", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun BandChart(bands: List<Pair<Session, Double>>) {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val grid = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
    Row(Modifier.fillMaxWidth()) {
        Column(Modifier.width(28.dp).height(170.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text("9", style = MaterialTheme.typography.labelSmall)
            Text("7", style = MaterialTheme.typography.labelSmall)
            Text("5", style = MaterialTheme.typography.labelSmall)
            Text("3", style = MaterialTheme.typography.labelSmall)
        }
        Canvas(Modifier.weight(1f).height(170.dp)) {
            val n = bands.size
            val w = size.width
            val h = size.height
            fun yFor(b: Double): Float {
                val clamped = b.coerceIn(3.0, 9.0)
                return h - ((clamped - 3.0) / 6.0 * h).toFloat()
            }
            listOf(3.0, 5.0, 7.0, 9.0).forEach { v ->
                drawLine(grid, Offset(0f, yFor(v)), Offset(w, yFor(v)), strokeWidth = 1f)
            }
            val pts = bands.mapIndexed { i, (s, b) ->
                Point(
                    index = i,
                    band = b,
                    speaking = s.module == "speaking",
                )
            }
            val step = if (n > 1) w / (n - 1) else w / 2
            fun xFor(i: Int) = if (n > 1) i * step else w / 2

            // 同模块相邻点连线，避免跨模块误导
            listOf(false to primary, true to secondary).forEach { (isSpeaking, color) ->
                val path = Path()
                var started = false
                pts.forEach { p ->
                    if (p.speaking != isSpeaking) {
                        started = false
                        return@forEach
                    }
                    val x = xFor(p.index)
                    val y = yFor(p.band)
                    if (!started) {
                        path.moveTo(x, y); started = true
                    } else {
                        path.lineTo(x, y)
                    }
                }
                drawPath(path, color, style = Stroke(width = 3f))
            }
            pts.forEach { p ->
                drawCircle(
                    color = if (p.speaking) secondary else primary,
                    radius = 6f,
                    center = Offset(xFor(p.index), yFor(p.band)),
                )
            }
        }
    }
}

@Composable
private fun ErrorBar(bucket: ErrorBucket, max: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row {
            Text(bucket.label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            Text("${bucket.count} 次", style = MaterialTheme.typography.labelSmall)
        }
        Box(
            Modifier.fillMaxWidth().height(8.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
        ) {
            Box(
                Modifier.fillMaxWidth(bucket.count.toFloat() / max)
                    .height(8.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
            )
        }
    }
}

private fun weekStart(): Long {
    val cal = Calendar.getInstance().apply {
        firstDayOfWeek = Calendar.MONDAY
        set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    return cal.timeInMillis
}

internal fun topErrors(sessions: List<Session>): List<ErrorBucket> {
    val text = sessions.joinToString(" ") { s ->
        runCatching {
            val o = JSONObject(s.fixesJson)
            val arr = o.optJSONArray("fixes") ?: return@runCatching ""
            (0 until arr.length()).joinToString(" ") { arr.get(it).toString() }
        }.getOrDefault("")
    }.lowercase()
    if (text.isBlank()) return emptyList()
    return WEAKNESS_RULES.map { (label, kws) ->
        ErrorBucket(label, kws.sumOf { kw -> Regex(Regex.escape(kw)).findAll(text).count() })
    }.filter { it.count > 0 }.sortedByDescending { it.count }
}

/** AI 复盘卡：最近 7 天总结 + 3 个当天可执行的行动 + 一句鼓励。 */
@Composable
private fun AiReviewSection(
    review: com.txw.ielts.llm.AiReview?,
    busy: Boolean,
    error: String?,
    onRequest: () -> Unit,
) {
    BookCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("AI 复盘 · 本地模型", style = MaterialTheme.typography.labelLarge)
            if (review != null) {
                if (review.summary.isNotBlank()) {
                    Text(review.summary, style = MaterialTheme.typography.bodyMedium)
                }
                review.tasks.forEachIndexed { i, t ->
                    Text("${i + 1}. ${t.title}", style = MaterialTheme.typography.titleSmall)
                    if (t.why.isNotBlank()) {
                        Text("为什么：${t.why}", style = MaterialTheme.typography.bodySmall)
                    }
                    if (t.how.isNotBlank()) {
                        Text("怎么做：${t.how}", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (review.encouragement.isNotBlank()) {
                    Text(
                        review.encouragement,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (review.summary.isBlank() && review.tasks.isEmpty() && review.raw.isNotBlank()) {
                    Text(review.raw.take(1200), style = MaterialTheme.typography.bodySmall)
                }
            }
            OutlinedButton(enabled = !busy, onClick = onRequest) {
                Text(
                    when {
                        busy -> "复盘生成中…"
                        review == null -> "让本地模型复盘最近 7 天"
                        else -> "重新复盘"
                    }
                )
            }
            if (busy) LocalAiBusy("本地模型复盘最近练习中（请留在 App 内）")
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
