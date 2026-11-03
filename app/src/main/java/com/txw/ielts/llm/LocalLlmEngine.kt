package com.txw.ielts.llm

import android.content.Context
import android.util.Log
import com.alibaba.mnnllm.android.llm.GenerateProgressListener
import com.alibaba.mnnllm.android.llm.LlmSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * 端侧离线评分引擎（MNN + 本地 LLM，支持多模型切换）。
 * 图：ielts-local-llm_workflow 节点「本地评分」。
 *
 * 模型分发沿用 ASR/ECDICT 的外部目录投递模式（每个模型一个子目录）：
 *   adb push <模型目录>/. /sdcard/Android/data/com.txw.ielts/files/models/mnn/<模型名>/
 * 首次评分前拷贝到内部 filesDir（/sdcard 是 FUSE，mmap 权重不可靠），之后直接复用。
 */
object LocalLlmEngine {

    private const val TAG = "LocalLlm"

    /** 默认模型（设置页可切换）。Instruct 版不输出思考过程，JSON 一次通过，实测最快。 */
    const val DEFAULT_MODEL = "qwen3-4b-instruct"

    /**
     * 出题时的「压缩输出」约束：4B 模型在 3500 token 预算里写不满 8 道题的双语解析，
     * 一旦超长就是被截断的半个 JSON，解析必然失败。宁可要短解析，也要整套题结构完整。
     */
    private const val COMPACT_HINT = "\n" +
        "Output compactness: keep the passage/script 250-300 words, each explain under 30 Chinese characters, " +
        "and each question prompt under 25 words. Keep the JSON structure complete."

    /** 单次生成硬上限：超过后通过 listener 中断。 */
    private const val GENERATE_TIMEOUT_MS = 8 * 60 * 1000L

    /** max_new_tokens：思考型模型需留足 JSON 输出空间；非思考模型用不满。 */
    private const val MAX_NEW_TOKENS = 3500

    private val mutex = Mutex()
    private val generationMutex = Mutex()
    private var session: LlmSession? = null
    private var loadedModel: String? = null

    @Volatile
    var state: String = "idle"
        private set

    fun modelsRoot(context: Context): File =
        File(context.getExternalFilesDir(null), "models/mnn")

    fun externalDir(context: Context, model: String): File = File(modelsRoot(context), model)

    fun internalDir(context: Context, model: String): File =
        File(context.filesDir, "models/mnn/$model")

    fun isReady(context: Context, model: String): Boolean =
        isComplete(internalDir(context, model))

    fun hasExternalCopy(context: Context, model: String): Boolean =
        File(externalDir(context, model), "config.json").isFile

    /** 目录完整才算就绪：只判断 config.json 会把拷贝中断的半成品当可用，之后永远加载失败。 */
    private fun isComplete(dir: File): Boolean =
        File(dir, "config.json").isFile &&
            File(dir, "llm.mnn").let { it.isFile && it.length() > 0 } &&
            File(dir, "llm.mnn.weight").let { it.isFile && it.length() > 0 }

    /** 已投递（或已拷贝）的模型列表。 */
    fun availableModels(context: Context): List<String> {
        val names = linkedSetOf<String>()
        modelsRoot(context).listFiles()?.forEach {
            if (it.isDirectory && File(it, "config.json").isFile) names.add(it.name)
        }
        File(context.filesDir, "models/mnn").listFiles()?.forEach {
            if (it.isDirectory && File(it, "config.json").isFile) names.add(it.name)
        }
        return names.sorted()
    }

    fun pushHint(context: Context, model: String): String =
        "缺少本地模型。先把模型推到设备：\n" +
            "adb push <模型目录>/. ${externalDir(context, model).absolutePath}/"

