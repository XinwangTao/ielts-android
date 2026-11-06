package com.txw.ielts

import com.txw.ielts.data.CardText
import com.txw.ielts.llm.VocabDraft
import com.txw.ielts.llm.VocabEdits
import com.txw.ielts.llm.VocabItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AI 生词卡「编辑 / 恢复原文」的纯函数回归。
 * 关键点：留空语义、改回原文即撤销、按搭配键存盘能跨重新提取生效；
 * 这些规则决定用户改过的释义/例句会不会在入库时被模型原文悄悄覆盖。
 */
class VocabEditsTest {

    private val items = listOf(
        VocabItem("take measures", "采取措施", "The government should take measures."),
        VocabItem("play a vital role", "起关键作用", "Parents play a vital role in education."),
    )

    @Test
    fun `未编辑时原样返回 且不算改过`() {
        assertEquals(items, VocabEdits.resolve(items, emptyMap()))
        assertEquals(0, VocabEdits.editedCount(items, emptyMap()))
        assertEquals(
            VocabDraft("take measures", "采取措施", "The government should take measures."),
            VocabEdits.draftOf(items[0], emptyMap()),
        )
    }

    @Test
    fun `编辑后解析为新内容 且清洗首尾与连续空白`() {
        val edits = VocabEdits.edit(
            emptyMap(), items[0],
            VocabDraft("  take   effective measures ", " 采取有效措施 ", "Governments take effective measures."),
        )
        assertTrue(VocabEdits.key(items[0]) in edits)
        val resolved = VocabEdits.resolve(items, edits)
        assertEquals("take effective measures", resolved[0].phrase)
        assertEquals("采取有效措施", resolved[0].meaning)
        assertEquals("Governments take effective measures.", resolved[0].example)
        // 没改的那条不动
        assertEquals(items[1], resolved[1])
        assertEquals(1, VocabEdits.editedCount(items, edits))
    }

    @Test
    fun `改回原文即视为未编辑`() {
        val edits = VocabEdits.edit(emptyMap(), items[0], VocabDraft("take action", "采取行动", ""))
        assertTrue(edits.isNotEmpty())
        val back = VocabEdits.edit(
            edits, items[0],
            VocabDraft(" take measures ", "采取措施", "The government should take measures."),
        )
        assertTrue(back.isEmpty())
        assertEquals(items, VocabEdits.resolve(items, back))
    }

    @Test
    fun `搭配与释义留空保留原文 例句可以清空`() {
        val edits = VocabEdits.edit(emptyMap(), items[0], VocabDraft("", "", ""))
        val resolved = VocabEdits.resolve(items, edits)[0]
        assertEquals("take measures", resolved.phrase)
        assertEquals("采取措施", resolved.meaning)
        assertEquals("", resolved.example)
        // 清掉例句也是一次有效修改，不能因为搭配/释义回退就被当成「没改」。
        assertEquals(1, VocabEdits.editedCount(items, edits))
    }

    @Test
    fun `单条恢复与全部恢复`() {
        var edits = VocabEdits.edit(emptyMap(), items[0], VocabDraft("take action", "采取行动", ""))
        edits = VocabEdits.edit(edits, items[1], VocabDraft("be vital", "至关重要", ""))
        assertEquals(2, VocabEdits.editedCount(items, edits))
        edits = VocabEdits.revert(edits, items[0])
        assertEquals(1, VocabEdits.editedCount(items, edits))
        assertEquals(items[0], VocabEdits.resolve(items, edits)[0])
        assertEquals("be vital", VocabEdits.resolve(items, edits)[1].phrase)
        assertEquals(items, VocabEdits.resolve(items, emptyMap()))
    }

    @Test
    fun `改完撞车能标出后出现的重复条目`() {
        val edits = VocabEdits.edit(emptyMap(), items[1], VocabDraft("Take Measures!", "采取措施", ""))
        assertEquals(mapOf(1 to 0), VocabEdits.duplicates(items, edits))
        assertTrue(VocabEdits.duplicates(items, emptyMap()).isEmpty())
    }

    @Test
    fun `超长字段按上限截断 搭配保留卡面省略号`() {
        val edits = VocabEdits.edit(
            emptyMap(), items[0],
            VocabDraft("a".repeat(80), "释义".repeat(200), "e".repeat(400)),
        )
        val resolved = VocabEdits.resolve(items, edits)[0]
        assertTrue(resolved.phrase.endsWith("…"))
        assertTrue(resolved.phrase.length <= CardText.SHORT_MAX + 1)
        assertTrue(resolved.meaning.endsWith("…"))
        assertTrue(resolved.meaning.length <= VocabEdits.MEANING_MAX + 1)
        assertTrue(resolved.example.endsWith("…"))
        assertTrue(resolved.example.length <= VocabEdits.EXAMPLE_MAX + 1)
    }

    @Test
    fun `按会话存盘互不影响 且可往返`() {
        val edits = VocabEdits.edit(emptyMap(), items[0], VocabDraft("take action", "采取行动", ""))
        val store = VocabEdits.save("", 42L, edits)
        val store2 = VocabEdits.save(
            store, 43L,
            VocabEdits.edit(emptyMap(), items[1], VocabDraft("be vital", "至关重要", "x")),
        )
        assertEquals(edits, VocabEdits.load(store2, 42L))
        assertEquals("be vital", VocabEdits.load(store2, 43L)[VocabEdits.key(items[1])]?.phrase)
        assertTrue(VocabEdits.load(store2, 99L).isEmpty())
        // 清空某个会话不碰别的会话
        val cleared = VocabEdits.save(store2, 42L, emptyMap())
        assertTrue(VocabEdits.load(cleared, 42L).isEmpty())
        assertEquals(1, VocabEdits.load(cleared, 43L).size)
    }

    @Test
    fun `损坏或缺失的存储按未编辑处理`() {
        assertTrue(VocabEdits.load(null, 1L).isEmpty())
        assertTrue(VocabEdits.load("", 1L).isEmpty())
        assertTrue(VocabEdits.load("not json", 1L).isEmpty())
        assertTrue(VocabEdits.load("""{"1":[1,2]}""", 1L).isEmpty())
        // 写入时也容错：坏存储直接覆盖成合法 JSON，而不是抛异常。
        val fixed = VocabEdits.save("not json", 1L, mapOf("k" to VocabDraft("p", "m", "e")))
        assertEquals("p", VocabEdits.load(fixed, 1L)["k"]?.phrase)
    }

    @Test
    fun `同一搭配重新提取时 修改仍然生效`() {
        val edits = VocabEdits.edit(emptyMap(), items[0], VocabDraft("take action", "采取行动", ""))
        val regenerated = listOf(
            VocabItem("Take  Measures.", "采取措施", "A different example."),
            VocabItem("be vital", "至关重要", "Parents are vital."),
        )
        val resolved = VocabEdits.resolve(regenerated, edits)
        assertEquals("take action", resolved[0].phrase)
        assertEquals("be vital", resolved[1].phrase)
    }
}
