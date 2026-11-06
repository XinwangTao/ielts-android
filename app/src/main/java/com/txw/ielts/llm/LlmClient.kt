package com.txw.ielts.llm

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class WritingEval(
    val tr: Double, val cc: Double, val lr: Double, val gra: Double, val overall: Double,
    val fixes: List<String>, val polished: String, val rawJson: String,
    /** 逐句改法（写作与口语共用结构）：原句 → 问题 → 改写。 */
    val sentenceFixes: List<SentenceFix> = emptyList(),
)

data class SentenceFix(
    val sentence: String,   // 原句
    val issue: String,      // 问题（语法/用词/表达）
    val better: String,     // 改写
)

data class SpeakingEval(
    val fluency: Double, val lexical: Double, val grammar: Double, val pronunciation: Double,
    val overall: Double, val fixes: List<String>, val improved: String, val rawJson: String,
    val sentenceFixes: List<SentenceFix> = emptyList(),
    /** P 模型没给、由 FC/LR/GRA 均值兜底：单独标记，不能混进 fixes 污染弱点统计。 */
    val estimatedP: Boolean = false,
)

/** AI 生成的一道阅读题，字段与 BankQuestion 对齐。 */
data class GeneratedQuestion(
    val type: String,
    val prompt: String,
    val options: List<String>,
    val answers: List<String>,
    val explain: String,
    val explainEn: String,
    val keys: List<String>,
)

/** AI 生成的一套阅读题。 */
data class GeneratedReading(
    val title: String,
    val passage: String,
    val questions: List<GeneratedQuestion>,
)

/** AI 生成的一套听力题：对话/独白稿 + 题目（音频由端侧 TTS 合成）。 */
data class GeneratedListening(
    val title: String,
    val transcript: String,
    val questions: List<GeneratedQuestion>,
)

/** AI 对一道错题的中英双语精讲。 */
data class BankExplain(
    val cn: String,
    val en: String,
    val keys: List<String>,
)

/**
 * OpenAI 兼容的 /chat/completions（默认智谱 open.bigmodel.cn/api/paas/v4）。
 * 三个关键约束（实测）：
 * - thinking token 计入 max_tokens，且耗时差 2–3 倍 → 显式关闭 thinking；
 * - 长评论文本会让模型在 JSON 字符串里带裸换行 → 解析前必须清洗控制字符；
 * - 服务端偶发长时间无响应（曾见 >200 s）→ 请求必须带超时与有限重试，
 *   且重试不能再扣每日额度（额度按「一次逻辑请求」计）。
 */
class LlmClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    /** 每次真正发请求前调用：用来做每日调用额度控制，超限直接抛异常。仅首轮调用，重试不重复扣。 */
    private val onBeforeCall: () -> Unit = {},
    /** 第 n 次重试前回调：用于把「正在重试」反馈到界面。 */
    private val onRetry: (attempt: Int, maxAttempts: Int, reason: String) -> Unit = { _, _, _ -> },
) {

    private class HttpError(val code: Int, message: String) : IOException(message)

    /**
     * 把云端错误翻译成用户能照着做的中文。原始返回体照旧附在后面便于排查。
     * 讯飞实测：账号没有有效订单/额度用尽时返回 403 + code 11200，只抛 "HTTP 403: {...}" 用户看不懂。
     */
    private fun friendlyHttpError(code: Int, body: String): String {
        val raw = body.take(200)
        val quota = body.contains("11200") || body.contains("insufficient quota") ||
            body.contains("lacks an active order")
        return when {
            quota -> "云端额度不可用（HTTP $code / 11200：无有效订单或额度已用尽）。" +
                "可到设置页把评分通道改为「仅本地」继续，或续费后重试。原始返回：$raw"
            code == 401 -> "API key 无效或已过期（HTTP 401）。请到设置页更新 key。原始返回：$raw"
            code == 403 -> "云端拒绝访问（HTTP 403）。请检查账号额度与模型权限。原始返回：$raw"
            code == 429 -> "云端限流（HTTP 429）。请稍后重试，或改用本地模型。原始返回：$raw"
            else -> "HTTP $code: $raw"
        }
    }

    fun evaluateWriting(
        topic: String,
        essay: String,
        weaknesses: List<String> = emptyList(),
    ): WritingEval {
        val words = essay.split(Regex("\\s+")).count { it.isNotBlank() }
        // 超长粘贴不能整段发出去：留开头与结尾（结论段），中部省略。
        val body = PromptBudget.clipText(essay, PromptBudget.SCORE_INPUT_TOKENS)
        val content = chat(
            WRITING_SYSTEM,
            buildString {
                append("Task: $topic\nWord count: $words\n\nEssay:\n$body")
                append(weaknessHint(weaknesses))
            },
        )
        val json = extractJson(content)
        val o = JSONObject(json)
        return WritingEval(
            tr = num(o, "TR", "task_response"),
            cc = num(o, "CC", "coherence_cohesion"),
            lr = num(o, "LR", "lexical_resource"),
            gra = num(o, "GRA", "grammatical_range_accuracy"),
            overall = num(o, "overall", "band"),
            fixes = strings(o, "top_fixes", "fixes"),
            polished = o.optString("polished", o.optString("rewrite", "")),
            rawJson = json,
            sentenceFixes = ScoreSanitizer.parseSentenceFixes(o),
        )
    }

    /** 写作 Task 1（图表描述）：TA 任务完成度 + 数据覆盖 + 比较。 */
    fun evaluateWritingTask1(chart: String, essay: String): WritingEval {
        val words = essay.split(Regex("\\s+")).count { it.isNotBlank() }
        val content = chat(
            TASK1_SYSTEM,
            "Chart data:\n${PromptBudget.clipText(chart, PromptBudget.SCORE_INPUT_TOKENS)}" +
                "\n\nWord count: $words\n\nReport:\n" +
                PromptBudget.clipText(essay, PromptBudget.SCORE_INPUT_TOKENS),
        )
        val json = extractJson(content)
        val o = JSONObject(json)
        return WritingEval(
            tr = num(o, "TA", "task_achievement", "task_response"),
            cc = num(o, "CC", "coherence_cohesion"),
            lr = num(o, "LR", "lexical_resource"),
            gra = num(o, "GRA", "grammatical_range_accuracy"),
            overall = num(o, "overall", "band"),
            fixes = strings(o, "top_fixes", "fixes"),
            polished = o.optString("polished", o.optString("rewrite", "")),
            rawJson = json,
            sentenceFixes = ScoreSanitizer.parseSentenceFixes(o),
        )
    }

    fun evaluateSpeaking(
        part: String,
        question: String,
        transcript: String,
        seconds: Int,
        weaknesses: List<String> = emptyList(),
    ): SpeakingEval {
        val words = transcript.split(Regex("\\s+")).count { it.isNotBlank() }
        val wpm = if (seconds > 0) (words * 60.0 / seconds).toInt() else 0
        // ASR 复读时转写可能几万字，整段发出去只会超长/超时：同样留两头。
        val transcriptBody = PromptBudget.clipText(transcript, PromptBudget.SCORE_INPUT_TOKENS)
        val content = chat(
            SPEAKING_SYSTEM,
            buildString {
                append("Part: $part\nQuestion: $question\nSpeaking time: ${seconds}s\n")
                append("Word count: $words\nWords per minute: $wpm\n\nTranscript (from offline ASR, punctuation unreliable):\n$transcript")
                append(weaknessHint(weaknesses))
            },
        )
        val json = extractJson(content)
        val o = JSONObject(json)
        return SpeakingEval(
            fluency = num(o, "FC", "fluency", "fluency_coherence"),
            lexical = num(o, "LR", "lexical_resource"),
            grammar = num(o, "GRA", "grammatical_range_accuracy"),
            pronunciation = num(o, "P", "pron"),
            overall = num(o, "overall", "band"),
            fixes = strings(o, "top_fixes", "fixes"),
            improved = o.optString("improved", o.optString("model_answer", "")),
            rawJson = json,
            sentenceFixes = ScoreSanitizer.parseSentenceFixes(o),
        )
    }

    /** 把学员历史弱点注入 prompt：让模型重点检查老毛病是否复发（个性化反馈闭环）。 */
    private fun weaknessHint(weaknesses: List<String>): String =
        if (weaknesses.isEmpty()) "" else
            "\n\nThis student's recurring weaknesses recorded from past attempts: ${weaknesses.joinToString("; ")}. " +
                "Check specifically whether these problems appear in this attempt. If they do, list them FIRST in top_fixes " +
                "and state clearly whether the student is improving on them."

    /** 生成一套原创阅读题：文章 + 题目 + 中英双语解析 + 关键短语。 */
    fun generateReading(difficulty: String, types: List<String>, count: Int): GeneratedReading =
        GeneratedParser.reading(
            chat(
                READING_GEN_SYSTEM,
                "Difficulty: $difficulty\nQuestion types: ${types.joinToString(", ")}\nQuestion count: $count",
            ),
            count,
        )

    /** 生成一套原创听力题：对话/独白稿 + 题目 + 双语解析（音频由端侧 TTS 合成）。 */
    fun generateListening(difficulty: String, types: List<String>, count: Int): GeneratedListening =
        GeneratedParser.listening(
            chat(
                LISTENING_GEN_SYSTEM,
                "Difficulty: $difficulty\nQuestion types: ${types.joinToString(", ")}\nQuestion count: $count",
            ),
            count,
        )

    /** 对一道错题生成中英双语精讲与关键点。 */
    fun explainBankWrong(prompt: String, right: String, yours: String, hint: String): BankExplain =
        GeneratedParser.bankExplain(
            chat(
                BANK_EXPLAIN_SYSTEM,
                "Question: $prompt\nCorrect answer: $right\nYour answer: $yours\nBrief hint: $hint",
            )
        )

    private fun chat(system: String, user: String): String {
        onBeforeCall()
        val payload = JSONObject().apply {
            put("model", model)
            put("max_tokens", 4000)
            put("temperature", 0.2)
            put("thinking", JSONObject().put("type", "disabled"))
            put("messages", JSONArray().apply {
                put(JSONObject().apply { put("role", "system"); put("content", system) })
                put(JSONObject().apply { put("role", "user"); put("content", user) })
            })
        }
        val url = "$baseUrl/chat/completions"
        val body = try {
            postWithRetry(url, payload.toString())
        } catch (e: HttpError) {
            // 智谱 2026-10 更新：部分模型始终思考，不接受 thinking disabled（code 1210）。
            // 自动降级为 low 档思考重试，并加大 max_tokens 给思考 token 留空间。
            if (e.code == 400 && e.message?.contains("1210") == true) {
                payload.put("thinking", JSONObject().put("type", "enabled").put("level", "low"))
                payload.put("max_tokens", 6000)
                postWithRetry(url, payload.toString())
            } else {
                throw e
            }
        }
        return JSONObject(body).getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getString("content")
    }

    /**
     * 带超时预算的重试：连接/读超时、5xx、429、408 视为可重试；
     * 4xx（除 429/408）与配置类错误直接抛出，重试没有意义。
     * 总耗时超过 [TOTAL_BUDGET_MS] 后不再发起新一轮，避免无限拖长。
     */
    private fun postWithRetry(url: String, body: String): String {
        val started = System.currentTimeMillis()
        var attempt = 0
        var last: IOException? = null
        while (true) {
            if (attempt > 0) {
                val wait = RETRY_DELAY_MS * attempt
                val reason = last?.message?.take(60) ?: "未知错误"
                onRetry(attempt, MAX_RETRIES, reason)
                runCatching { Thread.sleep(wait) }
                if (System.currentTimeMillis() - started > TOTAL_BUDGET_MS) {
                    throw IOException("请求超时（已重试 ${attempt - 1} 次）：$reason", last)
                }
            }
            try {
                return post(url, body)
            } catch (e: HttpError) {
                if (!retryable(e.code)) throw e
                last = e
            } catch (e: IOException) {
                if (apiKey.isBlank()) throw e
                last = e
            }
            attempt++
            if (attempt > MAX_RETRIES) {
                throw IOException("重试 $MAX_RETRIES 次仍失败：${last?.message}", last)
            }
            if (System.currentTimeMillis() - started > TOTAL_BUDGET_MS) {
                throw IOException("请求超时（已重试 ${attempt - 1} 次）：${last?.message}", last)
            }
        }
    }

    private fun retryable(code: Int): Boolean = code == 408 || code == 429 || code >= 500

    private fun post(url: String, body: String): String {
        if (apiKey.isBlank()) throw IOException("未配置 API key，请到设置页填写")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw HttpError(code, friendlyHttpError(code, text))
            return text
        } finally {
            conn.disconnect()
        }
    }

    private fun num(o: JSONObject, vararg keys: String): Double {
        for (k in keys) if (o.has(k)) return o.optDouble(k, 0.0)
        return 0.0
    }

    /** top_fixes/fixes 的清洗与本地评分共用一套，避免云端路径出现 "null"/"3.0" 这类脏条目。 */
    private fun strings(o: JSONObject, vararg keys: String): List<String> = ScoreSanitizer.strings(o, *keys)

    /** 截取第一个完整 JSON 对象，并把字符串值里的裸控制字符替换成空格。 */
    private fun extractJson(content: String): String {
        val cleaned = content.replace("```json", "").replace("```", "").trim()
        val start = cleaned.indexOf('{')
        if (start < 0) throw IOException("模型没有返回 JSON：${cleaned.take(120)}")
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
        if (end < 0) throw IOException("JSON 不完整：${cleaned.take(120)}")
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

    companion object {
        /** 首次请求 + 2 次重试。成功请求实测 17–20 s，60 s 读超时留 3 倍余量。 */
        private const val MAX_RETRIES = 2
        private const val RETRY_DELAY_MS = 2_000L
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 60_000
        /** 一次逻辑请求的总耗时上限：超了就不再开新的一轮。 */
        private const val TOTAL_BUDGET_MS = 190_000L

        private val WRITING_SYSTEM = """
            You are a strict IELTS Writing Task 2 examiner. Score only against these band descriptors.
            Task Response: B4 barely addresses the task; B5 partial coverage, ideas limited; B6 all parts but underdeveloped; B7 clear position, extended ideas; B8 fully developed, well-supported.
            Coherence & Cohesion: B4 no clear progression; B5 unfocused progression; B6 logical but faulty devices; B7 clear paragraphing with effective use of a range of cohesive devices; B8 cohesion is seamless and never draws attention.
            Lexical Resource: B4 limited range, frequent errors; B5 limited range, word-choice errors; B6 adequate, inaccuracies in less common words; B7 sufficient range, collocation awareness; B8 wide range, precise, natural.
            Grammatical Range & Accuracy: B4 limited structures, frequent errors; B5 limited structures, frequent errors; B6 mix, errors rarely impede; B7 complex variety, many error-free sentences; B8 wide range, almost all error-free.
            Calibration anchors (do not score above a band without matching evidence):
            - Band 4: barely responds to the task, frequent grammar errors obscure meaning, very limited vocabulary.
            - Band 5: 2-3 thin paragraphs, frequent grammar errors, limited/simple vocabulary, one-sided or underdeveloped argument.
            - Band 6: addresses all parts but ideas underdeveloped, some errors that occasionally obscure meaning, adequate but plain vocabulary.
            - Band 7: clear position throughout, extended and supported ideas, a range of complex structures mostly accurate, precise vocabulary.
            - Band 8: fully developed position, ideas logically sequenced with skilful cohesion, wide vocabulary used precisely, almost all sentences error-free.
            Band 8 exclusion tests (a B8 essay must pass ALL of them; failing one caps that criterion as stated):
            frequent formulaic linkers (On the one hand/Moreover/In conclusion/Firstly) cap CC at 7.0;
            only common everyday collocations cap LR at 7.0; generic or hypothetical examples cap TR at 7.0;
            any sentence listed in sentence_fixes caps GRA at 7.5.
            Hard rules: score in 0.5 steps; overall = average rounded to nearest 0.5; under 250 words caps TR at 5.0,
            under 150 words caps TR at 4.0; copied or off-topic content scores 0 for TR.
            Find the errors FIRST: list every sentence with a clear grammar or word-choice error in sentence_fixes
            (up to 5, do not hide errors to justify a high score). GRA must match those errors: 4+ erroneous sentences
            means GRA cannot exceed 6.5; 2-3 means GRA cannot exceed 7.0; 7.5-8.0 only if the essay is clean with at most
            one minor slip. Most real candidates score 5.5-6.0; Band 7 is a strong score (top ~10%); Band 8 is near-native
            and rare. Do not inflate. Reference: an essay of simple repetitive sentences with mechanical linkers
            (First/But/However) and undeveloped ideas scores 6.0.
            Return STRICT JSON only, single-line strings, keys in exactly this order:
            {"sentence_fixes":[{"sentence":"exact original","issue":"problem in Chinese","better":"corrected sentence"}],"GRA":x,"LR":x,"TR":x,"CC":x,"overall":x,"top_fixes":["..."],"polished":"..."}
            sentence_fixes: 2 to 5 items and NEVER empty - even when the work is strong, still pick the 2 weakest sentences and give a clearly better rewrite; the issue field MUST be written in Chinese.
            top_fixes: exactly 3 items and never empty; write each in Chinese; wrap any English phrase in single quotes ('...') and NEVER put a double quote inside a string value, it breaks the JSON; then say how to fix it.
            polished = the same ideas rewritten at band 7.
        """.trimIndent()

        private val TASK1_SYSTEM = """
            You are a strict IELTS Writing Task 1 (Academic) examiner. Score a report that describes visual data.
            Task Achievement (TA): B5 recounts data mechanically with no clear overview; B6 presents an overview but details may be irrelevant or inaccurate; B7 clear overview, main trends/features covered and compared, data accurately selected.
            Coherence & Cohesion: B5 unfocused progression; B6 logical but faulty devices; B7 clear paragraphing, range of devices.
            Lexical Resource: B5 limited range; B6 adequate but inaccuracies in less common words; B7 sufficient range, precise.
            Grammatical Range & Accuracy: B5 limited structures, frequent errors; B6 mix, errors rarely impede; B7 complex variety, many error-free sentences.
            Band 8 exclusion tests (a B8 report must pass ALL; failing one caps that criterion as stated):
            frequent formulaic linkers (Firstly/Moreover/In conclusion) cap CC at 7.0; only common everyday collocations
            cap LR at 7.0; incomplete data selection or missing comparisons cap TA at 7.0; any sentence listed in
            sentence_fixes caps GRA at 7.5.
            Hard rules: score in 0.5 steps; overall = average rounded to nearest 0.5; under 150 words caps TA at 5.0,
            under 100 words caps TA at 4.0; if the report gives opinions or argues instead of describing/comparing the data, cap TA at 5.0.
            Find the errors FIRST: list every sentence with a clear grammar or word-choice error in sentence_fixes
            (up to 5, do not hide errors to justify a high score). GRA must match those errors: 4+ erroneous sentences
            means GRA cannot exceed 6.5; 2-3 means GRA cannot exceed 7.0; 7.5-8.0 only if the report is clean with at most
            one minor slip. Most real candidates score 5.5-6.0; Band 7 is strong; Band 8 is near-native and rare. Do not inflate.
            Return STRICT JSON only, single-line strings, keys in exactly this order:
            {"sentence_fixes":[{"sentence":"exact original","issue":"problem in Chinese","better":"corrected sentence"}],"GRA":x,"LR":x,"TA":x,"CC":x,"overall":x,"top_fixes":["..."],"polished":"..."}
            sentence_fixes: 2 to 5 items and NEVER empty - even when the work is strong, still pick the 2 weakest sentences and give a clearly better rewrite; the issue field MUST be written in Chinese.
            top_fixes: exactly 3 items and never empty; write each in Chinese; wrap any English phrase in single quotes ('...') and NEVER put a double quote inside a string value, it breaks the JSON; then say how to fix it.
            polished = the same report rewritten at band 7.
        """.trimIndent()

        private val SPEAKING_SYSTEM = """
            You are a strict IELTS Speaking examiner scoring a transcript of a spoken answer.
            Fluency & Coherence: B5 hesitant, repetitive; B6 willing to speak at length, some coherence loss; B7 speaks at length without noticeable effort.
            Lexical Resource: B5 limited, frequent paraphrase; B6 adequate range; B7 flexible, idiomatic.
            Grammatical Range & Accuracy: B5 basic structures with errors; B6 mix of simple/complex, some errors; B7 flexible, frequent error-free sentences.
            Pronunciation: you only see a transcript, so score it as an ESTIMATE from word-level evidence (self-corrections,
            mangled words the ASR rendered as odd tokens, pacing implied by word count vs speaking time). State that it is an estimate.
            Hard rules: score in 0.5 steps; overall = average rounded; if the answer is under 20 words or
            off-topic, cap the overall at 4.0. Find the errors FIRST: list every answer segment with a clear grammar
            or word-choice error in sentence_fixes (up to 5, do not hide errors to justify a high score). GRA must match
            those errors: 4+ erroneous sentences means GRA cannot exceed 6.5; 2-3 means GRA cannot exceed 7.0; 7.5-8.0
            only if the transcript is clean with at most one minor slip. Most real candidates score 5.5-6.0; Band 7 is
            strong; Band 8 is near-native and rare. Do not inflate.
            Output JSON with sentence_fixes FIRST: {"sentence_fixes":[{"sentence":"exact original sentence","issue":"grammar/vocabulary problem in Chinese","better":"corrected natural version"}],"GRA":x,"LR":x,"FC":x,"P":x,"overall":x,"top_fixes":["..."],"improved":"..."}
            All five score keys (FC, LR, GRA, P, overall) are mandatory. Never omit P, even when the transcript is short.
            sentence_fixes: 2 to 5 items and NEVER empty - even when the work is strong, still pick the 2 weakest sentences and give a clearly better rewrite; the issue field MUST be written in Chinese.
            top_fixes: exactly 3 items and never empty; write each in Chinese; wrap any English phrase in single quotes ('...') and NEVER put a double quote inside a string value, it breaks the JSON; then say how to fix it.
            improved = a band-7 spoken-style version of the same answer (natural, not written prose).
        """.trimIndent()

        /** 出题/精讲的提示词本地模型也要用：同一套 prompt 才能保证云端与离线出题口径一致。 */
        internal val READING_GEN_SYSTEM = """
            You are an IELTS Academic Reading test author. Generate ONE original, self-contained reading passage
            and questions at the requested difficulty. The passage must be 350-450 words, split into 4-5 paragraphs
            labelled A-E, on an academic topic (science, environment, work, health, cities, technology).
            Difficulty guide:
            - Band 6.0-6.5: everyday topics, common vocabulary, shorter sentences, answers easy to locate.
            - Band 7.0-7.5: academic topics, some complex structures, answers require paraphrase matching.
            - Band 8.0-9.0: sophisticated academic writing, abstract concepts, dense data, inference-level questions.
            Write questions of these types ONLY:
            - choice: a multiple-choice question with 4 options labelled A-D, exactly one correct answer.
            - tfng: a TRUE / FALSE / NOT GIVEN statement about the passage.
            - gap: a fill-in-the-blank where the answer is 1-2 words from the passage.
            Put each accepted answer in its own array element; never join alternatives with a slash inside one string.
            Generate EXACTLY the requested number of questions, no more, no fewer. The number in the user
            message is a hard requirement, not a suggestion.
            Return STRICT JSON only, single-line strings, with this exact shape:
            {"title":"...","passage":"...","questions":[
              {"type":"choice","prompt":"...","options":["...","...","...","..."],"answers":["correct option"],"explain":"中文解析，说明答案在原文哪一段、为什么对","explain_en":"English explanation of where and why","keys":["关键定位短语","key locating phrase"]},
              {"type":"tfng","prompt":"...","options":["TRUE","FALSE","NOT GIVEN"],"answers":["TRUE"],"explain":"...","explain_en":"...","keys":["..."]},
              {"type":"gap","prompt":"... fill in the blank ...","options":[],"answers":["answer","another acceptable answer"],"explain":"...","explain_en":"...","keys":["..."]}
            ]}
            Every question MUST include explain (Chinese), explain_en (English) and keys (2-4 short phrases,
            mixed Chinese/English, that locate the answer). Do not wrap the JSON in markdown fences.
            Every string value must be valid JSON on one line. Never put a raw double quote inside a string;
            use single quotes or escape it as \" .
        """.trimIndent()

        internal val LISTENING_GEN_SYSTEM = """
            You are an IELTS Listening test author. Generate ONE original listening script and questions
            at the requested difficulty. The script is a two-speaker dialogue or a single-speaker monologue
            in IELTS Section 1/2 style: 250-350 words, natural spoken English, information-dense
            (names, numbers, dates, times, prices, places). Separate speaker turns with a blank line.
            Difficulty guide: Band 6.0-6.5 = slow clear exchange, common words, answers stated verbatim;
            Band 7.0-7.5 = faster, some distractors and corrections before the real answer;
            Band 8.0-9.0 = dense monologue, paraphrase and inference needed.
            Question types ONLY: choice (4 options A-D, one correct), tfng, gap (1-2 words heard in the script).
            For gap questions, at least one accepted answer MUST appear verbatim in the transcript; you may also list
            equivalent forms (e.g., 35 / thirty-five) so the student is not penalised for a different notation.
            For dates, times, prices and numbers, copy the exact words used in the script: if the script says
            'the thirteenth of June', include that form in answers (optionally alongside '13 June').
            For numbers, include both the digit and spoken forms in answers (e.g., ['44','forty-four']).
            Put each accepted answer in its own array element (e.g., ['twenty','20']); never join them with a slash.
            Generate EXACTLY the requested number of questions.
            Return STRICT JSON only, single-line strings:
            {"title":"...","transcript":"...","questions":[
              {"type":"gap","prompt":"...","options":[],"answers":["..."],"explain":"中文解析","explain_en":"English explanation","keys":["..."]},
              {"type":"choice","prompt":"...","options":["...","...","...","..."],"answers":["..."],"explain":"...","explain_en":"...","keys":["..."]}
            ]}
            Every question MUST include explain (Chinese), explain_en (English) and keys (2-4 locating phrases).
            keys is mandatory for EVERY question, including gap and choice types: if any question lacks keys,
            the whole output is invalid and will be rejected. Write keys immediately after answers.
            Do not wrap the JSON in markdown fences.
            Every string value must be valid JSON on one line. Never put a raw double quote inside a string;
            use single quotes or escape it as \" .
        """.trimIndent()

        internal val BANK_EXPLAIN_SYSTEM = """
            You are an IELTS Reading/Listening tutor explaining why an answer is correct, in bilingual form.
            Return STRICT JSON only, single-line strings, with keys: cn (Chinese explanation), en (English explanation),
            keys (2-4 short locating phrases mixed Chinese/English). Be concrete: point to the exact passage/audio
            evidence that decides the answer, and why the student's wrong answer is tempting but incorrect.
        """.trimIndent()
    }
}