    /** 内部没有就从外部投递目录拷贝（先拷到临时目录，完整后再改名，避免半成品被当成可用）。 */
    suspend fun ensure(context: Context, model: String): File = withContext(Dispatchers.IO) {
        val target = internalDir(context, model)
        if (isComplete(target)) return@withContext target
        val src = externalDir(context, model)
        if (!File(src, "config.json").isFile) throw IOException(pushHint(context, model))
        state = "copying"
        try {
            val tmp = File(target.parentFile, "${target.name}.tmp")
            tmp.deleteRecursively()
            tmp.mkdirs()
            src.listFiles()?.forEach { f ->
                if (f.isFile) f.copyTo(File(tmp, f.name), overwrite = true)
            }
            if (!isComplete(tmp)) throw IOException("模型文件不完整：${src.absolutePath}")
            target.deleteRecursively()
            if (!tmp.renameTo(target)) throw IOException("模型目录改名失败：${target.absolutePath}")
        } finally {
            state = "idle"
        }
        target
    }

    private suspend fun loaded(context: Context, model: String): LlmSession = mutex.withLock {
        session?.takeIf { it.isLoaded && loadedModel == model }?.let { return@withLock it }
        session?.release()
        session = null
        val dir = ensure(context, model)
        state = "loading"
        Log.i(TAG, "loading local model $model from ${dir.absolutePath}")
        val rawConfig = File(dir, "llm_config.json").readText()
        val merged = JSONObject(rawConfig).apply {
            put("max_new_tokens", MAX_NEW_TOKENS)
            put("thread_num", 6)
            // 评分要稳定：同一篇作文两次跑出 7.5/8.0 的波动不可接受，温度从 0.6 降到 0.2。
            put("temperature", 0.2)
        }.toString()
        val s = LlmSession()
        val ok = s.load(File(dir, "config.json").absolutePath, merged)
        state = "idle"
        if (!ok) throw IOException("本地模型 $model 加载失败（native 返回 0），请检查模型文件完整性")
        session = s
        loadedModel = model
        s
    }

    /** 释放模型内存（设置页可调用；下次评分会重新加载）。有生成任务在跑时返回 false，不打断 native 推理。 */
    fun release(): Boolean {
        if (!generationMutex.tryLock()) return false
        return try {
            session?.release()
            session = null
            loadedModel = null
            state = "idle"
            true
        } finally {
            generationMutex.unlock()
        }
    }

    suspend fun scoreWriting(
        context: Context,
        model: String,
        topic: String,
        essay: String,
        weaknesses: List<String> = emptyList(),
        task: String = "task2",
    ): WritingEval = withContext(Dispatchers.IO) {
        val words = essay.split(Regex("\\s+")).count { it.isNotBlank() }
        val system = if (task == "task1") TASK1_SYSTEM else WRITING_SYSTEM
        val user = buildString {
            if (task == "task1") {
                append("Chart data:\n$topic\n\nWord count: $words\n\nReport:\n$essay")
            } else {
                append("Task: $topic\nWord count: $words\n\nEssay:\n$essay")
            }
            append(weaknessHint(weaknesses))
        }
        val raw = generate(context, model, system, user)
        ScoreSanitizer.toWritingEval(raw, words, task, essay)
    }

    suspend fun scoreSpeaking(
        context: Context,
        model: String,
        part: String,
        question: String,
        transcript: String,
        seconds: Int,
        weaknesses: List<String> = emptyList(),
    ): SpeakingEval = withContext(Dispatchers.IO) {
        val words = transcript.split(Regex("\\s+")).count { it.isNotBlank() }
        val wpm = if (seconds > 0) (words * 60.0 / seconds).toInt() else 0
        // 客观声学代理指标：转写文本里的填充词与语速。给 P/FC 评分一个文本之外的证据。
        val fillers = Regex("\\b(um+|uh+|er+m?|hmm+|ah+)\\b", RegexOption.IGNORE_CASE)
            .findAll(transcript).count()
        val user = buildString {
            append("Part: $part\nQuestion: $question\nSpeaking time: ${seconds}s\n")
            append("Word count: $words\nWords per minute: $wpm\nFiller words (um/uh/er): $fillers\n\n")
            append("Transcript (from offline ASR, punctuation unreliable):\n$transcript")
            append(weaknessHint(weaknesses))
        }
        val raw = generate(context, model, SPEAKING_SYSTEM, user)
        ScoreSanitizer.toSpeakingEval(raw, words)
    }

