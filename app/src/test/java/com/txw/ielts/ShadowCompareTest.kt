package com.txw.ielts

import com.txw.ielts.data.ShadowCompare
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 跟读准确率的分词与对齐回归：算错会直接冤枉用户或放水。 */
class ShadowCompareTest {

    @Test
    fun `完全一致时准确率为 100%`() {
        val d = ShadowCompare.compare("Hello, world! This is a test.", "hello world this is a test")
        assertEquals(1.0, d.accuracy, 0.0001)
        assertTrue(d.missedRef.isEmpty())
        assertTrue(d.extraHyp.isEmpty())
    }

    @Test
    fun `漏读一个词时按参考词数计分`() {
        val d = ShadowCompare.compare("the cat sat on the mat", "the cat sat on mat")
        assertEquals(5.0 / 6.0, d.accuracy, 0.0001)
        assertEquals(setOf(4), d.missedRef)
        assertEquals(listOf("the"), d.refWords.drop(4).take(1))
    }

    @Test
    fun `替换词算漏读且记为多读`() {
        val d = ShadowCompare.compare("the dog sat", "the cat sat")
        assertEquals(2.0 / 3.0, d.accuracy, 0.0001)
        assertEquals(setOf(1), d.missedRef)
        assertEquals(listOf("cat"), d.extraHyp)
    }

    @Test
    fun `弯引号与直引号视为同一个词`() {
        val d = ShadowCompare.compare("I don’t know", "I don't know")
        assertEquals(1.0, d.accuracy, 0.0001)
    }

    @Test
    fun `带重音的字母不会被切碎`() {
        val d = ShadowCompare.compare("we met at a café", "we met at a café")
        assertEquals(1.0, d.accuracy, 0.0001)
        assertEquals(listOf("we", "met", "at", "a", "café"), d.refWords)
    }

    @Test
    fun `空转写时全部算漏读`() {
        val d = ShadowCompare.compare("one two three", "   ")
        assertEquals(0.0, d.accuracy, 0.0001)
        assertEquals(setOf(0, 1, 2), d.missedRef)
        assertTrue(d.extraHyp.isEmpty())
    }

    @Test
    fun `重复词按最长公共子序列对齐`() {
        val d = ShadowCompare.compare("the the cat", "the cat")
        assertEquals(2.0 / 3.0, d.accuracy, 0.0001)
        assertEquals(1, d.missedRef.size)
    }
}
