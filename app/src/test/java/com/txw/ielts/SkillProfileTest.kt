package com.txw.ielts

import com.txw.ielts.data.Session
import com.txw.ielts.ui.SkillProfile
import com.txw.ielts.ui.SkillTargets
import com.txw.ielts.ui.skillProfile
import com.txw.ielts.ui.subTargetLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 首页四科那行字：箭头方向、达标标记和「是否显示四科目标」都在这里定，算错就是误导。 */
class SkillProfileTest {

    private val profile = SkillProfile(listening = 6.0, reading = 6.5, writing = null, speaking = 5.5)

    @Test
    fun `没有目标时只显示估值`() {
        assertEquals("L 6.0 · R 6.5 · W 暂无 · S 5.5", profile.line())
    }

    @Test
    fun `有目标时标出差距与达标`() {
        val targets = SkillTargets(6.5, 6.5, 6.5, 6.5)
        assertEquals("L 6.0↓0.5 · R 6.5✓ · W 暂无 · S 5.5↓1.0", profile.line(targets))
    }

    @Test
    fun `超出目标也算达标，不出现负数差距`() {
        val targets = SkillTargets(6.0, 6.0, 6.0, 6.0)
        assertEquals("L 6.0✓ · R 6.5✓ · W 暂无 · S 5.5↓0.5", profile.line(targets))
    }

    @Test
    fun `四科目标与总分一致时不显示注脚`() {
        assertNull(subTargetLine(SkillTargets(6.5, 6.5, 6.5, 6.5), 6.5))
        assertNull(subTargetLine(null, 6.5))
    }

    @Test
    fun `某一科单独调整时显示四科目标与换算总分`() {
        // 口语保底 6.0：6.5+6.5+6.5+6.0 = 25.5 → 平均 6.375 → 总分 6.5
        val line = subTargetLine(SkillTargets(6.5, 6.5, 6.5, 6.0), 6.5)
        assertEquals("四科目标 L 6.5 · R 6.5 · W 6.5 · S 6.0，换算总分 6.5", line)
    }

    @Test
    fun `四科与总分整体不一致时也显示`() {
        // 总分填 7.0，四科还全在 6.5：换算只有 6.5，必须让用户看见
        val line = subTargetLine(SkillTargets(6.5, 6.5, 6.5, 6.5), 7.0)
        assertEquals("四科目标 L 6.5 · R 6.5 · W 6.5 · S 6.5，换算总分 6.5", line)
    }

    private fun bandSession(module: String, overall: Double, at: Long) = Session(
        id = at, module = module, status = "done", startedAt = at,
        scoresJson = """{"overall":$overall}""",
    )

    private fun accSession(module: String, correct: Int, total: Int, at: Long) = Session(
        id = at, module = module, status = "done", startedAt = at,
        scoresJson = """{"correct":$correct,"total":$total}""",
    )

    @Test
    fun `四科估值取最近五次，不是最早五次`() {
        // Room 返回倒序（最新在前）。老数据 6 条 5.0，新数据 5 条 8.0，估值应为 8.0。
        val old = (1L..6L).map { bandSession("writing", 5.0, it) }
        val recent = (1L..5L).map { bandSession("writing", 8.0, 100 + it) }
        val sessions = (recent + old).sortedByDescending { it.startedAt }
        assertEquals(8.0, skillProfile(sessions).writing!!, 1e-9)
    }

    @Test
    fun `听读按最近五次正确率映射`() {
        val old = (1L..6L).map { accSession("reading", 4, 10, it) }      // 40% → 5.0
        val recent = (1L..5L).map { accSession("reading", 9, 10, 100 + it) } // 90% → 7.5
        val sessions = (recent + old).sortedByDescending { it.startedAt }
        assertEquals(7.5, skillProfile(sessions).reading!!, 1e-9)
    }

    @Test
    fun `没练过的科目为 null，不拿别的科目顶替`() {
        val sessions = listOf(bandSession("writing", 6.5, 10))
        val p = skillProfile(sessions)
        assertEquals(6.5, p.writing!!, 1e-9)
        assertNull(p.listening)
        assertNull(p.reading)
        assertNull(p.speaking)
    }
}
