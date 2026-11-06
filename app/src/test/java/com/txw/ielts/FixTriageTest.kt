package com.txw.ielts

import com.txw.ielts.llm.FixTriage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AI 批注的忽略 / 撤销：判重键、两类批注不串味、过滤顺序，以及按会话的存盘格式。
 * 这些规则决定「加入背诵 / 提取生词」会不会把用户明确否掉的错误批注重新塞回 SRS。
 */
class FixTriageTest {

    private val fixes = listOf(
        "Use 'a' before consonant sounds.",
        "Replace 'very good' with 'excellent'.",
        "主谓一致：The list of items were → was",
    )

    private val sentenceFixes = listOf(
        Triple("I goes to school every day.", "主谓不一致", "I go to school every day."),
        Triple("There is many reasons.", "可数名词单复数", "There are many reasons."),
    )

    @Test
    fun `忽略键忽略大小写 空白与句末标点`() {
        assertEquals(FixTriage.fixKey("Use 'a' before consonants."), FixTriage.fixKey("  use  'A' before consonants  "))
        assertEquals(FixTriage.fixKey("主谓一致：The list of items were → was"), FixTriage.fixKey("主谓一致：the list of items were → was！"))
    }

    @Test
    fun `顶层批注与逐句改法的键不会互相命中`() {
        val same = "I goes to school."
        assertNotEquals(FixTriage.fixKey(same), FixTriage.sentenceKey(same))
        val muted = setOf(FixTriage.sentenceKey(same))
        assertEquals(listOf(same), FixTriage.filterFixes(listOf(same), muted))
        assertTrue(FixTriage.filterSentenceFixes(listOf(Triple(same, "x", "y")), muted).isEmpty())
    }

    @Test
    fun `忽略后可以原样撤销`() {
        val key = FixTriage.fixKey(fixes[0])
        val muted = FixTriage.toggle(emptySet(), key)
        assertTrue(key in muted)
        val restored = FixTriage.toggle(muted, key)
        assertFalse(key in restored)
        assertTrue(restored.isEmpty())
    }

    @Test
    fun `过滤只去掉被忽略的条目 顺序不变`() {
        val muted = setOf(FixTriage.fixKey(fixes[1]))
        assertEquals(listOf(fixes[0], fixes[2]), FixTriage.filterFixes(fixes, muted))
        assertEquals(fixes, FixTriage.filterFixes(fixes, emptySet()))
    }

    @Test
    fun `逐句改法按原句忽略 同一原句的多条一起隐藏`() {
        val duplicated = sentenceFixes + Triple(
            sentenceFixes[0].first,
            "另一种改法",
            "I am going to school every day.",
        )
        val muted = setOf(FixTriage.sentenceKey("i goes to school every day"))
        val visible = FixTriage.filterSentenceFixes(duplicated, muted)
        assertEquals(1, visible.size)
        assertEquals(sentenceFixes[1].first, visible[0].first)
    }

    @Test
    fun `按会话存盘 互不影响 且可往返`() {
        val store = FixTriage.withSession("", 42L, setOf(FixTriage.fixKey(fixes[0])))
        val store2 = FixTriage.withSession(store, 43L, setOf(FixTriage.sentenceKey(sentenceFixes[0].first)))

        assertEquals(setOf(FixTriage.fixKey(fixes[0])), FixTriage.sessionKeys(store2, 42L))
        assertEquals(setOf(FixTriage.sentenceKey(sentenceFixes[0].first)), FixTriage.sessionKeys(store2, 43L))
        assertTrue(FixTriage.sessionKeys(store2, 99L).isEmpty())
    }

    @Test
    fun `恢复全部后会话记录被清除`() {
        val store = FixTriage.withSession("", 7L, setOf(FixTriage.fixKey(fixes[0])))
        val cleared = FixTriage.withSession(store, 7L, emptySet())
        assertTrue(FixTriage.sessionKeys(cleared, 7L).isEmpty())
        // 清除是逐会话的：别把别的会话一起丢掉。
        val other = FixTriage.withSession(cleared, 8L, setOf(FixTriage.fixKey(fixes[2])))
        val cleared7 = FixTriage.withSession(other, 7L, emptySet())
        assertEquals(setOf(FixTriage.fixKey(fixes[2])), FixTriage.sessionKeys(cleared7, 8L))
    }

    @Test
    fun `损坏或缺失的存储按未忽略处理`() {
        assertTrue(FixTriage.sessionKeys(null, 1L).isEmpty())
        assertTrue(FixTriage.sessionKeys("", 1L).isEmpty())
        assertTrue(FixTriage.sessionKeys("not json", 1L).isEmpty())
        assertTrue(FixTriage.sessionKeys("""{"1":"oops"}""", 1L).isEmpty())
        // 写入时也容错：坏存储直接覆盖成合法 JSON，而不是抛异常。
        val fixed = FixTriage.withSession("not json", 1L, setOf(FixTriage.fixKey(fixes[0])))
        assertEquals(setOf(FixTriage.fixKey(fixes[0])), FixTriage.sessionKeys(fixed, 1L))
    }
}
