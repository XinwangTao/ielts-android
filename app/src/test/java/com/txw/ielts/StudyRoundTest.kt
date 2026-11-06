package com.txw.ielts

import com.txw.ielts.data.Card
import com.txw.ielts.data.Grade
import com.txw.ielts.data.StudyRound
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 学习页状态机回归。
 *
 * 重点是「评级按钮什么时候解锁」：线上表现是一轮复习只评得动第一张卡，
 * 因为置灰标志写在页面自己的 remember 里、换卡后没人复位；
 * 单卡队列选「不认识」就地重问更是直接卡死。这里把两条路径都钉住。
 */
class StudyRoundTest {

    private fun cards(vararg ids: Long): List<Card> = ids.map { Card(id = it, front = "card$it") }

    private fun StudyRound.grade(grade: Grade): StudyRound = beginGrade().applyGrade(grade)

    @Test
    fun `开始一轮时停在第一张且未翻面`() {
        val r = StudyRound.start(cards(1, 2, 3))
        assertEquals(1L, r.card?.id)
        assertEquals(3, r.total)
        assertEquals(0, r.index)
        assertFalse(r.revealed)
        assertTrue(r.canGrade)
        assertFalse(r.finished)
    }

    @Test
    fun `空队列立即算完成`() {
        val r = StudyRound.start(emptyList())
        assertNull(r.card)
        assertTrue(r.finished)
        assertFalse(r.canGrade)
        // 空队列上的任何操作都不许崩，也不许造出一张卡。
        assertEquals(r, r.reveal().beginGrade().applyGrade(Grade.GOOD).discard())
    }

    @Test
    fun `翻面后仍未评级`() {
        val r = StudyRound.start(cards(1)).reveal()
        assertTrue(r.revealed)
        assertTrue(r.canGrade)
    }

    @Test
    fun `评级发出后立即置灰防连点`() {
        val r = StudyRound.start(cards(1, 2)).beginGrade()
        assertFalse(r.canGrade)
        // 重复发起不会换成别的卡，也不会解锁。
        assertFalse(r.beginGrade().canGrade)
        assertEquals(1L, r.busyCardId)
    }

    @Test
    fun `没发起评级就推进会被忽略`() {
        val r = StudyRound.start(cards(1, 2))
        val applied = r.applyGrade(Grade.GOOD)
        assertEquals(r, applied)
        assertEquals(1L, applied.card?.id)
    }

    @Test
    fun `答对推进到下一张并解锁`() {
        val r = StudyRound.start(cards(1, 2, 3)).reveal().grade(Grade.GOOD)
        assertEquals(2L, r.card?.id)
        assertEquals(1, r.index)
        assertFalse(r.revealed)
        // 回归：上一张卡的锁不能粘到下一张。
        assertTrue(r.canGrade)
        assertNull(r.busyCardId)
    }

    @Test
    fun `连续评多张都不会被上一张的锁挡住`() {
        var r = StudyRound.start(cards(1, 2, 3))
        r = r.grade(Grade.GOOD)
        assertTrue(r.canGrade)
        r = r.grade(Grade.HARD)
        assertTrue(r.canGrade)
        assertEquals(3L, r.card?.id)
    }

    @Test
    fun `答错后隔几张再问同一张卡`() {
        val r = StudyRound.start(cards(1, 2, 3, 4, 5)).grade(Grade.AGAIN)
        assertEquals(listOf(2L, 3L, 4L, 1L, 5L), r.queue.map { it.id })
        assertEquals(2L, r.card?.id)
        assertTrue(r.canGrade)
    }

    @Test
    fun `单卡队列答错就地重问且能再评`() {
        val r = StudyRound.start(cards(1)).grade(Grade.AGAIN)
        assertEquals(1L, r.card?.id)
        assertEquals(0, r.index)
        // 卡没换、位置也没换，仍然必须解锁，否则这张卡永远评不动。
        assertTrue(r.canGrade)
        assertFalse(r.finished)
    }

    @Test
    fun `评完最后一张即完成`() {
        val r = StudyRound.start(cards(1, 2)).grade(Grade.GOOD).grade(Grade.EASY)
        assertTrue(r.finished)
        assertNull(r.card)
        assertEquals(2, r.index)
    }

    @Test
    fun `写库失败只解锁不推进`() {
        val r = StudyRound.start(cards(1, 2)).beginGrade().unlock()
        assertEquals(1L, r.card?.id)
        assertTrue(r.canGrade)
        // 解锁后可以重新评级。
        assertEquals(2L, r.grade(Grade.GOOD).card?.id)
    }

    @Test
    fun `删除当前卡后索引不越界`() {
        val r = StudyRound.start(cards(1, 2, 3)).grade(Grade.GOOD).grade(Grade.GOOD).discard()
        assertEquals(listOf(1L, 2L), r.queue.map { it.id })
        assertEquals(1, r.index)
        assertEquals(2L, r.card?.id)
        assertTrue(r.canGrade)
    }

    @Test
    fun `删掉最后一张回到上一张而不是结束`() {
        val r = StudyRound.start(cards(1, 2)).grade(Grade.GOOD)
        val after = r.discard()
        assertEquals(listOf(1L), after.queue.map { it.id })
        assertEquals(0, after.index)
        assertFalse(after.finished)
    }

    @Test
    fun `删空即结束`() {
        val r = StudyRound.start(cards(1)).discard()
        assertTrue(r.finished)
        assertEquals(StudyRound.EMPTY, r)
    }

    @Test
    fun `评级流水按发生顺序记录`() {
        val r = StudyRound.start(cards(1, 2)).grade(Grade.GOOD).grade(Grade.EASY)
        assertEquals(listOf(1L to Grade.GOOD, 2L to Grade.EASY), r.events)
        val s = r.summary()
        assertEquals(2, s.cards)
        assertEquals(2, s.ratings)
        assertEquals(2, s.solid)
    }

    @Test
    fun `Again 重问会再记一条且小结按最后结果算`() {
        val r = StudyRound.start(cards(1, 2)).grade(Grade.AGAIN)
        // 单张卡队列之外的情况：Again 的卡留在本轮靠后的位置，本轮没结束。
        assertFalse(r.finished)
        assertEquals(listOf(1L to Grade.AGAIN), r.events)
        // 隔几张再问，最后答对。
        val after = r.grade(Grade.GOOD).grade(Grade.GOOD)
        assertEquals(3, after.events.size)
        val s = after.summary()
        assertEquals(2, s.cards)
        assertEquals(3, s.ratings)
        assertEquals(2, s.solid)
        assertEquals(1, s.relearned)
        // 已经答对的卡不该被算成「停在 Again」。
        assertEquals(0, s.unfinished)
    }

    @Test
    fun `写库失败解锁时不记流水`() {
        val r = StudyRound.start(cards(1)).beginGrade().unlock()
        assertTrue(r.events.isEmpty())
        assertTrue(r.summary().empty)
    }

    @Test
    fun `删除到空仍保留已发生的评级`() {
        val r = StudyRound.start(cards(1, 2)).grade(Grade.GOOD)
        val deleted = r.discard().discard()
        assertTrue(deleted.finished)
        val s = deleted.summary()
        assertEquals(1, s.cards)
        assertEquals(1, s.solid)
        assertEquals("本轮 1 张卡 · 评级 1 次", s.headline)
    }
}
