package com.txw.ielts.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.txw.ielts.data.ChartSpec
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 程序化图表渲染（图：ielts-ai-question_workflow 的「Canvas 渲染图表」）。
 * 纯 Canvas 绘制、无图片资源；四种题型共用同一份 ChartSpec，保证图表与题干数字一致。
 * 注意：所有颜色必须在 @Composable 阶段取好再传入 DrawScope（DrawScope 内不能调 MaterialTheme）。
 */
@Composable
fun ChartView(spec: ChartSpec, modifier: Modifier = Modifier) {
    val c1 = MaterialTheme.colorScheme.primary
    val c2 = MaterialTheme.colorScheme.secondary
    val c3 = MaterialTheme.colorScheme.tertiary
    val axis = MaterialTheme.colorScheme.outlineVariant
    val text = MaterialTheme.colorScheme.onSurface
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val pieColors = listOf(c1, c2, c3, c1.copy(alpha = 0.55f), c2.copy(alpha = 0.55f))
    Canvas(modifier.fillMaxWidth().height(260.dp)) {
        val paint = android.graphics.Paint().apply { isAntiAlias = true; textSize = 11.sp.toPx() }
        fun label(s: String, x: Float, y: Float, color: Color, sizeSp: Float) {
            paint.color = color.toArgb()
            paint.textSize = sizeSp.sp.toPx()
            drawContext.canvas.nativeCanvas.drawText(s, x, y, paint)
        }
        when (spec.kind) {
            "line" -> drawLineChart(spec, c1, c2, axis, dim, ::label)
            "bar" -> drawBarChart(spec, c1, c2, axis, dim, ::label)
            "pie" -> drawPieChart(spec, pieColors, text, ::label)
            "map" -> drawMap(spec, c1, c2, axis, text, dim, ::label)
            "process" -> drawProcess(spec, c1, c2, axis, text, dim, ::label)
            else -> drawTable(spec, c1, axis, text, dim, ::label)
        }
        // 标题（统一放在底部之外，图表本体已含单位/图例）
        if (spec.title.isNotBlank()) {
            paint.color = text.toArgb()
            paint.textSize = 10.sp.toPx()
            drawContext.canvas.nativeCanvas.drawText(spec.title.take(52), 6f, size.height - 4f, paint)
        }
    }
}

private const val LEFT = 40f
private const val RIGHT = 12f
private const val TOP = 18f
private const val BOTTOM = 42f

private fun DrawScope.drawLineChart(
    spec: ChartSpec,
    c1: Color, c2: Color, axis: Color, dim: Color,
    label: (String, Float, Float, Color, Float) -> Unit,
) {
    val w = size.width; val h = size.height
    val plotW = w - LEFT - RIGHT; val plotH = h - TOP - BOTTOM
    val left = LEFT; val top = TOP
    for (v in listOf(0, 25, 50, 75, 100)) {
        val y = top + plotH - plotH * v / 100f
        drawLine(axis, Offset(left, y), Offset(left + plotW, y), strokeWidth = 1f)
        label(v.toString(), 6f, y + 4f, dim, 9f)
    }
    val years = spec.years
    if (years.size < 2) return
    val dx = plotW / (years.size - 1)
    years.forEachIndexed { i, y ->
        label(y.toString(), left + i * dx - 12f, h - BOTTOM + 14f, dim, 9f)
    }
    spec.series.take(2).forEachIndexed { si, s ->
        val color = if (si == 0) c1 else c2
        val pts = s.values.mapIndexed { i, v ->
            Offset(left + i * dx, top + plotH - plotH * v.toFloat() / 100f)
        }
        for (i in 1 until pts.size) drawLine(color, pts[i - 1], pts[i], strokeWidth = 2.5f)
        pts.forEach { p -> drawCircle(color, radius = 3.5f, center = p) }
        val ly = TOP + 6f + si * 22f
        drawLine(color, Offset(w - 120f, ly), Offset(w - 104f, ly), strokeWidth = 3f)
        label(s.name, w - 100f, ly + 4f, dim, 9f)
    }
}

