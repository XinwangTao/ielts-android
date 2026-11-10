package com.txw.ielts

import com.txw.ielts.data.BackupRestore
import org.junit.Assert.assertEquals
import org.junit.Test

/** 备份导入对 firstLearnedAt 的兼容：旧备份没有该字段时不能把已学卡算成今天的新卡。 */
class BackupRestoreFirstLearnedTest {

    @Test
    fun `导入卡片兼容 firstLearnedAt`() {
        val p = BackupRestore.parse(
            """{"version":1,"cards":[
                {"front":"a","reps":3,"lastReviewed":123},
                {"front":"b","reps":0,"lastReviewed":456},
                {"front":"c","reps":1,"lastReviewed":999,"firstLearnedAt":789}
            ]}"""
        )
        // 有复习记录的旧卡用 lastReviewed 近似。
        assertEquals(123L, p.cards[0].firstLearnedAt)
        // 没学过的卡保持 0，不能凭空算成今天学的。
        assertEquals(0L, p.cards[1].firstLearnedAt)
        // 新备份里的显式字段优先。
        assertEquals(789L, p.cards[2].firstLearnedAt)
    }
}