    /** 供口语对练等非评分场景使用的纯文本生成（复用串行锁与超时）。 */
    suspend fun generateText(
        context: Context, model: String, system: String, user: String,
    ): String = generate(context, model, system, user)

    /**
     * 错题精讲（本地）：提示词与云端同一份，只是慢一点、JSON 更容易缺字段。
     * 文案前缀标明「本地模型」，让用户知道这条解析没走云端、出现偏差时也知道该怀疑谁。
     */
    suspend fun explainBankWrong(
        context: Context, model: String,
        prompt: String, right: String, yours: String, hint: String,
    ): BankExplain = withContext(Dispatchers.IO) {
        val raw = generate(
            context, model, LlmClient.BANK_EXPLAIN_SYSTEM,
            "Question: $prompt\nCorrect answer: $right\nYour answer: $yours\nBrief hint: $hint",
        )
        val parsed = GeneratedParser.bankExplain(raw)
        parsed.copy(cn = "【本地模型】" + parsed.cn)
    }

    /** AI 出题（本地）：4B 模型出题质量不如云端，但断网/额度用尽时仍能出一套可做的题。 */
    suspend fun generateReading(
        context: Context, model: String, difficulty: String, types: List<String>, count: Int,
    ): GeneratedReading = withContext(Dispatchers.IO) {
        val raw = generate(
            context, model, LlmClient.READING_GEN_SYSTEM,
            "Difficulty: $difficulty\nQuestion types: ${types.joinToString(", ")}\nQuestion count: $count" + COMPACT_HINT,
        )
        GeneratedParser.reading(raw, count)
    }

    suspend fun generateListening(
        context: Context, model: String, difficulty: String, types: List<String>, count: Int,
    ): GeneratedListening = withContext(Dispatchers.IO) {
        val raw = generate(
            context, model, LlmClient.LISTENING_GEN_SYSTEM,
            "Difficulty: $difficulty\nQuestion types: ${types.joinToString(", ")}\nQuestion count: $count" + COMPACT_HINT,
        )
        GeneratedParser.listening(raw, count)
    }

    /** 仅预加载模型而不生成（诊断用：验证加载后的 RSS 与 release 是否真的释放）。 */
    suspend fun preload(context: Context, model: String) {
        loaded(context, model)
    }

    /** 阻塞生成一整段文本；native 回调是流式的，这里拼成完整输出。串行执行避免并发调用 native。 */
    private suspend fun generate(context: Context, model: String, system: String, user: String): String =
        generationMutex.withLock {
            val s = loaded(context, model)
            val prompt = "$system\n\n$user\n\n" +
                "IMPORTANT: Begin your reply with the JSON object immediately. " +
                "Do not write any thinking, analysis, or commentary before or after the JSON."
            val out = StringBuilder()
            val startedAt = System.currentTimeMillis()
            state = "generating"
            try {
                val stats = s.submit(prompt, object : GenerateProgressListener {
                    override fun onProgress(progress: String?): Boolean {
                        if (progress == null) return false
                        out.append(progress)
                        return System.currentTimeMillis() - startedAt > GENERATE_TIMEOUT_MS
                    }
                })
                Log.i(TAG, "local gen done ($model): ${stats["decode_len"]} tokens, ${stats["decode_time"]}us, text=${out.length} chars")
            } finally {
                state = "idle"
            }
            if (out.isBlank()) throw IOException("本地模型没有输出内容")
            Log.d(TAG, "raw output: ${out.take(1500)}")
            out.toString()
        }

