package com.txw.ielts.llm

import android.content.Context
import org.json.JSONObject

/** 写作提纲的一组「论点 + 解释 + 例子」。 */
data class OutlineArgument(
    val point: String,
    val explain: String,
    val example: String,
)

/**
 * 写作前的 AI 提纲。只展示、不预填正文：用户要写自己的文章，
 * 提纲的作用是解决「不知道写什么 / 论点不展开」，不是替他写。
 */
data class StudyOutline(
    val stance: String,
    val arguments: List<OutlineArgument>,
    val phrases: List<String>,
    /** 解析失败时保留模型原文，界面至少还能显示内容。 */
    val raw: String = "",
) {
    val isEmpty: Boolean get() = stance.isBlank() && arguments.isEmpty() && phrases.isEmpty() && raw.isBlank()
}

/** 口语范例答案：答案本身 + 可复用表达 + 中文结构提示。 */
/** AI 搭好的作文骨架：每段由若干带【中文槽位】的句式组成，学生自己填内容。 */
data class SkeletonParagraph(
    val label: String,
    val lines: List<String>,
    val hint: String,
)

data class EssaySkeleton(
    val paragraphs: List<SkeletonParagraph>,
    val raw: String = "",
) {
    val isEmpty: Boolean get() = paragraphs.isEmpty() && raw.isBlank()
    fun toDraftText(): String = paragraphs.joinToString(BLANK_LINE) { p -> p.lines.joinToString("\n") }

    private companion object {
        const val BLANK_LINE = "\n\n"
    }
}

/** AI 按当前题目填好的一句模板：句子本身 + 一句中文用法说明。 */
data class TemplateFill(
    val text: String,
    val why: String,
    val raw: String = "",
) {
    val isEmpty: Boolean get() = text.isBlank() && raw.isBlank()
}

data class SpeakingSample(
    val answer: String,
    val keywords: List<String>,
    val structure: List<String>,
    val raw: String = "",
) {
    val isEmpty: Boolean get() = answer.isBlank() && raw.isBlank()
}

/** 从作文/转写与逐句改法里提取的生词搭配。 */
data class VocabItem(
    val phrase: String,
    val meaning: String,
    val example: String,
)

data class VocabResult(
    val items: List<VocabItem>,
    val raw: String = "",
    /** 输入超过 token 预算时被省略的字符数（0 表示原文完整送进去了），结果卡据此提示。 */
    val trimmedChars: Int = 0,
)

/** AI 复盘的一条行动建议。 */
data class ReviewTask(
    val title: String,
    val why: String,
    val how: String,
)

/** 最近练习的 AI 复盘：总结 + 3 个行动 + 一句鼓励。 */
data class AiReview(
    val summary: String,
    val tasks: List<ReviewTask>,
    val encouragement: String,
    val raw: String = "",
) {
    val isEmpty: Boolean
        get() = summary.isBlank() && tasks.isEmpty() && encouragement.isBlank() && raw.isBlank()
}

/** 错题变式题：同一考点换新内容，防止「记住答案」而不是「学会方法」。 */
data class BankVariant(
    val question: String,
    val options: List<String>,
    val answer: String,
    val explain: String,
    val raw: String = "",
) {
    val isEmpty: Boolean get() = question.isBlank()
}

/**
 * 本地 AI 学习助手：写作提纲、口语范例、评分后生词卡、最近练习复盘。
 * 图：docs/archify/ielts-local-ai_workflow。
 *
 * 四个功能都只调用 LocalLlmEngine.generateText（同一把引擎闸门），
 * 不新增云端 provider —— 当前讯飞额度已死，用户要的是离线也能用的能力。
 */
object StudyAi {

    suspend fun outline(context: Context, model: String, topic: String, task: String): StudyOutline {
        val isTask1 = task == "task1"
        val system = if (isTask1) TASK1_OUTLINE_SYSTEM else TASK2_OUTLINE_SYSTEM
        val user = buildString {
            append("Task: ").append(topic.trim())
            if (isTask1 && topic.contains("Chart image shown", ignoreCase = true)) {
                append(
                    "\n\nNote: the chart image is not visible to you. " +
                        "Give a generic Task 1 report plan and useful language for trends/comparisons; do not invent figures.",
                )
            }
        }
        val raw = LocalLlmEngine.generateText(context, model, system, user)
        // 解析成功也保留原始输出：调用方据此缓存，返回页面时不必再跑一遍模型。
        return parseOutline(raw).let { if (it.isEmpty) it else it.copy(raw = raw) }
    }

