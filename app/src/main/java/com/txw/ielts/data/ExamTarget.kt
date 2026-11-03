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
}
