package com.txw.ielts.llm

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 云端候选模型队列的公共整理规则：去空白、去重、最多 8 个。
 *
 * 8 个不是技术上限，是交互上限：抽屉里的优先级序号再长就看不清了，
 * 而模型真的要一路失败到第 9 个，回退本地通常更划算。
 */
fun normalizeCloudModels(models: List<String>): List<String> =
    cleanCloudModels(models).take(MAX_CLOUD_MODELS)

/** 去空白、去重，但不限制数量：抽屉要把网关返回的模型全部列出来。 */
fun cleanCloudModels(models: List<String>): List<String> =
    models.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

const val MAX_CLOUD_MODELS = 8

/**
 * 「测试连接」结果。
 *
 * [reachable] 与 [models] 分开：网关可能连通但根本不提供 /models（返回 404/405），
 * 这时仍可用当前手填的模型做 1-token ping，不能直接判死。
 */
data class ProbeResult(
    val models: List<String> = emptyList(),
    val error: String? = null,
    val reachable: Boolean = false,
)

/**
 * 设置页「测试连接」与模型抽屉的网络侧。
 *
 * 只做两件事：拉模型列表、对单个模型发极短 ping。协议判定沿用 LlmClient：
 * 同一个地址两种鉴权头都带（Bearer + x-api-key），OpenAI 兼容与 Anthropic 网关都能过。
 */
object CloudProbe {

    /** 依次尝试的模型列表地址；base 已带 /v1 时不再重复拼。 */
    fun modelsUrls(baseUrl: String): List<String> {
        val base = normalizeBase(baseUrl)
        if (base.isEmpty()) return emptyList()
        if (base.endsWith("/v1")) return listOf("$base/models")
        return listOf("$base/v1/models", "$base/models").distinct()
    }

    /** 用户可能把完整接口地址粘进来：去掉 /chat/completions 或 /v1/messages 再拼列表地址。 */
    private fun normalizeBase(raw: String): String =
        raw.trim().trimEnd('/')
            .removeSuffix("/chat/completions")
            .removeSuffix("/v1/messages")
            .trimEnd('/')

    /**
     * 解析模型列表。兼容三种真实返回：
     * - OpenAI：`{"data":[{"id":"gpt-4o"}]}`
     * - CC Switch 网关：`{"models":[{"slug":"glm-5.3-flash","display_name":"..."}]}`
     * - 少数网关：顶层字符串数组 `["a","b"]`
     */
    fun parseModels(body: String): List<String> {
        val text = body.trim()
        if (text.isEmpty()) return emptyList()
        val out = mutableListOf<String>()
        val root = runCatching { JSONObject(text) }.getOrNull()
        if (root != null) {
            collect(root.optJSONArray("data"), out)
            collect(root.optJSONArray("models"), out)
        } else {
            collect(runCatching { JSONArray(text) }.getOrNull(), out)
        }
        return cleanCloudModels(out)
    }

    private fun collect(arr: JSONArray?, out: MutableList<String>) {
        if (arr == null) return
        for (i in 0 until arr.length()) {
            when (val v = arr.opt(i)) {
                is String -> out += v
                is JSONObject -> {
                    val id = v.optString("id").ifBlank {
                        v.optString("slug").ifBlank { v.optString("display_name") }
                    }
                    if (id.isNotBlank()) out += id
                }
            }
        }
    }

    /**
     * 拉模型列表。先试 `/v1/models` 再试 `/models`；
     * 任一地址返回 2xx 即视为网络连通（即使列表为空），交给界面决定是否退回手填模型。
     */
    fun listModels(baseUrl: String, apiKey: String, timeoutMs: Int = 8_000): ProbeResult {
        if (baseUrl.isBlank()) return ProbeResult(error = "Base URL 为空")
        var lastError: String? = null
        var reachable = false
        for (url in modelsUrls(baseUrl)) {
            try {
                val body = get(url, apiKey, timeoutMs)
                reachable = true
                val models = parseModels(body)
                if (models.isNotEmpty()) return ProbeResult(models, reachable = true)
                lastError = "连接成功，但该地址没有返回模型列表"
            } catch (e: HttpError) {
                // 404/405：网关存在但不提供列表，仍可退回手填模型；401/403 则不是连通问题。
                if (e.code == 404 || e.code == 405) reachable = true
                lastError = e.message
            } catch (e: Exception) {
                lastError = e.message ?: "连接失败"
            }
        }
        return ProbeResult(error = lastError ?: "连接失败", reachable = reachable)
    }

    /** 单个模型的极短 ping：验证地址/key/模型名，不要求正文，也不扣每日额度。 */
    fun ping(baseUrl: String, apiKey: String, model: String): Result<String> = runCatching {
        if (model.isBlank()) throw IOException("模型名为空")
        LlmClient(normalizeBase(baseUrl), apiKey, model).ping()
    }

    private class HttpError(val code: Int, message: String) : IOException(message)

    private fun get(url: String, apiKey: String, timeoutMs: Int): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            setRequestProperty("Accept", "application/json")
            if (apiKey.isNotBlank()) {
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("x-api-key", apiKey)
            }
            setRequestProperty("anthropic-version", "2023-06-01")
        }
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw HttpError(code, friendly(code, text))
            return text
        } finally {
            conn.disconnect()
        }
    }

    private fun friendly(code: Int, body: String): String {
        val raw = body.take(160)
        return when (code) {
            401 -> "API key 无效或已过期（HTTP 401）。原始返回：$raw"
            403 -> "云端拒绝访问（HTTP 403），请检查账号额度与模型权限。原始返回：$raw"
            404, 405 -> "该地址没有模型列表接口（HTTP $code）"
            429 -> "云端限流（HTTP 429），请稍后重试。原始返回：$raw"
            else -> "HTTP $code：$raw"
        }
    }
}
