package com.txw.ielts.ui

import com.txw.ielts.data.ExamTarget
import com.txw.ielts.data.Session
import com.txw.ielts.data.StatusLabel
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 一天在课表里的角色。只有 PRACTICE 允许换科目，其余日子的含义是固定的。 */
enum class PlanDayKind { PRACTICE, MOCK, LIGHT, REST, EXAM }

/**
 * 课表里的一天。
 *
 * @param date yyyy-MM-dd，同时充当「手动换科目」的稳定键：跨天重算后旧日期自然失效
 * @param label 今天 / 明天 / 周三，首页式相对称呼
 * @param swapped 这一天被用户手动换过科目，界面上要能一键恢复
 * @param done 当天计划的那一科已经练过（按练习记录判定，只认 status=done）
 */
data class PlanDay(
    val date: String,
    val weekday: String,
    val label: String,
    val kind: PlanDayKind,
    val module: String,
    val title: String,
    val detail: String,
    val minutes: Int,
    val swapped: Boolean = false,
    val done: Boolean = false,
) {
    val editable: Boolean get() = kind == PlanDayKind.PRACTICE
}

/** 一份 7 天课表 + 一句「为什么这么排」的说明。 */
data class StudyPlan(
    val days: List<PlanDay>,
    val headline: String,
    val intensity: String,
    val note: String? = null,
) {
    fun totalMinutes(): Int = days.sumOf { it.minutes }
    fun hasManualEdits(): Boolean = days.any { it.swapped }

    /** 需要动手的日子：练习日 + 模考日。休息日/考试日没有「完成」一说。 */
    fun taskDays(): List<PlanDay> =
        days.filter { it.kind == PlanDayKind.PRACTICE || it.kind == PlanDayKind.MOCK }

    fun doneCount(): Int = taskDays().count { it.done }
}

/**
 * 7 天备考计划。
 *
 * 今日任务回答「现在做什么」，这里回答「这周怎么排」：按考期倒排强度、按最弱科分配次数，
 * 并给模考、考前轻复习、休息日留出固定位置。之前 App 里只有单日建议，
 * 想按周规划的用户只能自己拿四科估值去猜该练几次写作。
 *
 * 课表规则（纯计算，可 JVM 测）：
 * - 到考试不足 7 天时课表只排到考试日，最后一天是 EXAM、前一天是 LIGHT；
 * - 距考试 ≥8 天才排整套模考（考前一周模考只会打击信心，还占掉复盘时间）；
 * - 距考试 >21 天才给休息日（临考期每天都要有动作，但不必排满）；
 * - 周内每科保底一次，剩余名额按「离目标差多少」用最高均数法分配；
 * - 相邻两天不排同一科，除非只剩这一科没排完。
 */
object StudyPlanCalc {

    const val PLAN_DAYS = 7
    private const val MOCK_OFFSET = 5
    private const val MOCK_MIN_DAYS = 8
    private const val RELAXED_DAYS = 21
    private const val NEVER_TRIED_WEIGHT = 1.6

    val MODULES = listOf("listening", "reading", "writing", "speaking")

    private val WEEKDAYS = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    private data class Meta(val title: String, val detail: String, val minutes: Int)

    private val METAS = mapOf(
        "listening" to Meta("听力", "精听一套 Section 3–4，错句跟读两遍", 25),
        "reading" to Meta("阅读", "限时 20 分钟一篇，再复盘定位句", 30),
        "writing" to Meta("写作", "一篇 Task 2 提纲 + 写成一段主体", 35),
        "speaking" to Meta("口语", "Part 2 两题录音，回听挑一个问题", 20),
    )

