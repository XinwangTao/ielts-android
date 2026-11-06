package com.txw.ielts

import com.txw.ielts.data.Card
import com.txw.ielts.data.CardType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 卡片类型标签回归。
 *
 * 真机现象：学习页右上角和语料页「内容构成」直接印着 `cloze` / `vocab` ——
 * 这两个类型是后加的，映射表没跟上，中文界面里露出英文枚举值。
 * 另外 `type=error` 同时代表内置错题卡、练习批注、听读错题、跟读漏词，
 * 单看 type 全写「错题」，只有 source 能说清这张卡从哪来。
 */
class CardTypeTest {

    @Test
    fun `所有已知类型都有中文文案`() {
        assertEquals("词汇", CardType.typeLabel("word"))
        assertEquals("搭配句型", CardType.typeLabel("collocation"))
        assertEquals("话题语料", CardType.typeLabel("topic"))
        assertEquals("错题", CardType.typeLabel("error"))
        assertEquals("逐句改错", CardType.typeLabel("cloze"))
        assertEquals("查词卡", CardType.typeLabel("vocab"))
    }

    @Test
    fun `库里出现过的类型一个都不能漏成英文`() {
        // 这是真机上的原始症状：界面出现 cloze / vocab。
        val used = listOf("word", "collocation", "topic", "error", "cloze", "vocab")
        used.forEach { t ->
            val label = CardType.typeLabel(t)
            assertFalse("类型 $t 仍显示原始英文", label == t)
            assertEquals("类型 $t 映射到空文案", true, label.isNotBlank())
        }
    }

    @Test
    fun `大小写与空白不影响映射`() {
        assertEquals("逐句改错", CardType.typeLabel(" Cloze "))
        assertEquals("查词卡", CardType.typeLabel("VOCAB"))
        assertEquals("练习批注", CardType.cardLabel("error", " ERROR "))
    }

    @Test
    fun `空类型兜底不出现空标签`() {
        assertEquals(CardType.FALLBACK, CardType.typeLabel(""))
        assertEquals(CardType.FALLBACK, CardType.typeLabel("   "))
        assertEquals(CardType.FALLBACK, CardType.cardLabel("", "seed"))
    }

    @Test
    fun `未知类型原样显示，不猜成别的卡`() {
        assertEquals("sentence", CardType.typeLabel("sentence"))
        assertEquals("sentence", CardType.cardLabel("sentence", "seed"))
    }

    @Test
    fun `单卡标签用 source 区分来源`() {
        assertEquals("练习批注", CardType.cardLabel("error", "error"))
        assertEquals("逐句改错", CardType.cardLabel("cloze", "fix"))
        assertEquals("听读错题", CardType.cardLabel("error", "bank"))
        assertEquals("跟读错词", CardType.cardLabel("error", "shadow"))
        assertEquals("查词卡", CardType.cardLabel("vocab", "lookup"))
    }

    @Test
    fun `seed 与 ai_vocab 不改变卡片性质，仍按 type 显示`() {
        assertEquals("错题", CardType.cardLabel("error", "seed"))
        assertEquals("搭配句型", CardType.cardLabel("collocation", "ai_vocab"))
        assertEquals("词汇", CardType.cardLabel("word", "seed"))
    }

    @Test
    fun `Card 重载与 type加source 双参版本一致`() {
        val card = Card(id = 1, type = "error", front = "x", source = "bank")
        assertEquals(CardType.cardLabel("error", "bank"), CardType.cardLabel(card))
    }
}