    private fun weaknessHint(weaknesses: List<String>): String =
        if (weaknesses.isEmpty()) "" else
            "\n\nThis student's recurring weaknesses recorded from past attempts: ${weaknesses.joinToString("; ")}. " +
                "Check specifically whether these problems appear in this attempt. If they do, list them FIRST in top_fixes " +
                "and state clearly whether the student is improving on them."

    // 与 LlmClient 的 system prompt 保持一致（本地/云端评分口径统一）。
    // 校准要点：先列错误再打分（JSON 键顺序即生成顺序），GRA 与列出错误数硬绑定，
    // B8 用「排除性特征」判定（出现 X 就限分），并给出真实考生分布先验——
    // 正向描述对小模型约束力不够（实测 300 词作文仍被评 8.0，真实约 7.0-7.5）。
    private val WRITING_SYSTEM = """
        You are a strict IELTS Writing Task 2 examiner. Score against these descriptors.
        TR: B4 barely addresses the task; B5 limited/underdeveloped; B6 covers all parts but thin; B7 clear position, extended ideas; B8 fully developed, well-supported.
        CC: B4 no clear progression; B5 unfocused; B6 logical but faulty devices; B7 clear paragraphing with effective use of a range of cohesive devices; B8 cohesion is seamless and never draws attention.
        LR: B4 limited, frequent errors; B5 limited, word-choice errors; B6 adequate, some inaccuracies; B7 sufficient range, collocation; B8 wide range, precise, natural.
        GRA: B4 limited structures, frequent errors; B5 limited structures, frequent errors; B6 mix, errors rarely impede; B7 complex variety, mostly error-free; B8 wide range, almost all error-free.
        Band 8 exclusion tests (a B8 essay must pass ALL of them; failing one caps that criterion as stated):
        - CC: frequent formulaic linkers (On the one hand / Moreover / In conclusion / Firstly) mean the cohesion draws attention: cap CC at 7.0.
        - LR: only common everyday collocations, no precise or less-common phrasing: cap LR at 7.0.
        - TR: examples are generic or hypothetical rather than specific and extended: cap TR at 7.0.
        - GRA: if you listed any sentence in sentence_fixes, cap GRA at 7.5.
        Hard rules (apply mechanically):
        - 0.5 steps; overall = average rounded to 0.5.
        - under 250 words caps TR at 5.0; under 150 words caps TR at 4.0; off-topic scores 0 for TR.
        - Find the errors FIRST: list every sentence with a clear grammar or word-choice error in sentence_fixes (up to 5, do not hide errors to justify a high score).
        - GRA must match those errors: 4+ erroneous sentences means GRA cannot exceed 6.5; 2-3 means GRA cannot exceed 7.0.
        - Most real candidates score 5.5-6.0. Band 7 is a strong score (top ~10%); Band 8 is near-native and rare. Do not inflate.
        Reference: an essay of simple repetitive sentences with mechanical linkers (First/But/However) and undeveloped ideas scores 6.0.
        Return STRICT JSON only, keys in exactly this order:
        {"sentence_fixes":[{"sentence":"exact original sentence","issue":"problem in Chinese","better":"corrected sentence"}],"GRA":x,"LR":x,"TR":x,"CC":x,"overall":x,"top_fixes":["..."],"polished":"..."}
        sentence_fixes: up to 5 items for sentences with real errors; [] if clean; the issue field MUST be written in Chinese.
        top_fixes: exactly 3 items, must not be empty; write each in Chinese, quote the problematic English phrase, and say how to fix it.
        polished = the same ideas rewritten at band 7.
    """.trimIndent()