    fun build(
        now: Long,
        examDate: String,
        targets: SkillTargets? = null,
        profile: SkillProfile = SkillProfile(null, null, null, null),
        dueCards: Int = 0,
        wrongCount: Int = 0,
        overrides: Map<String, String> = emptyMap(),
        sessions: List<Session> = emptyList(),
    ): StudyPlan {
        val daysLeft = ExamTarget.daysUntil(examDate, now)
        // 考试日期已过：继续倒排会给出「距考试 -3 天」这种假计划，直接停下并提示改日期。
        if (daysLeft != null && daysLeft < 0) {
            return StudyPlan(
                days = emptyList(),
                headline = "考试日期已过，倒排计划先停下",
                intensity = "已考完",
                note = "把设置里的考试日期改成下一次，课表会按新考期重排。",
            )
        }

        val today = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
        // 课表只排到考试当天：考前三天还列着「下周练什么」会让人误以为还有时间。
        val span = if (daysLeft == null) PLAN_DAYS else minOf(PLAN_DAYS, daysLeft + 1)
        val kinds = MutableList(span) { PlanDayKind.PRACTICE }

        val mockOn = (daysLeft == null || daysLeft >= MOCK_MIN_DAYS) && span > MOCK_OFFSET
        if (mockOn) kinds[MOCK_OFFSET] = PlanDayKind.MOCK
        val restOn = (daysLeft == null || daysLeft > RELAXED_DAYS) && span == PLAN_DAYS
        if (restOn && kinds[PLAN_DAYS - 1] == PlanDayKind.PRACTICE) kinds[PLAN_DAYS - 1] = PlanDayKind.REST
        if (daysLeft != null) {
            val lightOffset = daysLeft - 1
            if (lightOffset in 0 until span) kinds[lightOffset] = PlanDayKind.LIGHT
            if (daysLeft < span) kinds[daysLeft] = PlanDayKind.EXAM
        }

        val practiceIdx = kinds.indices.filter { kinds[it] == PlanDayKind.PRACTICE }
        val weights = weights(profile, targets)
        val sequence = spread(allocate(practiceIdx.size, weights), practiceIdx.size)
        val weakest = if (targets != null) weakestGap(profile, targets) else null

        val seen = mutableMapOf<String, Int>()
        val raw = kinds.indices.map { offset ->
            val date = today.plusDays(offset.toLong())
            val base = PlanDay(
                date = date.toString(),
                weekday = WEEKDAYS[date.dayOfWeek.value - 1],
                label = when (offset) {
                    0 -> "今天"
                    1 -> "明天"
                    else -> WEEKDAYS[date.dayOfWeek.value - 1]
                },
                kind = kinds[offset],
                module = "",
                title = "",
                detail = "",
                minutes = 0,
            )
            when (kinds[offset]) {
                PlanDayKind.PRACTICE -> {
                    val module = sequence[practiceIdx.indexOf(offset)]
                    val meta = METAS.getValue(module)
                    val nth = (seen[module] ?: 0) + 1
                    seen[module] = nth
                    base.copy(
                        module = module,
                        title = meta.title,
                        detail = buildString {
                            append(meta.detail)
                            if (nth > 1) append("（本周第 $nth 次）")
                            // 只标最弱的一科：四科都标「重点」等于没标。
                            if (weakest?.module == module) append(" · 最弱科，优先补齐")
                        },
                        minutes = meta.minutes,
                    )
                }
                PlanDayKind.MOCK -> base.copy(
                    module = "mock",
                    title = "整套模考",
                    detail = "听力 + 阅读连做，按考试时间不走神",
                    minutes = 90,
                )
                PlanDayKind.LIGHT -> base.copy(
                    module = "light",
                    title = "考前轻复习",
                    detail = "只看错题与范文，不做新题，早点睡",
                    minutes = 20,
                )
                PlanDayKind.REST -> base.copy(
                    module = "rest",
                    title = "休息日",
                    detail = "不派新任务，想练就自由做一套",
                    minutes = 0,
                )
                PlanDayKind.EXAM -> base.copy(
                    module = "exam",
                    title = "考试日",
                    detail = "带齐证件，提前 30 分钟到场",
                    minutes = 0,
                )
            }
        }

        // 完成状态在「换科目」之后才判定：用户把今天换成写作又写了作文，
        // 打勾要打在这张卡上，而不是原来排的听力。
        val practiced = practicedByDate(sessions, ZoneId.systemDefault())
        val days = raw.map { day ->
            val override = overrides[day.date]?.takeIf { it in MODULES }
            if (day.editable && override != null && override != day.module) {
                val meta = METAS.getValue(override)
                day.copy(
                    module = override,
                    title = meta.title,
                    detail = "${meta.detail} · 已手动调整",
                    minutes = meta.minutes,
                    swapped = true,
                )
            } else {
                day
            }
        }.map { day ->
            if (isDone(day, practiced)) day.copy(done = true) else day
        }

        return StudyPlan(
            days = days,
            headline = when {
                daysLeft == null -> "未设考试日期 · 先按均衡节奏排 7 天"
                daysLeft == 0 -> "今天是考试日 · 不安排新任务"
                else -> "距考试 $daysLeft 天 · ${intensityOf(daysLeft)}"
            },
            intensity = intensityOf(daysLeft),
            note = noteOf(days, daysLeft, dueCards, wrongCount),
        )
    }

