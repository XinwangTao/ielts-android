package com.txw.ielts

import com.txw.ielts.data.ExamTarget
import com.txw.ielts.ui.PlanDayKind
import com.txw.ielts.ui.PlanOverrides
import com.txw.ielts.ui.SkillProfile
import com.txw.ielts.ui.SkillTargets
import com.txw.ielts.ui.StudyPlanCalc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 7 天课表的排期规则。
 *
 * 这些输入就是首页/课表页的输入：考期、四科目标与估值、错题与到期卡数量。
 * 算错的后果是可见的——考前一周还在排整套模考、强科一周排三次而写作一次都没有、
 * 或者考试日期已过还显示「距考试 -3 天」。
 */
class StudyPlanTest {

    private val now = 1_800_000_000_000L
    private val today: LocalDate =
        Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()

    private val targets = SkillTargets(6.5, 6.5, 6.5, 6.5)
    private val blank = SkillProfile(null, null, null, null)

    private fun examIn(days: Int): String = today.plusDays(days.toLong()).toString()

    private fun build(
        examDate: String = "",
        profile: SkillProfile = blank,
        target: SkillTargets? = null,
        dueCards: Int = 0,
        wrongCount: Int = 0,
        overrides: Map<String, String> = emptyMap(),
    ) = StudyPlanCalc.build(
        now = now,
        examDate = examDate,
        targets = target,
        profile = profile,
        dueCards = dueCards,
        wrongCount = wrongCount,
        overrides = overrides,
    )

    @Test
    fun `未设考试日期时排满 7 天并留休息日`() {
        val plan = build()
        assertEquals(7, plan.days.size)
        assertEquals(1, plan.days.count { it.kind == PlanDayKind.REST })
        assertEquals(1, plan.days.count { it.kind == PlanDayKind.MOCK })
        assertEquals(0, plan.days.count { it.kind == PlanDayKind.EXAM })
        assertEquals(0, plan.days.count { it.kind == PlanDayKind.LIGHT })
        assertEquals("今天", plan.days.first().label)
        assertEquals("明天", plan.days[1].label)
        assertTrue(plan.headline.contains("未设考试日期"))
    }

    @Test
    fun `考试在 2 天后时课表只排到考试日并考前轻复习`() {
        val plan = build(examDate = examIn(2))
        assertEquals(3, plan.days.size)
        assertEquals(PlanDayKind.LIGHT, plan.days[1].kind)
        assertEquals(PlanDayKind.EXAM, plan.days[2].kind)
        // 考前三天排模考只会打击信心，且没有休息日
        assertEquals(0, plan.days.count { it.kind == PlanDayKind.MOCK })
        assertEquals(0, plan.days.count { it.kind == PlanDayKind.REST })
        assertEquals(examIn(2), plan.days[2].date)
    }

    @Test
    fun `考试当天只剩考试日`() {
        val plan = build(examDate = examIn(0))
        assertEquals(1, plan.days.size)
        assertEquals(PlanDayKind.EXAM, plan.days.single().kind)
        assertEquals(0, plan.days.single().minutes)
        assertTrue(plan.headline.contains("今天"))
    }

    @Test
    fun `考试日期已过时不给假倒排`() {
        val plan = build(examDate = examIn(-3))
        assertTrue(plan.days.isEmpty())
        assertEquals("已考完", plan.intensity)
        assertNotNull(plan.note)
        // 不能出现负数天数的文案
        assertFalse(plan.headline.contains("-3"))
    }

    @Test
    fun `距考 30 天有模考也有休息日，距考 10 天不休息`() {
        val relaxed = build(examDate = examIn(30))
        assertEquals(7, relaxed.days.size)
        assertEquals(PlanDayKind.MOCK, relaxed.days[5].kind)
        assertEquals(PlanDayKind.REST, relaxed.days[6].kind)

        val sprint = build(examDate = examIn(10))
        assertEquals(1, sprint.days.count { it.kind == PlanDayKind.MOCK })
        assertEquals(0, sprint.days.count { it.kind == PlanDayKind.REST })
        assertEquals(6, sprint.days.count { it.kind == PlanDayKind.PRACTICE })
    }

    @Test
    fun `最弱科一周排得最多并标出原因`() {
        val profile = SkillProfile(6.5, 6.5, 5.0, 6.5)
        val plan = build(examDate = examIn(10), profile = profile, target = targets)
        val counts = StudyPlanCalc.MODULES.associateWith { m ->
            plan.days.count { it.kind == PlanDayKind.PRACTICE && it.module == m }
        }
        // 权重 2.5 对 1：6 个练习日里写作拿 3 次（2.5/1、2.5/2、2.5/3 均高于其它科的 1）
        assertTrue(counts.getValue("writing") >= 2)
        assertEquals(counts.values.maxOrNull(), counts["writing"])
        val writingDays = plan.days.filter { it.module == "writing" }
        assertTrue(writingDays.first().detail.contains("最弱科"))
        // 第二次出现要标出「第 2 次」，否则两张卡看起来一样
        assertTrue(writingDays[1].detail.contains("本周第 2 次"))
    }

    @Test
    fun `强科权重再低也保底一次`() {
        val profile = SkillProfile(4.5, 4.5, 6.5, 4.5)
        val plan = build(examDate = examIn(10), profile = profile, target = targets)
        val counts = StudyPlanCalc.MODULES.associateWith { m ->
            plan.days.count { it.kind == PlanDayKind.PRACTICE && it.module == m }
        }
        assertEquals(1, counts["writing"])
        assertEquals(6, counts.values.sum())
    }

