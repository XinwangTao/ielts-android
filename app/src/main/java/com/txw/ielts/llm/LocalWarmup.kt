package com.txw.ielts.llm

/**
 * 练习页「停留几秒后提前加载本地模型」的判定。
 *
 * 为什么单独抽出来：第一版把条件写成「已就绪就跳过」，本意大概是「模型已经在内部目录了，
 * 不用再准备」，但 [LocalLlmEngine.isReady] 只表示**文件已完整拷贝到内部目录**，
 * 不表示模型已经加载进内存。冷启动进写作页时最常见的状态恰恰是「文件已就绪、尚未加载」，
 * 于是预热在最需要它的场景里一次都没跑过，用户提交后仍要白等约 60 秒冷加载；
 * 只有从没拷贝过、第一次投递模型的那一轮才会误打误撞地预热。
 *
 * 正确条件只排除「两条来源都没有」：内部已就绪可以直接加载；只有外部投递目录也没关系，
 * [LocalLlmEngine.preload] 会先拷贝再加载。开关关闭时一律不预热（用户可能还要跑别的本地大模型）。
 */
object LocalWarmup {

    /**
     * @param enabled 设置页的「进入练习页时提前加载模型」开关
     * @param ready 模型文件已完整拷贝到内部目录（可立即加载）
     * @param delivered 外部投递目录里已有模型（首次加载前需要先拷贝）
     */
    fun shouldPreload(enabled: Boolean, ready: Boolean, delivered: Boolean): Boolean =
        enabled && (ready || delivered)
}
