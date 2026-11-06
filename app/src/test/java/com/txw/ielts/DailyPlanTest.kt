package com.txw.ielts

import com.txw.ielts.data.Session
import com.txw.ielts.data.StatusLabel
import com.txw.ielts.ui.DailyPlanCalc
import com.txw.ielts.ui.DailyTask
import com.txw.ielts.ui.DailyTaskKind
import com.txw.ielts.ui.SkillProfile
import com.txw.ielts.ui.SkillTargets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 首页「今日任务」的排序、合并与文案。
 *
 * 这里的输入是练习记录 + 错题/到期卡计数，输出会直接变成首页可点击的行动项：
 * 算错就会推已练过的科目、或者把真正最弱的一科挤掉。
 */
class DailyPlanTest {

    private val now = 1_800_000_000_000L
    private val targets = SkillTargets(6.5, 6.5, 6.5, 6.5)
    private val emptyProfile = SkillProfile(null, null, null, null)

    private fun done(module: String, at: Long) = Session(
        id = at, module = module, status = StatusLabel.DONE, startedAt = at,
    )

    /** 最近刚练过口语和写作，避免「从没练过 → 永远 due」干扰其它断言。 */
    private fun quietSessions() = listOf(
        done("speaking", now - 60_000),
        done("writing", now - 60_000),
    )

    private fun build(
        sessions: List<Session> = quietSessions(),
        wrongCount: Int = 0,
        dueCards: Int = 0,
        profile: SkillProfile = emptyProfile,
        target: SkillTargets? = targets,
        daysLeft: Int? = null,
        maxTasks: Int = DailyPlanCalc.DEFAULT_MAX_TASKS,
    ): List<DailyTask> = DailyPlanCalc.build(
        sessions = sessions,
        wrongCount = wrongCount,
        dueCards = dueCards,
        targets = target,
        now = now,
        daysLeft = daysLeft,
        maxTasks = maxTasks,
        profile = profile,
    )

    @Test
    fun `复练间隔按自然日 昨晚练过今天下午就该提醒`() {
        val zone = java.time.ZoneId.systemDefault()
        val today = java.time.LocalDate.of(2027, 1, 15)
        val nowLocal = today.atTime(20, 0).atZone(zone).toInstant().toEpochMilli()
        val lastNight = today.minusDays(1).atTime(22, 0).atZone(zone).toInstant().toEpochMilli()
        val sessions = listOf(
            done("speaking", lastNight),
            done("writing", nowLocal - 60_000),
        )
        // 距考试 5 天 → 口语间隔收紧到 1 天；按 24 小时算 22h 还没到，旧逻辑会漏掉提醒。
        val tasks = DailyPlanCalc.build(
            sessions = sessions,
            wrongCount = 0,
            dueCards = 0,
            targets = targets,
            now = nowLocal,
            daysLeft = 5,
            maxTasks = DailyPlanCalc.DEFAULT_MAX_TASKS,
            profile = emptyProfile,
        )
        assertTrue(tasks.any { it.key == "due:speaking" })
    }

    @Test
    fun `没有到期任务时给一条自由练习，首页不会空白`() {
        val tasks = build(target = null)
        assertEquals(1, tasks.size)
        assertEquals(DailyTaskKind.FREE, tasks.single().kind)
        assertEquals("bank", tasks.single().module)
        assertTrue(tasks.single().detail.contains("没有到期任务"))
    }

    @Test
    fun `错题优先于到期卡与复练`() {
        val tasks = build(wrongCount = 3, dueCards = 5, target = null)
        assertEquals(DailyTaskKind.WRONG, tasks.first().kind)
        assertTrue(tasks.first().title.contains("重刷 3 张"))
        // 到期卡比「从没练过」的口语写作更优先，否则新手永远看不到卡片任务
        assertEquals(DailyTaskKind.CARDS, tasks[1].kind)
    }

