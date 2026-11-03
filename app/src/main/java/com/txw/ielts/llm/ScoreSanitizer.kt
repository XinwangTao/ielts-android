package com.txw.ielts.llm

import org.json.JSONArray
import org.json.JSONObject

/**
 * 本地/云端评分结果统一后处理（图：ielts-local-llm_workflow 节点「结果后处理」）。
 *
 * 实测 Qwen3.5-4B（MNN）会出三类偏差，不能依赖模型自觉：
 * 1. overall 直接给五项平均（如 5.3），不符合 IELTS 0.5 步长；
 * 2. 字数不足时 TR 封顶规则执行不稳定（<150 词应 4.0、<250 词应 5.0）；
 * 3. polished 值偶尔多包一层引号。
 *
 * 4B 模型的系统性偏差是「分数虚高」：同一篇 300 词作文在提示词里写满 Band 8 排除规则
 * 仍稳定给四项全 8.0（真实约 7.0-7.5），且会省略 sentence_fixes 绕过「先列错误」约束。
 * 因此校准改为确定性后处理（不依赖模型服从）：
 * - CC：依赖大量公式化连接词（On the one hand / Moreover / In conclusion…）的文章不是 B8，
 *   官方 B8 要求衔接「不引人注意」；出现 4 种以上不同公式化连接词 → CC ≤ 7.0，6 种以上 → ≤ 6.5。
 * - GRA：只要模型列出 1 条以上句子级错误 → ≤ 7.5；4 条以上 → ≤ 6.5。
 * - overall：任何带改进点（top_fixes 非空）的文章 → overall ≤ 7.5。Band 8 应是「几乎无可改」，
 *   而小模型对任何文章都会列改进点，所以这等价于给本地评分一个 7.5 的诚实上限。
 */
object ScoreSanitizer {

    /** 公式化连接词表：B6-B7 文章依赖显式连接词，B8 更依赖指代与逻辑推进。 */
    private val FORMULAIC_LINKERS = listOf(
        "on the one hand", "on the other hand", "moreover", "furthermore",
        "in conclusion", "to conclude", "in addition", "firstly", "secondly",
        "lastly", "in my view", "in my opinion", "in summary", "to sum up",
    )

    /** 统计文章中出现的不同公式化连接词种类数（同一短语多次出现只算一种）。 */
    fun formulaicLinkerKinds(text: String): Int {
        val lower = text.lowercase()
        return FORMULAIC_LINKERS.count { p ->
            Regex("\\b" + Regex.escape(p) + "\\b").containsMatchIn(lower)
        }
    }

    /** 规范化写作评分：四项（TR/CC/LR/GRA）+ overall。task1 用 Task 1 的字数规则。 */
    fun sanitizeWriting(
        tr: Double, cc: Double, lr: Double, gra: Double,
        overall: Double, wordCount: Int, task: String = "task2",
        essayText: String = "",
        sentenceFixCount: Int = 0,
        hasFixes: Boolean = false,
    ): WritingScores {
        var tr2 = band(tr)
        // 字数硬规则（比模型自觉更可靠）：Task2 <150 词 4.0、<250 词 5.0；
        // Task1 官方要求 150 词，<100 词 4.0、<150 词 5.0（此前误用 Task2 阈值）。
        if (task == "task1") {
            if (wordCount < 100) tr2 = tr2.coerceAtMost(4.0)
            else if (wordCount < 150) tr2 = tr2.coerceAtMost(5.0)
        } else {
            if (wordCount < 150) tr2 = tr2.coerceAtMost(4.0)
            else if (wordCount < 250) tr2 = tr2.coerceAtMost(5.0)
        }
        // 公式化连接词越多，衔接越依赖「显式胶水」，与 B8「不引人注意」相悖。
        val linkerKinds = if (essayText.isBlank()) 0 else formulaicLinkerKinds(essayText)
        val cc2 = band(cc).let {
            when {
                linkerKinds >= 6 -> it.coerceAtMost(6.5)
                linkerKinds >= 4 -> it.coerceAtMost(7.0)
                else -> it
            }
        }
        // GRA 与模型自己列出的错误数一致：列了错误就不能给近满分。
        val gra2 = band(gra).let {
            when {
                sentenceFixCount >= 4 -> it.coerceAtMost(6.5)
                sentenceFixCount >= 1 -> it.coerceAtMost(7.5)
                else -> it
            }
        }
        val lr2 = band(lr)
        val avg = (tr2 + cc2 + lr2 + gra2) / 4.0
        // overall 必须等于四项平均按 0.5 取整；模型给的 overall 只在缺失时参考。
        // 带改进点的文章封顶 7.5：4B 模型对任何文章都会列改进点，这是本地评分的诚实上限。
        var overall2 = roundHalf(avg)
        if (hasFixes) overall2 = overall2.coerceAtMost(7.5)
        return WritingScores(tr2, cc2, lr2, gra2, overall2)
    }

