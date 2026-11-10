package com.txw.ielts.data

import android.content.Context
import androidx.room.withTransaction
import org.json.JSONArray
import org.json.JSONObject

/**
 * 备份导入里「不写数据库」的纯逻辑。
 *
 * 真正的写库（单事务插入 sessions/cards）仍属 L1，待确认后按
 * `docs/backup-import-implementation.md` 落地；这里先把可测的部分做掉。
 */
object BackupImporter {

    /** AI 套题上限：与 BankRepo 的列表口径一致，避免导入把文件撑爆。 */
    const val MAX_AI_SETS = 200

    /** 从备份的 settings 里取「本 App 认识」的字段；缺失/类型不对的保持 null（即不覆盖）。 */
    data class ImportSettings(
        val targetBand: Float?,
        val examDate: String?,
        val dailyNewCards: Int?,
        val scoreChannel: String?,
        val localModel: String?,
        val readerFontSize: Int?,
        val readerLineHeight: Float?,
    ) {
        val isEmpty: Boolean
            get() = targetBand == null && examDate == null && dailyNewCards == null &&
                scoreChannel == null && localModel == null &&
                readerFontSize == null && readerLineHeight == null
    }

    fun parseSettings(obj: JSONObject?): ImportSettings {
        if (obj == null) return ImportSettings(null, null, null, null, null, null, null)
        fun num(key: String): Float? = if (obj.has(key)) runCatching { obj.getDouble(key).toFloat() }.getOrNull() else null
        fun int(key: String): Int? = if (obj.has(key)) runCatching { obj.getInt(key) }.getOrNull() else null
        fun str(key: String): String? =
            if (obj.has(key)) obj.optString(key).trim().takeIf { it.isNotBlank() } else null
        return ImportSettings(
            targetBand = num("targetBand"),
            examDate = str("examDate"),
            dailyNewCards = int("dailyNewCards"),
            scoreChannel = str("scoreChannel")?.takeIf { it in setOf("local", "cloud", "auto") },
            localModel = str("localModel"),
            readerFontSize = int("readerFontSize"),
            readerLineHeight = num("readerLineHeight"),
        )
    }

    /** 导入后若本地模型名在本机不存在，给一句提示（否则评分会一直加载失败）。 */
    fun modelWarning(localModel: String?, available: Set<String>): String? {
        val m = localModel?.trim().orEmpty()
        if (m.isBlank() || m in available) return null
        return "备份里的本地模型「$m」在本机不可用，请到设置页重新选择模型"
    }

    /**
     * 合并 AI 套题（`files/ai_bank.json` 的 `sets` 数组）。
     *
     * 规则：按 `id` 去重；新导入的放在前面（与 `BankRepo.saveAiSet` 的「新的在前」一致）；
     * 现有文件损坏时按空处理。返回可直接写回文件的 JSON 串。
     */
    fun mergeAiSets(existingBankJson: String?, incoming: JSONArray?): String {
        val seen = LinkedHashSet<String>()
        val out = JSONArray()

        fun putIfNew(node: JSONObject?) {
            if (node == null) return
            val id = node.optString("id").trim()
            // 没有 id 的套题无意义：界面/删除都按 id 定位。
            if (id.isBlank() || !seen.add(id)) return
            out.put(node)
        }

        // 新导入的在前。
        if (incoming != null) {
            for (i in 0 until incoming.length()) putIfNew(incoming.optJSONObject(i))
        }
        val existing = runCatching {
            JSONObject(existingBankJson?.takeIf { it.isNotBlank() } ?: "{}").optJSONArray("sets")
        }.getOrNull()
        if (existing != null) {
            for (i in 0 until existing.length()) putIfNew(existing.optJSONObject(i))
        }

        val capped = JSONArray()
        for (i in 0 until out.length().coerceAtMost(MAX_AI_SETS)) capped.put(out.get(i))
        return JSONObject().put("sets", capped).toString()
    }

    /** 导入结果：界面据此显示「新增了多少」。 */
    data class Outcome(
        val sessions: Int,
        val cards: Int,
        val essays: Int = 0,
        val aiSets: Int,
        val settingsApplied: Boolean,
        val modelWarning: String? = null,
        /** AI 套题/范文这类文件（不在 DB 事务内）写入失败时的说明：练习记录已经入库，不能说「导入失败」。 */
        val fileWarning: String? = null,
    )

    /**
     * 按确认页的决策落库：新增不覆盖、设置默认不导入、DB 单事务。
     *
     * 调用前已由预览确认「会新增多少」；这里再按当前去重键过滤一次，避免预览后本机又新增数据。
     * AI 套题是文件（files/ai_bank.json），不在事务内：DB 已提交后写入失败只记提示，
     * 不能让界面把已经进库的练习记录说成「导入失败」。
     */
    suspend fun apply(
        context: Context,
        db: AppDb,
        prefs: Prefs,
        plan: BackupRestore.Plan,
        applySettings: Boolean,
    ): Outcome {
        val bank = BankRepo(context, prefs)
        val kept = BackupRestore.keepNew(
            plan,
            db.sessions().dedupeKeys().toSet(),
            db.cards().dedupeKeys().toSet(),
            runCatching { ModelEssayStore.load(context).map { ModelEssayStore.essayKey(it) }.toSet() }
                .getOrDefault(emptySet()),
            runCatching { bank.aiSets().map { it.id }.toSet() }.getOrDefault(emptySet()),
        )

        db.withTransaction {
            kept.sessions.forEach { db.sessions().insert(it) }
            if (kept.cards.isNotEmpty()) db.cards().insertAll(kept.cards)
        }

        val aiMerge = runCatching { bank.mergeAiSets(kept.aiSets) }
        val addedAiSets = aiMerge.getOrDefault(0)
        // 范文与 AI 套题一样是文件存储：失败只记提示，不能让界面把已入库的练习说成导入失败。
        val essayMerge = runCatching { ModelEssayStore.mergeImported(context, kept.essays) }
        val addedEssays = essayMerge.getOrDefault(0)
        val fileWarning = listOfNotNull(
            aiMerge.exceptionOrNull()?.let {
                "AI 套题写入失败：${it.message ?: "未知错误"}（练习记录已导入，可重试导入补齐套题）"
            },
            essayMerge.exceptionOrNull()?.let {
                "范文写入失败：${it.message ?: "未知错误"}（练习记录已导入，可重试导入补齐范文）"
            },
        ).takeIf { it.isNotEmpty() }?.joinToString("；")

        var settingsApplied = false
        var warning: String? = null
        if (applySettings) {
            val imported = parseSettings(plan.settings)
            if (!imported.isEmpty) {
                prefs.applyImported(imported)
                settingsApplied = true
                val available = runCatching {
                    val dirs = listOf(
                        java.io.File(context.filesDir, "models/mnn"),
                        java.io.File(context.getExternalFilesDir(null), "models/mnn"),
                    )
                    dirs.flatMap { dir -> dir.listFiles().orEmpty().filter { it.isDirectory }.map { it.name } }.toSet()
                }.getOrDefault(emptySet())
                warning = modelWarning(imported.localModel, available)
            }
        }
        // 复盘/提纲缓存指向旧数据，导入后必须失效，否则会读到导入前的统计。
        prefs.clearDerivedCaches()

        return Outcome(
            sessions = kept.sessions.size,
            cards = kept.cards.size,
            essays = addedEssays,
            aiSets = addedAiSets,
            settingsApplied = settingsApplied,
            modelWarning = warning,
            fileWarning = fileWarning,
        )
    }
}