    fun intensityOf(daysLeft: Int?): String = when {
        daysLeft == null -> "自主节奏"
        daysLeft <= 7 -> "最后一周"
        daysLeft <= 21 -> "冲刺期"
        daysLeft <= 60 -> "常规期"
        else -> "打基础期"
    }

    /**
     * 科目权重：每科先给 1，再按离目标差多少加权（封顶 3）。
     * 从没练过的科目按「还没测出水平」给偏高的 1.6，先安排一次摸底，
     * 而不是把整周都排给已经有成绩的强科。
     */
    private fun weights(profile: SkillProfile, targets: SkillTargets?): Map<String, Double> =
        MODULES.associateWith { module ->
            val current = profileValue(profile, module)
            val target = targets?.let { targetValue(it, module) }
            when {
                current == null -> NEVER_TRIED_WEIGHT
                target == null -> 1.0
                else -> 1.0 + (target - current).coerceIn(0.0, 3.0)
            }
        }

    /** 最高均数法（D'Hondt）：每次把名额给「权重 / (已得次数+1)」最高的一科，平分时按固定顺序。 */
    private fun allocate(slots: Int, weights: Map<String, Double>): MutableMap<String, Int> {
        val counts = MODULES.associateWith { 0 }.toMutableMap()
        repeat(slots.coerceAtLeast(0)) {
            val pick = MODULES.maxWithOrNull(
                compareBy({ (weights[it] ?: 1.0) / (counts[it]!! + 1) }, { -MODULES.indexOf(it) })
            ) ?: return@repeat
            counts[pick] = counts[pick]!! + 1
        }
        // 保底一科一次：强科权重压得再低，也不该整周零次（写作/口语尤其不能断）。
        if (slots >= MODULES.size) {
            MODULES.forEach { module ->
                if (counts[module] == 0) {
                    val donor = MODULES.maxByOrNull { counts[it]!! } ?: return@forEach
                    if ((counts[donor] ?: 0) > 1) {
                        counts[donor] = counts[donor]!! - 1
                        counts[module] = 1
                    }
                }
            }
        }
        return counts
    }

    /** 把「每科几次」摊到具体日子上：优先填次数剩得多的科目，同科不排相邻两天。 */
    private fun spread(counts: Map<String, Int>, slots: Int): List<String> {
        val remaining = MODULES.associateWith { counts[it] ?: 0 }.toMutableMap()
        val seq = mutableListOf<String>()
        var prev: String? = null
        repeat(slots) {
            val avail = MODULES.filter { (remaining[it] ?: 0) > 0 }
            if (avail.isEmpty()) return@repeat
            val pick = avail.filter { it != prev }.ifEmpty { avail }
                .maxWithOrNull(compareBy({ remaining[it] ?: 0 }, { -MODULES.indexOf(it) }))!!
            seq += pick
            remaining[pick] = (remaining[pick] ?: 0) - 1
            prev = pick
        }
        return seq
    }

