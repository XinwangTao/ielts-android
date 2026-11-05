package com.txw.ielts.llm

import android.util.Log
import kotlinx.coroutines.CancellationException

/**
 * 云端/本地双通道，语义与设置页的「评分通道」一致：
 * - local：只走本地，离线、零额度，慢；
 * - cloud：只走云端，失败就报错（与引入本地通道之前的行为一致）；
 * - auto ：先云端，失败（额度用尽、断网、限流、返回格式坏）再回退本地。
 *
 * CancellationException 必须原样抛出：用户中途返回或协程作用域取消时，那不是「云端失败」，
 * 把它当失败去起本地推理，会让已经取消的任务继续占住 native 模型。
 */
suspend fun <T> routeLlm(
    channel: String,
    label: String,
    /** 云端失败时的回调（不会收到 CancellationException）：用于标记额度失效、触发冷却。 */
    onCloudFail: (Exception) -> Unit = {},
    cloud: suspend () -> T,
    local: suspend () -> T,
): T = when (channel) {
    "local" -> local()
    "cloud" -> cloud()
    else -> try {
        cloud()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w("LlmRouter", "$label 云端不可用，回退本地模型：${e.message}")
        runCatching { onCloudFail(e) }
        local()
    }
}