private fun DrawScope.drawBarChart(
    spec: ChartSpec,
    c1: Color, c2: Color, axis: Color, dim: Color,
    label: (String, Float, Float, Color, Float) -> Unit,
) {
    val w = size.width; val h = size.height
    val plotW = w - LEFT - RIGHT; val plotH = h - TOP - BOTTOM
    val left = LEFT; val top = TOP
    for (v in listOf(0, 25, 50, 75, 100)) {
        val y = top + plotH - plotH * v / 100f
        drawLine(axis, Offset(left, y), Offset(left + plotW, y), strokeWidth = 1f)
        label(v.toString(), 6f, y + 4f, dim, 9f)
    }
    val n = spec.categories.size
    if (n == 0) return
    val groupW = plotW / n
    val barW = groupW * 0.28f
    spec.categories.forEachIndexed { i, cat ->
        val cx = left + groupW * i + groupW / 2f
        spec.series.take(2).forEachIndexed { si, s ->
            val v = s.values.getOrElse(i) { 0.0 }.toFloat().coerceIn(0f, 100f)
            val bh = plotH * v / 100f
            val x = cx - barW - 2f + si * (barW + 4f)
            drawRect(
                color = if (si == 0) c1 else c2,
                topLeft = Offset(x, top + plotH - bh),
                size = Size(barW, bh),
            )
        }
        val text = if (cat.length > 10) cat.take(9) + "…" else cat
        label(text, cx - text.length * 3f, h - BOTTOM + 14f, dim, 9f)
    }
}

private fun DrawScope.drawPieChart(
    spec: ChartSpec,
    colors: List<Color>,
    textColor: Color,
    label: (String, Float, Float, Color, Float) -> Unit,
) {
    val w = size.width; val h = size.height
    val values = spec.series.firstOrNull()?.values ?: return
    val total = values.sum().takeIf { it > 0 } ?: return
    val radius = min(w * 0.5f, h) * 0.26f
    val cx = w * 0.36f; val cy = h * 0.42f
    var start = -90f
    values.forEachIndexed { i, v ->
        val sweep = (v / total * 360f).toFloat()
        drawArc(
            color = colors[i % colors.size],
            startAngle = start,
            sweepAngle = sweep,
            useCenter = true,
            topLeft = Offset(cx - radius, cy - radius),
            size = Size(radius * 2, radius * 2),
        )
        if (sweep > 18f) {
            val mid = Math.toRadians((start + sweep / 2).toDouble())
            label(
                "${Math.round(v)}%",
                (cx + radius * 0.55f * cos(mid)).toFloat() - 10f,
                (cy + radius * 0.55f * sin(mid)).toFloat() + 4f,
                Color.White, 10f,
            )
        }
        start += sweep
    }
    // 图例放到右侧竖排，避免和底部标题、相邻条目重叠
    val legendX = w * 0.68f
    val legendTop = h * 0.16f
    spec.categories.forEachIndexed { i, cat ->
        val y = legendTop + i * 22f
        drawRect(colors[i % colors.size], Offset(legendX, y - 8f), Size(8f, 8f))
        label(cat, legendX + 12f, y, textColor, 10f)
    }
}