    @Test
    fun `相邻练习日不排同一科`() {
        val plan = build(examDate = examIn(10))
        val practice = plan.days.withIndex().filter { it.value.kind == PlanDayKind.PRACTICE }
        practice.zipWithNext().forEach { (a, b) ->
            if (b.index == a.index + 1) {
                assertTrue(
                    "第 ${a.index} 天和第 ${b.index} 天都排了 ${a.value.module}",
                    a.value.module != b.value.module,
                )
            }
        }
    }

    @Test
    fun `模考日固定在第六天且时长为整套`() {
        val plan = build(examDate = examIn(30))
        val mock = plan.days[5]
        assertEquals(PlanDayKind.MOCK, mock.kind)
        assertEquals("mock", mock.module)
        assertEquals(90, mock.minutes)
        assertFalse(mock.editable)
    }

    @Test
    fun `手动换科目可还原`() {
        val base = build(examDate = examIn(10))
        val day = base.days.first { it.kind == PlanDayKind.PRACTICE }
        val swapped = PlanOverrides.cycle(day.module)
        val edited = build(examDate = examIn(10), overrides = mapOf(day.date to swapped))
        val editedDay = edited.days.first { it.date == day.date }
        assertTrue(editedDay.swapped)
        assertEquals(swapped, editedDay.module)
        assertTrue(editedDay.detail.contains("已手动调整"))
        assertTrue(edited.hasManualEdits())

        val undone = build(examDate = examIn(10), overrides = emptyMap())
        assertFalse(undone.days.first { it.date == day.date }.swapped)
        assertEquals(day.module, undone.days.first { it.date == day.date }.module)
    }

    @Test
    fun `休息日与模考日不接受换科目`() {
        val base = build(examDate = examIn(30))
        val restDate = base.days[6].date
        val mockDate = base.days[5].date
        val edited = build(
            examDate = examIn(30),
            overrides = mapOf(restDate to "writing", mockDate to "speaking"),
        )
        assertEquals(PlanDayKind.REST, edited.days[6].kind)
        assertFalse(edited.days[6].swapped)
        assertEquals(PlanDayKind.MOCK, edited.days[5].kind)
        assertFalse(edited.days[5].swapped)
    }

    @Test
    fun `未知科目与坏日期的覆盖被忽略`() {
        val base = build(examDate = examIn(10))
        val date = base.days.first { it.kind == PlanDayKind.PRACTICE }.date
        val edited = build(examDate = examIn(10), overrides = mapOf(date to "math", "not-a-date" to "writing"))
        assertEquals(7, edited.days.size)
        assertFalse(edited.hasManualEdits())
    }

    @Test
    fun `课表说明写明分配与手头存量`() {
        val plan = build(examDate = examIn(30), dueCards = 12, wrongCount = 4)
        val note = plan.note ?: error("note 不应为空")
        assertTrue(note.contains("本周课表："))
        assertTrue(note.contains("另有一次整套模考"))
        assertTrue(note.contains("4 张错题待重刷"))
        assertTrue(note.contains("12 张卡片今天到期"))
        assertTrue(plan.totalMinutes() > 0)
    }

    @Test
    fun `临考期提醒最后一天只看错题`() {
        val plan = build(examDate = examIn(5))
        assertTrue(plan.note!!.contains("考前一天只看错题"))
        assertEquals("最后一周", plan.intensity)
        assertTrue(plan.headline.contains("距考试 5 天"))
    }

    @Test
    fun `优先级把考期换算成强度`() {
        assertEquals("自主节奏", StudyPlanCalc.intensityOf(null))
        assertEquals("最后一周", StudyPlanCalc.intensityOf(7))
        assertEquals("冲刺期", StudyPlanCalc.intensityOf(21))
        assertEquals("常规期", StudyPlanCalc.intensityOf(60))
        assertEquals("打基础期", StudyPlanCalc.intensityOf(61))
    }

    @Test
    fun `覆盖设置编码可往返且吞掉脏数据`() {
        val map = mapOf(examIn(1) to "writing", examIn(2) to "speaking")
        assertEquals(map, PlanOverrides.decode(PlanOverrides.encode(map)))
        assertEquals(emptyMap<String, String>(), PlanOverrides.decode(""))
        assertEquals(emptyMap<String, String>(), PlanOverrides.decode("不是 json"))
        assertEquals(emptyMap<String, String>(), PlanOverrides.decode("{}"))
        // 脏值不入库：不认识的科目、解析不了的时间
        val dirty = PlanOverrides.encode(mapOf("2026-13-40" to "writing", examIn(3) to "math"))
        assertEquals(emptyMap<String, String>(), PlanOverrides.decode(dirty))
    }

    @Test
    fun `换一科按四科循环且未知回到第一科`() {
        assertEquals("reading", PlanOverrides.cycle("listening"))
        assertEquals("speaking", PlanOverrides.cycle("writing"))
        assertEquals("listening", PlanOverrides.cycle("speaking"))
        assertEquals("listening", PlanOverrides.cycle("unknown"))
    }

    @Test
    fun `课表日期逐日递增且与考期对齐`() {
        val plan = build(examDate = examIn(9))
        plan.days.forEachIndexed { index, day ->
            assertEquals(today.plusDays(index.toLong()).toString(), day.date)
            assertEquals(index, ExamTarget.daysUntil(day.date, now))
        }
    }
}
