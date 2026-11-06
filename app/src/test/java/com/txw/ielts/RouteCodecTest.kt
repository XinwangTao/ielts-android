package com.txw.ielts

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Route 编解码回归测试。
 *
 * 这层编解码是 rememberSaveable 的唯一依赖：编错了用户旋转屏就跳错页面，
 * 最坏情况是从「口语模考进行中」跳回「结果页」而丢作答。
 */
class RouteCodecTest {

    private fun roundTrip(r: Route): Route = decodeRoute(r.encode())

    @Test
    fun `单例页面编解码可还原`() {
        val pages = listOf(
            Route.Home, Route.FirstRun, Route.Writing, Route.Speaking, Route.Settings, Route.Review,
            Route.Corpus, Route.Study, Route.Bank, Route.AiGen, Route.Shadow,
            Route.Topics, Route.Arguments, Route.ModelEssays, Route.WritingMock,
            Route.ChartGen, Route.SpeakingPartner, Route.SpeakingMock,
        )
        pages.forEach { assertEquals(it, roundTrip(it)) }
    }

    @Test
    fun `带参页面编解码可还原`() {
        assertEquals(Route.Quiz("reading-1"), roundTrip(Route.Quiz("reading-1")))
        assertEquals(Route.BankResult(12L, "reading-1"), roundTrip(Route.BankResult(12L, "reading-1")))
        assertEquals(Route.ShadowResult(7L), roundTrip(Route.ShadowResult(7L)))
        assertEquals(Route.ShadowStudy("cam15-t2"), roundTrip(Route.ShadowStudy("cam15-t2")))
        assertEquals(Route.Result(34L), roundTrip(Route.Result(34L)))
    }

    @Test
    fun `模考多记录 id 列表可还原`() {
        assertEquals(Route.MockResult(listOf(1L, 2L, 3L)), roundTrip(Route.MockResult(listOf(1L, 2L, 3L))))
    }

    @Test
    fun `setId 含冒号不会解析崩`() {
        assertEquals(Route.BankResult(3L, "ai-1:2"), roundTrip(Route.BankResult(3L, "ai-1:2")))
    }

    @Test
    fun `未知或损坏的串一律回首页`() {
        // 存档里可能残留旧版本写的 route，宁可回首页也不能崩
        assertEquals(Route.Home, decodeRoute(""))
        assertEquals(Route.Home, decodeRoute("garbage"))
        assertEquals(Route.Home, decodeRoute("result:abc"))
        assertEquals(Route.Home, decodeRoute("mockresult:"))
        assertEquals(Route.Home, decodeRoute("shadowstudy:"))
        assertEquals(Route.Home, decodeRoute("quiz"))
    }
}
