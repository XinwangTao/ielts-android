package com.txw.ielts

import com.txw.ielts.data.BackupImporter
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
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
}
