package com.txw.ielts.data

/**
 * 一次「把这几点加入背诵」的入库账目：新增 / 与已有卡片重复 / 内容不可用。
 *
 * 为什么要有：`CardRepo` 一直按 [CardText.key] 跨会话去重，但只把「新增了几张」返回给
 * 调用方，结果页拿到 true/false 就当成功。用户点完看到「已加入背诵」，实际可能一张都没进库
 * ——同一份批注再次点击、或模型换了个标点重复给同一条，都会被静默丢掉。复习队列里
 * 到底多了什么、为什么没多，界面得说清楚，否则用户会以为反馈已进入背诵，下一轮却找不到。
 */
data class IngestResult(
    /** 真正写库的新卡张数。 */
    val added: Int = 0,
    /** 与已有卡片（或同批内的前一条）重复而跳过的条数。 */
    val duplicate: Int = 0,
    /** 空白、或逐句改法的「原句」与「更好」只差标点/大小写，做不成卡片。 */
    val invalid: Int = 0,
) {
    val total: Int get() = added + duplicate + invalid

    operator fun plus(other: IngestResult) = IngestResult(
        added = added + other.added,
        duplicate = duplicate + other.duplicate,
        invalid = invalid + other.invalid,
    )
}

/**
 * 反馈卡入库前的清洗与去重（纯逻辑，不碰数据库）。
 *
 * `CardRepo` 只负责建实体和写库；这里决定「哪些条目值得做卡、哪些是重复」，
 * 这样计数和文案能在 PC 上单测，不用起 Room。
 */
object FeedbackIngest {

    /** 逐句改法卡正面前缀：与普通批注区分开。 */
    const val SENTENCE_PREFIX = "改错："

    /** 逐句改法正面长度上限（与历史行为一致）。 */
    const val SENTENCE_MAX = 160

    /** 一条可入库的练习批注：正面是清洗后的短句，背面保留完整批注。 */
    data class FixCard(val front: String, val back: String)

    /** 一条可入库的逐句改法：正面「改错：原句」，背面是改写 + 中文问题。 */
    data class SentenceCard(val front: String, val back: String)

    data class Plan<T>(
        val accepted: List<T>,
        val duplicate: Int,
        val invalid: Int,
    ) {
        val ingest: IngestResult get() = IngestResult(accepted.size, duplicate, invalid)
    }

    /**
     * 批注 → 复习卡。正面按 [CardText.FIX_MAX] 截断，判重键与 [CardRepo] 其他入口一致。
     * @param existingKeys 库里已有卡片的 [CardText.key]；同批内后出现的重复条目也计入 duplicate。
     */
    fun planFixes(raw: List<String>, existingKeys: Set<String>): Plan<FixCard> {
        val seen = HashSet(existingKeys)
        var duplicate = 0
        var invalid = 0
        val accepted = ArrayList<FixCard>(raw.size)
        raw.forEach { rawText ->
            val head = CardText.front(rawText, CardText.FIX_MAX)
            when {
                head.isBlank() -> invalid++
                !seen.add(CardText.key(head)) -> duplicate++
                else -> accepted.add(FixCard(front = head, back = rawText.trim()))
            }
        }
        return Plan(accepted, duplicate, invalid)
    }

    /**
     * 逐句改法 → 改错卡。
     *
     * 原句与「更好」只差大小写/空白/句末标点时，正反面内容相同，练了也学不到东西：
     * 旧会话里已存盘的这类条目同样要在入库时挡掉，所以计入 invalid 而不是建成空卡。
     */
    fun planSentences(
        items: List<Triple<String, String, String>>,
        existingKeys: Set<String>,
    ): Plan<SentenceCard> {
        val seen = HashSet(existingKeys)
        var duplicate = 0
        var invalid = 0
        val accepted = ArrayList<SentenceCard>(items.size)
        items.forEach { (sentence, issue, better) ->
            val s = sentence.trim()
            val b = better.trim()
            when {
                s.isBlank() || b.isBlank() -> invalid++
                CardText.key(s) == CardText.key(b) -> invalid++
                else -> {
                    val front = SENTENCE_PREFIX + CardText.front(s, SENTENCE_MAX)
                    if (!seen.add(CardText.key(front))) {
                        duplicate++
                    } else {
                        val back = b + if (issue.isNotBlank()) "\n\n（${issue.trim()}）" else ""
                        accepted.add(SentenceCard(front = front, back = back))
                    }
                }
            }
        }
        return Plan(accepted, duplicate, invalid)
    }

    /**
     * 结果页按钮下方的一行交代。
     *
     * 三种情况必须分开说：真的加了新卡（顺带说重复/无效几条）、一张没加但都是重复、
     * 一张没加且内容本身不可用。都写成「已加入背诵」等于骗用户去复习队列里找不存在的东西。
     */
    fun summary(result: IngestResult): String? {
        if (result.total <= 0) return null
        if (result.added > 0) {
            val notes = buildList {
                add("${result.added} 张新卡")
                if (result.duplicate > 0) add("${result.duplicate} 条与已有卡片重复")
                if (result.invalid > 0) add("${result.invalid} 条内容不可用")
            }
            return "已加入背诵：${notes.joinToString("，")}。下轮复习会优先安排反馈卡。"
        }
        return when {
            result.duplicate > 0 && result.invalid == 0 ->
                "没有新卡：这 ${result.duplicate} 条都已经在背诵里了。"
            result.duplicate == 0 && result.invalid > 0 ->
                "没有可加入的内容：这批批注为空白，或原句与改法只差标点/大小写。"
            else ->
                "没有新卡：${result.duplicate} 条已在背诵里，${result.invalid} 条内容不可用。"
        }
    }
}
