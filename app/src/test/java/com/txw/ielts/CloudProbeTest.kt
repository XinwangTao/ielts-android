package com.txw.ielts

import com.txw.ielts.llm.CloudProbe
import com.txw.ielts.llm.MAX_CLOUD_MODELS
import com.txw.ielts.llm.normalizeCloudModels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 模型列表解析与地址候选项：真实网关的三种返回形状都要认。 */
class CloudProbeTest {

    @Test
    fun `解析 OpenAI 的 data 数组`() {
        val models = CloudProbe.parseModels(
            """{"object":"list","data":[{"id":"gpt-4o"},{"id":"glm-5.3-flash"}]}""",
        )
        assertEquals(listOf("gpt-4o", "glm-5.3-flash"), models)
    }

    @Test
    fun `解析 CC Switch 的 models slug`() {
        val models = CloudProbe.parseModels(
            """{"models":[{"slug":"glm-5.3-flash"},{"display_name":"deepseek"},{"id":"k3"}]}""",
        )
        assertEquals(listOf("glm-5.3-flash", "deepseek", "k3"), models)
    }

    @Test
    fun `解析顶层字符串数组`() {
        val models = CloudProbe.parseModels("""["a","b","a"]""")
        assertEquals(listOf("a", "b"), models)
    }

    @Test
    fun `坏 JSON 返回空列表而不是崩溃`() {
        assertTrue(CloudProbe.parseModels("not json").isEmpty())
        assertTrue(CloudProbe.parseModels("").isEmpty())
    }

    @Test
    fun `整理队列时去空白去重并限制上限`() {
        val models = normalizeCloudModels(
            listOf(" a ", "", "a", "b") + (1..20).map { "m$it" },
        )
        assertEquals("a", models.first())
        assertEquals(MAX_CLOUD_MODELS, models.size)
    }

    @Test
    fun `抽屉列表不被队列上限截断`() {
        val body = (1..12).joinToString(",", prefix = "[", postfix = "]") { "\"m$it\"" }
        assertEquals(12, CloudProbe.parseModels(body).size)
    }

    @Test
    fun `base 已带 v1 时不重复拼 v1`() {
        assertEquals(
            listOf("http://h:15721/v1/models"),
            CloudProbe.modelsUrls("http://h:15721/v1/"),
        )
    }

    @Test
    fun `普通 base 先试 v1 再试 models`() {
        assertEquals(
            listOf("http://h:15721/v1/models", "http://h:15721/models"),
            CloudProbe.modelsUrls("http://h:15721"),
        )
        assertEquals(
            listOf("https://open.bigmodel.cn/api/paas/v4/v1/models", "https://open.bigmodel.cn/api/paas/v4/models"),
            CloudProbe.modelsUrls("https://open.bigmodel.cn/api/paas/v4"),
        )
    }

    @Test
    fun `粘完整接口地址也能回到 base`() {
        assertEquals(
            listOf("http://h:15721/v1/models", "http://h:15721/models"),
            CloudProbe.modelsUrls("http://h:15721/v1/messages"),
        )
        assertEquals(
            listOf("http://h:15721/v1/models"),
            CloudProbe.modelsUrls("http://h:15721/v1/chat/completions"),
        )
    }
}