    @Test
    fun `最弱科任务带当前分、目标分和时长`() {
        val profile = SkillProfile(listening = 5.0, reading = 7.0, writing = 7.0, speaking = 7.0)
        val gap = build(profile = profile).first { it.kind == DailyTaskKind.GAP }
        assertEquals("gap:listening", gap.key)
        assertEquals("听力差 1.5 分 · 优先补", gap.title)
        assertEquals("当前 5.0 · 目标 6.5", gap.detail)
        assertEquals(15, gap.minutes)
        assertEquals("listening", gap.module)
    }

    @Test
    fun `最弱科正好到点复练时合并成一条`() {
        val profile = SkillProfile(null, null, null, 5.5)
        val tasks = build(
            sessions = listOf(done("speaking", now - 3 * DailyPlanCalc.DAY_MS), done("writing", now - 60_000)),
            profile = profile,
        )
        val speaking = tasks.filter { it.module == "speaking" }
        assertEquals(1, speaking.size)
        assertEquals(DailyTaskKind.SPEAKING, speaking.single().kind)
        assertEquals("口语该练了", speaking.single().title)
        assertTrue(speaking.single().detail.contains("距上次 3 天"))
        assertTrue(speaking.single().detail.contains("当前 5.5 差 1.0"))
        assertFalse(tasks.any { it.kind == DailyTaskKind.GAP })
    }

    @Test
    fun `评分失败的记录不算练过`() {
        val sessions = listOf(
            Session(id = 1, module = "speaking", status = StatusLabel.PENDING_EVAL, startedAt = now - 60_000),
            done("writing", now - 60_000),
        )
        val tasks = build(sessions = sessions, target = null)
        val speaking = tasks.first { it.module == "speaking" }
        assertEquals("还没开口过", speaking.detail)
    }

    @Test
    fun `临考一周内口语每天复练`() {
        // 距上次 1 天：平时间隔 2 天不该提醒；考前 5 天收紧到每天，必须提醒。
        val sessions = listOf(done("speaking", now - DailyPlanCalc.DAY_MS), done("writing", now - 60_000))
        assertFalse(build(sessions = sessions, target = null).any { it.kind == DailyTaskKind.SPEAKING })
        assertTrue(build(sessions = sessions, target = null, daysLeft = 5).any { it.kind == DailyTaskKind.SPEAKING })
    }

    @Test
    fun `临考提示写进任务依据`() {
        val task = build(wrongCount = 1, target = null, daysLeft = 3).first { it.kind == DailyTaskKind.WRONG }
        assertTrue(task.detail.contains("临考 3 天"))
    }

    @Test
    fun `按优先级最多保留三条`() {
        val profile = SkillProfile(listening = 5.0, reading = 7.0, writing = 7.0, speaking = 7.0)
        val tasks = build(wrongCount = 2, dueCards = 3, profile = profile)
        assertEquals(3, tasks.size)
        assertEquals(
            listOf(DailyTaskKind.WRONG, DailyTaskKind.GAP, DailyTaskKind.CARDS),
            tasks.map { it.kind },
        )
    }

    @Test
    fun `没有四科目标时不推最弱科`() {
        val profile = SkillProfile(listening = 5.0, reading = 5.0, writing = 5.0, speaking = 5.0)
        val tasks = build(profile = profile, target = null)
        assertFalse(tasks.any { it.kind == DailyTaskKind.GAP })
    }

    @Test
    fun `到期卡时长随数量增长但有上限`() {
        val few = build(dueCards = 3, target = null).first { it.kind == DailyTaskKind.CARDS }
        val many = build(dueCards = 50, target = null, maxTasks = 5).first { it.kind == DailyTaskKind.CARDS }
        assertEquals(5, few.minutes)
        assertEquals(20, many.minutes)
    }

    @Test
    fun `今日总时长等于各任务之和`() {
        val tasks = build(wrongCount = 3, dueCards = 4, target = null)
        assertEquals(tasks.sumOf { it.minutes }, DailyPlanCalc.totalMinutes(tasks))
        assertTrue(DailyPlanCalc.totalMinutes(tasks) > 0)
    }
}
