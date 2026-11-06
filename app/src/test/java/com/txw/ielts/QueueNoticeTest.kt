package com.txw.ielts

import com.txw.ielts.data.QueueNotice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 空队列提示的文案与口径。
 *
 * 真机现象：首页「今日任务」点「复习 3 张到期卡片」，因为到期数把错题卡算进去了，
 * 实际队列是空的，而错误字段只在语料页显示 —— 点击看起来完全没反应。
 * 提示必须做到三件事：说清是「今天没有」而不是出错；有错题可练时给真实张数并指向错题专项；
 * 没有错题时不编造一个「0 张」。
 */
class QueueNoticeTest {

    @Test
    fun `到期队列为空时说明只是今天没有，不是失败`() {
        val notice = QueueNotice.emptyReview(wrongAvailable = 0)
        assertEquals("今天没有到期的复习卡", notice.title)
        assertTrue(notice.detail.contains("新卡"))
        assertTrue(notice.detail.contains("题库"))
        assertFalse(notice.detail.contains("错题专项里还有 0"))
        // 正常状态不能用报错口吻，否则用户以为复习坏了。
        assertFalse(notice.title.contains("失败"))
        assertFalse(notice.title.contains("错误"))
    }

    @Test
    fun `有错题可练时给真实张数并指向错题专项`() {
        val notice = QueueNotice.emptyReview(wrongAvailable = 3)
        assertTrue(notice.detail.contains("3 张"))
        assertTrue(notice.detail.contains("错题专项"))
        assertFalse(notice.detail.contains("新卡明天"))
    }

    @Test
    fun `负的错题数按零处理，不出现负数张`() {
        val notice = QueueNotice.emptyReview(wrongAvailable = -2)
        assertFalse(notice.detail.contains("-2"))
        assertFalse(notice.detail.contains("错题专项里还有"))
    }

    @Test
    fun `错题队列为空时说明只是没到到期时间`() {
        val notice = QueueNotice.emptyWrong()
        assertEquals("暂无到期的错题", notice.title)
        assertTrue(notice.detail.contains("到期"))
        assertTrue(notice.detail.contains("自动"))
        assertFalse(notice.title.contains("失败"))
    }
}
