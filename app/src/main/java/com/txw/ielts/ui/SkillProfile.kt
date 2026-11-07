package com.txw.ielts.ui

import com.txw.ielts.data.ExamTarget
import com.txw.ielts.data.Session
import org.json.JSONObject

/** 四科估值：听/读按正确率映射，写/说按最近 5 次 overall 平均（图：学习闭环扩展）。 */
data class SkillProfile(
    val listening: Double?,
    val reading: Double?,
    val writing: Double?,
    val speaking: Double?,
) {
    /** 目标对比版：没有目标就退回纯估值，避免首页在旧设置下显示一堆箭头。 */
    fun line(targets: SkillTargets? = null): String = buildString {
        append("L ").append(cell(listening, targets?.listening))
        append(" · R ").append(cell(reading, targets?.reading))
        append(" · W ").append(cell(writing, targets?.writing))
        append(" · S ").append(cell(speaking, targets?.speaking))
    }

    /** 有估值才算差距；缺科的「暂无」不加箭头，否则像是差了一整档。 */
    private fun cell(value: Double?, target: Double?): String {
        if (value == null) return "暂无"
        if (target == null) return "%.1f".format(value)
        val gap = target - value
        // 符号与数字之间留空格：「7.0✓」挤在一起，「7.0 差0.5」读起来也歧义
        // （↓ 会被读成「降了」，实际是「离目标还差」）
        return if (gap <= 0.001) "%.1f ✓".format(value)
        else "%.1f 差%.1f".format(value, gap)
    }

    /** 四科估值换算总分；缺任何一科返回 null（同官方规则，见 ExamTarget.overallBand）。 */
    fun overall(): Double? = ExamTarget.overallBand(listening, reading, writing, speaking)
}

/** 离目标还差的一科：module 用于跳转，label 用于文案。 */
data class SkillGap(val module: String, val label: String, val gap: Double)

/**
 * 哪一科最拖后腿。
 *
 * 只看已经有数据的科目：听力从没练过时差 6.5 分，但那是「没练」不是「偏弱」，
 * 把它排在写作（真差 0.5 分）前面，首页就会天天推一个用户根本不熟的入口。
 * 全科都达标返回 null，此时今日任务保持原来的节奏提醒。
 */
fun weakestGap(profile: SkillProfile, targets: SkillTargets): SkillGap? {
    val pairs = listOf(
        Triple("listening", "听力", profile.listening to targets.listening),
        Triple("reading", "阅读", profile.reading to targets.reading),
        Triple("writing", "写作", profile.writing to targets.writing),
        Triple("speaking", "口语", profile.speaking to targets.speaking),
    )
    return pairs.mapNotNull { (module, label, pair) ->
        val value = pair.first ?: return@mapNotNull null
        val gap = pair.second - value
        if (gap > 0.001) SkillGap(module, label, gap) else null
    }.maxByOrNull { it.gap }
}

/** 四科目标分（来自设置）。换算总分与判断达标都走这一份，避免首页和设置页各算一套。 */
data class SkillTargets(
    val listening: Double,
    val reading: Double,
    val writing: Double,
    val speaking: Double,
) {
    fun overall(): Double? = ExamTarget.overallBand(listening, reading, writing, speaking)
}

/**
 * 首页「四科目标」注脚。
 *
 * 四科目标和总分目标一致时不显示——那行只是把设置原样念一遍；
 * 只有用户把某一科单独调低（保底）或调高（拉分）时才出现，顺便给出换算总分，
 * 让「四科全 6.5 才是 6.5」这件事在改设置后立刻看得见。
 */
internal fun subTargetLine(targets: SkillTargets?, overallTarget: Double): String? {
    if (targets == null) return null
    val skills = listOf(targets.listening, targets.reading, targets.writing, targets.speaking)
    if (skills.all { kotlin.math.abs(it - overallTarget) < 0.001 }) return null
    return buildString {
        append(
            "四科目标 L %.1f · R %.1f · W %.1f · S %.1f".format(
                targets.listening, targets.reading, targets.writing, targets.speaking
            )
        )
        targets.overall()?.let { append("，换算总分 %.1f".format(it)) }
    }
}

fun skillProfile(sessions: List<Session>): SkillProfile {
    // sessions 来自 Room 的 ORDER BY startedAt DESC（最新在前）。这里取的是「最近 5 次」，
    // 所以是 take(5) 而不是 takeLast(5)——写成 takeLast 会拿到最早的 5 次，
    // 首页四科一直停在上周的低分，练了也不会涨（实测 W 5.5，实际最近几次都是 7.5–8）。
    fun avgBand(module: String): Double? {
        val bands = sessions.filter { it.module == module && it.status == "done" }
            .mapNotNull {
                runCatching { JSONObject(it.scoresJson).optDouble("overall", 0.0) }.getOrNull()
            }
            // 与首页单条记录的判定同口径：越界/非法 Band（0、10、NaN）都不进平均。
            .filter { com.txw.ielts.llm.ScoreGate.isValidBand(it) }
            .take(5)
        if (bands.isEmpty()) return null
        return Math.round(bands.average() * 2.0) / 2.0
    }

    fun accuracyBand(module: String): Double? {
        val pairs = sessions.filter { it.module == module && it.status == "done" }
            .mapNotNull {
                runCatching {
                    val o = JSONObject(it.scoresJson)
                    if (o.has("correct")) {
                        val total = o.optInt("total")
                        // 坏数据里 correct 可能大于 total：先夹住，避免正确率 >100%。
                        if (total > 0) o.optInt("correct").coerceIn(0, total) to total else null
                    } else null
                }.getOrNull()
            }
            .filter { it.second > 0 }
            .take(5)
        if (pairs.isEmpty()) return null
        val acc = pairs.sumOf { it.first }.toDouble() / pairs.sumOf { it.second }
        return accuracyToBand(acc)
    }

    return SkillProfile(
        listening = accuracyBand("listening"),
        reading = accuracyBand("reading"),
        writing = avgBand("writing"),
        speaking = avgBand("speaking"),
    )
}

/** 正确率 → 保守 Band 映射（只做学习参考，不声称等于官方换算）。 */
fun accuracyToBand(acc: Double): Double = when {
    acc >= 0.90 -> 7.5
    acc >= 0.80 -> 7.0
    acc >= 0.70 -> 6.5
    acc >= 0.60 -> 6.0
    acc >= 0.50 -> 5.5
    acc >= 0.40 -> 5.0
    else -> 4.5
}