    /**
     * 每天实际练过的科目（按本地日期归档）。
     *
     * 只认 status=done：待评分/没听清的记录代表「还不知道做没做成」，
     * 打勾会让人以为自己已经练完了。跟读（shadow）不排进课表，这里忽略。
     */
    private fun practicedByDate(sessions: List<Session>, zone: ZoneId): Map<String, Set<String>> {
        val out = HashMap<String, MutableSet<String>>()
        sessions.forEach { s ->
            if (s.status != StatusLabel.DONE || s.module !in MODULES) return@forEach
            val date = Instant.ofEpochMilli(s.startedAt).atZone(zone).toLocalDate().toString()
            out.getOrPut(date) { mutableSetOf() }.add(s.module)
        }
        return out
    }

    /** 练习日看当天排的那一科；模考日听力/阅读做了任意一套就算完成。 */
    private fun isDone(day: PlanDay, practiced: Map<String, Set<String>>): Boolean {
        val modules = practiced[day.date] ?: return false
        return when (day.kind) {
            PlanDayKind.PRACTICE -> day.module in modules
            PlanDayKind.MOCK -> "listening" in modules || "reading" in modules
            // 休息日不用打勾；考前轻复习是「只看错题」，没有可判定的记录；
            // 考试日也不是练习，打勾会像在催人再练一套。
            else -> false
        }
    }

    private fun noteOf(days: List<PlanDay>, daysLeft: Int?, dueCards: Int, wrongCount: Int): String? {
        val parts = mutableListOf<String>()
        val mix = MODULES.mapNotNull { module ->
            val times = days.count { it.kind == PlanDayKind.PRACTICE && it.module == module }
            if (times > 0) "${METAS.getValue(module).title} ×$times" else null
        }
        if (mix.isNotEmpty()) parts += "本周课表：" + mix.joinToString(" · ")
        if (days.any { it.kind == PlanDayKind.MOCK }) parts += "另有一次整套模考"
        val backlog = buildString {
            if (wrongCount > 0) append("$wrongCount 张错题待重刷")
            if (dueCards > 0) {
                if (isNotEmpty()) append("，")
                append("$dueCards 张卡片今天到期")
            }
        }
        if (backlog.isNotEmpty()) parts += "手头还有 $backlog"
        if (daysLeft != null && daysLeft in 1..7) parts += "考前一天只看错题，别做新题"
        return parts.takeIf { it.isNotEmpty() }?.joinToString("；")
    }

    private fun profileValue(profile: SkillProfile, module: String): Double? = when (module) {
        "listening" -> profile.listening
        "reading" -> profile.reading
        "writing" -> profile.writing
        "speaking" -> profile.speaking
        else -> null
    }

    private fun targetValue(targets: SkillTargets, module: String): Double = when (module) {
        "listening" -> targets.listening
        "reading" -> targets.reading
        "writing" -> targets.writing
        else -> targets.speaking
    }
}

/**
 * 手动换科目/撤销的存取。
 *
 * 只存「日期 → 科目」这一层修改：课表本身每次由 [StudyPlanCalc.build] 重算，
 * 所以练习数据变了课表会跟着变，但用户亲手换过的那几天保持不变。
 * 坏数据（日期解析不了、科目名不认识）一律丢弃，不让设置里的一行脏串把整页计划打空。
 */
object PlanOverrides {

    fun encode(overrides: Map<String, String>): String {
        val obj = JSONObject()
        overrides.toSortedMap().forEach { (date, module) ->
            if (isDate(date) && module in StudyPlanCalc.MODULES) obj.put(date, module)
        }
        return obj.toString()
    }

    fun decode(raw: String?): Map<String, String> {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return emptyMap()
        val obj = runCatching { JSONObject(text) }.getOrNull() ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        obj.keys().asSequence().sorted().forEach { date ->
            val module = obj.optString(date, "")
            if (isDate(date) && module in StudyPlanCalc.MODULES) out[date] = module
        }
        return out
    }

    /** 「换一科」：按听力 → 阅读 → 写作 → 口语循环，未知科目回到第一个。 */
    fun cycle(module: String): String {
        val index = StudyPlanCalc.MODULES.indexOf(module)
        return StudyPlanCalc.MODULES[(index + 1).mod(StudyPlanCalc.MODULES.size)]
    }

    private fun isDate(s: String): Boolean = runCatching { LocalDate.parse(s) }.isSuccess
}
