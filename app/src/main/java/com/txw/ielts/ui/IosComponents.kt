package com.txw.ielts.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * iOS 经典 UISegmentedControl：灰底胶囊，选中段白色浮起 + 阴影。
 * 适合「单选、短标签」的切换（Task 类型、倍速、Part、ASR 模型等）。
 */
@Composable
fun IosSegmented(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val innerShape = RoundedCornerShape(8.dp)
    val outerShape = RoundedCornerShape(10.dp)
    val scroll = rememberScrollState()
    val density = androidx.compose.ui.platform.LocalDensity.current
    BoxWithConstraints(
        modifier = modifier
            .clip(outerShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(2.dp),
    ) {
        // 每段至少 56dp：段数多到一屏放不下时改为横向滚动，而不是把最后几段裁掉。
        // 实测 360dp 手机上 AI 出题的 7 档难度、设置页的模型列表都会超过一屏。
        val scrollable = maxWidth < 56.dp * options.size
        // 重新进入页面时把当前选中项滚到可见区域，否则 9.0 / 最后一个模型会藏在屏幕外。
        // scroll.maxValue 作为 key：首帧布局完成后 maxValue 才非 0，届时再滚一次。
        LaunchedEffect(scrollable, selected, scroll.maxValue, options.size) {
            if (scrollable && selected in options.indices && scroll.maxValue > 0) {
                val target = with(density) { (56.dp * selected).roundToPx() }
                scroll.animateScrollTo(target.coerceAtMost(scroll.maxValue))
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (scrollable) Modifier.horizontalScroll(scroll) else Modifier),
        ) {
            options.forEachIndexed { i, label ->
                val isSel = i == selected
                Box(
                    modifier = Modifier
                        .then(if (scrollable) Modifier.widthIn(min = 56.dp) else Modifier.weight(1f))
                        .minimumInteractiveComponentSize()   // 触摸目标 ≥48dp（R-03）
                    .then(
                        if (isSel) Modifier.shadow(1.dp, innerShape)
                            .background(MaterialTheme.colorScheme.surface, innerShape)
                        else Modifier
                    )
                    .clip(innerShape)
                    // 用 selectable 而不是 clickable：TalkBack 能播报「已选中」并给出 Tab 语义；
                    // 禁用时整体降透明度，用户能看出当前不可点。
                    .selectable(selected = isSel, enabled = enabled, role = Role.Tab) { onSelect(i) }
                    .alpha(if (enabled) 1f else 0.45f)
                    .padding(vertical = 7.dp, horizontal = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isSel) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        }
    }
}

/**
 * iOS 导航栏：左侧 chevron 返回 + 大标题 + 可选右侧内容（如计时）。
 *
 * 窄屏（手机 360dp）下右侧操作和标题抢同一行，会把标题压成一列竖排字
 * （实测「口语练习室」被挤成每行一个字）。这里在 <420dp 时把 trailing 移到
 * 标题下方独立一行，并用 FlowRow 允许操作换行，标题永远单行省略。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IosNavBar(title: String, onBack: () -> Unit, trailing: @Composable () -> Unit = {}) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val compact = maxWidth < 420.dp
        if (compact) {
            Column(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onBack) {
                        Text("‹", fontSize = 30.sp, lineHeight = 30.sp)
                    }
                    Text(
                        title,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) { trailing() }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) {
                    Text("‹", fontSize = 30.sp, lineHeight = 30.sp)
                }
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                trailing()
            }
        }
    }
}

/**
 * iOS 风格输入框：圆角灰底填充、无下划线指示线（对比 Material Outlined 的描边样式）。
 */
@Composable
fun IosTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    singleLine: Boolean = false,
    enabled: Boolean = true,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    supportingText: (@Composable () -> Unit)? = null,
) {
    val shape = RoundedCornerShape(12.dp)
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.clip(shape),
        enabled = enabled,
        singleLine = singleLine,
        visualTransformation = visualTransformation,
        shape = shape,
        label = if (label != null) ({ Text(label) }) else null,
        supportingText = supportingText,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
        ),
    )
}
