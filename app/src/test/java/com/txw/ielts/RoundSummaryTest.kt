package com.txw.ielts

import com.txw.ielts.data.Grade
import com.txw.ielts.data.RoundSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 一轮复习的收尾小结。
 *
 * 这段逻辑决定「评完最后一个字之后用户看到什么」：语料页顶部那句
 * 「本轮 N 张卡 · 评级 M 次」如果按评级次数当卡数，会把 Again 重问算成新卡；
 * 如果按最后结果判断，中途退出停在「不认识」的卡又容易被说成「已经记住」。
 * 两种错法用户都看不出来，只能用单测钉住。
 */
private typealias Event = Pair<Long, Grade>

class RoundSummaryTest {

    @Test
    fun `没有评级时不算一轮`() {
        val s = RoundSummary.of(emptyList())
        assertTrue(s.empty)
        assertEquals(RoundSummary.EMPTY, s)
        assertEquals("这一轮没有评级记录", s.headline)
        assertEquals("", s.detail)
        assertEquals("", s.note)
    }

    @Test
    fun `每张卡只评一次时次数等于卡数`() {
        val s = RoundSummary.of(listOf<Event>(1L to Grade.GOOD, 2L to Grade.EASY))
        assertEquals(2, s.cards)
        assertEquals(2, s.ratings)
        assertEquals(2, s.solid)
        assertEquals(0, s.shaky)
        assertEquals(0, s.unfinished)
        assertEquals(0, s.relearned)
        assertFalse(s.empty)
    }

    @Test
    fun `认识与太简单都算记住`() {
        val s = RoundSummary.of(listOf<Event>(1L to Grade.GOOD, 2L to Grade.EASY, 3L to Grade.EASY))
        assertEquals(3, s.solid)
        assertEquals("认识 3", s.detail)
    }

    @Test
    fun `模糊算还需巩固而不是记住`() {
        val s = RoundSummary.of(listOf<Event>(1L to Grade.HARD))
        assertEquals(0, s.solid)
        assertEquals(1, s.shaky)
        assertEquals("还需巩固 1", s.detail)
    }

    @Test
    fun `Again 后重问不会被当成两张新卡`() {
        // 同一张卡：先不认识，本轮隔几张又问了，最后答对。
        val s = RoundSummary.of(listOf<Event>(7L to Grade.AGAIN, 7L to Grade.GOOD))
        assertEquals(1, s.cards)
        assertEquals(2, s.ratings)
        assertEquals(1, s.solid)
        assertEquals(0, s.unfinished)
        assertEquals(1, s.relearned)
        assertEquals("认识 1 · 1 张本轮重问过", s.detail)
        // 重问过的卡最终答对：不能说它排在明天。
        assertTrue(s.note.contains("又问了"))
        assertFalse(s.note.contains("明天"))
        assertEquals("本轮 1 张卡 · 评级 2 次", s.headline)
    }

    @Test
    fun `重问多次只算一张卡重问过`() {
        val s = RoundSummary.of(
            listOf<Event>(1L to Grade.AGAIN, 1L to Grade.AGAIN, 1L to Grade.AGAIN, 1L to Grade.HARD)
        )
        assertEquals(1, s.cards)
        assertEquals(4, s.ratings)
        assertEquals(1, s.relearned)
        // 最后一次是模糊：算还需巩固，不算停在 Again。
        assertEquals(1, s.shaky)
        assertEquals(0, s.unfinished)
    }

    @Test
    fun `中途退出停在 Again 的卡单独计数`() {
        val s = RoundSummary.of(listOf<Event>(1L to Grade.AGAIN))
        assertEquals(1, s.cards)
        assertEquals(1, s.unfinished)
        assertEquals("停在「不认识」1", s.detail)
        assertTrue(s.note.contains("明天"))
    }

    @Test
    fun `明细不列出没发生过的档位`() {
        val s = RoundSummary.of(listOf<Event>(1L to Grade.GOOD, 2L to Grade.AGAIN, 2L to Grade.EASY))
        assertEquals("认识 2 · 1 张本轮重问过", s.detail)
        // 0 次也写成「不认识 0」会让人以为踩过雷。
        assertFalse(s.detail.contains("不认识 0"))
    }

    @Test
    fun `一轮正常评完时说明不会提重问`() {
        val s = RoundSummary.of(listOf<Event>(1L to Grade.GOOD, 2L to Grade.HARD))
        assertTrue(s.note.contains("一次评过"))
        assertEquals(0, s.relearned)
    }
}
