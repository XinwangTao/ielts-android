package com.txw.ielts

import com.txw.ielts.data.ModelEssay
import com.txw.ielts.data.ModelEssayStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelEssayStoreTest {

    private fun essay(topic: String, body: String, id: Long = 0) = ModelEssay(
        sessionId = id,
        topic = topic,
        polished = body,
        band = 6.5,
        savedAt = id,
    )

    @Test
    fun `合并按题目与正文去重且忽略大小写空白`() {
        val existing = listOf(essay("Education", "Body text", id = 5))
        val (merged, added) = ModelEssayStore.mergeList(
            existing,
            listOf(essay("  education ", " body TEXT ", id = 9)),
        )
        assertEquals(0, added)
        assertEquals(1, merged.size)
        // 已有条目原样保留，不被别机版本覆盖。
        assertEquals(5L, merged.single().sessionId)
        assertEquals("Body text", merged.single().polished)
    }

    @Test
    fun `空正文不导入`() {
        val (merged, added) = ModelEssayStore.mergeList(
            emptyList(),
            listOf(essay("Topic", "   "), essay("Topic2", "")),
        )
        assertEquals(0, added)
        assertTrue(merged.isEmpty())
    }

    @Test
    fun `导入条目发负数id且不与已有负数冲突`() {
        // 库里已有一次导入的 -1，以及一条本机真实会话（正数）。
        val existing = listOf(essay("A", "a", id = -1), essay("B", "b", id = 3))
        val (merged, added) = ModelEssayStore.mergeList(
            existing,
            listOf(essay("C", "c", id = 7), essay("D", "d", id = 8)),
        )
        assertEquals(2, added)
        val imported = merged.drop(2)
        assertEquals(listOf(-2L, -3L), imported.map { it.sessionId })
        // 备份里的 sessionId 不能原样沿用（会指向本机不相干的练习）。
        assertTrue(imported.none { it.sessionId == 7L || it.sessionId == 8L })
        // 本机原有条目不受影响。
        assertEquals(listOf(-1L, 3L), merged.take(2).map { it.sessionId })
    }

    @Test
    fun `同一份备份重复导入新增为零`() {
        val backup = listOf(essay("C", "c", id = 7), essay("D", "d", id = 8))
        val first = ModelEssayStore.mergeList(emptyList(), backup)
        assertEquals(2, first.second)
        val second = ModelEssayStore.mergeList(first.first, backup)
        assertEquals(0, second.second)
        assertEquals(2, second.first.size)
    }

    @Test
    fun `去重键忽略大小写与首尾空白`() {
        assertEquals(
            ModelEssayStore.essayKey(essay(" Education ", " Body Text ")),
            ModelEssayStore.essayKey(essay("education", "body text")),
        )
    }
}
