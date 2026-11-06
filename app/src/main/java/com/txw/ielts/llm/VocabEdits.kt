package com.txw.ielts.llm

import com.txw.ielts.data.CardText
import org.json.JSONObject

/**
 * AI 生词卡的「手动编辑 / 恢复原文」。
 *
 * 端侧 4B 提取的搭配并非条条可用：释义张冠李戴、例句把原句抄回来、
 * 搭配本身少个冠词——以前只能在「加入词汇积累」时整条勾掉，改不了。
 * 现在每条可以在入库前改搭配 / 释义 / 例句，改错了可逐条恢复，
 * 也可以一键全部恢复成模型原文；修改按会话存盘，切走再回来仍在。
 *
 * 编辑以「原始搭配」为键（复用 [CardText.key]），所以重新提取生词时，
 * 只要模型仍给出同一个搭配，之前的修改依然套得上；不同搭配的旧修改不会串到别的条目。
 *
 * 留空语义刻意不对称：
 * - 搭配、释义留空 = 保留模型原文（卡面/背面不能空，否则复习时无内容可看）；
 * - 例句留空 = 真的不要例句（模型编的例句常常是错的，允许清掉）。
 */
data class VocabDraft(
    val phrase: String,
    val meaning: String,
    val example: String,
)

object VocabEdits {

    /** 释义、例句的输入上限：与生词卡展示区匹配，防止一次粘贴撑爆结果页。 */
    const val MEANING_MAX = 160
    const val EXAMPLE_MAX = 240

    private val ws = Regex("\\s+")

    /** 编辑归属键：原始搭配归一化后的文本（忽略大小写、空白与句末标点）。 */
    fun key(item: VocabItem): String = CardText.key(item.phrase)

    /** 当前展示内容：改过就用改过的，没改过就是模型原文。 */
    fun draftOf(item: VocabItem, edits: Map<String, VocabDraft>): VocabDraft =
        edits[key(item)] ?: VocabDraft(item.phrase, item.meaning, item.example)

    /**
     * 写入一条修改。改回与原文一致时直接删除记录（= 未编辑），
     * 这样「撤销」不依赖另一个状态位，按钮的显隐与存盘内容始终一致。
     */
    fun edit(edits: Map<String, VocabDraft>, item: VocabItem, draft: VocabDraft): Map<String, VocabDraft> {
        val k = key(item)
        if (k.isBlank()) return edits
        val next = normalize(draft)
        val original = normalize(VocabDraft(item.phrase, item.meaning, item.example))
        return if (next == original) edits - k else edits + (k to next)
    }

    /** 单条恢复原文。 */
    fun revert(edits: Map<String, VocabDraft>, item: VocabItem): Map<String, VocabDraft> =
        edits - key(item)

    /** 当前结果里真正被改过的条数（存盘里可能有别的会话/别的搭配的残留，不计入）。 */
    fun editedCount(items: List<VocabItem>, edits: Map<String, VocabDraft>): Int =
        items.count { key(it) in edits }

    /** 加入 SRS 前的最终内容：套用修改并处理留空回退。 */
    fun resolve(items: List<VocabItem>, edits: Map<String, VocabDraft>): List<VocabItem> =
        items.map { item ->
            val d = edits[key(item)] ?: return@map item
            VocabItem(
                phrase = d.phrase.ifBlank { item.phrase.trim() },
                meaning = d.meaning.ifBlank { item.meaning.trim() },
                example = d.example,
            )
        }

    /**
     * 改完可能撞车：把第 2 条改成和第 1 条同一个搭配，入库时只会留一条。
     * 返回「后出现的下标 → 首次出现的下标」，界面据此提示，避免用户以为少加了卡。
     */
    fun duplicates(items: List<VocabItem>, edits: Map<String, VocabDraft>): Map<Int, Int> {
        val seen = HashMap<String, Int>()
        val out = LinkedHashMap<Int, Int>()
        resolve(items, edits).forEachIndexed { i, v ->
            val k = CardText.key(v.phrase)
            if (k.isBlank()) return@forEachIndexed
            val first = seen.putIfAbsent(k, i)
            if (first != null) out[i] = first
        }
        return out
    }

    /**
     * 按会话存盘：{"<sessionId>":{"<原始搭配键>":{"phrase":..,"meaning":..,"example":..}}}。
     * 空集合不写入（连旧键一起删掉），避免设置文件越攒越大。
     */
    fun save(store: String?, sessionId: Long, edits: Map<String, VocabDraft>): String {
        val root = runCatching { JSONObject(store ?: "") }.getOrNull() ?: JSONObject()
        val id = sessionId.toString()
        if (edits.isEmpty()) {
            root.remove(id)
        } else {
            val obj = JSONObject()
            edits.toSortedMap().forEach { (k, d) ->
                obj.put(
                    k,
                    JSONObject()
                        .put("phrase", d.phrase)
                        .put("meaning", d.meaning)
                        .put("example", d.example),
                )
            }
            root.put(id, obj)
        }
        return root.toString()
    }

    /** 读取某个会话的修改；存储损坏/类型不对一律当作「没改过」。 */
    fun load(store: String?, sessionId: Long): Map<String, VocabDraft> {
        val root = runCatching { JSONObject(store ?: "") }.getOrNull() ?: return emptyMap()
        val obj = runCatching { root.getJSONObject(sessionId.toString()) }.getOrNull() ?: return emptyMap()
        val out = LinkedHashMap<String, VocabDraft>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val draft = runCatching {
                val x = obj.getJSONObject(k)
                normalize(
                    VocabDraft(
                        phrase = x.optString("phrase", ""),
                        meaning = x.optString("meaning", ""),
                        example = x.optString("example", ""),
                    )
                )
            }.getOrNull() ?: continue
            if (k.isNotBlank()) out[k] = draft
        }
        return out
    }

    /** 统一清洗：搭配压平空白并按卡面上限截断，释义/例句去首尾空白并限制长度。 */
    private fun normalize(d: VocabDraft): VocabDraft = VocabDraft(
        phrase = CardText.front(d.phrase, CardText.SHORT_MAX),
        meaning = clip(d.meaning.trim().replace(ws, " "), MEANING_MAX),
        example = clip(d.example.trim(), EXAMPLE_MAX),
    )

    private fun clip(raw: String, max: Int): String =
        if (raw.length <= max) raw else raw.take(max).trimEnd() + "…"
}
