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
            val rawAnswers = strings(q, "answers")
            if (prompt.isBlank() || rawAnswers.isEmpty()) continue   // 残题直接丢，不拖垮整套
            // 模型常把 type 写成 Choice/TFNG，或 choice 不给选项、tfng 不写标准选项；
            // 归一化到 UI 认识的三种题型，避免出现无法作答的题。
            var options = strings(q, "options")
            var answers = rawAnswers
            var type = canonicalType(q.optString("type"), options, answers)
            when (type) {
                "choice" -> if (options.size < 2) {
                    type = "gap"
                    options = emptyList()
                } else {
                    // 提示词要求答案写选项原文，但模型常只写 "B" / "(B)" / "B. 选项原文"。
                    // 不折回原文的话，用户点了正确选项也判错，整套题白做。
                    answers = answers.map { canonicalChoice(it, options) }
                }
                "tfng" -> {
                    if (options.size < 2) options = DEFAULT_TFNG
                    answers = answers.map { canonicalTfng(it) }
                }
            }
            // 模型（尤其 4B）偶尔把同一题写两遍：题干+答案归一化后只收第一次。
            if (!seen.add(questionKey(prompt, answers))) continue
            out.add(
                GeneratedQuestion(
                    type = type,
                    prompt = prompt,
                    options = options,
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

    private val DEFAULT_TFNG = listOf("TRUE", "FALSE", "NOT GIVEN")

    private val TFNG_VALUES = setOf("TRUE", "FALSE", "NOT GIVEN")

    /** 题型别名：统一小写并去掉空格/下划线/斜杠/连字符后比较。 */
    private val CHOICE_TYPES = setOf(
        "choice", "singlechoice", "multiplechoice", "mcq", "select", "selectone", "选择题", "单选",
    )
    private val TFNG_TYPES = setOf(
        "tfng", "tfn", "truefalse", "truefalseng", "truefalsenotgiven", "yesnonotgiven", "判断题", "判断",
    )
    private val GAP_TYPES = setOf(
        "gap", "blank", "cloze", "completion", "sentencecompletion", "shortanswer",
        "fill", "fillin", "fillblank", "fillintheblank", "填空题", "填空", "简答",
    )
    private val TYPE_NOISE = Regex("[^a-z0-9\\u4e00-\\u9fff]")

    /**
     * 把模型写的题型折到 choice/tfng/gap 三种 UI 能画的类型。
     * 模型很爱写 `Multiple Choice`、`True/False/Not Given`、`fill-in-the-blank`，
     * 原样透传会让题目在 UI 里退化成填空题，明明有选项却只能手输。
     * 别名认不出时按字段推断：有选项且答案能对上选项就按选择题，答案像 T/F/NG 就按判断题。
     */
    private fun canonicalType(raw: String, options: List<String>, answers: List<String>): String {
        return when (raw.lowercase().replace(TYPE_NOISE, "")) {
            "" -> inferType(options, answers)
            in CHOICE_TYPES -> "choice"
            in TFNG_TYPES -> "tfng"
            in GAP_TYPES -> "gap"
            else -> inferType(options, answers)
        }
    }

    private fun inferType(options: List<String>, answers: List<String>): String {
        val tfngOptions = options.isEmpty() || options.all { it.trim().uppercase() in TFNG_VALUES }
        if (tfngOptions && answers.any { canonicalTfng(it) in TFNG_VALUES }) return "tfng"
        if (options.size >= 2 && answers.any { answerPointsAtOption(it, options) }) return "choice"
        return "gap"
    }

    /** 答案是否是「选项原文 / 选项字母 / 字母 + 原文」，用来判断一组带选项的题能否当选择题画。 */
    private fun answerPointsAtOption(answer: String, options: List<String>): Boolean {
        val a = answer.trim()
        if (options.any { it.trim().equals(a, ignoreCase = true) }) return true
        CHOICE_LETTER.find(a)?.let { m ->
            if (optionAt(m.groupValues[1], options) != null) return true
        }
        CHOICE_PREFIX.find(a)?.let { m ->
            if (optionAt(m.groupValues[1], options) != null) return true
        }
        return false
    }

    /** 只含选项字母：「B」「(b)」「C.」「D、」。 */
    private val CHOICE_LETTER = Regex("^[（(]?([A-Za-z])[)）.．:：、]?$")

    /** 字母 + 选项原文：「B. The depth of soil」「(C) ...」。 */
    private val CHOICE_PREFIX = Regex("^[（(]?([A-Za-z])[)）.．:：、]\\s*(.+)$")

    /**
     * choice 答案折回选项原文：先比原文，再认字母，最后认「字母 + 原文」。
     * 字母越界（只有 4 个选项却给 E）原样保留，不去猜。
     */
    private fun canonicalChoice(answer: String, options: List<String>): String {
        val a = answer.trim()
        if (options.any { it.trim().equals(a, ignoreCase = true) }) return a
        CHOICE_LETTER.find(a)?.let { m ->
            optionAt(m.groupValues[1], options)?.let { return it }
        }
        CHOICE_PREFIX.find(a)?.let { m ->
            val rest = m.groupValues[2].trim()
            // 序号可能与原文不完全一致：先认序号对应的选项，对不上再按原文全表找。
            val byIndex = optionAt(m.groupValues[1], options)
            if (byIndex != null && byIndex.equals(rest, ignoreCase = true)) return byIndex
            options.firstOrNull { it.trim().equals(rest, ignoreCase = true) }?.let { return it.trim() }
            if (byIndex != null) return byIndex
        }
        return a
    }

    private fun optionAt(letter: String, options: List<String>): String? {
        val i = letter.uppercase()[0] - 'A'
        return options.getOrNull(i)?.trim()?.takeIf { it.isNotBlank() }
    }

    /** TFNG 答案的常见缩写归一化；判分本身忽略大小写与空格，这里统一成 UI 选项文本。 */
    private fun canonicalTfng(answer: String): String {
        val a = answer.trim().uppercase().replace(ws, " ")
        return when (a) {
            "T", "TRUE" -> "TRUE"
            "F", "FALSE" -> "FALSE"
            "NG", "N", "NOTGIVEN", "NOT GIVEN" -> "NOT GIVEN"
            else -> answer.trim()
        }
    }

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