    private val TASK1_SYSTEM = """
        You are a strict IELTS Writing Task 1 examiner. Score a data report.
        TA: B5 mechanical, no overview; B6 overview but details inaccurate; B7 clear overview, main trends compared.
        CC: B5 unfocused; B6 logical but faulty devices; B7 clear paragraphing, range of devices.
        LR: B5 limited; B6 adequate, some inaccuracies; B7 sufficient range, precise.
        GRA: B5 limited structures, frequent errors; B6 mix, errors rarely impede; B7 complex variety, mostly error-free.
        Band 8 exclusion tests (a B8 report must pass ALL; failing one caps that criterion as stated):
        - CC: frequent formulaic linkers (Firstly / Moreover / In conclusion) mean cohesion draws attention: cap CC at 7.0.
        - LR: only common everyday collocations, no precise data-report phrasing (surge, plateau, marginally): cap LR at 7.0.
        - TA: data selection is incomplete or comparisons are missing: cap TA at 7.0.
        - GRA: if you listed any sentence in sentence_fixes, cap GRA at 7.5.
        Hard rules (apply mechanically):
        - 0.5 steps; overall = average rounded to 0.5; under 150 words caps TA at 5.0; under 100 words caps TA at 4.0.
        - opinions instead of description caps TA at 5.0.
        - Find the errors FIRST: list every sentence with a clear grammar or word-choice error in sentence_fixes (up to 5, do not hide errors to justify a high score).
        - GRA must match those errors: 4+ erroneous sentences means GRA cannot exceed 6.5; 2-3 means GRA cannot exceed 7.0.
        - Most real candidates score 5.5-6.0. Band 7 is strong; Band 8 is near-native and rare. Do not inflate.
        Return STRICT JSON only, keys in exactly this order:
        {"sentence_fixes":[{"sentence":"exact original sentence","issue":"problem in Chinese","better":"corrected sentence"}],"GRA":x,"LR":x,"TA":x,"CC":x,"overall":x,"top_fixes":["..."],"polished":"..."}
        sentence_fixes: up to 5 items for sentences with real errors; [] if clean; the issue field MUST be written in Chinese.
        top_fixes: exactly 3 items, must not be empty; write each in Chinese, quote the problematic English phrase, and say how to fix it.
        polished = the same report rewritten at band 7.
    """.trimIndent()

    private val SPEAKING_SYSTEM = """
        You are a strict IELTS Speaking examiner scoring a transcript.
        FC: B4 frequent pauses; B5 hesitant; B6 willing to speak, some coherence loss; B7 fluent, no noticeable effort; B8 fluent, idiomatic.
        LR: B4 limited, frequent errors; B5 limited; B6 adequate; B7 flexible, idiomatic; B8 wide, precise.
        GRA: B4 basic with frequent errors; B5 basic with errors; B6 mix, some errors; B7 flexible, mostly error-free; B8 wide range, almost error-free.
        P: ESTIMATE from transcript evidence (self-corrections, mangled ASR words) AND the objective metrics (words/min, filler count); state it is an estimate.
        Use the metrics as evidence: very slow pace or many fillers support a lower FC; near-native pace with few fillers supports a higher FC.
        Hard rules (apply mechanically):
        - 0.5 steps; overall = average rounded; under 20 words or off-topic caps overall at 4.0.
        - Find the errors FIRST: list every answer segment with a clear grammar or word-choice error in sentence_fixes (up to 5, do not hide errors to justify a high score).
        - GRA must match those errors: 4+ erroneous sentences means GRA cannot exceed 6.5; 2-3 means GRA cannot exceed 7.0; 7.5-8.0 only if the transcript is clean with at most one minor slip.
        - Most real candidates score 5.5-6.0. Band 7 is strong; Band 8 is near-native and rare. Do not inflate.
        Output JSON with sentence_fixes FIRST and keys in exactly this order:
        {"sentence_fixes":[{"sentence":"exact original sentence","issue":"grammar/vocabulary problem in Chinese","better":"corrected natural version"}],"GRA":x,"LR":x,"FC":x,"P":x,"overall":x,"top_fixes":["..."],"improved":"..."}
        sentence_fixes: up to 5 items for sentences with real errors; [] if clean.
        top_fixes: exactly 3 items; write each in Chinese.
        improved = a band-7 spoken-style version of the same answer.
    """.trimIndent()
}
