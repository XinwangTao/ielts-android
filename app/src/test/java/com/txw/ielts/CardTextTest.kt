package com.txw.ielts

import com.txw.ielts.data.CardText
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 生成卡清洗/去重回归：AI 批注与错题常带多余空白、大小写漂移和句末标点，
 * 不归一化就会反复建近重复卡，把 AI 卡池挤满。
 */
class CardTextTest {

    @Test
    fun `front 压平空白并去首尾`() {
        assertEquals("use 'however' to contrast", CardText.front("  use   'however'\n to contrast  ", 70))
    }

    @Test
    fun `front 超长截断并补省略号`() {
        val raw = "a".repeat(100)
        val front = CardText.front(raw, 20)
        assertEquals(21, front.length)
        assertEquals("a".repeat(20) + "…", front)
    }

    @Test
    fun `front 恰好等于上限时不截断`() {
        val raw = "b".repeat(20)
        assertEquals(raw, CardText.front(raw, 20))
    }

    @Test
    fun `front 全空白返回空串`() {
        assertEquals("", CardText.front(" \n\t ", 70))
    }

    @Test
    fun `key 忽略大小写、空白与句末标点`() {
        val a = CardText.key("Use 'However', to contrast. ")
        val b = CardText.key("  use 'however',   to contrast  ")
        assertEquals(a, b)
    }

    @Test
    fun `key 保留句中标点`() {
        assertEquals("a, b", CardText.key("A, B."))
    }

    @Test
    fun `key 幂等`() {
        val once = CardText.key("  Check   articles! ")
        assertEquals(once, CardText.key(once))
    }
}
