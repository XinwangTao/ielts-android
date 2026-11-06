package com.txw.ielts.ui

import android.content.res.AssetManager
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 从 assets 异步加载一张图。
 *
 * 直接在 remember 里 decodeStream 会把 PNG 解码塞进首帧组合，低端机打开写作页会顿一下；
 * 这里放到 IO 线程，解码完成前返回 null（调用方按「图还没到」处理，不占位）。
 */
@Composable
fun rememberAssetImage(assets: AssetManager, path: String): ImageBitmap? {
    var bitmap by remember(path) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(path) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                assets.open(path).use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
            }.getOrNull()
        }
    }
    return bitmap
}
