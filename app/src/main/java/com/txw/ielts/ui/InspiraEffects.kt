package com.txw.ielts.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Inspira UI（Vue/Tailwind）的视觉母题在 Compose 里的轻量复刻。
 *
 * 不能直接复用它的组件：那是 Web 的 Vue 组件 + Tailwind/CSS/Three.js。这里只搬
 * Aurora Background / Meteors / Shimmer Button / Glow Border 四种效果，并让它们
 * 跟着「练习册」主题取色（纸白/墨色/朱砂），不引入霓虹色板，避免把书页感盖掉。
 *
 * 性能：全部是 Canvas + 单个无限动画，无模糊滤镜（minSdk 26 不保证 RenderEffect），
 * 粒子/流星数量固定且很少；只在首页顶部这一小块区域启用。
 */

/** Aurora 背景：三团缓慢漂移的径向光晕，底部渐隐回纸面。 */
@Composable
fun AuroraBackdrop(
    modifier: Modifier = Modifier,
    dark: Boolean = isSystemInDarkTheme(),
) {
    val colors = MaterialTheme.colorScheme
    val t = rememberInfiniteTransition(label = "aurora")
    val phase by t.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(24_000, easing = LinearEasing), RepeatMode.Restart),
        label = "auroraPhase",
    )
    val alpha = if (dark) 0.18f else 0.10f
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas
        val p = phase * 2f * PI.toFloat()
        fun blob(cx: Float, cy: Float, radius: Float, color: Color, alphaScale: Float = 1f) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(color.copy(alpha = alpha * alphaScale), Color.Transparent),
                    center = Offset(cx, cy),
                    radius = radius,
                ),
                radius = radius,
                center = Offset(cx, cy),
            )
        }
        blob(w * (0.16f + 0.05f * sin(p)), h * (0.28f + 0.05f * cos(p * 0.8f)), w * 0.62f, colors.primary)
        // 墨蓝只做一点冷色对比：与暖色同强度时会在纸面上混出灰绿。
        blob(
            w * (0.84f + 0.05f * cos(p * 0.7f)), h * (0.20f + 0.07f * sin(p * 1.1f)),
            w * 0.55f, colors.secondary, alphaScale = 0.4f,
        )
        // 旧金只做一点点暖色偏移：与朱砂/墨蓝同强度时混出来会发橄榄灰。
        blob(
            w * (0.55f + 0.07f * sin(p * 1.3f)), h * (0.52f + 0.05f * cos(p)),
            w * 0.50f, colors.tertiary, alphaScale = 0.45f,
        )
        // 底部渐隐：不让光晕扩散到正文和目录，纸面仍是主角。
        drawRect(
            Brush.verticalGradient(
                0f to Color.Transparent,
                0.55f to Color.Transparent,
                1f to colors.surface,
            )
        )
    }
}

/** 流星：深色模式下首页顶部几道极淡的斜线，浅色纸面上不启用。 */
@Composable
fun MeteorField(
    modifier: Modifier = Modifier,
    enabled: Boolean = isSystemInDarkTheme(),
    count: Int = 5,
) {
    if (!enabled) return
    val color = MaterialTheme.colorScheme.onBackground
    val t = rememberInfiniteTransition(label = "meteors")
    val progress by t.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(7_000, easing = LinearEasing), RepeatMode.Restart),
        label = "meteorProgress",
    )
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas
        repeat(count) { i ->
            val cycle = (progress + i * 0.19f) % 1f
            val lane = (i % 3) / 2f
            val startX = w * (0.45f + 0.5f * lane)
            val x = startX - cycle * w * 0.55f
            val y = -h * 0.25f + cycle * h * 1.35f
            val length = h * (0.09f + 0.03f * (i % 3))
            val tailX = x + length * 0.45f
            val tailY = y - length
            drawLine(
                brush = Brush.linearGradient(
                    colors = listOf(Color.Transparent, color.copy(alpha = 0.16f)),
                    start = Offset(x, y),
                    end = Offset(tailX, tailY),
                ),
                start = Offset(x, y),
                end = Offset(tailX, tailY),
                strokeWidth = 1.4f,
            )
        }
    }
}

/**
 * Shimmer 按钮：在 Material3 Button 上叠一条斜向扫光，其它行为（禁用态、点击、
 * 内容布局）完全等同于 Button，调用方可以无痛替换。
 */
@Composable
fun ShimmerButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    shape: Shape = ButtonDefaults.shape,
    content: @Composable RowScope.() -> Unit,
) {
    val t = rememberInfiniteTransition(label = "shimmerButton")
    val sweep by t.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2_800, easing = LinearEasing), RepeatMode.Restart),
        label = "shimmerSweep",
    )
    Box(modifier) {
        Button(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            colors = colors,
            contentPadding = contentPadding,
            shape = shape,
            content = content,
        )
        if (enabled) {
            Canvas(Modifier.matchParentSize().clip(shape)) {
                val band = size.width * 0.22f
                val cx = -band + sweep * (size.width + band * 2f)
                drawRect(
                    Brush.linearGradient(
                        colors = listOf(Color.Transparent, Color.White.copy(alpha = 0.20f), Color.Transparent),
                        start = Offset(cx - band / 2f, 0f),
                        end = Offset(cx + band / 2f, size.height),
                    )
                )
            }
        }
    }
}

/**
 * Glow 边框卡：纸面容器 + 一圈缓慢旋转的渐变描边（Inspira 的 Glow Border/Border Beam
 * 静态化版本）。内容仍按原来的 Column 排，换成它不需要改排版。
 */
@Composable
fun GlowBorderBox(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.medium,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Box(modifier.clip(shape).background(colors.surface)) {
        Canvas(Modifier.matchParentSize()) {
            val stroke = 1.5.dp.toPx()
            val radius = CornerRadius(12.dp.toPx())
            // 内圈柔光：不依赖模糊滤镜，用一条更宽的半透明描边模拟。
            drawRoundRect(
                color = colors.primary.copy(alpha = 0.07f),
                topLeft = Offset(stroke, stroke),
                size = Size(size.width - stroke * 2f, size.height - stroke * 2f),
                cornerRadius = radius,
                style = Stroke(stroke * 5f),
            )
            // 不能用 rotate 旋转整块画布：旋转后的矩形四角会被卡片裁掉，只剩两条对角线。
            // 静态扫掠渐变已经能给出四边不同色的层次，不需要再转。
            drawRoundRect(
                brush = Brush.sweepGradient(
                    colors = listOf(
                        colors.primary.copy(alpha = 0.85f),
                        colors.tertiary.copy(alpha = 0.55f),
                        colors.secondary.copy(alpha = 0.80f),
                        colors.primary.copy(alpha = 0.85f),
                    ),
                ),
                topLeft = Offset(stroke / 2f, stroke / 2f),
                size = Size(size.width - stroke, size.height - stroke),
                cornerRadius = radius,
                style = Stroke(stroke),
            )
        }
        Column(Modifier.fillMaxWidth().padding(contentPadding), content = content)
    }
}
