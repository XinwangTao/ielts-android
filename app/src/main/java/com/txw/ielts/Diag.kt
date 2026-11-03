package com.txw.ielts

import java.io.File

/**
 * 内存观测小工具。
 *
 * 端侧模型（qwen3-4b 约 2.5GB）+ Whisper 同时驻留会被 lmkd 杀掉，这类问题
 * 只看日志里的「已完成」是查不出来的，必须看到 RSS 的涨落。VmRSS 读自身进程无需权限。
 */
object Diag {
    /** 当前进程 RSS（MB）；读取失败返回 -1，不让诊断代码影响主流程。 */
    fun rssMb(): Int = runCatching {
        File("/proc/self/status").readLines()
            .firstOrNull { it.startsWith("VmRSS:") }
            ?.trim()
            ?.split(Regex("\\s+"))
            ?.getOrNull(1)
            ?.toInt()
            ?.div(1024)
            ?: -1
    }.getOrDefault(-1)
}
