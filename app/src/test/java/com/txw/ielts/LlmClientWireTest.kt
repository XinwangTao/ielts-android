package com.txw.ielts

import com.txw.ielts.llm.LlmClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket

/**
 * LlmClient 的线上协议回归：用本地 mock HTTP 服务捕获 App 真正发出的 OpenAI 请求，
 * 确认 thinking:disabled / max_tokens / messages 结构，以及 1210 自动降级分支。
 *
 * 用裸 ServerSocket 而不是 com.sun.net.httpserver：后者不在 Android 单测的 JDK 模块图里。
 */
class LlmClientWireTest {

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResourceAsStream("/ai_probe/$name.txt")) {
            "缺少夹具 /ai_probe/$name.txt"
        }.bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun okBody(content: String): String = JSONObject().put(
        "choices",
        JSONArray().put(JSONObject().put("message", JSONObject().put("content", content))),
    ).toString()

    private fun readLine(input: InputStream): String {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0 || b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
        }
        return sb.toString()
    }

    /** 依次返回 responses 里的 (状态码, body)；每个请求体交给 onRequest。 */
    private fun mockServer(
        responses: List<Pair<Int, String>>,
        onRequest: (String) -> Unit,
    ): ServerSocket {
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        Thread {
            try {
                for ((code, body) in responses) {
                    server.accept().use { socket ->
                        val input = socket.getInputStream()
                        var len = 0
                        while (true) {
                            val line = readLine(input)
                            if (line.isEmpty()) break
                            if (line.startsWith("Content-Length:", ignoreCase = true)) {
                                len = line.substringAfter(":").trim().toIntOrNull() ?: 0
                            }
                        }
                        onRequest(String(input.readNBytes(len), Charsets.UTF_8))
                        val resp = body.toByteArray(Charsets.UTF_8)
                        val head = "HTTP/1.1 $code OK\r\nContent-Type: application/json\r\n" +
                            "Content-Length: ${resp.size}\r\nConnection: close\r\n\r\n"
                        socket.getOutputStream().apply {
                            write(head.toByteArray(Charsets.ISO_8859_1))
                            write(resp)
                            flush()
                        }
                    }
                }
            } catch (_: Exception) {
                // 测试失败/关闭 server 时 accept 会抛异常，忽略即可。
            } finally {
                runCatching { server.close() }
            }
        }.start()
        return server
    }

    @Test
    fun `请求带 thinking disabled 且能解析出题响应`() {
        val requests = mutableListOf<String>()
        val server = mockServer(listOf(200 to okBody(fixture("reading_generation")))) { requests.add(it) }
        try {
            val client = LlmClient(
                "http://127.0.0.1:${server.localPort}/v1",
                "test-key",
                "test-model",
            )
            val r = client.generateReading("7.0", listOf("choice", "tfng", "gap"), 6)
            assertEquals(6, r.questions.size)

            val req = JSONObject(requests.single())
            assertEquals("test-model", req.getString("model"))
            // 出题是长输出：8 题 + 双语解析需要 6000，否则后几题会被截断成缺 explain_en。
            assertEquals(6000, req.getInt("max_tokens"))
            assertEquals(0.2, req.getDouble("temperature"), 0.0)
            assertEquals("disabled", req.getJSONObject("thinking").getString("type"))
            val messages = req.getJSONArray("messages")
            assertEquals("system", messages.getJSONObject(0).getString("role"))
            assertEquals("user", messages.getJSONObject(1).getString("role"))
        } finally {
            runCatching { server.close() }
        }
    }

    /**
     * 回归：口语评分曾把 ASR 原始转写整段拼进提示词（`transcriptBody` 算了却用 `$transcript`）。
     * 复读几分钟就能产出几万字，云端请求直接超时长/超量，而本地路径正常。
     * 这里抓真实发出的请求体，确认中部被裁、结尾还在、指标仍按原文算。
     */
    @Test
    fun `口语评分超长转写裁中间后再发出`() {
        val requests = mutableListOf<String>()
        val server = mockServer(listOf(200 to okBody(fixture("scoring_speaking")))) { requests.add(it) }
        try {
            val client = LlmClient(
                "http://127.0.0.1:${server.localPort}/v1",
                "test-key",
                "test-model",
            )
            val head = "First sentence of the answer about my city. "
            val middle = (1..10000).joinToString(" ") { "filler$it" } + " "
            val tail = "Last sentence closes with a short conclusion."
            val transcript = head + middle + tail
            client.evaluateSpeaking("Part 2", "Describe a city", transcript, 600)

            val user = JSONObject(requests.single())
                .getJSONArray("messages").getJSONObject(1).getString("content")
            assertTrue("应带省略标记", user.contains("middle trimmed"))
            assertTrue("结尾必须保留", user.contains(tail))
            assertTrue("中部不该被整段发出", !user.contains(" filler5000 "))
            assertTrue(user.contains("Speaking time: 600s"))
            // 字数/语速仍按原文算：裁剪不能让模型以为回答很短。
            assertTrue(user.contains("Word count: ${com.txw.ielts.llm.ScorePrompt.wordCount(transcript)}"))
            assertTrue(
                "整条 user 消息应在预算内",
                com.txw.ielts.llm.PromptBudget.estimateTokens(user) <=
                    com.txw.ielts.llm.PromptBudget.SCORE_INPUT_TOKENS + 100,
            )
        } finally {
            runCatching { server.close() }
        }
    }

    @Test
    fun `1210 时自动降级为 low thinking 且加大 max tokens`() {
        val requests = mutableListOf<String>()
        val server = mockServer(
            listOf(
                400 to """{"error":{"code":1210,"message":"thinking disabled is not supported"}}""",
                200 to okBody(fixture("reading_generation")),
            ),
        ) { requests.add(it) }
        try {
            val client = LlmClient(
                "http://127.0.0.1:${server.localPort}/v1",
                "test-key",
                "test-model",
            )
            val r = client.generateReading("7.0", listOf("choice", "tfng", "gap"), 6)
            assertEquals(6, r.questions.size)
            assertTrue("应发生一次降级重试", requests.size >= 2)

            val retry = JSONObject(requests[1])
            assertEquals("enabled", retry.getJSONObject("thinking").getString("type"))
            assertEquals("low", retry.getJSONObject("thinking").getString("level"))
            assertEquals(6000, retry.getInt("max_tokens"))
        } finally {
            runCatching { server.close() }
        }
    }
}