    suspend fun speakingSample(
        context: Context,
        model: String,
        part: String,
        question: String,
    ): SpeakingSample {
        val user = "Part: $part\nQuestion: ${question.trim()}"
        val firstRaw = LocalLlmEngine.generateText(context, model, SPEAKING_SYSTEM, user)
        val first = parseSample(firstRaw)
        // 本地 4B 的 Part 2 实测只写 146 词（目标 180-220）；给一次更明确的重写机会，
        // 只有确实更长才采用，重试失败也不丢第一版。
        if (part == "Part 2" && wordCount(first.answer) < 170) {
            val retryRaw = try {
                LocalLlmEngine.generateText(
                    context, model, SPEAKING_SYSTEM,
                    "$user\n\nYour previous answer was too short. Rewrite it with at least 180 words " +
                        "(about 12-15 sentences). Do not stop early.",
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            val retry = retryRaw?.let { parseSample(it) }
            if (retry != null && wordCount(retry.answer) > wordCount(first.answer)) {
                return retry.copy(raw = retryRaw!!)
            }
        }
        // 解析成功也保留原始输出，供调用方缓存复用。
        return first.copy(raw = firstRaw)
    }

    private fun wordCount(s: String): Int = s.split(Regex("\\s+")).count { it.isNotBlank() }

    suspend fun vocab(
        context: Context,
        model: String,
        text: String,
        sentenceFixes: List<Triple<String, String, String>>,
    ): VocabResult {
        val corrections = sentenceFixes.joinToString("\n") { (sentence, issue, better) ->
            "- $sentence -> $better" + if (issue.isNotBlank()) " ($issue)" else ""
        }
        // 原文和逐句改法都可能有几万个字符：按预算装配，先砍原文（且留两头），改法后砍。
        val prompt = PromptBudget.fit(
            listOf(
                PromptBudget.Part("essay", "Essay:\n${text.trim()}", priority = 1),
                PromptBudget.Part(
                    "corrections",
                    "Corrections:\n" + corrections.ifBlank { "(no sentence corrections)" },
                    priority = 3,
                ),
            ),
            PromptBudget.STUDY_INPUT_TOKENS,
        )
        val raw = LocalLlmEngine.generateText(context, model, VOCAB_SYSTEM, prompt.text)
        val parsed = parseVocab(raw)
        // 解析成功也保留原始输出，供调用方缓存复用（重开结果页不必再跑一次）。
        return if (parsed.items.isEmpty()) parsed else parsed.copy(raw = raw, trimmedChars = prompt.removedChars)
    }

    /** 最近练习复盘：输入由调用方拼好的「最近成绩 + 高频问题」摘要。 */
    suspend fun review(context: Context, model: String, summary: String): AiReview {        // 摘要末尾是目标分与距考期天数，正是模型排计划强度的依据：留两头，别只留前 4000 字。
        val fitted = PromptBudget.clipText(
            "Recent practice summary:\n${summary.trim()}",
            PromptBudget.STUDY_INPUT_TOKENS,
        )
        val raw = LocalLlmEngine.generateText(context, model, REVIEW_SYSTEM, fitted)
        return parseReview(raw)
    }

    // ---- 解析：JSON 优先，坏了退纯文本 ----

    private fun jsonBody(raw: String): String? =
        // 与评分共用一套提取：括号配对、字符串内裸换行清洗，且不会把 JSON 后面的说明文字一起吞掉。
        runCatching { ScoreSanitizer.extractJson(raw) }.getOrNull()

    /**
     * Part 3 追问：基于考生刚才的回答，生成一个自然的考官追问（真实考试 Part 2 之后就是这个流程）。
     * 只返回一句问题，不落库；提示词已在 tools/ai_probe.py 的 speaking_follow_up 用例验证。
     */
    suspend fun followUp(
        context: Context,
        model: String,
        question: String,
        answer: String,
    ): String {
        val prompt = PromptBudget.clipText(
            "Previous question: ${question.trim()}\nCandidate's answer: ${answer.trim()}",
            PromptBudget.STUDY_INPUT_TOKENS,
        )
        return cleanFollowUp(LocalLlmEngine.generateText(context, model, FOLLOW_UP_SYSTEM, prompt))
    }

    /**
     * 同类型写作新题：写完一篇后换一个考同样能力的题目再写，避免反复重写同一题。
     * 只返回一句题目，不落库；真机入口在结果页「换一个同类型题目」。
     */
    suspend fun writingTopic(
        context: Context,
        model: String,
        topic: String,
        task: String,
    ): String {
        val kind = if (task == "task1") "Academic Task 1 (chart/report)" else "Task 2 (discussion essay)"
        val user = "Task type: $kind\nOriginal prompt: ${topic.trim()}"
        val raw = LocalLlmEngine.generateText(
            context, model, WRITING_TOPIC_SYSTEM, PromptBudget.clipText(user, PromptBudget.STUDY_INPUT_TOKENS),
        )
        return cleanPrompt(raw)
    }

    /**
     * 按题目搭一篇「可填空的作文骨架」：4 段，每段 2-4 个句式，内容词换成【中文槽位】，
     * 学生自己往里填；提示词明确禁止写成完整范文。
     */
    suspend fun essaySkeleton(
        context: Context,
        model: String,
        topic: String,
        task: String,
    ): EssaySkeleton {
        val taskType = if (task == "task1") "Academic Task 1 (chart/report)" else "Task 2 (essay)"
        val frames = if (task == "task1") TASK1_FRAMES else TASK2_FRAMES
        val user = "Task type: " + taskType +
            "\nPrompt: " + topic.trim() +
            "\nUseful sentence frames (adapt, do not invent new structure):\n" + frames
        val raw = LocalLlmEngine.generateText(
            context, model, SKELETON_SYSTEM,
            PromptBudget.clipText(user, PromptBudget.STUDY_INPUT_TOKENS),
        )
        val parsed = parseSkeleton(raw)
        // 4B 常把槽位也填掉，给出一篇「范文」——那就失去填空的意义了。
        // 只要整篇带【】的行少于两行，就退回本地固定骨架，保证学生永远有东西填。
        val slotLines = parsed.paragraphs.flatMap { it.lines }.count { it.contains("【") }
        // 结构也要认：4B 偶尔把四段塞进一段（Introduction 里出现 First 和 In conclusion），
        // 这种「AI 版」不如固定骨架，直接退回。
        return if (slotLines >= 2 && parsed.paragraphs.size >= 3) parsed else fallbackSkeleton(task)
    }

    /** 本地保底骨架：模型不听话时用它，保证四段结构和【中文槽位】一定到位。 */
    fun fallbackSkeleton(task: String): EssaySkeleton {
        val t2 = listOf(
            SkeletonParagraph(
                "Introduction",
                listOf(
                    "It is often argued that 【观点 A】, while others maintain that 【观点 B】.",
                    "While I accept that 【对方合理之处】, I would argue that 【你的立场】.",
                ),
                "两个观点各写一句（原文换个说法），第二句亮你的立场。",
            ),
            SkeletonParagraph(
                "Body 1",
                listOf(
                    "First and foremost, 【支撑你立场的第一个理由】.",
                    "This is largely because 【解释这个理由为什么成立】.",
                    "A case in point is 【一个具体例子：人 / 国家 / 场景】.",
                ),
                "理由要具体到一个场景，例子只写一句，别编统计数字。",
            ),
            SkeletonParagraph(
                "Body 2",
                listOf(
                    "Another factor worth considering is 【第二个理由】.",
                    "As a consequence, 【这个理由带来的结果】.",
                    "Admittedly, 【对方可能的反驳】; however, 【你的回应】.",
                ),
                "第二段换一个角度（经济 / 公平 / 效率 / 长期影响），最后一句让步一句反驳。",
            ),
            SkeletonParagraph(
                "Conclusion",
                listOf(
                    "In conclusion, 【换词重申立场】, and 【一句建议或预测】.",
                ),
                "换词不换意地重述立场，末尾给一句展望，不要加新论点。",
            ),
        )
        val t1 = listOf(
            SkeletonParagraph(
                "Introduction",
                listOf("The 【图类型】 illustrates 【主题】 over the period from 【起始年】 to 【结束年】."),
                "改写题目：图类型 + 主题 + 时间范围，别照抄题干。",
            ),
            SkeletonParagraph(
                "Overview",
                listOf("Overall, 【最显著趋势】, while 【次要趋势】."),
                "不写数据，只写两句话能概括的整体趋势；这段没有就上不了 6 分。",
            ),
            SkeletonParagraph(
                "Detail 1",
                listOf(
                    "There was a marked increase in 【指标 A】, rising from 【数】 to 【数】.",
                    "【指标 B】 declined steadily, from 【数】 in 【年】 to 【数】 in 【年】.",
                ),
                "挑变化最大的两条线，数据必须来自图表。",
            ),
            SkeletonParagraph(
                "Detail 2",
                listOf(
                    "【A】 accounted for 【百分比】 of the total, compared with 【B】 at 【百分比】.",
                    "While 【A】 rose sharply, 【B】 remained almost unchanged.",
                ),
                "补一组对比（占比或倍数），把剩下的关键数据写完。",
            ),
        )
        return EssaySkeleton(if (task == "task1") t1 else t2)
    }

    /** JSON 优先；模型写坏 JSON 时退化成按空行切段，界面至少还给得出一份骨架。 */
    fun parseSkeleton(raw: String): EssaySkeleton {
        val json = runCatching { ScoreSanitizer.extractJson(raw) }.getOrNull()
        if (json != null) {
            val list = runCatching {
                val root = JSONObject(json)
                val arr = root.optJSONArray("paragraphs") ?: root.optJSONArray("body")
                (0 until (arr?.length() ?: 0)).mapNotNull { i ->
                    val o = arr!!.optJSONObject(i) ?: return@mapNotNull null
                    val label = o.optString("label").ifBlank { o.optString("name") }
                    val linesArr = o.optJSONArray("lines")
                    val lines = if (linesArr != null) {
                        (0 until linesArr.length()).map { linesArr.optString(it) }.filter { it.isNotBlank() }
                    } else {
                        o.optString("text").lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()
                    }
                    if (lines.isEmpty()) null else SkeletonParagraph(label.ifBlank { "Paragraph" }, lines, o.optString("hint"))
                }
            }.getOrNull()
            if (!list.isNullOrEmpty()) return EssaySkeleton(list, raw)
        }
        val blocks = raw.split(Regex("\n\\s*\n")).map { it.trim() }.filter { it.isNotBlank() }
        if (blocks.isEmpty()) return EssaySkeleton(emptyList(), raw)
        return EssaySkeleton(
            blocks.mapIndexed { i, b ->
                SkeletonParagraph(
                    label = "Paragraph ${i + 1}",
                    lines = b.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList(),
                    hint = "",
                )
            },
            raw,
        )
    }

    /**
     * 给定题目 + 句子模板，让 AI 填槽位，并给一句中文「为什么这么填」。
     * 模板是骨架不是答案：提示词要求保留结构、只换内容，输出 1-3 句可直接进正文的英文。
     */
    suspend fun fillTemplate(
        context: Context,
        model: String,
        topic: String,
        frame: String,
        whenTo: String,
    ): TemplateFill {
        val user = "Topic: " + topic.trim() +
            "\nTemplate: " + frame.trim() +
            "\nChinese hint about when to use it: " + whenTo.trim()
        val raw = LocalLlmEngine.generateText(
            context, model, TEMPLATE_FILL_SYSTEM,
            PromptBudget.clipText(user, PromptBudget.STUDY_INPUT_TOKENS),
        )
        return parseTemplateFill(raw)
    }

    /** 兼容三种输出：JSON、`Filled: ...` 冒号格式、直接一段英文。 */
    fun parseTemplateFill(raw: String): TemplateFill {
        val json = runCatching { ScoreSanitizer.extractJson(raw) }.getOrNull()
        if (json != null) {
            val parsed = runCatching {
                val o = JSONObject(json)
                val text = o.optString("filled").ifBlank { o.optString("sentence") }.ifBlank { o.optString("text") }
                val why = o.optString("why").ifBlank { o.optString("explain") }.ifBlank { o.optString("note") }
                text.trim() to why.trim()
            }.getOrNull()
            if (parsed != null && parsed.first.isNotBlank()) return TemplateFill(parsed.first, parsed.second, raw)
        }
        val lines = raw.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()
        if (lines.isEmpty()) return TemplateFill("", "", raw)
        var text = lines.first()
        var why = ""
        for (prefix in listOf("Filled:", "Sentence:", "filled:", "sentence:")) {
            if (text.startsWith(prefix)) text = text.removePrefix(prefix).trim()
        }
        val whyLine = lines.drop(1).firstOrNull { it.startsWith("Why:") || it.startsWith("why:") || it.startsWith("说明") }
        if (whyLine != null) why = whyLine.substringAfter(':').substringAfter("：").trim()
        return TemplateFill(text.trim('"', '\'', '“', '”', '*', '`').trim(), why, raw)
    }

    /** 题目只留第一段纯文本：模型偶尔带 Prompt:/Question: 前缀、引号或「Here is the prompt:」引导语。 */
    fun cleanPrompt(raw: String): String {
        // 本地 4B 有把任何输出包成 JSON 的习惯（追问已经实测到），先认 prompt/question/topic 字段。
        val json = runCatching { ScoreSanitizer.extractJson(raw) }.getOrNull()
        if (json != null) {
            val fromJson = runCatching {
                val o = org.json.JSONObject(json)
                o.optString("prompt").ifBlank { o.optString("question") }.ifBlank { o.optString("topic") }
            }.getOrNull()
            if (!fromJson.isNullOrBlank()) return fromJson.trim().trim('"', '\'', '*', '`').trim()
        }
        val lines = raw.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()
        if (lines.isEmpty()) return ""
        // 第一行只是「Here is the new prompt:」这类引导语时取下一行。
        var s = if (lines.first().endsWith(":") && lines.size > 1) lines[1] else lines.first()
        s = s
            .removePrefix("Prompt:").removePrefix("prompt:").removePrefix("Question:").removePrefix("Q:").trim()
        return s.trim('"', '\'', '“', '”', '‘', '’', '*', '`').trim()
    }

    /** 模型偶尔带上 "Question:" 前缀、引号、解释或 JSON 包装：只留第一句问题。 */
    fun cleanFollowUp(raw: String): String {
        // 本地 4B 实测会按训练习惯返回 {"question":"..."}（提示词要求纯文本也不听）：先拆 JSON。
        // extractJson 对纯文本会抛 IOException，这里当成「没有 JSON」继续走文本清洗。
        val json = runCatching { ScoreSanitizer.extractJson(raw) }.getOrNull()
        if (json != null) {
            val fromJson = runCatching {
                val o = org.json.JSONObject(json)
                o.optString("question").ifBlank { o.optString("follow_up") }
            }.getOrNull()
            if (!fromJson.isNullOrBlank()) return fromJson.trim()
        }
        val line = raw.lineSequence().map { it.trim() }.firstOrNull { it.isNotBlank() } ?: return ""
        var s = line
            .removePrefix("Question:").removePrefix("question:").removePrefix("Q:").trim()
            .trim('"', '\'', '“', '”', '‘', '’', '*', '`').trim()
        // 第一行如果只是「Here is a follow-up question:」之类的引导语，取下一句带问号的行。
        if (!s.contains("?")) {
            val next = raw.lineSequence().map { it.trim() }.firstOrNull { it.contains("?") }
            if (next != null) s = next.trim('"', '\'', '“', '”', '*', '`').trim()
        }
        return s
    }

    /**
     * 错题变式题：同一考点换新内容。
     *
     * 直接重做原题容易变成「记住答案」；换一道考同样能力的新题，才看得出是不是真的会了。
     * 只生成、不落库，用户对着卡片自测即可。
     */
    suspend fun variant(
        context: Context,
        model: String,
        prompt: String,
        correct: String,
        yours: String,
        explain: String,
    ): BankVariant {
        val user = buildString {
            append("Original question: ").append(prompt.trim())
            append("\nCorrect answer: ").append(correct.trim())
            if (yours.isNotBlank()) append("\nStudent's answer: ").append(yours.trim())
            if (explain.isNotBlank()) append("\nExplanation: ").append(explain.trim())
        }
        val raw = LocalLlmEngine.generateText(
            context, model, VARIANT_SYSTEM, PromptBudget.clipText(user, PromptBudget.STUDY_INPUT_TOKENS),
        )
        // 解析成功也保留原始输出：调用方据此缓存，重开结果页不必再跑一次。
        return parseVariant(raw).let { if (it.isEmpty) it else it.copy(raw = raw) }
    }

    fun parseVariant(raw: String): BankVariant {
        val body = jsonBody(raw) ?: return BankVariant("", emptyList(), "", "", raw.trim())
        val parsed = runCatching {
            val o = JSONObject(body)
            // 模型常把选项写成「A. xxx」：去掉标号，界面自己按 A/B/C 排。
            val opts = cleanOptions(
                o.optJSONArray("options")?.let { a -> (0 until a.length()).map { a.optString(it).trim() } }.orEmpty()
            )
            BankVariant(
                question = o.optString("question").trim(),
                options = opts,
                answer = normalizeVariantAnswer(o.optString("answer").trim(), opts),
                explain = o.optString("explain").trim(),
            )
        }.getOrNull()
        return if (parsed == null || parsed.isEmpty) {
            BankVariant("", emptyList(), "", "", raw.trim())
        } else parsed
    }

    /** 去掉选项前面的「A. 」「B、」等标号：标号由界面统一生成，模型自己加会和界面重复。 */
    private val OPTION_PREFIX = Regex("^[A-Ea-e][.、)]\\s*")

    fun cleanOptions(options: List<String>): List<String> =
        options.map { it.replace(OPTION_PREFIX, "").trim() }.filter { it.isNotBlank() }.take(5)

    /**
     * 把模型给的答案折回某个选项原文。
     *
     * 提示词要求「answer 必须等于选项原文」，但实测常写成 `B`、`(B)`、`B. Save money`。
     * 不折回就会在界面上出现「答案：B」而选项里没有 B 这种对不上的情况。
     */
    fun normalizeVariantAnswer(answer: String, options: List<String>): String {
        val a = answer.trim()
        if (a.isBlank() || options.isEmpty()) return a
        options.firstOrNull { it.equals(a, ignoreCase = true) }?.let { return it }
        val letter = a.trim('(', ')', '.', ' ', '\t', '、', '）', '（')
        if (letter.length == 1) {
            val idx = letter[0].uppercaseChar() - 'A'
            if (idx in options.indices) return options[idx]
        }
        // 「B. Save money」这类：用包含关系兜底，取最长匹配，避免 B 选项是 A 选项子串时误判。
        return options.filter { a.contains(it, ignoreCase = true) || it.contains(a, ignoreCase = true) }
            .maxByOrNull { it.length } ?: a
    }

    fun parseOutline(raw: String): StudyOutline {
        val body = jsonBody(raw) ?: return StudyOutline("", emptyList(), emptyList(), raw.trim())
        val parsed = runCatching {
            val o = JSONObject(body)
            val arguments = o.optJSONArray("arguments")?.let { a ->
                (0 until a.length()).mapNotNull { i ->
                    runCatching {
                        val x = a.getJSONObject(i)
                        OutlineArgument(
                            point = x.optString("point").trim(),
                            explain = x.optString("explain").trim(),
                            example = x.optString("example").trim(),
                        )
                    }.getOrNull()
                }
            }.orEmpty().filter { it.point.isNotBlank() || it.explain.isNotBlank() }
            val phrases = o.optJSONArray("phrases")?.let { a ->
                (0 until a.length()).map { a.optString(it).trim() }
            }.orEmpty().filter { it.isNotBlank() }.take(8)
            StudyOutline(o.optString("stance").trim(), arguments, phrases)
        }.getOrNull()
        return if (parsed == null || parsed.isEmpty) {
            StudyOutline("", emptyList(), emptyList(), raw.trim())
        } else parsed
    }

    fun parseSample(raw: String): SpeakingSample {
        val body = jsonBody(raw) ?: return SpeakingSample(raw.trim(), emptyList(), emptyList())
        val parsed = runCatching {
            val o = JSONObject(body)
            val answer = o.optString("answer").trim()
            val keywords = o.optJSONArray("keywords")?.let { a ->
                (0 until a.length()).map { a.optString(it).trim() }
            }.orEmpty().filter { it.isNotBlank() }.take(8)
            val structure = o.optJSONArray("structure")?.let { a ->
                (0 until a.length()).map { a.optString(it).trim() }
            }.orEmpty().filter { it.isNotBlank() }.take(5)
            SpeakingSample(answer, keywords, structure)
        }.getOrNull()
        return if (parsed == null || parsed.isEmpty) SpeakingSample(raw.trim(), emptyList(), emptyList()) else parsed
    }

    fun parseVocab(raw: String): VocabResult {
        val body = jsonBody(raw) ?: return VocabResult(emptyList(), raw.trim())
        val items = runCatching {
            val o = JSONObject(body)
            val a = o.optJSONArray("items") ?: return@runCatching emptyList<VocabItem>()
            (0 until a.length()).mapNotNull { i ->
                runCatching {
                    val x = a.getJSONObject(i)
                    VocabItem(
                        phrase = x.optString("phrase").trim(),
                        meaning = x.optString("meaning").trim(),
                        example = x.optString("example").trim(),
                    )
                }.getOrNull()
            }.filter { it.phrase.isNotBlank() }.distinctBy { it.phrase.lowercase() }.take(8)
        }.getOrDefault(emptyList())
        return if (items.isEmpty()) VocabResult(emptyList(), raw.trim()) else VocabResult(items)
    }

    fun parseReview(raw: String): AiReview {
        val body = jsonBody(raw) ?: return AiReview("", emptyList(), "", raw.trim())
        val parsed = runCatching {
            val o = JSONObject(body)
            val tasks = o.optJSONArray("tasks")?.let { a ->
                (0 until a.length()).mapNotNull { i ->
                    runCatching {
                        val x = a.getJSONObject(i)
                        ReviewTask(
                            title = x.optString("title").trim(),
                            why = x.optString("why").trim(),
                            how = x.optString("how").trim(),
                        )
                    }.getOrNull()
                }
            }.orEmpty().filter { it.title.isNotBlank() }.take(5)
            AiReview(
                summary = o.optString("summary").trim(),
                tasks = tasks,
                encouragement = o.optString("encouragement").trim(),
            )
        }.getOrNull()
        return if (parsed == null || parsed.isEmpty) {
            AiReview("", emptyList(), "", raw.trim())
        } else parsed
    }

    // ---- 提示词：都要求 JSON 在最前、字符串内禁止双引号（4B 常写坏 JSON） ----

    private val TASK2_OUTLINE_SYSTEM = """
        You are an IELTS Writing Task 2 tutor. Build a concise essay plan for the task.
        Return STRICT JSON only, with exactly this shape:
        {"stance":"one-sentence thesis","arguments":[{"point":"topic sentence","explain":"1-2 sentence explanation","example":"a specific example with a name, place or number"}],"phrases":["band-7 phrase"]}
        Rules:
        - exactly 3 arguments; each must support the stance.
        - phrases: exactly 5 natural band-7 collocations usable in this essay.
        - never use a double quote inside a string value; use single quotes instead.
        - no markdown, no commentary, JSON only.
    """.trimIndent()

    private val TASK1_OUTLINE_SYSTEM = """
        You are an IELTS Writing Task 1 tutor. Build a concise report plan.
        Return STRICT JSON only, with exactly this shape:
        {"stance":"one-sentence overview without figures","arguments":[{"point":"key feature or trend","explain":"comparison between categories","example":"specific figures from the data"}],"phrases":["band-7 phrase"]}
        Rules:
        - exactly 3 key features; the overview must summarise the main trend without numbers.
        - phrases: exactly 5 data-report phrases (surge, plateau, marginally, respectively...).
        - never use a double quote inside a string value; use single quotes instead.
        - no markdown, no commentary, JSON only.
    """.trimIndent()

    private val SPEAKING_SYSTEM = """
        You are an IELTS Speaking examiner. Write ONE band-7 sample answer to the question.
        Return STRICT JSON only, with exactly this shape:
        {"answer":"sample answer","keywords":["useful phrase"],"structure":["中文结构提示"]}
        Rules:
        - Part 2 MUST be 180-220 words (about 12-15 sentences); an answer under 180 words is invalid, count the words before you finish. Part 1 and Part 3: 70-110 words; never exceed 120 and keep it to one short paragraph.
        - natural spoken English; contractions are fine; answer the question directly.
        - keywords: exactly 5 useful English collocations or phrases taken from the answer.
        - structure: exactly 3 short hints in Chinese.
        - never use a double quote inside a string value; use single quotes instead.
        - no markdown, no commentary, JSON only.
    """.trimIndent()

    private val FOLLOW_UP_SYSTEM = """
        You are an IELTS Speaking Part 3 examiner. Ask ONE natural follow-up question based on the candidate's answer.
        Output only the question sentence, no quotes, no explanation.
        Rules:
        - the question must be abstract or opinion-based, like a real Part 3 question, not a repeat of the Part 2 cue card.
        - reference a detail the candidate actually said, so it feels like a real examiner listening.
        - one sentence, at most 25 words, ending with a question mark.
        - return plain text only, never JSON, never key-value wrapping.
    """.trimIndent()

    private val SKELETON_SYSTEM = """
        You are an IELTS writing coach building a fill-in-the-blank essay skeleton for one prompt.
        Rules:
        - Use the given paragraph structure only. Task 2: Introduction, Body 1, Body 2, Conclusion.
          Task 1: Introduction, Overview, Detail 1, Detail 2.
        - Each paragraph has 2-4 lines built from the provided sentence frames; adapt the frames to the topic.
        - Replace all content words with Chinese slot markers in 【】 (for example 【你支持的理由】,
          【具体例子】, 【数据趋势】). Keep English connectors and grammar words.
        - Do NOT write a finished essay and do NOT fill the slots with content; the student must think.
        - hint: one short Chinese sentence telling the student what to put in this paragraph.
        - Every line MUST contain at least one 【】 slot. Example of an acceptable line:
          "It is often argued that 【观点 A】, while others maintain that 【观点 B】."
          If you write a complete sentence with no 【】, it is wrong.
        - Return JSON only: {"paragraphs":[{"label":"Introduction","lines":["line 1","line 2"],"hint":"中文提示"}]}
    """.trimIndent()

    /** 给骨架用的句式清单：只喂结构，避免 4B 自由发挥。 */
    private val TASK2_FRAMES = """
        - It is often argued that ..., while others maintain that ...
        - While I accept that ..., I would argue that ...
        - First and foremost, ...
        - The primary reason for this is that ...
        - This is largely because ...
        - A case in point is ...
        - As a consequence, ...
        - Admittedly, ...; however, ...
        - In conclusion, ..., and ...
    """.trimIndent()

    private val TASK1_FRAMES = """
        - The ... illustrates ... over the period from ... to ...
        - Overall, ..., while ...
        - There was a marked increase in ..., rising from ... to ...
        - ... declined steadily, from ... to ...
        - ... accounted for ... of the total, compared with ... at ...
        - While ... rose sharply, ... remained almost unchanged.
    """.trimIndent()

    private val TEMPLATE_FILL_SYSTEM = """
        You are an IELTS tutor helping a student fill a sentence template for their essay or speaking answer.
        Input: the exam topic, a template with 【slots】, and a Chinese hint about when to use it.
        Rules:
        - Keep the template's structure and connectors; replace only the 【slots】 with content that fits THIS topic.
        - Output 1-3 natural sentences at band 6.5-7.5 level: concrete, specific, no vague filler.
        - Never invent statistics that look authoritative; if a number is needed, use a clearly generic phrase
          such as "around two thirds of respondents" or keep the slot qualitative.
        - Do not add new arguments that the template does not ask for.
        - Return JSON only: {"filled": "<final English sentences>", "why": "<one short Chinese sentence on how to use it, max 60 chars>"}
    """.trimIndent()

    private val WRITING_TOPIC_SYSTEM = """
        You are an IELTS Writing task writer. Given an original prompt, write ONE new prompt that tests
        the same task type and skill but on a different topic.
        Rules:
        - Task 2: a discussion/opinion essay prompt in the same style (e.g. Some people believe ... Others think ... Discuss both views and give your own opinion), 25-45 words.
        - Task 1: one sentence describing a chart/table/map/process to summarise; do not invent specific figures.
        - same difficulty as the original; do not reuse its wording or topic.
        - return plain text only: one paragraph, never JSON, never key-value wrapping, no quotes, no numbering, no commentary.
    """.trimIndent()

    private val VARIANT_SYSTEM = """
        You are an IELTS question writer. The student answered a question incorrectly.
        Write ONE new practice question that tests the SAME skill but with different content.
        Return STRICT JSON only, with exactly this shape:
        {"question":"the new question","options":["option 1","option 2"],"answer":"the correct option text","explain":"中文解析：为什么是这个答案"}
        Rules:
        - same question type and difficulty as the original.
        - options: exactly 4 for four-option multiple choice; 3 for True/False/Not Given or Yes/No/Not Given; empty array for gap-fill / short answer.
        - answer MUST exactly equal one of the options (or, for gap-fill, the target word/phrase).
        - do not reuse the original wording or numbers; change the content, keep the tested skill.
        - explain in Chinese, one or two sentences.
        - never use a double quote inside a string value.
        - no markdown, no commentary, JSON only.
    """.trimIndent()

    private val VOCAB_SYSTEM = """
        You are an IELTS vocabulary tutor. From the student's essay and the sentence corrections, extract 5-8 useful collocations or phrases worth learning.
        Return STRICT JSON only, with exactly this shape:
        {"items":[{"phrase":"2-5 word collocation","meaning":"中文释义","example":"one natural English sentence"}]}
        Rules:
        - return 5 to 8 items; never fewer than 5.
        - each phrase must be 2-5 words at band 6.5-7.5 level, and either appears in the text or is a natural upgrade of it.
        - the meaning MUST explain that exact phrase. Do not borrow the meaning of a neighbouring phrase; if you cannot explain a phrase, skip it and choose another.
        - meaning in Chinese; example in English, different from the student's original sentence.
        - no duplicates; never use a double quote inside a string value.
        - no markdown, no commentary, JSON only.
    """.trimIndent()

    private val REVIEW_SYSTEM = """
        You are an IELTS coach. Review the student's recent practice summary and give a short, specific plan.
        Return STRICT JSON only, with exactly this shape:
        {"summary":"2-3 sentences in Chinese","tasks":[{"title":"short Chinese action","why":"why this matters, in Chinese","how":"concrete next step, in Chinese"}],"encouragement":"one sentence in Chinese"}
        Rules:
        - exactly 3 tasks, ordered by impact; prioritise the weakest skill and the most frequent error.
        - every task must be doable today (specific app section, exercise or time), not generic advice like 'practise more'.
        - refer to the student's actual numbers and weaknesses in the summary; do not invent data.
        - never use a double quote inside a string value; use single quotes instead.
        - no markdown, no commentary, JSON only.
    """.trimIndent()
}
