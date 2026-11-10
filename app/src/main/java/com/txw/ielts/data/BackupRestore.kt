package com.txw.ielts.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * 备份导入的「解析 / 校验 / 预演」纯逻辑（不写库）。
 *
 * 真正写入数据库属于 L1 流程（见 docs/archify/ielts-backup-restore_workflow），确认前不实现；
 * 这里先把「能读到什么、会新增什么」算清楚，既能为确认页的预览提供数据，也能单测。
 */
object BackupRestore {
    /** 单文件上限：正常导出（几百条练习 + 几百张卡）在 1 MB 量级，20 MB 足够且能挡住误选大文件。 */
    const val MAX_BYTES = 20 * 1024 * 1024

    /** 认识的最小/最大版本：目前导出只有 version=1。 */
    const val SUPPORTED_VERSION = 1

    class InvalidBackup(message: String) : IllegalArgumentException(message)

    data class Plan(
        val version: Int,
        val sessions: List<Session>,
        val cards: List<Card>,
        val essays: List<ModelEssay> = emptyList(),
        val aiSets: JSONArray?,
        val settings: JSONObject?,
        val skippedSessions: Int,
        val skippedCards: Int,
        val skippedEssays: Int = 0,
    )

    /**
     * 解析导出 JSON。整体不可用时抛 [InvalidBackup]（调用方据此提示「文件不是备份/版本不支持」）；
     * 单条坏数据只计入 skipped，不拖垮整次导入。
     */
    fun parse(json: String): Plan {
        if (json.isBlank()) throw InvalidBackup("文件是空的")
        if (json.toByteArray(Charsets.UTF_8).size > MAX_BYTES) throw InvalidBackup("文件超过 20 MB")
        val root = runCatching { JSONObject(json) }.getOrElse { throw InvalidBackup("不是有效的 JSON") }
        val version = root.optInt("version", 0)
        if (version <= 0) throw InvalidBackup("缺少 version 字段，可能不是本 App 的备份")
        if (version > SUPPORTED_VERSION) throw InvalidBackup("备份版本 $version 高于本机支持的 $SUPPORTED_VERSION")

        var skippedSessions = 0
        val sessions = ArrayList<Session>()
        val rawSessions = root.optJSONArray("sessions")
        if (rawSessions != null) {
            for (i in 0 until rawSessions.length()) {
                val o = rawSessions.optJSONObject(i)
                if (o == null) { skippedSessions++; continue }
                val module = o.optString("module").trim()
                val topic = o.optString("topic").trim()
                if (module.isBlank() || topic.isBlank()) { skippedSessions++; continue }
                sessions += Session(
                    // id 归零：导入时重新分配，避免与现有主键冲突（策略见确认图）。
                    id = 0,
                    module = module,
                    topic = topic,
                    startedAt = o.optLong("startedAt", 0L),
                    durationSec = o.optInt("durationSec", 0),
                    // 音频文件不在备份里：不恢复路径，避免结果页出现「点播放没反应」。
                    audioPath = null,
                    transcript = o.optString("transcript"),
                    essayText = o.optString("essayText"),
                    scoresJson = o.optString("scoresJson"),
                    fixesJson = o.optString("fixesJson"),
                    polished = o.optString("polished"),
                    status = o.optString("status").ifBlank { "done" },
                    task = o.optString("task"),
                )
            }
        }

        var skippedCards = 0
        val cards = ArrayList<Card>()
        val rawCards = root.optJSONArray("cards")
        if (rawCards != null) {
            for (i in 0 until rawCards.length()) {
                val o = rawCards.optJSONObject(i)
                if (o == null) { skippedCards++; continue }
                val front = o.optString("front").trim()
                if (front.isBlank()) { skippedCards++; continue }
                val reps = o.optInt("reps", 0)
                val lastReviewed = o.optLong("lastReviewed", 0L)
                cards += Card(
                    id = 0,
                    type = o.optString("type").ifBlank { "word" },
                    front = front,
                    back = o.optString("back"),
                    example = o.optString("example"),
                    exampleCn = o.optString("exampleCn"),
                    tags = o.optString("tags"),
                    source = o.optString("source").ifBlank { "seed" },
                    createdAt = o.optLong("createdAt", 0L),
                    ease = o.optDouble("ease", 2.5),
                    intervalDays = o.optInt("intervalDays", 0),
                    reps = reps,
                    lapses = o.optInt("lapses", 0),
                    dueAt = o.optLong("dueAt", 0L),
                    lastReviewed = lastReviewed,
                    // 旧备份没有 firstLearnedAt：有复习记录的卡用 lastReviewed 近似，
                    // 避免导入后已学的卡被重新算成「今天的新卡」。
                    firstLearnedAt = o.optLong(
                        "firstLearnedAt",
                        if (reps > 0) lastReviewed else 0L,
                    ),
                )
            }
        }

        var skippedEssays = 0
        val essays = ArrayList<ModelEssay>()
        val rawEssays = root.optJSONArray("modelEssays")
        if (rawEssays != null) {
            for (i in 0 until rawEssays.length()) {
                val o = rawEssays.optJSONObject(i)
                if (o == null) { skippedEssays++; continue }
                val polished = o.optString("polished").trim()
                // 正文为空没有收藏价值；sessionId 在导入时重新分配，这里不读。
                if (polished.isBlank()) { skippedEssays++; continue }
                essays += ModelEssay(
                    sessionId = 0L,
                    topic = o.optString("topic").trim(),
                    polished = polished,
                    band = o.optDouble("band", 0.0).takeIf { it.isFinite() } ?: 0.0,
                    savedAt = o.optLong("savedAt", 0L),
                )
            }
        }

        return Plan(
            version = version,
            sessions = sessions,
            cards = cards,
            essays = essays,
            aiSets = root.optJSONArray("aiSets"),
            settings = root.optJSONObject("settings"),
            skippedSessions = skippedSessions,
            skippedCards = skippedCards,
            skippedEssays = skippedEssays,
        )
    }

