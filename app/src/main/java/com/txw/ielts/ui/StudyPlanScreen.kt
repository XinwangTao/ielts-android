package com.txw.ielts.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 7 天课表页（纯展示壳，排期逻辑在 ui/StudyPlan）。
 *
 * 每日任务是一列待办，这里是按周铺开的课表：今天练什么、哪天模考、哪天休息一眼能看完。
 * 练习日可以「换一科」，换错了就地「恢复」，比重新生成整份计划轻。
 */
@Composable
fun StudyPlanScreen(
    plan: StudyPlan,
    onSwapDay: (String) -> Unit,
    onResetDay: (String) -> Unit,
    onResetAll: () -> Unit,
    onOpenModule: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        IosNavBar("7 天学习计划", onBack = onBack)

        Text(
            plan.headline,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                // 强度与总时长写在一起：计划页最常被问的就是「这周要花多久」
                "${plan.intensity} · 共约 ${plan.totalMinutes()} 分钟",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            if (plan.hasManualEdits()) {
                TextButton(onClick = onResetAll) { Text("全部恢复") }
            }
        }
        if (plan.taskDays().isNotEmpty()) {
            // 计划 vs 实际：只给排期不给进度，用户看不出这周到底练了没有。
            Text(
                "本周计划 ${plan.taskDays().size} 次 · 已完成 ${plan.doneCount()} 次",
                style = MaterialTheme.typography.bodySmall,
                color = if (plan.doneCount() > 0) MaterialTheme.colorScheme.tertiary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        plan.note?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(2.dp))

        if (plan.days.isEmpty()) {
            BookCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("暂时没有可排的日子", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "设置考试日期后，课表会从今天排到考试当天。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onOpenSettings) { Text("去设置考试日期") }
                }
            }
        }

        plan.days.forEach { day ->
            BookCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .width(2.5.dp)
                                .height(16.dp)
                                .background(
                                    when {
                                        // 已练过的日子换成「旧金」，和设置页的「掌握」同色
                                        day.done -> MaterialTheme.colorScheme.tertiary
                                        day.kind == PlanDayKind.PRACTICE -> MaterialTheme.colorScheme.primary
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                                    },
                                    RoundedCornerShape(1.dp),
                                )
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(day.label, style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            day.date,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.weight(1f))
                        when {
                            day.done -> Text(
                                "✓ 已完成",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                            day.minutes > 0 -> Text(
                                "约 ${day.minutes} 分钟",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Text(
                        day.title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.Medium,
                    )
                    if (day.detail.isNotBlank()) {
                        Text(
                            day.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // 休息/考试日没有可执行动作，不摆一个点了没反应的「去练」。
                        when (day.kind) {
                            PlanDayKind.PRACTICE -> TextButton(onClick = { onOpenModule(day.module) }) {
                                Text(if (day.done) "再练一次" else "去练")
                            }
                            PlanDayKind.MOCK -> TextButton(onClick = { onOpenModule("mock") }) {
                                Text(if (day.done) "再模考一次" else "去模考")
                            }
                            PlanDayKind.LIGHT -> TextButton(onClick = { onOpenModule("light") }) {
                                Text("看错题")
                            }
                            else -> Unit
                        }
                        if (day.editable) {
                            TextButton(
                                onClick = {
                                    if (day.swapped) onResetDay(day.date) else onSwapDay(day.date)
                                },
                            ) { Text(if (day.swapped) "恢复" else "换一科") }
                        }
                    }
                }
            }
        }

        Text(
            "课表随练习数据每天重算；「换一科」只改那一天，点「恢复」就还原。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
