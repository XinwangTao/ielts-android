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

    @Test
    fun `显式仅云端又断网时提前报错并给出改法`() {
        val msg = ChannelPick.cloudOfflineError("cloud", hasNetwork = false)
        assertTrue(msg != null && msg.contains("仅本地"))
        assertTrue(msg != null && msg.contains("无网络"))
    }

    @Test
    fun `自动或仅本地断网不拦请求`() {
        // auto 断网会落本地，local 本来就离线；只有 cloud 是必失败。
        assertNull(ChannelPick.cloudOfflineError("auto", hasNetwork = false))
        assertNull(ChannelPick.cloudOfflineError("local", hasNetwork = false))
        assertNull(ChannelPick.cloudOfflineError("cloud", hasNetwork = true))
    }

    @Test
    fun `首页提示说清自动降级原因`() {
        val offline = ChannelPick.notice(
            "auto", cloudDown = false, hasNetwork = false, remainingCalls = 99,
        )
        assertEquals("无网络：已改用本地模型", offline?.title)
        assertTrue(offline != null && offline.detail.contains("端侧"))
        assertFalse(offline!!.needsAction)

        val cooling = ChannelPick.notice(
            "auto", cloudDown = true, hasNetwork = true, remainingCalls = 99,
        )
        assertTrue(cooling != null && cooling.title.contains("冷却"))

        val noQuota = ChannelPick.notice(
            "auto", cloudDown = false, hasNetwork = true, remainingCalls = 0,
        )
        assertTrue(noQuota != null && noQuota.title.contains("额度已用完"))
    }

    @Test
    fun `显式仅云端断网提示需要动手改设置`() {
        val n = ChannelPick.notice("cloud", cloudDown = false, hasNetwork = false, remainingCalls = 99)
        assertTrue(n != null && n.needsAction)
        assertTrue(n != null && n.detail.contains("设置页"))
    }

    @Test
    fun `仅本地或一切正常时不打扰用户`() {
        assertNull(ChannelPick.notice("local", cloudDown = true, hasNetwork = false, remainingCalls = 0))
        assertNull(ChannelPick.notice("cloud", cloudDown = false, hasNetwork = true, remainingCalls = 99))
        assertNull(ChannelPick.notice("auto", cloudDown = false, hasNetwork = true, remainingCalls = 99))
    }

    // —— 降级到本地之前先确认本地真的可用 ——

    @Test
    fun `自动降级但本地模型未投递时要说会失败并给出口`() {
        val offline = ChannelPick.notice(
            "auto", cloudDown = false, hasNetwork = false, remainingCalls = 99,
            localAvailable = false,
        )
        assertTrue(offline != null && offline.title.contains("本地模型还没就绪"))
        assertTrue(offline != null && offline.detail.contains("失败"))
        assertTrue(offline != null && offline.detail.contains("设置页"))
        assertTrue(offline!!.needsAction)

        val cooling = ChannelPick.notice(
            "auto", cloudDown = true, hasNetwork = true, remainingCalls = 99,
            localAvailable = false,
        )
        assertTrue(cooling != null && cooling.title.contains("本地模型还没就绪"))
        assertTrue(cooling!!.needsAction)

        val noQuota = ChannelPick.notice(
            "auto", cloudDown = false, hasNetwork = true, remainingCalls = 0,
            localAvailable = false,
        )
        assertTrue(noQuota != null && noQuota.title.contains("本地模型还没就绪"))
        assertTrue(noQuota!!.needsAction)
    }

    @Test
    fun `本地模型可用时不再误报降级失败`() {
        // 同一个断网场景，本地可用就仍然只说「已改用本地模型」，不要变成错误提示。
        val n = ChannelPick.notice(
            "auto", cloudDown = false, hasNetwork = false, remainingCalls = 99,
            localAvailable = true,
        )
        assertEquals("无网络：已改用本地模型", n?.title)
        assertFalse(n!!.needsAction)
    }

    @Test
    fun `仅本地但模型未投递也要提醒`() {
        // 主动选「仅本地」不代表通道可用：没投递时每次评分都会失败，首页不能一声不响。
        val n = ChannelPick.notice(
            "local", cloudDown = false, hasNetwork = false, remainingCalls = 0,
            localAvailable = false,
        )
        assertTrue(n != null && n.needsAction)
        assertTrue(n != null && n.title.contains("还没就绪"))
        assertNull(
            ChannelPick.notice(
                "local", cloudDown = false, hasNetwork = false, remainingCalls = 0,
                localAvailable = true,
            )
        )
    }

    @Test
    fun `仅云端断网且本地也未投递时不再建议切到本地`() {
        val msg = ChannelPick.cloudOfflineError("cloud", hasNetwork = false, localAvailable = false)
        assertTrue(msg != null && msg.contains("无网络"))
        // 原来的文案让用户「改为仅本地/自动」，但本地模型没投递时那句话同样是死路。
        assertFalse(msg!!.contains("改为「仅本地」"))
        assertTrue(msg.contains("投递"))
    }
}
