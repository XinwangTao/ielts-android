package com.txw.ielts

import com.txw.ielts.llm.ChannelPick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 选路规则：原来写作/口语各抄一遍，AI 出题三处完全没有 ——
 * 云端额度死掉时那三条路每次都先打一次必失败的请求再回退。
 */
class ChannelPickTest {

    @Test
    fun `仅本地模式永远走本地`() {
        assertEquals("local", ChannelPick.effective("local", cloudDown = false, hasNetwork = true, remainingCalls = 99))
        assertEquals("local", ChannelPick.effective("local", cloudDown = false, hasNetwork = false, remainingCalls = 0))
    }

    @Test
    fun `显式选云端时即使冷却也照打 不偷偷降级`() {
        // 用户明确选了 cloud，失败要报错让他知道，而不是静默跑本地。
        assertEquals("cloud", ChannelPick.effective("cloud", cloudDown = true, hasNetwork = false, remainingCalls = 0))
    }

    @Test
    fun `auto 在冷却 断网 无额度时直接落本地`() {
        assertEquals("local", ChannelPick.effective("auto", cloudDown = true, hasNetwork = true, remainingCalls = 99))
        assertEquals("local", ChannelPick.effective("auto", cloudDown = false, hasNetwork = false, remainingCalls = 99))
        assertEquals("local", ChannelPick.effective("auto", cloudDown = false, hasNetwork = true, remainingCalls = 0))
    }

    @Test
    fun `auto 一切正常时先试云端`() {
        assertEquals("auto", ChannelPick.effective("auto", cloudDown = false, hasNetwork = true, remainingCalls = 1))
    }

    @Test
    fun `自动降级原因按断网 冷却 额度优先级给出`() {
        // 断网优先于额度冷却：用户先要知道的是「没网」。
        assertEquals(
            "当前无网络",
            ChannelPick.localReason("auto", cloudDown = true, hasNetwork = false, remainingCalls = 0),
        )
        assertEquals(
            "云端额度或限流冷却中",
            ChannelPick.localReason("auto", cloudDown = true, hasNetwork = true, remainingCalls = 99),
        )
        assertEquals(
            "今日云端额度已用完",
            ChannelPick.localReason("auto", cloudDown = false, hasNetwork = true, remainingCalls = 0),
        )
    }

    @Test
    fun `未降级或显式选通道时没有提示`() {
        assertNull(ChannelPick.localReason("auto", cloudDown = false, hasNetwork = true, remainingCalls = 99))
        assertNull(ChannelPick.localReason("local", cloudDown = false, hasNetwork = false, remainingCalls = 0))
        assertNull(ChannelPick.localReason("cloud", cloudDown = false, hasNetwork = false, remainingCalls = 0))
    }

    @Test
    fun `额度类失败要识别出来 否则不会冷却`() {
        assertTrue(ChannelPick.isQuotaFailure("云端额度不可用（HTTP 403 / 11200：无有效订单或额度已用尽）"))
        assertTrue(ChannelPick.isQuotaFailure("HTTP 429: rate limited"))
        assertTrue(ChannelPick.isQuotaFailure("insufficient quota"))
        assertTrue(ChannelPick.isQuotaFailure("额度已用尽"))
    }

    @Test
    fun `瞬时故障不该被当成额度死亡`() {
        assertFalse(ChannelPick.isQuotaFailure("java.net.SocketTimeoutException"))
        assertFalse(ChannelPick.isQuotaFailure("Unable to resolve host"))
        assertFalse(ChannelPick.isQuotaFailure(null))
        assertFalse(ChannelPick.isQuotaFailure(""))
    }
}
