package com.txw.ielts.llm

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * AI 出题 / 错题精讲的 JSON 解析（云端与本地模型共用）。
 *
 * 两边的失败方式不一样但都常见：云端会包 ```json 围栏、把长文本里的换行写成裸换行；
 * 4B 本地模型会漏字段、把 options 写成字符串数组以外的形状、偶尔少给一两道题。
 * 所以这里只强制「最小必需项」：题干和答案缺一不可，其余字段用空值兜底；
 * 缺题干/答案的题丢掉，而不是让整套题解析失败——用户宁可拿到 4 道题，也不想看到一句报错。
 */
object GeneratedParser {

    fun reading(content: String, count: Int): GeneratedReading {
        val o = JSONObject(ScoreSanitizer.extractJson(content))
        val passage = o.optString("passage").ifBlank { o.optString("article") }
        if (passage.isBlank()) throw IOException("模型没有给出文章正文")
        return GeneratedReading(
            title = o.optString("title").ifBlank { "AI 生成阅读" },
            passage = passage,
            questions = questions(o, count),
        )
    }

    fun listening(content: String, count: Int): GeneratedListening {
        val o = JSONObject(ScoreSanitizer.extractJson(content))
        val script = o.optString("transcript").ifBlank { o.optString("script") }
        if (script.isBlank()) throw IOException("模型没有给出听力稿")
        return GeneratedListening(
            title = o.optString("title").ifBlank { "AI 生成听力" },
            transcript = script,
            questions = questions(o, count),
        )
    }

    fun bankExplain(content: String): BankExplain {
        val o = JSONObject(ScoreSanitizer.extractJson(content))
        // 云端历史上用过 explain/explain_en 这两个键名，本地模型偶尔也照抄，两种都认。
        val cn = o.optString("cn").ifBlank { o.optString("explain") }.trim()
        val en = o.optString("en").ifBlank { o.optString("explain_en") }.trim()
        if (cn.isBlank() && en.isBlank()) throw IOException("模型没有给出解析内容")
        return BankExplain(cn = cn, en = en, keys = strings(o, "keys"))
    }

    private fun questions(o: JSONObject, count: Int): List<GeneratedQuestion> {
        val arr = o.optJSONArray("questions") ?: throw IOException("模型没有返回 questions 数组")
        val out = ArrayList<GeneratedQuestion>(arr.length())
        val seen = HashSet<String>()
        for (i in 0 until arr.length()) {
            val q = arr.optJSONObject(i) ?: continue
            val prompt = q.optString("prompt").trim()
            val answers = strings(q, "answers")
            if (prompt.isBlank() || answers.isEmpty()) continue   // 残题直接丢，不拖垮整套
            // 模型（尤其 4B）偶尔把同一题写两遍：题干+答案归一化后只收第一次。
            if (!seen.add(questionKey(prompt, answers))) continue
            out.add(
                GeneratedQuestion(
                    type = q.optString("type").ifBlank { "gap" },
                    prompt = prompt,
                    options = strings(q, "options"),
                    answers = answers,
                    explain = q.optString("explain").trim(),
                    explainEn = q.optString("explain_en").trim(),
                    keys = strings(q, "keys"),
                )
            )
        }
        if (out.isEmpty()) throw IOException("模型返回的题目都缺少题干或答案")
        // 模型偶发超量（提示词写了数量也可能多给），按请求数截断；count<=0 视为不限制。
        return if (count > 0) out.take(count) else out
    }

    private val ws = Regex("\\s+")

    /** 归一化题干与答案：忽略大小写和连续空白，用来识别模型重复生成的同一题。 */
    private fun questionKey(prompt: String, answers: List<String>): String =
        prompt.replace(ws, " ").lowercase() + "|" +
            answers.joinToString("|") { it.replace(ws, " ").lowercase() }

    private fun strings(o: JSONObject, key: String): List<String> {
        val a: JSONArray? = o.optJSONArray(key)
        if (a != null) return (0 until a.length()).mapNotNull { i ->
            (a.opt(i) as? String)?.trim()?.takeIf { it.isNotBlank() }
        }
        // 单个答案/单个关键词被写成裸字符串时也收下
        return o.optString(key).trim().takeIf { it.isNotBlank() }?.let { listOf(it) } ?: emptyList()
    }
}
