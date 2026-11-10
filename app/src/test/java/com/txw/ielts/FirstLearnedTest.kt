package com.txw.ielts

import com.txw.ielts.data.FirstLearned
import org.junit.Assert.assertEquals
import org.junit.Test

class FirstLearnedTest {

    @Test
    fun `first rating records now`() {
        assertEquals(1_700_000L, FirstLearned.next(existing = 0L, repsAfter = 1, now = 1_700_000L))
    }

    @Test
    fun `later ratings keep the first timestamp`() {
        assertEquals(1_700_000L, FirstLearned.next(existing = 1_700_000L, repsAfter = 4, now = 1_800_000L))
    }

    @Test
    fun `again on an old card does not count as newly learned`() {
        // 昨天学过（existing 非 0），今天评 Again 仍保留原首次时间。
        assertEquals(1_600_000L, FirstLearned.next(existing = 1_600_000L, repsAfter = 3, now = 1_800_000L))
    }

    @Test
    fun `rating without reps stays unlearned`() {
        assertEquals(0L, FirstLearned.next(existing = 0L, repsAfter = 0, now = 1_800_000L))
    }

    @Test
    fun `legacy backfill uses lastReviewed as approximation`() {
        // 迁移 3→4 对旧数据只能用 lastReviewed 近似，这里锁住「有复习记录才算学过」的口径。
        assertEquals(0L, FirstLearned.next(existing = 0L, repsAfter = 0, now = 1_900_000L))
        assertEquals(1_500_000L, FirstLearned.next(existing = 1_500_000L, repsAfter = 2, now = 1_900_000L))
    }
}