    /** 规范化口语评分：FC/LR/GRA/P + overall；不足 20 词或跑题时 overall 封顶 4.0。 */
    fun sanitizeSpeaking(
        fluency: Double, lexical: Double, grammar: Double, pronunciation: Double,
        overall: Double, wordCount: Int,
    ): SpeakingScores {
        val fc = band(fluency)
        val lr = band(lexical)
        val gra = band(grammar)
        val p = band(pronunciation)
        var o = roundHalf((fc + lr + gra + p) / 4.0)
        if (wordCount < 20) o = o.coerceAtMost(4.0)
        return SpeakingScores(fc, lr, gra, p, o)
    }

    /** 清洗 polished 文本：去掉模型偶尔多包的一层引号与首尾空白。 */
    fun cleanPolished(raw: String): String {
        var s = raw.trim()
        if (s.length >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length - 1).trim()
        }
        return s.replace("\\n", "\n")
    }

    /** 0.5 步长取整（四舍五入到最近的 0.5）。 */
    fun band(v: Double): Double = roundHalf(v)

    private fun roundHalf(v: Double): Double {
        if (v.isNaN() || v <= 0) return 0.0
        return Math.round(v * 2.0) / 2.0
    }

    data class WritingScores(
        val tr: Double, val cc: Double, val lr: Double, val gra: Double, val overall: Double,
    )

    data class SpeakingScores(
        val fluency: Double, val lexical: Double, val grammar: Double,
        val pronunciation: Double, val overall: Double,
    )

    /** 从模型原始输出中提取并解析 JSON 对象；严格解析失败时走字段级兜底（本地小模型常输出裸引号）。 */
    fun parseObject(content: String): JSONObject {
        val extracted = runCatching { extractJson(content) }.getOrNull()
        if (extracted != null) {
            runCatching { JSONObject(extracted) }.getOrNull()?.let { return it }
        }
        // 兜底：直接对全量输出做字段级正则提取（控制字符已由上面的清洗逻辑处理到不了这里时也能用）
        return fallbackParse(content)
    }

    /**
     * 字段级兜底：模型把 polished 里的引号写坏时，JSONObject 整体会挂。
     * 这里按 key 逐个提取，能拿到多少算多少。
     */
    private fun fallbackParse(raw: String): JSONObject {
        val o = JSONObject()
        val numKeys = listOf(
            "TR", "TA", "CC", "LR", "GRA", "overall",
            "FC", "P",
        )
        for (k in numKeys) {
            Regex("\"$k\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)").find(raw)?.let {
                runCatching { o.put(k, it.groupValues[1].toDouble()) }
            }
        }
        val fixesBody = Regex("\"top_fixes\"\\s*:\\s*\\[(.*?)\\]", RegexOption.DOT_MATCHES_ALL)
            .find(raw)?.groupValues?.get(1)
        if (fixesBody != null) {
            val fixes = JSONArray()
            Regex("\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(fixesBody).forEach {
                fixes.put(it.groupValues[1])
            }
            if (fixes.length() > 0) o.put("top_fixes", fixes)
        }
        Regex("\"polished\"\\s*:\\s*\"", RegexOption.DOT_MATCHES_ALL).find(raw)?.let { m ->
            val rest = raw.substring(m.range.last + 1)
            // 取第一个未转义引号作为结束；用 lastIndexOf 会把前面字段也吞进 polished。
            var i = 0
            var esc = false
            while (i < rest.length) {
                val c = rest[i]
                if (esc) esc = false
                else if (c == '\\') esc = true
                else if (c == '"') break
                i++
            }
            if (i > 0) o.put("polished", rest.substring(0, i))
        }
        if (!o.has("overall")) throw java.io.IOException("模型输出无法解析出评分：${raw.take(160)}")
        return o
    }
    fun extractJson(content: String): String {
        val cleaned = content.replace("```json", "").replace("```", "").trim()
        val start = cleaned.indexOf('{')
        if (start < 0) throw java.io.IOException("模型没有返回 JSON：${cleaned.take(120)}")
        var depth = 0
        var inStr = false
        var esc = false
        var end = -1
        for (i in start until cleaned.length) {
            val c = cleaned[i]
            when {
                esc -> esc = false
                c == '\\' -> esc = true
                c == '"' -> inStr = !inStr
                inStr -> Unit
                c == '{' -> depth++
                c == '}' -> {
                    depth--
                    if (depth == 0) { end = i; break }
                }
            }
        }
        if (end < 0) throw java.io.IOException("JSON 不完整：${cleaned.take(120)}")
        val raw = cleaned.substring(start, end + 1)
        val sb = StringBuilder(raw.length)
        inStr = false; esc = false
        for (c in raw) {
            when {
                esc -> { sb.append(c); esc = false }
                c == '\\' -> { sb.append(c); esc = true }
                c == '"' -> { inStr = !inStr; sb.append(c) }
                inStr && (c == '\n' || c == '\r' || c == '\t') -> sb.append(' ')
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    /** 从 JSON 对象中读数组字段为 List<String>（兼容 top_fixes / fixes）；过滤空白项。 */
    fun strings(o: JSONObject, vararg keys: String): List<String> {
        for (k in keys) {
            val arr = o.optJSONArray(k) ?: continue
            return (0 until arr.length()).map { arr.get(it).toString().trim() }.filter { it.isNotBlank() }
        }
        return emptyList()
    }

    fun num(o: JSONObject, vararg keys: String): Double {
        for (k in keys) if (o.has(k)) return o.optDouble(k, 0.0)
        return 0.0
    }

    /** 读分项分；缺失或非数字返回 null。评分结果必须四项齐全，缺项不能再落库成 Band 0。 */
    private fun numOrNull(o: JSONObject, vararg keys: String): Double? {
        for (k in keys) {
            if (!o.has(k)) continue
            val v = o.optDouble(k, Double.NaN)
            if (!v.isNaN() && v in 0.0..9.0) return v
        }
        return null
    }

    /** 把评分 JSON 规整为固定字段（供本地引擎直接落库复用云端格式）。 */
    fun toWritingEval(rawJson: String, wordCount: Int, task: String = "task2", essay: String = ""): WritingEval {
        val o = parseObject(rawJson)
        val tr = numOrNull(o, "TR", "TA", "task_response", "task_achievement")
            ?: throw java.io.IOException("评分缺少任务分项（TR/TA）：${rawJson.take(160)}")
        val cc = numOrNull(o, "CC", "coherence_cohesion")
            ?: throw java.io.IOException("评分缺少 CC：${rawJson.take(160)}")
        val lr = numOrNull(o, "LR", "lexical_resource")
            ?: throw java.io.IOException("评分缺少 LR：${rawJson.take(160)}")
        val gra = numOrNull(o, "GRA", "grammatical_range_accuracy")
            ?: throw java.io.IOException("评分缺少 GRA：${rawJson.take(160)}")
        val fixes = strings(o, "top_fixes", "fixes")
        val sentenceFixes = parseSentenceFixes(o)
        val s = sanitizeWriting(
            tr = tr, cc = cc, lr = lr, gra = gra,
            overall = num(o, "overall", "band"),
            wordCount = wordCount, task = task,
            essayText = essay,
            sentenceFixCount = sentenceFixes.size,
            hasFixes = fixes.isNotEmpty(),
        )
        return WritingEval(
            tr = s.tr, cc = s.cc, lr = s.lr, gra = s.gra, overall = s.overall,
            fixes = fixes,
            polished = cleanPolished(o.optString("polished", o.optString("rewrite", ""))),
            rawJson = rawJson,
            sentenceFixes = sentenceFixes,
        )
    }

    fun toSpeakingEval(rawJson: String, wordCount: Int): SpeakingEval {
        val o = parseObject(rawJson)
        val fc = numOrNull(o, "FC", "fluency", "fluency_coherence")
            ?: throw java.io.IOException("评分缺少 FC：${rawJson.take(160)}")
        val lr = numOrNull(o, "LR", "lexical_resource")
            ?: throw java.io.IOException("评分缺少 LR：${rawJson.take(160)}")
        val gra = numOrNull(o, "GRA", "grammatical_range_accuracy")
            ?: throw java.io.IOException("评分缺少 GRA：${rawJson.take(160)}")
        val p = numOrNull(o, "P", "pron")
            ?: throw java.io.IOException("评分缺少 P：${rawJson.take(160)}")
        val s = sanitizeSpeaking(
            fluency = fc, lexical = lr, grammar = gra, pronunciation = p,
            overall = num(o, "overall", "band"),
            wordCount = wordCount,
        )
        return SpeakingEval(
            fluency = s.fluency, lexical = s.lexical, grammar = s.grammar,
            pronunciation = s.pronunciation, overall = s.overall,
            fixes = strings(o, "top_fixes", "fixes"),
            improved = cleanPolished(o.optString("improved", o.optString("model_answer", ""))),
            rawJson = rawJson,
            sentenceFixes = parseSentenceFixes(o),
        )
    }

    fun parseSentenceFixes(o: JSONObject): List<SentenceFix> {
        val arr = o.optJSONArray("sentence_fixes") ?: return emptyList()
        return (0 until arr.length()).mapNotNull {
            runCatching {
                val x = arr.getJSONObject(it)
                SentenceFix(
                    sentence = x.optString("sentence"),
                    issue = x.optString("issue"),
                    better = x.optString("better"),
                )
            }.getOrNull()
        }
    }

    @Suppress("unused")
    private fun unusedJsonArray(): JSONArray = JSONArray()
}
