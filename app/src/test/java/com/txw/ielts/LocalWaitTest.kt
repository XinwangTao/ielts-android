package com.txw.ielts

import com.txw.ielts.data.LocalWait
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归锁定「结果页重新评分出口永不激活」这个 bug：
 * 真机现象是口语/写作评分被 force-stop 打断后，结果页一直显示「评分中…（10 分钟后可重试）」，
 * 等 30 分钟也不会变成可点 —— 因为判定用的是父级组合期的快照，父级不重组就不会更新。
 */
class LocalWaitTest {

    @Test
    fun `等待不足 10 分钟且快照为 false 时不给出口`() {
        assertFalse(LocalWait.isStale(waitedSec = 0, staleFromSnapshot = false))
        assertFalse(LocalWait.isStale(waitedSec = 599, staleFromSnapshot = false))
    }

    @Test
    fun `仅凭每秒刷新的 waitedSec 到 10 分钟就该给出口`() {
        // 这正是修复点：快照一直是 false，但 waitedSec 会走到 600。
        assertTrue(LocalWait.isStale(waitedSec = 600, staleFromSnapshot = false))
        assertTrue(LocalWait.isStale(waitedSec = 3600, staleFromSnapshot = false))
    }

    @Test
    fun `父级快照已判死时立即给出口`() {
        assertTrue(LocalWait.isStale(waitedSec = 0, staleFromSnapshot = true))
    }

    @Test
    fun `文案优先级 运行中 高于 已中断`() {
        assertEquals("第 2 次生成中", LocalWait.retryLabel("第 2 次生成中", stale = true))
        assertEquals("评分已中断，重新评分", LocalWait.retryLabel(null, stale = true))
        assertEquals("评分中…（10 分钟后可重试）", LocalWait.retryLabel(null, stale = false))
    }
}
