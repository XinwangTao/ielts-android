package com.txw.ielts.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CardElevation
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.txw.ielts.data.Session
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 「纸片」卡片：木色细线框 + 零投影。
 *
 * 实测（设备 cc75218b，density 360）：卡片填充与纸底的对比只有 1.28:1（浅）/ 1.25:1（深），
 * 边界几乎读不出来；加一条 1dp 线后边缘对比升到 1.82:1（浅）/ 1.68:1（深），
 * 线始终是纸底、卡填、线三者中最暗的一层，所以读作「画上去的框」而不是投影。
 * 印刷品的解法本来就是细线而不是阴影，视差着色器在纸质感上会显脏。
 */
@Composable
fun BookCard(
    modifier: Modifier = Modifier,
    shape: Shape = CardDefaults.shape,
    colors: CardColors = CardDefaults.cardColors(),
    elevation: CardElevation = CardDefaults.cardElevation(),
    border: BorderStroke? = defaultBookBorder(),
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier,
        shape = shape,
        colors = colors,
        elevation = elevation,
        border = border,
        content = content,
    )
}

/** 可点击的纸片（整块可点，如记录条目）。 */
@Composable
fun BookCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = CardDefaults.shape,
    colors: CardColors = CardDefaults.cardColors(),
    elevation: CardElevation = CardDefaults.cardElevation(),
    border: BorderStroke? = defaultBookBorder(),
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = shape,
        colors = colors,
        elevation = elevation,
        border = border,
        content = content,
    )
}

@Composable
private fun defaultBookBorder(): BorderStroke = BorderStroke(1.dp, bookRuleColor())

/**
 * 最近 14 天做题量柱状图（书页脚注风格，深色柱=今天）。
 * 原本在首页与统计页各存一份完全相同的拷贝，且首页那份从未被调用。
 */
@Composable
fun DailyBarsCard(sessions: List<Session>) {
    val primary = MaterialTheme.colorScheme.primary
    val today = LocalDate.now()
    val counts = remember(sessions) {
        sessions.groupBy {
            Instant.ofEpochMilli(it.startedAt)
                .atZone(ZoneId.systemDefault()).toLocalDate().toString()
        }.mapValues { it.value.size }
    }
    val max = (counts.values.maxOrNull() ?: 0).coerceAtLeast(1)
    BookCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("最近 14 天做题量", style = MaterialTheme.typography.titleSmall)
            Row(Modifier.fillMaxWidth().height(120.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (0..13).forEach { i ->
                    val date = today.minusDays((13 - i).toLong())
                    val c = counts[date.toString()] ?: 0
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            Box(
                                Modifier.align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .fillMaxHeight(if (c == 0) 0.02f else c.toFloat() / max)
                                    .background(
                                        if (date == today) primary else primary.copy(alpha = 0.4f),
                                        RoundedCornerShape(4.dp),
                                    ),
                            )
                        }
                        Text(
                            if (date == today) "今" else "${date.dayOfMonth}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (date == today) primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
