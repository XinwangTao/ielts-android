package com.txw.ielts.ui

import com.txw.ielts.data.ExamTarget
import com.txw.ielts.data.Session
import com.txw.ielts.data.StatusLabel

/** 今日任务的行为类型：首页只据此决定点一下去哪个页面。 */
enum class DailyTaskKind {
    WRONG,
    GAP,
    SPEAKING,
    WRITING,
    CARDS,
    FREE,
}

/**
 * 首页「今日任务」的一行。
 *
 * @param key 稳定标识，便于去重与测试，不直接展示
 * @param detail 一句依据（为什么今天推这个），不再是只有一个标题
 * @param minutes 预计时长，首页汇总成「约 N 分钟」，避免点进去才发现要写 40 分钟作文
 * @param module 导航用模块名（listening/reading/writing/speaking/bank/corpus）
 */
data class DailyTask(
    val key: String,
    val kind: DailyTaskKind,
    val title: String,
    val detail: String,
    val minutes: Int,
    val module: String = "",
)

/**
 * 每日建议的排序规则。
 *
 * 之前这段逻辑直接写在 MainActivity 的 buildList 里：错题永远第一、四个 due 判断各写一遍，
 * 而且「距上次 N 天」把 pending_eval/abandoned 的失败练习也算成练过——
 * 用户明明评分失败，首页却说「今天练过」。抽成纯函数后：
 * - 只把 status=done 的练习算作「练过」；
 * - 最弱科与到点复练合并成一条，不占两个位置；
 * - 按优先级截断，避免错题/差距被后面的卡片任务挤掉；
 * - 全部任务完成时给一条兜底建议，首页不会变成空白。
 */
object DailyPlanCalc {

    const val DAY_MS = 86_400_000L
    const val DEFAULT_MAX_TASKS = 3

    private const val PRIORITY_WRONG = 90
    private const val PRIORITY_GAP = 85
    private const val PRIORITY_DUE = 70
    private const val PRIORITY_CARDS = 75
    private const val PRIORITY_FREE = 10

    private data class DueRule(
        val module: String,
        val label: String,
        val baseIntervalDays: Int,
        val minutes: Int,
        val never: String,
    )

    private val DUE_RULES = listOf(
        DueRule("speaking", "口语", 2, 12, "还没开口过"),
        DueRule("writing", "写作", 3, 25, "还没写过"),
    )

    fun build(
        sessions: List<Session>,
        wrongCount: Int,
        dueCards: Int,
        targets: SkillTargets?,
        now: Long,
        daysLeft: Int? = null,
        maxTasks: Int = DEFAULT_MAX_TASKS,
        profile: SkillProfile = skillProfile(sessions),
    ): List<DailyTask> {
        // 只有正常完成的练习才算「练过」：待评分/没听清/中断的记录不计入复练间隔，
        // 否则一次评分失败会把口语写作的提醒压掉好几天。
        val done = sessions.filter { it.status == StatusLabel.DONE && it.startedAt <= now }
        fun lastAt(module: String): Long? = done.filter { it.module == module }.maxOfOrNull { it.startedAt }

        val gap = targets?.let { weakestGap(profile, it) }
        val tasks = mutableListOf<DailyTask>()
        val gapUsed = mutableSetOf<String>()

        // 听力/阅读没有固定复练间隔，最弱科直接作为一条独立任务。
        if (gap != null && gap.module != "speaking" && gap.module != "writing") {
            tasks += gapTask(gap, profile)
            gapUsed += gap.module
        }

        if (wrongCount > 0) {
            tasks += DailyTask(
                key = "wrong",
                kind = DailyTaskKind.WRONG,
                title = "错题专项 · 重刷 $wrongCount 张",
                detail = listOfNotNull(
                    "答对才算过，反复错的优先",
                    examNote(daysLeft),
                ).joinToString(" · "),
                minutes = (wrongCount * 2).coerceIn(6, 20),
                module = "corpus",
            )
        }

        // 到点复练：与最弱科重合时合并成一条，只保留「该练了 + 差多少」。
        DUE_RULES.forEach { rule ->
            val last = lastAt(rule.module)
            val interval = ExamTarget.reviewInterval(rule.baseIntervalDays, daysLeft)
            if (!isDue(last, now, interval)) return@forEach
            val mergedGap = gap?.takeIf { it.module == rule.module }
            val detail = buildString {
                append(if (last == null) rule.never else agoText(last, now))
                mergedGap?.let {
                    val current = valueOf(profile, rule.module)
                    if (current != null) append(" · 当前 %.1f 差 %.1f".format(current, it.gap))
                }
                examNote(daysLeft)?.let { append(" · ").append(it) }
            }
            tasks += DailyTask(
                key = "due:${rule.module}",
                kind = if (rule.module == "speaking") DailyTaskKind.SPEAKING else DailyTaskKind.WRITING,
                title = "${rule.label}该练了",
                detail = detail,
                minutes = rule.minutes,
                module = rule.module,
            )
            if (mergedGap != null) gapUsed += rule.module
        }

        // 最弱科到了口语/写作但还没到复练间隔：单独推一条，避免一周只等固定间隔。
        if (gap != null && gap.module !in gapUsed) {
            tasks += gapTask(gap, profile)
        }

        if (dueCards > 0) {
            tasks += DailyTask(
                key = "cards",
                kind = DailyTaskKind.CARDS,
                title = "复习 $dueCards 张到期卡片",
                detail = "间隔复习 · 今天到期，先清完再学新卡",
                minutes = dueCards.coerceIn(5, 20),
                module = "corpus",
            )
        }

        val ranked = tasks.sortedByDescending { priorityOf(it, daysLeft) }
            .take(maxTasks.coerceAtLeast(1))
            .toMutableList()

        if (ranked.isEmpty()) {
            ranked += DailyTask(
                key = "free",
                kind = DailyTaskKind.FREE,
                title = "自由练习 · 保持手感",
                detail = "今天没有到期任务，做一套听力/阅读",
                minutes = 20,
                module = "bank",
            )
        }
        return ranked
    }

