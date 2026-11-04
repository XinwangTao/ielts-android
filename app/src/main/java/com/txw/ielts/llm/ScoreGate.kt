package com.txw.ielts.llm

/**
 * 评分准入：这次评分能不能当成「有效成绩」入库。
 *
 * 实测失败模式（2026-10-31 OPPO 联调在库里留下 6 条脏记录）：
 * 1. 端侧模型解析失败时会把四项指标全给 0，[ScoreSanitizer] 只会向下压分、不会向上兜底，
 *    于是 0 分照样以 `status=done` 落库。首页「写/说最近 3 次平均 2.8」就是被
 *    overall=0 和 overall=1 两条假成绩把真实的 7.5 拉垮的。
 * 2. 空文本也会被送进模型：白烧 3-4 分钟端侧推理，产出必然是垃圾分。
 *
 * 所以把「什么算成绩」提到入库前显式判断，而不是让四条落库链路各自自觉。
 */
object ScoreGate {

    /**
     * 有效成绩下限。IELTS 里 Band 0 的含义是「未作答」，不可能是真实成绩；
     * 低于 0.5 的只可能是模型失败。上限 9.0 是官方满分的硬边界。
     */
    const val MIN_VALID_BAND = 0.5
    const val MAX_VALID_BAND = 9.0

    /** 这次 overall 能不能当成有效成绩。 */
    fun isValidBand(overall: Double): Boolean =
        !overall.isNaN() && overall >= MIN_VALID_BAND && overall <= MAX_VALID_BAND

    /**
     * 入库状态：无效成绩退回可重试，不要写成 done。
     *
     * `pending_eval` 在结果页显示「这篇还没评分成功…可以点下面重新评分」，
     * 既不会被 [com.txw.ielts.ui.bandValue] 计入统计，也保留了重试入口。
     */
    fun statusFor(overall: Double): String = if (isValidBand(overall)) "done" else "pending_eval"

    /** 空文本不值得送进模型。 */
    fun shouldScore(text: String): Boolean = text.isNotBlank()
}
