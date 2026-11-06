package com.txw.ielts.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.txw.ielts.data.FirstRunPlan
import com.txw.ielts.data.GuideStep

/**
 * 首启引导页（纯展示壳，判定逻辑在 data/FirstRun）。
 *
 * 目标只有一个：让新装机用户在三步之内知道「现在能不能出分、还差什么」。
 * 因此每一步都带状态（✓ / 待办）和可点的下一步，而不是一段说明书。
 */
@Composable
fun FirstRunScreen(
    plan: FirstRunPlan,
    onOpenSettings: () -> Unit,
    onStartPractice: () -> Unit,
    onDone: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IosNavBar("新手引导", onBack = onBack)

        Text(
            "雅思随身练",
            style = MaterialTheme.typography.headlineMedium,
            fontFamily = FontFamily.Serif,
        )
        Text(
            plan.headline,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )

        plan.steps.forEachIndexed { index, step ->
            BookCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (step.ready) "✓" else "${index + 1}",
                            style = MaterialTheme.typography.titleMedium,
                            color = if (step.ready) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        Text(
                            step.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    Text(
                        step.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    step.action?.let { label ->
                        // 只有「去做题」进题库，其余动作（去设置）都指向设置页。
                        val toSettings = step.id != com.txw.ielts.data.FirstRun.STEP_PRACTICE
                        OutlinedButton(
                            onClick = { if (toSettings) onOpenSettings() else onStartPractice() },
                        ) { Text(label) }
                    }
                }
            }
        }

        Text(
            plan.detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(4.dp))
        // 引导不是关卡：任何一步没配好也能先进去用，缺的项在设置页随时补。
        Text(
            if (plan.allReady) "三步都打勾了，去首页开始今天的任务。"
            else "还有 ${plan.pending} 步没完成也能先进去用；设置页底部可重新打开本引导。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = onDone,
        ) { Text("开始使用") }
    }
}
