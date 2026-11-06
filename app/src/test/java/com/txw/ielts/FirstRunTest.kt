package com.txw.ielts

import com.txw.ielts.data.FirstRun
import com.txw.ielts.data.LocalModelState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * 首启引导的判定：新装机第一次打开时，用户必须能从这三步看出「现在能不能出分」。
 *
 * 这里的判断写反的后果都是真实的：说「已就绪」但点评分报错，
 * 或者全都配好了还被引导拦在首页外。
 */
class FirstRunTest {

    private val now = 1_800_000_000_000L

    private fun date(offsetDays: Long): String =
        Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate().plusDays(offsetDays).toString()

    private fun plan(
        targetBand: Float = 6.5f,
        examDate: String = date(30),
        localModel: String = "qwen3-4b-instruct",
        localModelState: LocalModelState = LocalModelState.READY,
        cloudKeyReady: Boolean = false,
        online: Boolean = true,
        practiceCount: Int = 0,
    ) = FirstRun.plan(
        targetBand = targetBand,
        examDate = examDate,
        localModel = localModel,
        localModelState = localModelState,
        cloudKeyReady = cloudKeyReady,
        online = online,
        practiceCount = practiceCount,
        nowMs = now,
    )

    @Test
    fun `步骤顺序固定为目标-AI-第一题`() {
        val p = plan()
        assertEquals(
            listOf(FirstRun.STEP_TARGET, FirstRun.STEP_AI, FirstRun.STEP_PRACTICE),
            p.steps.map { it.id },
        )
    }

    @Test
    fun `全部满足时没有待办且引导结论是开始练习`() {
        val p = plan(practiceCount = 3)
        assertEquals(0, p.pending)
        assertTrue(p.allReady)
        assertTrue(p.steps.all { it.ready })
        assertTrue(p.headline.contains("都准备好了"))
        // 全打勾的步骤不该再给按钮，否则用户会以为还有事没做
        assertTrue(p.steps.all { it.action == null })
    }

    @Test
    fun `没设考期时目标步骤未完成并给去设置`() {
        val target = plan(examDate = "").step(FirstRun.STEP_TARGET)!!
        assertFalse(target.ready)
        assertEquals("去设置", target.action)
        assertTrue(target.detail.contains("考试日期"))
    }

    @Test
    fun `考期格式错误也算未完成`() {
        val target = plan(examDate = "2026/03/14").step(FirstRun.STEP_TARGET)!!
        assertFalse(target.ready)
        assertTrue(target.detail.contains("yyyy-MM-dd"))
    }

    @Test
    fun `考期已过时提示过去多少天`() {
        val target = plan(examDate = date(-5)).step(FirstRun.STEP_TARGET)!!
        assertFalse(target.ready)
        assertTrue(target.detail.contains("已经过去 5 天"))
    }

    @Test
    fun `考期有效时显示目标分与剩余天数`() {
        val target = plan(targetBand = 7.0f, examDate = date(30)).step(FirstRun.STEP_TARGET)!!
        assertTrue(target.ready)
        assertTrue(target.detail.contains("7.0"))
        assertTrue(target.detail.contains("还剩 30 天"))
        assertNull(target.action)
    }

    @Test
    fun `本地模型就绪时 AI 步骤打勾并点名模型`() {
        val ai = plan(localModelState = LocalModelState.READY, online = false).step(FirstRun.STEP_AI)!!
        assertTrue(ai.ready)
        assertTrue(ai.detail.contains("qwen3-4b-instruct"))
        assertTrue(ai.detail.contains("断网也能评分"))
    }

    @Test
    fun `模型已投递但未拷贝时也算可用并说明首次要等`() {
        val ai = plan(localModelState = LocalModelState.DELIVERED).step(FirstRun.STEP_AI)!!
        assertTrue(ai.ready)
        assertTrue(ai.detail.contains("拷贝"))
    }

    @Test
    fun `没有本地模型但云端 key 在线时仍算可用`() {
        val ai = plan(localModelState = LocalModelState.MISSING, cloudKeyReady = true, online = true)
            .step(FirstRun.STEP_AI)!!
        assertTrue(ai.ready)
        assertTrue(ai.detail.contains("云端"))
        assertNull(ai.action)
    }

    @Test
    fun `云端 key 有但断网且无本地模型时判为不可用`() {
        val ai = plan(localModelState = LocalModelState.MISSING, cloudKeyReady = true, online = false)
            .step(FirstRun.STEP_AI)!!
        assertFalse(ai.ready)
        assertEquals("去设置", ai.action)
        assertTrue(ai.detail.contains("一定失败"))
    }

    @Test
    fun `两条通道都没准备时提示会直接报错`() {
        val ai = plan(localModelState = LocalModelState.MISSING, cloudKeyReady = false, online = true)
            .step(FirstRun.STEP_AI)!!
        assertFalse(ai.ready)
        assertTrue(ai.detail.contains("api key", ignoreCase = true))
        assertEquals("去设置", ai.action)
    }

    @Test
    fun `没网也没模型时说明完全无法评分`() {
        val ai = plan(localModelState = LocalModelState.MISSING, cloudKeyReady = false, online = false)
            .step(FirstRun.STEP_AI)!!
        assertFalse(ai.ready)
        assertTrue(ai.detail.contains("没法评分") || ai.detail.contains("无法评分"))
    }

    @Test
    fun `没练过题时练习步骤给去做题`() {
        val step = plan(practiceCount = 0).step(FirstRun.STEP_PRACTICE)!!
        assertFalse(step.ready)
        assertEquals("去做题", step.action)
    }

    @Test
    fun `练过题后练习步骤打勾并带上次数`() {
        val step = plan(practiceCount = 4).step(FirstRun.STEP_PRACTICE)!!
        assertTrue(step.ready)
        assertTrue(step.detail.contains("4 次"))
        assertNull(step.action)
    }

    @Test
    fun `待办数只统计未完成的步骤`() {
        val p = plan(examDate = "", localModelState = LocalModelState.MISSING, cloudKeyReady = false, practiceCount = 0)
        assertEquals(3, p.pending)
        assertTrue(p.headline.contains("还差 3 步"))

        val one = plan(examDate = "", practiceCount = 2)
        assertEquals(1, one.pending)
        assertTrue(one.headline.contains("还差 1 步"))
    }

    @Test
    fun `看完之后不再自动弹出`() {
        assertTrue(FirstRun.shouldAutoShow(completed = false))
        assertFalse(FirstRun.shouldAutoShow(completed = true))
    }

    @Test
    fun `考期当天剩余天数为零仍算有效`() {
        val target = plan(examDate = date(0)).step(FirstRun.STEP_TARGET)!!
        assertTrue(target.ready)
        assertTrue(target.detail.contains("还剩 0 天"))
    }
}
