package com.txw.ielts

import com.txw.ielts.llm.LlmClient
import com.txw.ielts.llm.routeLlm
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class RoutingTest {

    @Test
    fun `auto 模式下格式类失败会重试一次云端`() = runBlocking {
        var cloudCalls = 0
        var localCalls = 0
        val out = routeLlm<String>(
            channel = "auto",
            label = "测试",
            cloud = {
                cloudCalls++
                if (cloudCalls == 1) throw IOException("JSON 解析失败：Expecting ':'")
                "ok"
            },
            local = { localCalls++; "local" },
        )
        assertEquals("ok", out)
        assertEquals(2, cloudCalls)
        assertEquals(0, localCalls)
    }

    @Test
    fun `HTTP 失败不重试，直接回退本地`() = runBlocking {
        var cloudCalls = 0
        var failed: Exception? = null
        val out = routeLlm<String>(
            channel = "auto",
            label = "测试",
            onCloudFail = { failed = it },
            cloud = {
                cloudCalls++
                throw LlmClient.HttpError(403, "HTTP 403: 额度不可用")
            },
            local = { "local" },
        )
        assertEquals("local", out)
        assertEquals(1, cloudCalls)
        assertTrue(failed != null)
    }

    @Test
    fun `重试仍失败时回退本地`() = runBlocking {
        var cloudCalls = 0
        val out = routeLlm<String>(
            channel = "auto",
            label = "测试",
            cloud = { cloudCalls++; throw IOException("坏 JSON") },
            local = { "local" },
        )
        assertEquals("local", out)
        assertEquals(2, cloudCalls)
    }

    @Test
    fun `取消异常原样抛出，不起本地推理`() = runBlocking {
        var localCalls = 0
        try {
            routeLlm<String>(
                channel = "auto",
                label = "测试",
                cloud = { throw CancellationException("用户返回") },
                local = { localCalls++; "local" },
            )
            fail("应该抛出 CancellationException")
        } catch (e: CancellationException) {
            assertEquals(0, localCalls)
        }
    }

    @Test
    fun `local 通道不碰云端`() = runBlocking {
        var cloudCalls = 0
        val out = routeLlm<String>(
            channel = "local",
            label = "测试",
            cloud = { cloudCalls++; "cloud" },
            local = { "local" },
        )
        assertEquals("local", out)
        assertEquals(0, cloudCalls)
    }
}