private fun DrawScope.drawTable(
    spec: ChartSpec,
    accent: Color,
    axis: Color,
    textColor: Color,
    dim: Color,
    label: (String, Float, Float, Color, Float) -> Unit,
) {
    val w = size.width; val h = size.height
    val rows = spec.categories.size
    if (rows == 0) return
    val colW = w / (spec.series.size + 1)
    val rowH = (h - 34f) / (rows + 1)
    drawRect(accent.copy(alpha = 0.12f), Offset(0f, 0f), Size(w, rowH))
    label("Category", 6f, rowH * 0.65f, textColor, 10f)
    spec.series.forEachIndexed { si, s ->
        label(s.name, colW * (si + 1) + 6f, rowH * 0.65f, textColor, 10f)
    }
    spec.categories.forEachIndexed { r, cat ->
        val y = rowH * (r + 1)
        label(cat, 6f, y + rowH * 0.65f, dim, 10f)
        spec.series.forEachIndexed { si, s ->
            val v = s.values.getOrElse(r) { 0.0 }
            val shown = if (v % 1.0 == 0.0) v.toInt().toString() else v.toString()
            label(shown, colW * (si + 1) + 6f, y + rowH * 0.65f, dim, 10f)
        }
        drawLine(axis, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
    }
    drawLine(axis, Offset(0f, 0f), Offset(w, 0f), strokeWidth = 1f)
    for (c in 0..spec.series.size) {
        drawLine(axis, Offset(colW * c, 0f), Offset(colW * c, h - 34f), strokeWidth = 1f)
    }
}

/** 地图题：左右两栏对比（2000 / today），状态码 0=空地、1=已有、2=新建。 */
private fun DrawScope.drawMap(
    spec: ChartSpec,
    c1: Color, c2: Color, axis: Color, textColor: Color, dim: Color,
    label: (String, Float, Float, Color, Float) -> Unit,
) {
    val w = size.width; val h = size.height
    val panelW = w / 2f - 16f
    val top = 22f
    val panelH = h - top - 34f
    val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 4f))
    spec.series.take(2).forEachIndexed { pi, s ->
        val left = pi * (panelW + 24f) + 4f
        label(s.name, left + 6f, 15f, textColor, 10f)
        val cols = 3
        val rows = 2
        val cellW = panelW / cols
        val cellH = panelH / rows
        spec.categories.forEachIndexed { i, cat ->
            if (i >= cols * rows) return@forEachIndexed
            val col = i % cols
            val row = i / cols
            val x = left + col * cellW + 4f
            val y = top + row * cellH + 4f
            val bw = cellW - 8f
            val bh = cellH - 8f
            when (val st = s.values.getOrElse(i) { 0.0 }) {
                in 2.0..99.0 -> {
                    drawRect(c1.copy(alpha = 0.85f), Offset(x, y), Size(bw, bh))
                    label(cat, x + 4f, y + bh / 2 + 4f, Color.White, 8f)
                }
                in 0.5..1.5 -> {
                    drawRect(c2.copy(alpha = 0.45f), Offset(x, y), Size(bw, bh))
                    label(cat, x + 4f, y + bh / 2 + 4f, textColor, 8f)
                }
                else -> {
                    drawRect(axis.copy(alpha = 0.12f), Offset(x, y), Size(bw, bh))
                    drawRect(
                        axis, Offset(x, y), Size(bw, bh),
                        style = Stroke(width = 1.2f, pathEffect = dash),
                    )
                    label(cat, x + 4f, y + bh / 2 + 4f, dim, 8f)
                }
            }
        }
    }
}

/** 流程图：5 个圆角盒子 + 箭头，步骤文本自动分两行。 */
private fun DrawScope.drawProcess(
    spec: ChartSpec,
    c1: Color, c2: Color, axis: Color, textColor: Color, dim: Color,
    label: (String, Float, Float, Color, Float) -> Unit,
) {
    val w = size.width; val h = size.height
    val n = spec.categories.size
    if (n == 0) return
    val gap = 12f
    val boxW = (w - 8f - gap * (n - 1)) / n
    val boxH = 58f
    val y = h / 2 - boxH / 2 - 8f
    val radius = CornerRadius(6f, 6f)
    spec.categories.forEachIndexed { i, step ->
        val x = 4f + i * (boxW + gap)
        drawRoundRect(c1.copy(alpha = 0.14f), Offset(x, y), Size(boxW, boxH), cornerRadius = radius)
        drawRoundRect(c1, Offset(x, y), Size(boxW, boxH), cornerRadius = radius, style = Stroke(width = 1.5f))
        val words = step.split(" ")
        val mid = (words.size + 1) / 2
        val line1 = words.take(mid).joinToString(" ")
        val line2 = words.drop(mid).joinToString(" ")
        label(line1, x + 5f, y + boxH / 2 - 3f, textColor, 8f)
        if (line2.isNotBlank()) label(line2, x + 5f, y + boxH / 2 + 11f, textColor, 8f)
        if (i < n - 1) {
            val ay = y + boxH / 2
            val ax = x + boxW + 2f
            drawLine(axis, Offset(ax, ay), Offset(ax + gap - 5f, ay), strokeWidth = 2f)
            drawLine(axis, Offset(ax + gap - 9f, ay - 4f), Offset(ax + gap - 5f, ay), strokeWidth = 2f)
            drawLine(axis, Offset(ax + gap - 9f, ay + 4f), Offset(ax + gap - 5f, ay), strokeWidth = 2f)
        }
    }
}
