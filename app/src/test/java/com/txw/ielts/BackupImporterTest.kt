package com.txw.ielts

import com.txw.ielts.data.BackupImporter
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupImporterTest {

    private fun sets(vararg ids: String): JSONArray = JSONArray().apply {
        ids.forEach { put(JSONObject().put("id", it).put("title", "t-$it")) }
    }

    @Test
    fun `合并按 id 去重且新导入在前`() {
        val existing = JSONObject().put("sets", sets("a", "b")).toString()
        val merged = JSONObject(BackupImporter.mergeAiSets(existing, sets("c", "a"))).getJSONArray("sets")
        val ids = (0 until merged.length()).map { merged.getJSONObject(it).getString("id") }
        assertEquals(listOf("c", "a", "b"), ids)
    }

    @Test
    fun `坏文件按空处理`() {
        val merged = JSONObject(BackupImporter.mergeAiSets("不是 JSON", sets("x"))).getJSONArray("sets")
        assertEquals(1, merged.length())
        assertEquals("x", merged.getJSONObject(0).getString("id"))
    }

    @Test
    fun `没有 id 的条目丢弃`() {
        val incoming = JSONArray().apply {
            put(JSONObject().put("title", "no-id"))
            put(JSONObject().put("id", ""))
            put(JSONObject().put("id", "ok"))
        }
        val merged = JSONObject(BackupImporter.mergeAiSets(null, incoming)).getJSONArray("sets")
        assertEquals(1, merged.length())
        assertEquals("ok", merged.getJSONObject(0).getString("id"))
    }

    @Test
    fun `超过上限时截断`() {
        val many = JSONArray().apply { (1..BackupImporter.MAX_AI_SETS + 5).forEach { put(JSONObject().put("id", "s$it")) } }
        val merged = JSONObject(BackupImporter.mergeAiSets(null, many)).getJSONArray("sets")
        assertEquals(BackupImporter.MAX_AI_SETS, merged.length())
    }

    @Test
    fun `设置解析只收认识的字段`() {
        val obj = JSONObject()
            .put("targetBand", 6.5)
            .put("scoreChannel", "auto")
            .put("dailyNewCards", 12)
            .put("readerFontSize", 18)
            .put("unknownKey", "ignore")
        val s = com.txw.ielts.data.BackupImporter.parseSettings(obj)
        assertEquals(6.5f, s.targetBand!!, 0.0f)
        assertEquals("auto", s.scoreChannel)
        assertEquals(12, s.dailyNewCards)
        assertEquals(18, s.readerFontSize)
        // 缺失字段保持 null（不覆盖）。
        assertEquals(null, s.examDate)
    }

    @Test
    fun `非法通道与空设置`() {
        val s = com.txw.ielts.data.BackupImporter.parseSettings(JSONObject().put("scoreChannel", "weird"))
        assertEquals(null, s.scoreChannel)
        assertTrue(s.isEmpty)
        assertTrue(com.txw.ielts.data.BackupImporter.parseSettings(null).isEmpty)
    }

    @Test
    fun `模型不在本机时给提示`() {
        assertEquals(null, com.txw.ielts.data.BackupImporter.modelWarning("qwen3-4b-instruct", setOf("qwen3-4b-instruct")))
        assertEquals(null, com.txw.ielts.data.BackupImporter.modelWarning(null, emptySet()))
        val w = com.txw.ielts.data.BackupImporter.modelWarning("qwen35-2b", setOf("qwen3-4b-instruct"))
        assertTrue(w != null && w.contains("qwen35-2b"))
    }
}
