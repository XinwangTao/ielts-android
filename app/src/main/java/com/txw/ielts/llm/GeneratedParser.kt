package com.txw.ielts.llm

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import kotlin.math.abs
import kotlin.math.floor

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
                    // 判断题有两套标准选项：TRUE/FALSE 与 YES/NO（都带 NOT GIVEN）。
                    // 模型常写 YES/NO 且答案只给 Y/N；若一律补 TRUE/FALSE 三选项，
                    // 用户点「YES」会被判错，因为答案 "Y" 与选项「YES」不是同一个字符串。
                    val yesNo = usesYesNo(options, answers)
                    options = tfngOptions(options, yesNo)
                    answers = answers.map { canonicalTfng(it, yesNo) }
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

    /** YES/NO/NOT GIVEN 是判断题的另一套标准选项（IELTS 里与 T/F/NG 并列）。 */
    private val DEFAULT_YNNG = listOf("YES", "NO", "NOT GIVEN")

    /** 题型别名：统一小写并去掉空格/下划线/斜杠/连字符后比较。 */
    private val CHOICE_TYPES = setOf(
        "choice", "singlechoice", "multiplechoice", "mcq", "select", "selectone", "选择题", "单选",
    )
    private val TFNG_TYPES = setOf(
        "tfng", "tfn", "truefalse", "truefalseng", "truefalsenotgiven", "yesnonotgiven",
        "ynng", "yesno", "判断题", "判断",
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
        val yesNo = usesYesNo(options, answers)
        val tfngOptions = options.isEmpty() || options.all { tfngLabel(it, yesNo) != null }
        if (tfngOptions && answers.any { tfngLabel(it, yesNo) != null }) return "tfng"
        if (options.size >= 2 && answers.any { answerPointsAtOption(it, options) }) return "choice"
        return "gap"
    }

    /**
     * 这组选项/答案是不是 YES/NO/NOT GIVEN 那一套。
     * 先看选项：YES/NO 与 TRUE/FALSE 各记一票，票多者胜（NOT GIVEN 两套共用，不投票）。
     * 选项看不出时再看答案，因此「无选项 + 答案 Y」也能落回判断题而不是填空题。
     */
    private fun usesYesNo(options: List<String>, answers: List<String>): Boolean {
        val yn = options.count { yesNoVote(tfngLabel(it, yesNo = true)) }
        val tf = options.count { yesNoVote(tfngLabel(it, yesNo = false)) }
        if (yn != tf) return yn > tf
        return answers.any { yesNoVote(tfngLabel(it, yesNo = true)) } &&
            answers.none { yesNoVote(tfngLabel(it, yesNo = false)) }
    }

    /** NOT GIVEN 两套共用，不参与 YES/NO 与 TRUE/FALSE 的投票。 */
    private fun yesNoVote(label: String?): Boolean = label != null && label != "NOT GIVEN"

    /**
     * 判断题选项归一化：认得出来就补成标准三选项并统一成 UI 文案；
     * 模型只给了 TRUE/FALSE（漏 NOT GIVEN）或顺序/大小写不标准时都能修正。
     * 出现认不出的选项就原样保留，不擅自改写题目。
     */
    private fun tfngOptions(options: List<String>, yesNo: Boolean): List<String> {
        if (options.isEmpty()) return if (yesNo) DEFAULT_YNNG else DEFAULT_TFNG
        val labels = options.map { tfngLabel(it, yesNo) }
        if (labels.any { it == null }) return options
        val standard = if (yesNo) DEFAULT_YNNG else DEFAULT_TFNG
        // 全是标准项：统一文案与顺序，并补齐缺失项（如只给 TRUE/FALSE 时补 NOT GIVEN），
        // 否则模型漏写的那一项在 UI 里选不到，选对也判错。
        return standard
    }

    /** 把单个判断题答案折到当前选项集的文案；认不出时原样返回（不猜）。 */
    private fun canonicalTfng(answer: String, yesNo: Boolean): String =
        tfngLabel(answer, yesNo) ?: answer.trim()

    /** 判断题文案归一：TRUE/FALSE 与 YES/NO 两套各自认缩写；不认识的返回 null。 */
    private fun tfngLabel(raw: String, yesNo: Boolean): String? {
        val a = raw.trim().uppercase().replace(ws, " ")
        return if (yesNo) when (a) {
            "Y", "YES" -> "YES"
            "N", "NO" -> "NO"
            "NG", "N G", "NOTGIVEN", "NOT GIVEN" -> "NOT GIVEN"
            else -> null
        } else when (a) {
            "T", "TRUE" -> "TRUE"
            "F", "FALSE" -> "FALSE"
            "NG", "N", "N G", "NOTGIVEN", "NOT GIVEN" -> "NOT GIVEN"
            else -> null
        }
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

    /** 归一化题干与答案：忽略大小写和连续空白，用来识别模型重复生成的同一题。 */
    private fun questionKey(prompt: String, answers: List<String>): String =
        prompt.replace(ws, " ").lowercase() + "|" +
            answers.joinToString("|") { it.replace(ws, " ").lowercase() }

    /**
     * 读答案/选项/关键词这类文本数组。
     *
     * 模型（尤其本地 4B）常把数字、布尔答案写成裸 JSON 值：`"answers":[35]`、
     * `"answers":[true]`。原来只认 String，这类题会因「缺答案」被整题丢掉——
     * 听力里的价格、时间、数量题恰好最容易写成数字，用户还没做就先少了几道。
     * 这里把标量统一折成文本，整数值不补 ".0"（`20.0` 还原成 `20`，用户才会这么打）。
     * 单个答案被写成裸字符串/数字时同样收下。
     */
    private fun strings(o: JSONObject, key: String): List<String> {
        val a: JSONArray? = o.optJSONArray(key)
        if (a != null) return (0 until a.length()).mapNotNull { i -> scalarText(a.opt(i)) }
        return scalarText(o.opt(key))?.let { listOf(it) } ?: emptyList()
    }

    /** JSON 标量转文本；对象/数组/缺失/null 都返回 null（交给调用方按缺字段处理）。 */
    private fun scalarText(v: Any?): String? {
        if (v == null || v == JSONObject.NULL) return null
        val text = when (v) {
            is String -> v.trim()
            is Number -> numberText(v)
            is Boolean -> v.toString()
            else -> return null
        }
        return text.takeIf { it.isNotBlank() }
    }

    /** JSON 无整数类型之分：35 可能是 Integer/Long，20.0 是 Double。整数值按整数文本输出。 */
    private fun numberText(n: Number): String {
        val d = n.toDouble()
        return if (d.isFinite() && d == floor(d) && abs(d) < 1e15) d.toLong().toString() else n.toString()
    }
}