    /** 练习去重键：同模块 + 同题目 + 同开始时间视为同一条。 */
    fun sessionKey(s: Session): String = "${s.module}\u0001${s.topic}\u0001${s.startedAt}"

    /** 卡片去重键：同类型 + 同正面（大小写与首尾空白不敏感）。 */
    fun cardKey(c: Card): String = "${c.type}\u0001${c.front.trim().lowercase()}"

    /**
     * 预览用一句话摘要（不写库）：确认页/今后的预览 UI 直接展示。
     * 例：「将新增 12 条练习、30 张卡片、2 套 AI 题；跳过 3 条坏数据」。
     */
    fun summary(plan: Plan): String {
        val parts = mutableListOf<String>()
        parts += "${plan.sessions.size} 条练习"
        parts += "${plan.cards.size} 张卡片"
        if (plan.essays.isNotEmpty()) parts += "${plan.essays.size} 篇范文"
        val ai = plan.aiSets?.length() ?: 0
        if (ai > 0) parts += "$ai 套 AI 题"
        val settingsCount = plan.settings?.length() ?: 0
        if (settingsCount > 0) parts += "设置 $settingsCount 项"
        val skipped = plan.skippedSessions + plan.skippedCards + plan.skippedEssays
        val head = "将新增 " + parts.joinToString("、")
        return if (skipped > 0) "$head；跳过 $skipped 条坏数据" else head
    }

    /** 预演：去掉与本机已有数据重复的条目（默认「新增不覆盖」）。 */
    fun keepNew(
        plan: Plan,
        existingSessionKeys: Set<String>,
        existingCardKeys: Set<String>,
        existingEssayKeys: Set<String> = emptySet(),
        existingAiSetIds: Set<String> = emptySet(),
    ): Plan = plan.copy(
        sessions = plan.sessions.filter { sessionKey(it) !in existingSessionKeys },
        cards = plan.cards.filter { cardKey(it) !in existingCardKeys },
        essays = plan.essays.filter { ModelEssayStore.essayKey(it) !in existingEssayKeys },
        // AI 套题按 id 去重，与 `BackupImporter.mergeAiSets` 同口径；没有 id 的条目
        // 合并时会被丢弃，预览也不该把它算进「将新增」。
        aiSets = plan.aiSets?.let { arr ->
            JSONArray().apply {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("id").trim()
                    if (id.isNotBlank() && id !in existingAiSetIds) put(o)
                }
            }
        },
    )
}
