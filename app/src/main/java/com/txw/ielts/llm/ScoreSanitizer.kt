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

    /** 带词边界的例证标记（结尾是标点的不能加 \b，见下面 PLAIN）。 */
    private val EVIDENCE_WORDS = listOf(
        "for example", "for instance", "such as", "a case in point",
        "consider the case", "according to", "research shows", "studies show",
        "take the case",
    )

    /** 结尾是点的标记：\b 在标点后会失效，改用纯包含判断。 */
    private val EVIDENCE_PLAIN = listOf("e.g.")

    /**
     * 明确指向「语法/准确性」的措辞。
     * 刻意不收「句子结构」「表达」「用词」「重复」「空洞」—— 那些是文风与内容批评，
     * 属 CC/LR/TR 的事，压 GRA 属错位。
     */
    private val GRAMMAR_MARKERS = listOf(
        "语法", "时态", "主谓", "单复数", "冠词", "介词", "语态", "被动",
        "非谓语", "动词形态", "词性", "拼写", "从句错误", "标点", "悬垂", "搭配错误",
    )

    /**
     * 一条逐句建议是不是「语法类」问题。
     *
     * 为什么要分类型：提示词现在强制 sentence_fixes 2–5 条「即使写得好也要挑最弱的句子」，
     * 于是条数只反映模型服从度，不再反映错误密度。OPPO 真机实测（id=9，269 词）那 5 条
     * 全是「句子空洞 / 用词泛泛 / 显得机械」这类文风建议，一条语法错误都没有，
     * 却因命中「≥4 条 → GRA ≤ 6.5」把 GRA 从 8.0 压到 6.5 —— 等于告诉学生语法是短板，
     * 而这篇的真实短板是 TR（零例证）。诊断指错方向比给分轻一点更糟。
     */
    fun isGrammarIssue(issue: String): Boolean {
        val t = issue.lowercase()
        return GRAMMAR_MARKERS.any { t.contains(it) }
    }

    /** 语法类建议的条数（GRA 只认这个，不认总条数）。 */
    fun grammarIssueCount(fixes: List<SentenceFix>): Int = fixes.count { isGrammarIssue(it.issue) }

    /**
     * 统计不同种类的例证标记数（含百分比/统计数据）。
     * B7 与 B8 在任务回应上最可检测的差别就是「有没有具体支撑」：B7 可以只讲道理（易过度概括），
     * B8 要求每个论点都被展开、被支撑。4B 模型对任何文章都给 TR 8，这条规则把它拉回现实。
     */
    fun evidenceKinds(text: String): Int {
        val lower = text.lowercase()
        var n = EVIDENCE_WORDS.count { p ->
            Regex("\\b" + Regex.escape(p) + "\\b").containsMatchIn(lower)
        }
        n += EVIDENCE_PLAIN.count { lower.contains(it) }
        if (Regex("\\d+\\s*(%|percent|per cent)").containsMatchIn(lower)) n++
        return n
    }

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
        grammarIssueCount: Int = 0,
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
        // TR 校准（仅 Task 2，字数已达标）：B8 要求「充分展开并支撑每个论点」，
        // 而 4B 模型对任何文章都给 8.0。全文零例证 = 典型「过度概括」（B7 特征）→ TR ≤ 7.0；
        // 只有一处 → ≤ 7.5。Task 1 是描述数据，不需要例证，不能套这条。
        if (task != "task1" && wordCount >= 250 && essayText.isNotBlank()) {
            when (evidenceKinds(essayText)) {
                0 -> tr2 = tr2.coerceAtMost(7.0)
                1 -> tr2 = tr2.coerceAtMost(7.5)
            }
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
        // GRA 只看「语法类」建议。总条数已被提示词的「2–5 条永不为空」污染：
        // 挑最弱句子给更好改写是文风打磨，不该算成语法错误。
        val gra2 = band(gra).let {
            when {
                grammarIssueCount >= 4 -> it.coerceAtMost(6.5)
                grammarIssueCount >= 1 -> it.coerceAtMost(7.0)
                // 有打磨建议但没提语法：不给满分，但也不冤枉。
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
        // 兜底提取 sentence_fixes：top_fixes / polished 里的裸引号会让整体 JSONObject 解析失败，
        // 但逐句改法是最高价值字段，而且它一丢就会连带 GRA 封顶失效（sentenceFixCount=0）。
        extractArrayBody(raw, "sentence_fixes")?.let { body ->
            val arr = JSONArray()
            Regex("\\{(?:[^{}]|\\{[^{}]*\\})*\\}", RegexOption.DOT_MATCHES_ALL).findAll(body).forEach { m ->
                val obj = m.value
                val sentence = jsonStringField(obj, "sentence")
                val better = jsonStringField(obj, "better")
                if (!sentence.isNullOrBlank() && !better.isNullOrBlank()) {
                    arr.put(
                        JSONObject()
                            .put("sentence", sentence)
                            .put("issue", jsonStringField(obj, "issue") ?: "")
                            .put("better", better)
                    )
                }
            }
            if (arr.length() > 0) o.put("sentence_fixes", arr)
        }
        if (!o.has("overall")) throw java.io.IOException("模型输出无法解析出评分：${raw.take(160)}")
        return o
    }

    /**
     * 取出 `"key": [ ... ]` 的方括号内容（跳过字符串内的括号）。
     * 用于兜底提取 sentence_fixes —— 这段内容丢了不只是少个展示字段，
     * 还会让 GRA 封顶（列了错误就不能给近满分）失效。
     */
    private fun extractArrayBody(raw: String, key: String): String? {
        val m = Regex("\"$key\"\\s*:\\s*\\[").find(raw) ?: return null
        var depth = 0
        var inStr = false
        var esc = false
        var i = m.range.last
        val start = i + 1
        while (i < raw.length) {
            val c = raw[i]
            when {
                esc -> esc = false
                c == '\\' -> esc = true
                c == '"' -> inStr = !inStr
                inStr -> Unit
                c == '[' -> depth++
                c == ']' -> {
                    depth--
                    if (depth == 0) return raw.substring(start, i)
                }
            }
            i++
        }
        return null
    }

    /** 从对象字面量里取字符串字段并反转义。 */
    private fun jsonStringField(obj: String, key: String): String? {
        val m = Regex("\"$key\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(obj) ?: return null
        return unescapeJson(m.groupValues[1])
    }

    private fun unescapeJson(s: String): String {
        if (!s.contains('\\')) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '\\' || i == s.length - 1) {
                sb.append(c); i++; continue
            }
            when (val n = s[i + 1]) {
                'n' -> { sb.append('\n'); i += 2 }
                't' -> { sb.append('\t'); i += 2 }
                'r' -> { sb.append('\r'); i += 2 }
                '"' -> { sb.append('"'); i += 2 }
                '\\' -> { sb.append('\\'); i += 2 }
                '/' -> { sb.append('/'); i += 2 }
                'u' -> {
                    val hex = s.substring(i + 2, minOf(i + 6, s.length))
                    val code = hex.toIntOrNull(16)
                    if (code != null && hex.length == 4) { sb.append(code.toChar()); i += 6 }
                    else { sb.append(n); i += 2 }
                }
                else -> { sb.append(n); i += 2 }
            }
        }
        return sb.toString()
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
            grammarIssueCount = grammarIssueCount(sentenceFixes),
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
        // P 是转写文本最不可观测的一项，4B 模型偶尔会整项漏掉（真机实测 id=21 因此整次评分失败）。
        // 先认别名；仍缺就按 FC/LR/GRA 均值估算。用独立标记返回，不写进 fixes：
        // fixes 会进「高频问题」统计，把模型漏项算成学生「发音（估计）」弱点是错误归因。
        val pRaw = numOrNull(o, "P", "PR", "pron", "pronunciation", "pronunciation_score")
        val estimatedP = pRaw == null
        val p = pRaw ?: band((fc + lr + gra) / 3.0)
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
            estimatedP = estimatedP,
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
