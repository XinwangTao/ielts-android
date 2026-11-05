package com.txw.ielts.ui

import android.app.Activity
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView

/**
 * 本地评分期间保持亮屏。
 *
 * 为什么需要：OPPO/ColorOS 在 App 窗口不可见（熄屏 / 切后台）时会把进程降到 `cpuset:/background`，
 * 阻塞在 native 推理里的调用就再也回不来 —— 实测 utime 增量恒为 0，评分永久停在 pending_local。
 * 端侧评分最快的可靠路径就是「留在 App 且亮屏」，所以这里替用户把屏幕钉住，
 * 避免「提交完把手机放下 → 熄屏 → 评分失败」。
 * 详见 docs/oppo-background-scoring-findings.md §九。
 */
@Composable
fun KeepScreenOn(active: Boolean) {
    val view = LocalView.current
    DisposableEffect(active, view) {
        val window = (view.context as? Activity)?.window
        if (active) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            // 只在由本组件点亮时撤销，避免把别处设置的常亮一起清掉。
            if (active) window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}
