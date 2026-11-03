package com.txw.ielts.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.txw.ielts.data.Session
import com.txw.ielts.data.StreakCalc
import java.text.SimpleDateFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Date
import java.util.Locale

/** 书目录的一章。 */
private data class Chapter(
    val num: String,
    val title: String,
    val note: String,
    val onClick: () -> Unit,
)

@Composable
fun HomeScreen(
    sessions: List<Session>,
    onStartWriting: () -> Unit,
    onStartSpeaking: () -> Unit,
    onReview: () -> Unit,
    onCorpus: () -> Unit,
    onBank: () -> Unit,
    onAiGen: () -> Unit,
    onShadow: () -> Unit,
    onOpen: (Long) -> Unit,
    onSettings: () -> Unit,
    onModelEssays: () -> Unit = {},
    aiGenBusy: Boolean = false,
    aiGenStage: String? = null,
    aiGenReadyId: String? = null,
    aiGenError: String? = null,
    onOpenReadySet: (String) -> Unit = {},
    onDismissAiGenError: () -> Unit = {},
    plan: List<Pair<String, () -> Unit>> = emptyList(),
    targetBand: Float = 6.5f,
    examDate: String = "",
) {
    val streak = remember(sessions) { StreakCalc.currentStreak(sessions) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // —— 扉页 ——
        Text(
            "雅思练习册",
            style = MaterialTheme.typography.headlineMedium,
            fontFamily = FontFamily.Serif,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            "IELTS  PRACTICE  BOOK",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 3.sp,
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onSettings) { Text("设置") }
            Spacer(Modifier.weight(1f))
            Text(
                if (streak > 0) "连续打卡 $streak 天" else "今天，翻开第一页",
                style = MaterialTheme.typography.labelMedium,
                color = if (streak > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f), thickness = 1.dp)
        HorizontalDivider(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.18f), thickness = 0.5.dp)
        Spacer(Modifier.height(18.dp))

        // 目标差距：让用户每次打开都知道离目标还有多远（目标分与考试日期在设置里改）
        val target = targetBand.toDouble()
        val daysLeft = remember(examDate) {
            if (examDate.isBlank()) null
            else com.txw.ielts.data.ExamTarget.daysUntil(examDate, System.currentTimeMillis())
        }
        val recentBands = sessions.mapNotNull { bandValue(it.scoresJson) }.take(5)
        val avgBand = if (recentBands.isNotEmpty()) recentBands.average() else null
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "目标 Band %.1f".format(target),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                if (avgBand != null) {
                    if (avgBand >= target) "  ·  最近 ${recentBands.size} 次平均 %.1f，已达标".format(avgBand)
                    else "  ·  最近 ${recentBands.size} 次平均 %.1f，还差 %.1f".format(
                        avgBand, (target - avgBand).coerceAtLeast(0.0)
                    )
                } else {
                    "  ·  还没有评分记录"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // 倒计时只在设了日期且还没考完时出现；考完了就不要天天提。
        if (daysLeft != null && daysLeft >= 0) {
            Text(
                if (daysLeft == 0) "考试就在今天，别刷新题了" else "距考试 $daysLeft 天",
                style = MaterialTheme.typography.bodySmall,
                color = if (daysLeft <= 7) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // 四科 profile：L/R 按正确率映射，W/S 按最近 5 次 overall 平均
        Text(
            skillProfile(sessions).line(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        Spacer(Modifier.height(10.dp))

        // —— 今日任务：基于数据的推荐，碎片时间不用自己想练什么 ——
        if (plan.isNotEmpty()) {
            Text(
                "今日任务",
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Serif,
                modifier = Modifier.padding(bottom = 2.dp),
            )
            plan.forEach { (label, go) ->
                Row(
                    Modifier.fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable { go() }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 朱砂竖线 = 书页上的批注笔道（身份母题），标记「今天该练的」
                    Box(
                        Modifier
                            .width(2.5.dp)
                            .height(20.dp)
                            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(1.dp))
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // —— 目录标题 ——
        Text(
            "目　录",
            style = MaterialTheme.typography.titleLarge,
            fontFamily = FontFamily.Serif,
            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
        )

        ChapterRow("第一章", "写作练习", if (sessions.any { it.module == "writing" }) "已开篇" else "待开篇", onStartWriting)
        ChapterRow("第二章", "口语练习", if (sessions.any { it.module == "speaking" }) "已开篇" else "待开篇", onStartSpeaking)
        ChapterRow("第三章", "听力 · 阅读", "离线题库 · 精听精读", onBank)
        ChapterRow("第四章", "AI 出题", if (aiGenBusy) "生成中…" else if (aiGenReadyId != null) "新题已生成" else "现生成更难的原创题", onAiGen)
        ChapterRow("第五章", "词汇积累", "间隔复习 · 错题专项", onCorpus)
        ChapterRow("第六章", "口语跟读", "零 API 练发音", onShadow)

        Spacer(Modifier.height(14.dp))
        Text(
            "附　录",
            style = MaterialTheme.typography.titleMedium,
            fontFamily = FontFamily.Serif,
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
        )
        ChapterRow("一", "学习统计", "Band 趋势 · 高频问题", onReview)
        ChapterRow("二", "范文库", "收藏的高分范文", onModelEssays)

        // —— AI 生成状态（书页脚注式）——
        if (aiGenBusy || aiGenReadyId != null || aiGenError != null) {
            Spacer(Modifier.height(16.dp))
            BookCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        when {
                            aiGenReadyId != null -> "新章已生成"
                            aiGenError != null -> "生成未成功"
                            else -> "正在编写新章…"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = if (aiGenError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        when {
                            aiGenReadyId != null -> "已收录进「AI 出题」，去第四章做题"
                            aiGenError != null -> aiGenError
                            else -> aiGenStage ?: "后台编写中，不影响其他章节使用"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (aiGenReadyId != null) {
                        Button(onClick = { onOpenReadySet(aiGenReadyId) }) { Text("去查看") }
                    } else if (aiGenError != null) {
                        // 错误卡给个关闭入口，否则会一直占着首页直到下次生成。
                        TextButton(onClick = onDismissAiGenError) { Text("知道了") }
                    }
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.18f), thickness = 0.5.dp)
        Spacer(Modifier.height(10.dp))

        // —— 批注（练习记录）——
        Text(
            "练习批注",
            style = MaterialTheme.typography.titleMedium,
            fontFamily = FontFamily.Serif,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        if (sessions.isEmpty()) {
            Text("还没有留下批注，从第一章开始吧。", style = MaterialTheme.typography.bodySmall)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // 只组合最近 20 条：记录上百条后首页不再为全部历史付出重组成本。
                sessions.take(20).forEach { s ->
                    SessionRow(s) { onOpen(s.id) }
                }
                if (sessions.size > 20) {
                    Text(
                        "还有 ${sessions.size - 20} 条，去「学习统计」看全部",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }

        StreakCalendar(sessions)
    }
}

/** 目录章行：衬线章号 + 章名 + 右侧注记。 */
@Composable
private fun ChapterRow(num: String, title: String, note: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            num,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Serif,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(76.dp),
        )
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            fontFamily = FontFamily.Serif,
            modifier = Modifier.weight(1f),
        )
        Text(
            note,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Text("›", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider(
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.10f),
        thickness = 0.5.dp,
    )
}

/** GitHub 风格打卡热力图：最近 12 周，格子深浅 = 当天练习次数。 */
@Composable
private fun StreakCalendar(sessions: List<Session>) {
    val dayFmt = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }
    val counts = remember(sessions) {
        sessions.groupBy { dayFmt.format(Date(it.startedAt)) }.mapValues { it.value.size }
    }
    val today = LocalDate.now()
    val start = today.minusWeeks(11).with(DayOfWeek.MONDAY)

    var streak = 0
    var d = today
    if (counts[d.toString()] == null) d = d.minusDays(1)
    while (counts[d.toString()] != null) { streak++; d = d.minusDays(1) }

    BookCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("打卡", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(
                    if (streak > 0) "连续 $streak 天" else "今天开始第一练",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (streak > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                for (week in 0..11) {
                    val weekStart = start.plusWeeks(week.toLong())
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        for (day in 0..6) {
                            val date = weekStart.plusDays(day.toLong())
                            val count = if (date.isAfter(today)) -1 else (counts[date.toString()] ?: 0)
                            Box(
                                Modifier.size(13.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(heatColor(count)),
                            )
                        }
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("少", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                listOf(0, 1, 2, 4).forEach { c ->
                    Box(Modifier.size(11.dp).clip(RoundedCornerShape(2.dp)).background(heatColor(c)))
                }
                Text("多", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun heatColor(count: Int): Color = when {
    count < 0 -> Color.Transparent
    count == 0 -> MaterialTheme.colorScheme.surfaceVariant
    count == 1 -> MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
    count == 2 -> MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
    else -> MaterialTheme.colorScheme.primary
}

@Composable
private fun SessionRow(s: Session, onClick: () -> Unit) {
    val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    BookCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        // 色条要跟内容一样高：原来是写死的 46dp，标题+两行正文通常 80dp 以上，
        // 色条只盖住上半截，看起来像没对齐。用 IntrinsicSize.Min 让行高由内容决定，
        // 色条 fillMaxHeight 跟着撑满。
        Row(
            Modifier
                .height(IntrinsicSize.Min)
                .padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 14.dp),
        ) {
            Box(
                Modifier.width(4.dp)
                    .fillMaxHeight()
                    .background(moduleColor(s.module), RoundedCornerShape(2.dp)),
            )
            Spacer(Modifier.width(11.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(moduleLabel(s.module),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary)
                    Text("  " + fmt.format(Date(s.startedAt)), style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f))
                    Text(bandText(s.scoresJson), style = MaterialTheme.typography.labelLarge)
                }
                Text(s.topic.take(60), style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                Text(
                    if (s.module == "reading" || s.module == "listening") {
                        "${s.durationSec / 60} 分 ${s.durationSec % 60} 秒 · 离线判分"
                    } else if (s.module == "shadow") {
                        "跟读练习 · 零 API"
                    } else {
                        "${s.durationSec / 60} 分 ${s.durationSec % 60} 秒 · ${if (s.audioPath != null) "口述" else "键盘"} · ${wordCount(s.essayText.ifBlank { s.transcript })} 词"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun moduleColor(module: String): Color = when (module) {
    "speaking" -> MaterialTheme.colorScheme.secondary        // 墨蓝
    "reading", "listening" -> MaterialTheme.colorScheme.tertiary  // 旧金
    "shadow" -> Color(0xFF5C85A6)                            // 跟读：蓝灰（图表分类色）
    else -> MaterialTheme.colorScheme.primary                // 朱砂
}

internal fun wordCount(text: String): Int =
    text.split(Regex("\\s+")).count { it.isNotBlank() }

internal fun moduleLabel(module: String): String = when (module) {
    "speaking" -> "口语"
    "reading" -> "阅读"
    "listening" -> "听力"
    "shadow" -> "跟读"
    else -> "写作"
}

internal fun bandText(scoresJson: String): String {
    if (scoresJson.isBlank()) return "待评分"
    return runCatching {
        val o = org.json.JSONObject(scoresJson)
        when {
            o.has("correct") -> "${o.optInt("correct")}/${o.optInt("total")}"
            o.has("accuracy") -> "${o.optInt("accuracy")}%"
            else -> "Band " + o.optDouble("overall", 0.0)
        }
    }.getOrDefault("暂无")
}

/** 写作/口语记录的 Band 数值（非评分记录返回 null）。 */
internal fun bandValue(scoresJson: String): Double? = runCatching {
    val o = org.json.JSONObject(scoresJson)
    if (o.has("overall")) o.optDouble("overall", 0.0) else null
}.getOrNull()

