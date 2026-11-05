package com.txw.ielts.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class BankQuestion(
    val id: Int,
    val type: String,          // choice | tfng | gap
    val prompt: String,
    val options: List<String>,
    val answers: List<String>,
    val explain: String,
    val explainEn: String = "",
    val keys: List<String> = emptyList(),
)

/** 听力精听的一句：start/end 秒 + 原文。 */
data class BankSentence(val start: Float, val end: Float, val text: String)

data class BankSet(
    val id: String,
    val module: String,        // reading | listening
    val title: String,
    val intro: String,
    val passage: String,
    val audio: String,         // listening 才有
    val transcript: String,    // listening 才有，降级时显示
    val sentences: List<BankSentence> = emptyList(),
    val questions: List<BankQuestion>,
)

/** 一道错题的入库素材：题干 / 正确答案 / 解析。 */
data class BankWrong(val prompt: String, val correct: String, val explain: String)

/**
 * 听力/阅读题库：题目打包在 assets/bank.json，音频打包在 assets/bank 目录下的 mp3。
 * 判分纯本地规则，不调用任何网络接口。
 */
class BankRepo(private val context: Context, private val prefs: Prefs) {

    private var cache: List<BankSet>? = null

    fun sets(): List<BankSet> = cache ?: load().also { cache = it }

    fun byId(id: String): BankSet? = sets().firstOrNull { it.id == id }