    /** 今日任务预计总时长，首页标题右侧显示「约 N 分钟」。 */
    fun totalMinutes(tasks: List<DailyTask>): Int = tasks.sumOf { it.minutes }

    private fun isDue(last: Long?, now: Long, intervalDays: Int): Boolean {
        if (last == null) return true
        return now - last >= intervalDays * DAY_MS
    }

    private fun priorityOf(task: DailyTask, daysLeft: Int?): Int {
        val base = when (task.kind) {
            DailyTaskKind.WRONG -> PRIORITY_WRONG
            DailyTaskKind.GAP -> PRIORITY_GAP
            DailyTaskKind.SPEAKING, DailyTaskKind.WRITING -> PRIORITY_DUE
            DailyTaskKind.CARDS -> PRIORITY_CARDS
            DailyTaskKind.FREE -> PRIORITY_FREE
        }
        // 临考一周内，口语/写作的紧迫度往上提，但不超过最弱科。
        val boost = if (task.kind == DailyTaskKind.SPEAKING || task.kind == DailyTaskKind.WRITING) {
            if (daysLeft != null && daysLeft in 0..7) 10 else 0
        } else 0
        return base + boost
    }

    private fun gapTask(gap: SkillGap, profile: SkillProfile): DailyTask {
        val current = valueOf(profile, gap.module)
        val detail = if (current != null) {
            "当前 %.1f · 目标 %.1f".format(current, current + gap.gap)
        } else {
            "离目标还差 %.1f 分".format(gap.gap)
        }
        return DailyTask(
            key = "gap:${gap.module}",
            kind = DailyTaskKind.GAP,
            title = "${gap.label}差 %.1f 分 · 优先补".format(gap.gap),
            detail = detail,
            minutes = when (gap.module) {
                "writing" -> 25
                "speaking" -> 12
                else -> 15
            },
            module = gap.module,
        )
    }

    /** 距上次练习的说法：不再出现「距上次 0 天」这种像没练过的文案。 */
    private fun agoText(last: Long, now: Long): String {
        val days = ((now - last) / DAY_MS).toInt().coerceAtLeast(0)
        return when {
            days == 0 -> "今天练过"
            days == 1 -> "距上次 1 天"
            else -> "距上次 $days 天"
        }
    }

    private fun examNote(daysLeft: Int?): String? = when {
        daysLeft == null || daysLeft < 0 -> null
        daysLeft == 0 -> "考试就在今天"
        daysLeft <= 7 -> "临考 $daysLeft 天"
        else -> null
    }

    private fun valueOf(profile: SkillProfile, module: String): Double? = when (module) {
        "listening" -> profile.listening
        "reading" -> profile.reading
        "writing" -> profile.writing
        "speaking" -> profile.speaking
        else -> null
    }
}
