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
        return parseOutline(raw)
    }

    suspend fun speakingSample(
        context: Context,
        model: String,
        part: String,
        question: String,
    ): SpeakingSample {
        val user = "Part: $part\nQuestion: ${question.trim()}"
        val first = parseSample(LocalLlmEngine.generateText(context, model, SPEAKING_SYSTEM, user))
        // 本地 4B 的 Part 2 实测只写 146 词（目标 180-220）；给一次更明确的重写机会，
        // 只有确实更长才采用，重试失败也不丢第一版。
        if (part == "Part 2" && wordCount(first.answer) < 170) {
            val retry = try {
                parseSample(
                    LocalLlmEngine.generateText(
                        context, model, SPEAKING_SYSTEM,
                        "$user\n\nYour previous answer was too short. Rewrite it with at least 180 words " +
                            "(about 12-15 sentences). Do not stop early.",
                    )
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (retry != null && wordCount(retry.answer) > wordCount(first.answer)) return retry
        }
        return first
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
        return parseVocab(raw).copy(trimmedChars = prompt.removedChars)
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