    private fun load(): List<BankSet> {
        val text = context.assets.open("bank.json").bufferedReader().use { it.readText() }
        val arr = JSONObject(text).getJSONArray("sets")
        val out = ArrayList<BankSet>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val qs = o.getJSONArray("questions")
            val questions = ArrayList<BankQuestion>(qs.length())
            for (j in 0 until qs.length()) {
                val q = qs.getJSONObject(j)
                questions.add(
                    BankQuestion(
                        id = q.getInt("id"),
                        type = q.getString("type"),
                        prompt = q.getString("prompt"),
                        options = q.optJSONArray("options")?.let { a ->
                            (0 until a.length()).map { a.getString(it) }
                        } ?: emptyList(),
                        answers = q.getJSONArray("answers").let { a ->
                            (0 until a.length()).map { a.getString(it) }
                        },
                        explain = q.optString("explain", ""),
                        explainEn = q.optString("explain_en", ""),
                        keys = q.optJSONArray("keys")?.let { a ->
                            (0 until a.length()).map { a.getString(it) }
                        } ?: emptyList(),
                    )
                )
            }
            out.add(
                BankSet(
                    id = o.getString("id"),
                    module = o.getString("module"),
                    title = o.getString("title"),
                    intro = o.optString("intro", ""),
                    passage = o.optString("passage", ""),
                    audio = o.optString("audio", ""),
                    transcript = o.optString("transcript", ""),
                    sentences = o.optJSONArray("sentences")?.let { a ->
                        (0 until a.length()).map { i ->
                            val so = a.getJSONObject(i)
                            BankSentence(
                                so.optDouble("start", 0.0).toFloat(),
                                so.optDouble("end", 0.0).toFloat(),
                                so.optString("text", ""),
                            )
                        }
                    } ?: emptyList(),
                    questions = questions,
                )
            )
        }
        return out
    }

    /** 音频解析：AI 生成的听力音频在私有目录 filesDir/bank；手写题从 assets 拷到 cacheDir（assets fd 部分机型不可靠）。 */
    fun audioFile(set: BankSet): File? {
        if (set.audio.isBlank()) return null
        return runCatching {
            val name = set.audio.substringAfterLast('/')
            val local = File(File(context.filesDir, "bank"), name)
            if (local.exists() && local.length() > 0L) return@runCatching local
            val f = File(File(context.cacheDir, "bank"), name)
            f.parentFile?.mkdirs()
            val needCopy = !f.exists() || f.length() == 0L
            if (needCopy) {
                context.assets.open(set.audio).use { input ->
                    f.outputStream().use { input.copyTo(it) }
                }
            }
            f
        }.getOrNull()
    }

    // ---- AI 生成题库：存应用私有目录 ai_bank.json，与手写 assets/bank.json 分区 ----

    private val aiBankFile: File get() = File(context.filesDir, "ai_bank.json")

    fun aiSets(): List<BankSet> = runCatching {
        if (!aiBankFile.exists()) return@runCatching emptyList()
        val arr = JSONObject(aiBankFile.readText()).optJSONArray("sets") ?: return@runCatching emptyList()
        (0 until arr.length()).map { parseSet(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun saveAiSet(set: BankSet) {
        val existing = aiSets().toMutableList()
        existing.removeAll { it.id == set.id }
        existing.add(0, set)
        writeAiSets(existing)
    }

    fun deleteAiSet(id: String) {
        val sets = aiSets()
        val target = sets.firstOrNull { it.id == id }
        writeAiSets(sets.filter { it.id != id })
        // 套题删了，私有目录里的 TTS 音频也要清掉，否则会一直占空间。
        BankAudio.ownedWavName(target?.audio.orEmpty())?.let { name ->
            runCatching { File(File(context.filesDir, "bank"), name).delete() }
        }
    }

    private fun writeAiSets(sets: List<BankSet>) {
        val arr = JSONArray()
        sets.forEach { arr.put(setToJson(it)) }
        aiBankFile.writeText(JSONObject().put("sets", arr).toString())
    }

    private fun parseSet(o: JSONObject): BankSet {
        val qs = o.getJSONArray("questions")
        val questions = ArrayList<BankQuestion>(qs.length())
        for (j in 0 until qs.length()) {
            val q = qs.getJSONObject(j)
            questions.add(
                BankQuestion(
                    id = q.getInt("id"),
                    type = q.getString("type"),
                    prompt = q.getString("prompt"),
                    options = q.optJSONArray("options")?.let { a ->
                        (0 until a.length()).map { a.getString(it) }
                    } ?: emptyList(),
                    answers = q.getJSONArray("answers").let { a ->
                        (0 until a.length()).map { a.getString(it) }
                    },
                    explain = q.optString("explain", ""),
                    explainEn = q.optString("explain_en", ""),
                    keys = q.optJSONArray("keys")?.let { a ->
                        (0 until a.length()).map { a.getString(it) }
                    } ?: emptyList(),
                )
            )
        }
        return BankSet(
            id = o.getString("id"),
            module = o.optString("module", "reading"),
            title = o.getString("title"),
            intro = o.optString("intro", ""),
            passage = o.optString("passage", ""),
            audio = o.optString("audio", ""),
            transcript = o.optString("transcript", ""),
            questions = questions,
        )
    }

    private fun setToJson(set: BankSet): JSONObject {
        val qs = JSONArray()
        set.questions.forEach { q ->
            val options = JSONArray()
            q.options.forEach { options.put(it) }
            val answers = JSONArray()
            q.answers.forEach { answers.put(it) }
            val keys = JSONArray()
            q.keys.forEach { keys.put(it) }
            qs.put(
                JSONObject().apply {
                    put("id", q.id)
                    put("type", q.type)
                    put("prompt", q.prompt)
                    put("options", options)
                    put("answers", answers)
                    put("explain", q.explain)
                    put("explain_en", q.explainEn)
                    put("keys", keys)
                }
            )
        }
        return JSONObject().apply {
            put("id", set.id)
            put("module", set.module)
            put("title", set.title)
            put("intro", set.intro)
            put("passage", set.passage)
            put("audio", set.audio)
            put("transcript", set.transcript)
            put("questions", qs)
        }
    }

    // ---- 判分（忽略大小写、标点与多余空格；填空题支持多个可接受答案）----

    fun normalize(s: String): String = AnswerKey.normalize(s)

    fun isCorrect(q: BankQuestion, answer: String?): Boolean {
        if (answer.isNullOrBlank()) return false
        val got = normalize(answer)
        if (got.isEmpty()) return false
        return q.answers.any { normalize(it) == got }
    }

    fun correctAnswerText(q: BankQuestion): String = q.answers.firstOrNull() ?: ""

    // ---- 作答进度：按套题存 JSON 字符串，退出后可续做 ----

    fun savedAnswers(setId: String): Map<Int, String> {
        val all = runCatching { JSONObject(prefs.bankProgress) }.getOrDefault(JSONObject())
        val o = all.optJSONObject(setId) ?: return emptyMap()
        val m = HashMap<Int, String>()
        o.keys().forEach { k ->
            k.toIntOrNull()?.let { m[it] = o.optString(k) }
        }
        return m
    }

    fun saveAnswers(setId: String, answers: Map<Int, String>) {
        val all = runCatching { JSONObject(prefs.bankProgress) }.getOrDefault(JSONObject())
        val o = JSONObject()
        answers.forEach { (k, v) -> if (v.isNotBlank()) o.put(k.toString(), v) }
        if (o.length() == 0) all.remove(setId) else all.put(setId, o)
        prefs.bankProgress = all.toString()
    }

    fun clearAnswers(setId: String) {
        val all = runCatching { JSONObject(prefs.bankProgress) }.getOrDefault(JSONObject())
        all.remove(setId)
        prefs.bankProgress = all.toString()
    }
}
