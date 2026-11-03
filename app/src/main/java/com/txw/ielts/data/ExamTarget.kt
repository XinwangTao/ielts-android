package com.txw.ielts.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 备考目标：目标分与考试日期。
 *
 * 单独抽出来是为了能被 JVM 单测覆盖——日期解析和「临考收紧间隔」这两处一旦算错，
 * 用户看到的就是「距考试 -3 天」或复习提醒突然消失，很难从日志里发现。
 */
object ExamTarget {

    /** 解析 yyyy-MM-dd，返回距 [nowMs]（本地时区当天）还有几天；当天为 0，已过考试为负数。无效返回 null。 */
    fun daysUntil(iso: String, nowMs: Long): Int? {
        val exam = runCatching { LocalDate.parse(iso.trim()) }.getOrNull() ?: return null
        val today = Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault()).toLocalDate()
        return ChronoUnit.DAYS.between(today, exam).toInt()
    }

    /**
     * 临考收紧复练间隔：>14 天按原间隔，≤14 天减半（至少 1 天），≤7 天每天都练。
     * 考试日期未设或已过则回到原间隔，避免「考完了还天天催」。
     */
    fun reviewInterval(baseDays: Int, daysLeft: Int?): Int {
        if (daysLeft == null || daysLeft < 0) return baseDays
        return when {
            daysLeft <= 7 -> 1
            daysLeft <= 14 -> (baseDays / 2).coerceAtLeast(1)
            else -> baseDays
        }
    }

    /** 把一个分数吸附到 0.5 步长并限制在 4.0–9.0；非法值回退 [fallback]。 */
    fun snapBand(value: Float?, fallback: Float = 6.5f): Float {
        val v = value?.takeIf { it.isFinite() } ?: return fallback
        return ((v * 2).toInt() / 2f).coerceIn(4f, 9f)
    }

    /**
     * 四科目标/成绩换算总分。
     *
     * 官方规则：四科求平均后取最近的 0.5 档，正好落在 .25/.75 时向上进
     * （6.375 → 6.5，6.125 → 6.0）。所以是 floor(avg*2 + 0.5)/2，
     * 既不是向上取整（那会把 6.125 抬到 6.5，白送半档），也不是普通的
     * 四舍五入到整数（6.25 会被算成 6.0）。
     * 加 1e-9 防浮点误差：6.5*4/4 在 double 下可能算出 6.499999999999999，
     * 正好踩在边界上时会往下掉半档。
     *
     * 四科缺任意一科返回 null：缺项按 0 算或按其余三科平均，都会给出假的达标结论。
     */
    fun overallBand(listening: Double?, reading: Double?, writing: Double?, speaking: Double?): Double? {
        val values = listOf(listening, reading, writing, speaking)
        if (values.any { it == null || !it.isFinite() }) return null
        val avg = values.sumOf { it!! } / 4.0
        return kotlin.math.floor(avg * 2 + 0.5 + 1e-9) / 2.0
    }
}
