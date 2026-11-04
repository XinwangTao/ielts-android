package com.txw.ielts.data

/**
 * Session.status 的字面量到「给人看的话」的映射。
 *
 * 之前复盘的「待处理」卡片直接把 status 原样渲染，用户会看到 `pending_eval`
 * 这种内部状态串。状态字面量在库里是协议（4 条落库链路 + 3 处查询都认它），
 * 不能改成中文入库，所以把翻译这一步单独提出来。
 */
object StatusLabel {

    const val DONE = "done"
    const val PENDING_EVAL = "pending_eval"
    const val PENDING_LOCAL = "pending_local"
    const val ASR_EMPTY = "asr_empty"
    const val ABANDONED = "abandoned"

    /**
     * 未知状态不回显原串：未来新增状态时宁可显示一句含糊的话，
     * 也不要把 `some_new_state` 直接怼到用户脸上。
     */
    fun label(status: String): String = when (status) {
        DONE -> "已完成"
        PENDING_EVAL -> "待评分"
        PENDING_LOCAL -> "评分中"
        ASR_EMPTY -> "没听清"
        ABANDONED -> "已中断"
        else -> "处理中"
    }

    /** 是否需要用户动手（重评/重录），复盘页据此排序。 */
    fun needsAction(status: String): Boolean =
        status == PENDING_EVAL || status == ASR_EMPTY || status == ABANDONED
}
