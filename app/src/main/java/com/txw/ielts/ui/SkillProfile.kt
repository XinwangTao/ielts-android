package com.txw.ielts.ui

import com.txw.ielts.data.Session
import org.json.JSONObject

/** 四科估值：听/读按正确率映射，写/说按最近 5 次 overall 平均（图：学习闭环扩展）。 */
data class SkillProfile(
    val listening: Double?,
    val reading: Double?,
    val writing: Double?,
    val speaking: Double?,
) {
    fun line(): String = buildString {
        append("L ").append(fmt(listening))
        append(" · R ").append(fmt(reading))
        append(" · W ").append(fmt(writing))
        append(" · S ").append(fmt(speaking))
    }

    private fun fmt(v: Double?): String = if (v == null) "暂无" else "%.1f".format(v)
}

fun skillProfile(sessions: List<Session>): SkillProfile {
    fun avgBand(module: String): Double? {
        val bands = sessions.filter { it.module == module && it.status == "done" }
            .mapNotNull {
                runCatching { JSONObject(it.scoresJson).optDouble("overall", 0.0) }.getOrNull()
            }
            .filter { it > 0 }
            .takeLast(5)
        if (bands.isEmpty()) return null
        return Math.round(bands.average() * 2.0) / 2.0
    }

    fun accuracyBand(module: String): Double? {
        val pairs = sessions.filter { it.module == module && it.status == "done" }
            .mapNotNull {
                runCatching {
                    val o = JSONObject(it.scoresJson)
                    if (o.has("correct")) o.optInt("correct") to o.optInt("total") else null
                }.getOrNull()
            }
            .filter { it.second > 0 }
            .takeLast(5)
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
