package com.txw.ielts

import com.txw.ielts.data.StatusLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 状态到用户可见文案的映射。
 *
 * 回归点：复盘页曾把 status 原样渲染，用户看到 `pending_eval`；
 * MockResultScreen 曾用 `else -> "待评分"` 把 `asr_empty` 也归为待评分
 * （实际是「没听清、要重录」，两条完全不同的操作路径）。
 */
class StatusLabelTest {

    @Test
    fun `库里出现的五种状态都有人话`() {
        assertEquals("已完成", StatusLabel.label("done"))
        assertEquals("待评分", StatusLabel.label("pending_eval"))
        assertEquals("评分中", StatusLabel.label("pending_local"))
        assertEquals("没听清", StatusLabel.label("asr_empty"))
        assertEquals("已中断", StatusLabel.label("abandoned"))
    }

    @Test
    fun `未知状态不回显原串`() {
        // 新增状态时宁可含糊，也不要把 some_new_state 怼到用户脸上
        assertEquals("处理中", StatusLabel.label("some_new_state"))
        assertEquals("处理中", StatusLabel.label(""))
    }

    @Test
    fun `每个已知状态都有自己的文案且互不重复`() {
        val all = listOf(
            StatusLabel.DONE, StatusLabel.PENDING_EVAL, StatusLabel.PENDING_LOCAL,
            StatusLabel.ASR_EMPTY, StatusLabel.ABANDONED,
        ).map { StatusLabel.label(it) }
        assertEquals("文案不应重复，否则用户分不清状态", all.size, all.toSet().size)
    }

    @Test
    fun `需要用户动手的状态`() {
        assertTrue(StatusLabel.needsAction(StatusLabel.PENDING_EVAL))
        assertTrue(StatusLabel.needsAction(StatusLabel.ASR_EMPTY))
        assertTrue(StatusLabel.needsAction(StatusLabel.ABANDONED))
        assertFalse(StatusLabel.needsAction(StatusLabel.DONE))
        assertFalse(StatusLabel.needsAction(StatusLabel.PENDING_LOCAL))
    }

    @Test
    fun `常量值与库里的字面量一致`() {
        // 这些串同时被 4 条落库链路和 3 处查询使用，改了要同步改库
        assertEquals("done", StatusLabel.DONE)
        assertEquals("pending_eval", StatusLabel.PENDING_EVAL)
        assertEquals("pending_local", StatusLabel.PENDING_LOCAL)
        assertEquals("asr_empty", StatusLabel.ASR_EMPTY)
        assertEquals("abandoned", StatusLabel.ABANDONED)
    }
}
