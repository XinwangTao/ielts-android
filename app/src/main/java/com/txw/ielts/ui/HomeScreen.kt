package com.txw.ielts.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.txw.ielts.data.Session
import com.txw.ielts.data.StreakCalc
import java.text.SimpleDateFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Date
import java.util.Locale

/** 今日任务的一行：标题 + 依据 + 预计时长，点击即开始。 */
data class PlanEntry(
    val title: String,
    val detail: String = "",
    val minutes: Int = 0,
    val onClick: () -> Unit,
)

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
    onOpenPlan: () -> Unit = {},
    aiGenBusy: Boolean = false,
    aiGenStage: String? = null,
    aiGenReadyId: String? = null,
    aiGenError: String? = null,
    onOpenReadySet: (String) -> Unit = {},
    onDismissAiGenError: () -> Unit = {},
    plan: List<PlanEntry> = emptyList(),
    targetBand: Float = 6.5f,
    examDate: String = "",
    skillTargets: SkillTargets? = null,
    recoveryNotice: String? = null,
    channelNotice: com.txw.ielts.llm.ChannelNotice? = null,
) {
    val streak = remember(sessions) { StreakCalc.currentStreak(sessions) }

    Column(
        // 页边距与其余 19 个屏保持一致（原来首页是 28dp，切到章节页会横跳 18px）
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 24.dp),
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

        // 启动时自动恢复上次未完成的评分：必须让用户看见，否则会以为 App 自己在后台卡住。
        if (!recoveryNotice.isNullOrBlank()) {
            BookCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("正在恢复评分", style = MaterialTheme.typography.labelLarge)
                    Text(
                        recoveryNotice,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
        }

        // 通道降级提示：断网/云端冷却/额度用完时说明「现在为什么用本地模型」，
        // 显式选云端又断网则把用户引到设置页改通道。
        if (channelNotice != null) {
            BookCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        channelNotice.title,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (channelNotice.needsAction) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        channelNotice.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (channelNotice.needsAction) {
                        TextButton(onClick = onSettings) { Text("去设置") }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
        }

        // 目标差距：让用户每次打开都知道离目标还有多远（目标分与考试日期在设置里改）
        val target = targetBand.toDouble()
        val daysLeft = remember(examDate) {
            if (examDate.isBlank()) null
            else com.txw.ielts.data.ExamTarget.daysUntil(examDate, System.currentTimeMillis())
        }
        val recentBands = sessions.mapNotNull { bandValue(it.scoresJson) }.take(5)
        val avgBand = if (recentBands.isNotEmpty()) recentBands.average() else null
        val profile = remember(sessions) { skillProfile(sessions) }
        // 达标判定用四科换算（官方口径），不再拿写作口语的平均分冒充总分：
        // 写作 8 分、口语 6 分混在一起平均 7.0，看着像达标，实际四科缺两科根本还算不出总分。
        val profileOverall = remember(profile) { profile.overall() }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "目标 Band %.1f".format(target),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                when {
                    profileOverall != null ->
                        if (profileOverall >= target - 0.001) "  ·  四科换算 %.1f，已达标".format(profileOverall)
                        else "  ·  四科换算 %.1f，还差 %.1f".format(
                            profileOverall, (target - profileOverall).coerceAtLeast(0.0)
                        )
                    // 四科还没凑齐（新手/只练过写作口语）时退回旧口径，但要写明这是写/说的平均。
                    avgBand != null -> "  ·  写/说最近 ${recentBands.size} 次平均 %.1f".format(avgBand)
                    else -> "  ·  还没有评分记录"
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
        // 四科 profile：L/R 按正确率映射，W/S 按最近 5 次 overall 平均；
        // 带目标时每科直接标出「差多少 / 已达标」，省得自己拿总分去猜哪科拖后腿。
        Text(
            profile.line(skillTargets),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        // 四科目标不等于总分目标时，把换算结果写出来：官方是先平均再向上取 0.5，
        // 四科都考 6.5 才是 6.5，一科 6.0 总分就掉到 6.0。
        subTargetLine(skillTargets, target)?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // 分节标题的排版规则：上方空隙要明显大于下方，标题才会「跟着自己的内容」；
        // 上下相等时标题会浮在两节中间，读者分不清它属于哪一段。
        Spacer(Modifier.height(24.dp))

        // —— 今日任务：基于数据的推荐，碎片时间不用自己想练什么 ——
        if (plan.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "今日任务",
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Serif,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
                Spacer(Modifier.weight(1f))
                val minutes = plan.sumOf { it.minutes }
                if (minutes > 0) {
                    Text(
                        "约 $minutes 分钟",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // 单日任务旁边直接给课表入口：想按周规划的人不用先翻到附录
                TextButton(onClick = onOpenPlan) { Text("7 天计划 ›") }
            }
            plan.forEach { entry ->
                Row(
                    Modifier.fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable { entry.onClick() }
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
                    Column(Modifier.weight(1f)) {
                        Text(entry.title, style = MaterialTheme.typography.bodyLarge)
                        if (entry.detail.isNotBlank()) {
                            Text(
                                entry.detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }

        // —— 目录标题 ——
        Text(
            "目　录",
            style = MaterialTheme.typography.titleLarge,
            fontFamily = FontFamily.Serif,
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
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
        // 与「学习统计」同页，但明确标出 AI 复盘入口，避免功能藏在看板顶部没人发现。
        ChapterRow("三", "AI 复盘", "本地模型 · 最近 7 天行动建议", onReview)
        // 今日任务为空时上面的入口不渲染；附录常驻一个，保证课表永远进得去。
        ChapterRow("四", "7 天计划", "按考期倒排一周 · 可换科目", onOpenPlan)

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
        )
        // 点线落在标题的基线上（居中会偏高约 5dp）；两侧各留 8dp 免得点贴到字
        DotLeader(
            Modifier.weight(1f).padding(horizontal = 8.dp).offset(y = 5.dp)
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
    // 打卡 heatmap 与首页顶部「连续打卡」必须同一套日期归一：这里原先是自己用
    // SimpleDateFormat 拼 key、自己 while 数连续，跨年/时区跟 StreakCalc 对不上时
    // 同一屏会显示两个不一样的连续天数。统一走 StreakCalc。
    val counts = remember(sessions) {
        sessions.groupBy { StreakCalc.dayKey(it.startedAt) }.mapValues { it.value.size }
    }
    val today = LocalDate.now()
    val streak = remember(counts, today) { StreakCalc.currentStreak(counts.keys, today) }

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

            // 周数自适应：手机 12 周，平板一路铺到 26 周。原来写死 12 周 + 13dp 格子，
            // 在这台 711dp 宽的平板上只占 189dp，右边空一大片，看着像没做完。
            BoxWithConstraints {
                val gap = 3.dp
                val labelW = 18.dp
                // usable 要减掉 labelW 和「标签与第一格之间」的那一个 gap：
                // spacedBy 是在所有相邻子项间插 gap，漏算它总宽就超一行，末列会被挤成 0 宽
                val usable = (maxWidth - labelW - gap).coerceAtLeast(120.dp)
                val weeks = ((usable + gap) / 17.dp).toInt().coerceIn(12, 26)
                // 上限 22dp：周数封顶后让格子继续长，正好铺满卡片内容宽，不留空档
                val cell = (((usable + gap) / weeks) - gap).coerceIn(9.dp, 22.dp)
                val start = today.minusWeeks((weeks - 1).toLong()).with(DayOfWeek.MONDAY)

                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    // 月份：只在「换月的那一周」标一次，避免每个格子都带字
                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        Spacer(Modifier.width(labelW))
                        for (w in 0 until weeks) {
                            val monday = start.plusWeeks(w.toLong())
                            val prev = if (w == 0) null else start.plusWeeks((w - 1).toLong())
                            Box(Modifier.width(cell), contentAlignment = Alignment.CenterStart) {
                                if (prev == null || prev.month != monday.month) {
                                    Text(
                                        "${monday.monthValue}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                    for (day in 0..6) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(gap),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.width(labelW), contentAlignment = Alignment.CenterStart) {
                                // 只标一/三/五，七天全标会挤成一竖列噪点
                                if (day == 0 || day == 2 || day == 4) {
                                    Text(
                                        listOf("一", "二", "三", "四", "五", "六", "日")[day],
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                    )
                                }
                            }
                            for (w in 0 until weeks) {
                                val date = start.plusWeeks(w.toLong()).plusDays(day.toLong())
                                val count = if (date.isAfter(today)) -1 else (counts[date.toString()] ?: 0)
                                Box(
                                    Modifier.size(cell)
                                        .clip(RoundedCornerShape(cell * 0.2f))
                                        .background(heatColor(count)),
                                )
                            }
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
                Text(s.topic, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
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

/** 写作/口语记录的 Band 数值（非评分记录、无效成绩返回 null）。 */
internal fun bandValue(scoresJson: String): Double? = runCatching {
    val o = org.json.JSONObject(scoresJson)
    if (!o.has("overall")) return@runCatching null
    val v = o.optDouble("overall", 0.0)
    // 0 分不是成绩（Band 0 = 未作答）：失败的评分会把四项全写成 0 并退回 pending_eval，
    // 此前这种记录被当成 done 计入均分，首页显示过「写/说最近 3 次平均 2.8」（0 + 1 + 7.5）。
    if (!com.txw.ielts.llm.ScoreGate.isValidBand(v)) null else v
}.getOrNull()

