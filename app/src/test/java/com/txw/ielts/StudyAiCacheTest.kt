package com.txw.ielts

import com.txw.ielts.llm.StudyAiCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StudyAiCacheTest {

    private val t0 = 1_700_000_000_000L

    @Test
    fun `写入后能按同一 key 读回`() {
        val k = StudyAiCache.key("outline", "task2", "Some topic")
        val store = StudyAiCache.put("{}", k, """{"stance":"x"}""", t0)
        assertEquals("""{"stance":"x"}""", StudyAiCache.get(store, k, t0 + 1000))
    }

    @Test
    fun `不同题目不同 key 不串`() {
        val a = StudyAiCache.key("outline", "task2", "Topic A")
        val b = StudyAiCache.key("outline", "task2", "Topic B")
        assertTrue(a != b)
        var store = StudyAiCache.put("{}", a, "raw-a", t0)
        assertNull(StudyAiCache.get(store, b, t0))
    }

    @Test
    fun `超过 TTL 视为 miss`() {
        val k = StudyAiCache.key("sample", "Part 2", "Q")
        val store = StudyAiCache.put("{}", k, "raw", t0)
        assertNull(StudyAiCache.get(store, k, t0 + StudyAiCache.TTL_MS + 1))
        assertEquals("raw", StudyAiCache.get(store, k, t0 + StudyAiCache.TTL_MS - 1))
    }

    @Test
    fun `空内容不写入`() {
        val store = StudyAiCache.put("{}", "k", "   ", t0)
        assertNull(StudyAiCache.get(store, "k", t0))
    }

    @Test
    fun `坏 JSON 不崩且当作空缓存`() {
        assertNull(StudyAiCache.get("这不是 JSON", "k", t0))
        val k = StudyAiCache.key("outline", "task1", "t")
        val store = StudyAiCache.put("这不是 JSON", k, "raw", t0)
        assertEquals("raw", StudyAiCache.get(store, k, t0))
    }

    @Test
    fun `超过上限按时间淘汰最老的`() {
        var store = "{}"
        repeat(35) { i ->
            store = StudyAiCache.put(store, "k$i", "raw$i", t0 + i)
        }
        // 前 5 条（最老）应被淘汰，最近 30 条保留。
        assertNull(StudyAiCache.get(store, "k0", t0 + 100))
        assertNull(StudyAiCache.get(store, "k4", t0 + 100))
        assertEquals("raw5", StudyAiCache.get(store, "k5", t0 + 100))
        assertEquals("raw34", StudyAiCache.get(store, "k34", t0 + 100))
    }
}
