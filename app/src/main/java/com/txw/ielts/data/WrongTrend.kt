package com.txw.ielts.data

import org.json.JSONObject
import kotlin.math.roundToInt

/** 一个统计窗口内的正确率：听读按 correct/total 汇总，跟读把每次的 accuracy 折成百分制。 */
data class AccuracyWindow(
    val attempts: Int = 0,
    val correct: Int = 0,
    val total: Int = 0,
) {
    val accuracy: Double? get() = if (total > 0) correct.toDouble() / total else null
    val percent: Int? get() = accuracy?.let { (it * 100).roundToInt() }

    internal fun plus(correct: Int, total: Int) = copy(
        attempts = attempts + 1,
        correct = this.correct + correct,
        total = this.total + total,
    )
}

/** 单科趋势：近 7 天 vs 上一个 7 天。deltaPoints 为正表示正确率上升。 */
data class ModuleTrend(
    val module: String,
    val label: String,
    val recent: AccuracyWindow,
    val previous: AccuracyWindow,
) {
    val recentPercent: Int? get() = recent.percent
    val previousPercent: Int? get() = previous.percent
    val deltaPoints: Int? get() {
        val now = recentPercent ?: return null
        val before = previousPercent ?: return null
        return now - before
    }

    val hasData: Boolean get() = recent.attempts > 0 || previous.attempts > 0
}

/**
 * 错题本趋势：听读/跟读正确率的前后两周对比 + 存量错题构成 + 一句可执行的诊断。
 *
 * 之前错题本只有「今天有几张到期」一个数字，用户看不出是「错题越积越多」还是
 * 「正在变好」。这里全部用已有数据算：练习记录的 correct/total（跟读用 accuracy），
 * 以及错题卡的 tags/lapses/reps，不新增表、不改 schema。
 */
data class WrongTrend(
    val modules: List<ModuleTrend> = emptyList(),
    val cardTotal: Int = 0,
    val listeningCards: Int = 0,
    val readingCards: Int = 0,
    val shadowCards: Int = 0,
    /** 复习时忘过的错题（lapses > 0），是真正需要优先重刷的。 */
    val repeatCards: Int = 0,
    /** 还没开始重刷的错题（reps = 0）。 */
    val freshCards: Int = 0,
    val diagnosis: String = "",
) {
    val activeModules: List<ModuleTrend> get() = modules.filter { it.hasData }
    val isEmpty: Boolean get() = activeModules.isEmpty() && cardTotal == 0
}

object WrongTrendCalc {

    const val WINDOW_DAYS = 7
    private const val DAY_MS = 86_400_000L

    private val MODULES = listOf(
        "listening" to "听力",
        "reading" to "阅读",
        "shadow" to "跟读",
    )

    fun build(
        sessions: List<Session>,
        wrongCards: List<Card>,
        now: Long = System.currentTimeMillis(),
        windowDays: Int = WINDOW_DAYS,
    ): WrongTrend {
        val span = windowDays.coerceAtLeast(1) * DAY_MS
        val recentFrom = now - span
        val previousFrom = recentFrom - span

        val modules = MODULES.map { (module, label) ->
            var recent = AccuracyWindow()
            var previous = AccuracyWindow()
            sessions.forEach { s ->
                if (s.module != module || s.status != StatusLabel.DONE) return@forEach
                if (s.startedAt > now) return@forEach
                val score = scoreOf(s) ?: return@forEach
                when {
                    s.startedAt >= recentFrom -> recent = recent.plus(score.first, score.second)
                    s.startedAt >= previousFrom -> previous = previous.plus(score.first, score.second)
                }
            }
            ModuleTrend(module, label, recent, previous)
        }

        val wrong = wrongCards.filter { it.source == "bank" || it.source == "shadow" }
        var listening = 0
        var reading = 0
        var shadow = 0
        var repeat = 0
        var fresh = 0
        wrong.forEach { c ->
            when (cardModule(c)) {
                "listening" -> listening++
                "shadow" -> shadow++
                else -> reading++
            }
            if (c.lapses > 0) repeat++
            if (c.reps == 0) fresh++
        }

        val trend = WrongTrend(
            modules = modules,
            cardTotal = wrong.size,
            listeningCards = listening,
            readingCards = reading,
            shadowCards = shadow,
            repeatCards = repeat,
            freshCards = fresh,
        )
        return trend.copy(diagnosis = diagnose(trend))
    }

    /** 单次练习的正确率样本；没有可用的分数（旧数据、空 scoresJson、total=0）返回 null。 */
    private fun scoreOf(s: Session): Pair<Int, Int>? {
        val o = runCatching { JSONObject(s.scoresJson) }.getOrNull() ?: return null
        return when (s.module) {
            "listening", "reading" -> {
                val total = o.optInt("total", 0)
                if (total <= 0) null
                else o.optInt("correct", 0).coerceIn(0, total) to total
            }
            "shadow" -> {
                val acc = o.optDouble("accuracy", Double.NaN)
                if (!acc.isFinite()) null
                else acc.roundToInt().coerceIn(0, 100) to 100
            }
            else -> null
        }
    }

    /** 错题来源：跟读卡单独一科；题库卡按 tags 里的「听力」区分，缺标签按阅读计。 */
    private fun cardModule(c: Card): String = when {
        c.source == "shadow" -> "shadow"
        c.tags.split(',').any { it.trim() == "听力" } -> "listening"
        else -> "reading"
    }

    private fun diagnose(t: WrongTrend): String {
        val active = t.activeModules
        if (active.isEmpty() && t.cardTotal == 0) {
            return "做完一套听读题或跟读后，这里会显示近 7 天正确率变化。"
        }
        val drop = active
            .filter { it.deltaPoints != null && it.deltaPoints!! <= -5 }
            .minByOrNull { it.deltaPoints!! }
        if (drop != null) {
            return "${drop.label}比上一周低 ${-drop.deltaPoints!!} 个百分点，先重刷错题再开新套题。"
        }
        val weakest = active
            .filter { it.recentPercent != null }
            .minByOrNull { it.recentPercent!! }
        if (weakest != null && weakest.recentPercent!! < 60) {
            return "${weakest.label}近 7 天正确率 ${weakest.recentPercent}%，是当前最薄弱的一科。"
        }
        val rise = active
            .filter { it.deltaPoints != null && it.deltaPoints!! >= 5 }
            .maxByOrNull { it.deltaPoints!! }
        if (rise != null) {
            return "${rise.label}比上一周高 ${rise.deltaPoints} 个百分点，保持这个节奏。"
        }
        if (t.repeatCards > 0) {
            return "有 ${t.repeatCards} 张错题反复遗忘，优先重刷这几张。"
        }
        if (active.isEmpty()) {
            return "近 7 天没有听读/跟读练习，错题本还有 ${t.cardTotal} 张存量。"
        }
        if (t.freshCards > 0) {
            return "正确率稳定，错题本还有 ${t.freshCards} 张没重刷过。"
        }
        return "正确率稳定，保持现在的节奏。"
    }
}
