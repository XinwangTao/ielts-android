package com.txw.ielts.data

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
}
