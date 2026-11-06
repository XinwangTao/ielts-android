package com.txw.ielts

import com.txw.ielts.llm.ScoreFailures
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 评分失败原因的存盘格式：结果页靠它说明「上次为什么没出分」，
 * 所以清除、过期、损坏存储和超长消息都必须有确定行为。
 */
class ScoreFailuresTest {

    private val now = 1_800_000_000_000L
    private val day = 24L * 3_600_000L

    @Test
    fun `写入后可读回同一条原因`() {
        val store = ScoreFailures.record("{}", 42L, "本地模型未投递", now)
        assertEquals("本地模型未投递", ScoreFailures.reasonFor(store, 42L, now))
    }

    @Test
    fun `不同会话互不覆盖`() {
        var store = ScoreFailures.record("{}", 1L, "原因 A", now)
        store = ScoreFailures.record(store, 2L, "原因 B", now)
        assertEquals("原因 A", ScoreFailures.reasonFor(store, 1L, now))
        assertEquals("原因 B", ScoreFailures.reasonFor(store, 2L, now))
    }

    @Test
    fun `空白原因等于清除旧条目`() {
        var store = ScoreFailures.record("{}", 7L, "上一次失败", now)
        store = ScoreFailures.record(store, 7L, "  \n ", now)
        assertNull(ScoreFailures.reasonFor(store, 7L, now))
        // 空存储也要能继续工作
        assertNull(ScoreFailures.reasonFor(ScoreFailures.clear(store, 7L), 7L, now))
    }

    @Test
    fun `原因单行化并截断到可读长度`() {
        val reason = "缺少本地模型。先把模型推到设备：\n adb push <模型目录>/. /sdcard/...  " + "x".repeat(400)
        val store = ScoreFailures.record("{}", 9L, reason, now)
        val got = ScoreFailures.reasonFor(store, 9L, now)!!
        assertTrue(got.length <= ScoreFailures.MAX_REASON)
        assertTrue(got.endsWith("…"))
        assertTrue(!got.contains("\n"))
        // 压缩空白后不再有连续两个空格
        assertTrue(!got.contains("  "))
    }

    @Test
    fun `字面量 null 视为无原因`() {
        assertNull(ScoreFailures.normalize("null"))
        assertNull(ScoreFailures.normalize("NULL"))
        assertNull(ScoreFailures.normalize(""))
        assertNull(ScoreFailures.normalize("   "))
    }

    @Test
    fun `过期原因不再返回`() {
        val store = ScoreFailures.record("{}", 5L, "七天前的失败", now - 7 * day - 1)
        assertNull(ScoreFailures.reasonFor(store, 5L, now))
        // 刚好第七天还保留，避免边界上忽然消失
        val fresh = ScoreFailures.record("{}", 5L, "七天前的失败", now - 7 * day)
        assertNotNull(ScoreFailures.reasonFor(fresh, 5L, now))
    }

    @Test
    fun `损坏或不认识的存储当作空`() {
        assertNull(ScoreFailures.reasonFor("not json", 1L, now))
        assertNull(ScoreFailures.reasonFor("""{"1":"oops"}""", 1L, now))
        assertNull(ScoreFailures.reasonFor("""{"1":{"msg":"缺时间"}}""", 1L, now))
        // 从坏存储写入时不应抛异常
        val store = ScoreFailures.record("not json", 1L, "新的原因", now)
        assertEquals("新的原因", ScoreFailures.reasonFor(store, 1L, now))
    }

    @Test
    fun `非法会话 id 不写入脏键`() {
        assertEquals("{\"a\":1}", ScoreFailures.record("{\"a\":1}", 0L, "x", now))
        assertEquals("{}", ScoreFailures.record(null, -3L, "x", now))
        assertNull(ScoreFailures.reasonFor("{}", 0L, now))
    }

    @Test
    fun `超过上限只保留最新的条目`() {
        var store = "{}"
        repeat(ScoreFailures.KEEP + 5) { i ->
            store = ScoreFailures.record(store, (i + 1).toLong(), "失败 $i", now + i)
        }
        val kept = JSONObject(store).length()
        assertEquals(ScoreFailures.KEEP, kept)
        // 最早的 5 条被挤掉，最新的仍在
        assertNull(ScoreFailures.reasonFor(store, 1L, now + 100))
        assertNotNull(ScoreFailures.reasonFor(store, (ScoreFailures.KEEP + 5).toLong(), now + 100))
    }
}
