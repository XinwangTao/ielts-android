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

    /** 按 id 查套题：手写题库在前，AI 生成题库在后（两者 id 前缀不同，不会冲突）。 */
    fun byIdAny(id: String): BankSet? = byId(id) ?: aiSets().firstOrNull { it.id == id }

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
            // assets fd 在部分机型不可靠，拿不到长度就退化为「空文件才重拷」。
            val expected = runCatching { context.assets.openFd(set.audio).length }.getOrDefault(-1L)
            // 长度不足（上次拷到一半）也必须重拷，否则半截 mp3 会被永远当成可用缓存。
            val needCopy = !f.exists() || f.length() == 0L || (expected > 0L && f.length() < expected)
            if (needCopy) {
                val part = File(f.parentFile, "$name.part")
                context.assets.open(set.audio).use { input ->
                    part.outputStream().use { input.copyTo(it) }
                }
                if (expected > 0L && part.length() < expected) {
                    part.delete()
                    throw java.io.IOException("音频缓存不完整")
                }
                if (!part.renameTo(f)) {
                    f.delete()
                    if (!part.renameTo(f)) {
                        part.delete()
                        throw java.io.IOException("音频缓存写入失败")
                    }
                }
            }
            f
        }.getOrNull()
    }

    // ---- AI 生成题库：存应用私有目录 ai_bank.json，与手写 assets/bank.json 分区 ----

    private val aiBankFile: File get() = File(context.filesDir, "ai_bank.json")

    fun aiSets(): List<BankSet> = try {
        if (!aiBankFile.exists()) emptyList() else {
            val arr = JSONObject(aiBankFile.readText()).optJSONArray("sets") ?: JSONArray()
            (0 until arr.length()).map { parseSet(arr.getJSONObject(it)) }
        }
    } catch (e: Exception) {
        // 半截/损坏的文件不能静默当「没有套题」：隔离成 .corrupt，避免下一次保存把原内容覆盖掉。
        android.util.Log.w("BankRepo", "ai_bank.json 解析失败，已隔离：${e.message}")
        runCatching { aiBankFile.renameTo(File(aiBankFile.parentFile, "ai_bank.json.corrupt")) }
        emptyList()
    }

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
        writeAiSetsText(JSONObject().put("sets", arr).toString())
    }

    /**
     * 备份导入：把备份里的 AI 套题按 id 合并进 `files/ai_bank.json`。
     *
     * 只新增不覆盖（同 id 保留本机版本），与确认页「新增不覆盖」口径一致；返回新增套题数。
     * 文件写失败会抛异常，调用方负责提示（数据库已经写入，不能假装整体失败）。
     */
    @Synchronized
    fun mergeAiSets(incoming: JSONArray?): Int {
        if (incoming == null || incoming.length() == 0) return 0
        val before = aiSets().map { it.id }.toSet()
        val existingText = runCatching {
            if (aiBankFile.exists()) aiBankFile.readText() else null
        }.getOrNull()
        val merged = BackupImporter.mergeAiSets(existingText, incoming)
        writeAiSetsText(merged)
        val after = runCatching {
            JSONObject(merged).optJSONArray("sets")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("id") }
            }.orEmpty()
        }.getOrDefault(emptyList())
        return after.count { it.isNotBlank() && it !in before }
    }

    private fun writeAiSetsText(text: String) {
        // 原子写：先写 .tmp 再改名。直接 writeText 截断重写，进程被杀/磁盘满会留下半截 JSON，
        // 下次打开列表静默变空，再保存一次就把剩余套题永久覆盖。
        val tmp = File(aiBankFile.parentFile, "ai_bank.json.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(aiBankFile)) {
            aiBankFile.delete()
            if (!tmp.renameTo(aiBankFile)) {
                tmp.delete()
                throw java.io.IOException("题库写入失败（磁盘空间不足或文件被占用）")
            }
        }
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
        prefs.editJson {
            val all = runCatching { JSONObject(prefs.bankProgress) }.getOrDefault(JSONObject())
            val o = JSONObject()
            answers.forEach { (k, v) -> if (v.isNotBlank()) o.put(k.toString(), v) }
            if (o.length() == 0) all.remove(setId) else all.put(setId, o)
            prefs.bankProgress = all.toString()
        }
    }

    fun clearAnswers(setId: String) {
        prefs.editJson {
            val all = runCatching { JSONObject(prefs.bankProgress) }.getOrDefault(JSONObject())
            all.remove(setId)
            prefs.bankProgress = all.toString()
        }
    }
}
