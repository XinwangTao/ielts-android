package com.txw.ielts.data

import android.content.Context
import org.json.JSONObject

class CardRepo(private val context: Context, private val db: AppDb) {

    companion object {
        /** AI 生成卡池上限（不含 168 张内置卡）。每天约 3 张，够两个多月；超限按未复习优先淘汰。 */
        const val AI_CARD_CAP = 200
    }

    /** 首次启动把 assets/cards.json 灌进库；已有数据则跳过。 */
    suspend fun seedIfEmpty(): Int {
        if (db.cards().countAll() > 0) return 0
        val text = context.assets.open("cards.json").bufferedReader().use { it.readText() }
        val arr = JSONObject(text).getJSONArray("cards")
        val cards = ArrayList<Card>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            cards.add(
                Card(
                    type = o.optString("type", "word"),
                    front = o.optString("front"),
                    back = o.optString("back"),
                    example = o.optString("example"),
                    exampleCn = o.optString("example_cn"),
                    tags = o.optJSONArray("tags")?.let { a ->
                        (0 until a.length()).joinToString(",") { a.getString(it) }
                    } ?: "",
                )
            )
        }
        db.cards().insertAll(cards)
        return cards.size
    }

    /** 到期卡优先，再补新卡（受每日上限与队列总长约束）。 */
    suspend fun buildQueue(dailyNew: Int, limit: Int = 60): List<Card> {
        val now = System.currentTimeMillis()
        val due = db.cards().due(now, limit)
        val dailyLeft = (dailyNew - countStudiedToday()).coerceAtLeast(0)
        val slots = QueuePolicy.freshSlots(limit, due.size, dailyLeft)
        // 新卡名额先给「还没复习过的练习反馈卡」，剩下的再随机补内置/生成的词卡。
        // 反馈卡入库时 reps=0，和内置词卡一样算新卡：不单独排前面的话，
        // 刚做完写作收到的几条批注会被 168 张内置词卡按 RANDOM() 挤掉，永远轮不到。
        val feedback = db.cards().freshFeedback(QueuePolicy.FEEDBACK_FIRST_MAX)
        val split = QueuePolicy.freshSplit(slots, feedback.size)
        val fresh = feedback.take(split.feedback) + db.cards().fresh(split.random)
        return due + fresh
    }

    /** 错题专项：只刷听读判分与跟读产生的错题卡，不受每日新卡上限约束。 */
    suspend fun buildWrongQueue(limit: Int = 60): List<Card> =
        db.cards().wrongCards(System.currentTimeMillis(), limit)

    suspend fun wrongCount(): Int = db.cards().countWrong(System.currentTimeMillis())

    /** 错题本全量（含已到期与未到期），供趋势分析统计存量、遗忘与未重刷张数。 */
    suspend fun wrongBook(): List<Card> = db.cards().wrongBook()

    /** 今日已复习的卡片数（含错题、改正卡）。 */
    suspend fun reviewedToday(): Int {
        val start = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        return db.cards().countReviewedSince(start)
    }

    /** 今日已学的新卡数。 */
    private suspend fun countStudiedToday(): Int = db.cards().countFirstLearnedSince(startOfToday())

    /** 今天还能学几张新卡（受每日上限约束）。与 [buildQueue] 同口径：反馈卡每轮最多先占 10 张。 */
    suspend fun todayNewAvailable(dailyNew: Int): Int = QueuePolicy.newCardAvailable(
        dailyLeft = (dailyNew - countStudiedToday()).coerceAtLeast(0),
        feedbackPending = db.cards().countFreshFeedback(),
        otherNew = db.cards().countFreshOthers(),
    )

    /** 还没复习过的练习反馈卡张数：队列会先安排这些（见 [buildQueue]）。 */
    suspend fun feedbackPending(): Int = db.cards().countFreshFeedback()

    private fun startOfToday(): Long = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, 0)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis

    suspend fun grade(card: Card, grade: Grade) {
        // 队列里的 Card 是评级前快照：同一张卡本轮被 Again 重问后再评一次时，
        // 必须用库里的最新状态算 SM-2，否则第一次的 lapses/ease/间隔会被旧值覆盖。
        val fresh = db.cards().byId(card.id) ?: card
        val s = Sm2.next(fresh, grade)
        db.cards().updateSrs(card.id, s.ease, s.intervalDays, s.reps, s.lapses, s.dueAt, System.currentTimeMillis())
    }

    /** 手动删除一张卡；返回后调用方负责把它从当前学习队列移除。 */
    suspend fun delete(card: Card) = db.cards().deleteById(card.id)

    /**
     * 把一次练习的批注转成复习卡，不调用模型；去重后写入 AI 池，超上限自动淘汰。
     *
     * 返回新增/重复/无效的账目，供结果页说清楚「到底加了几张」——之前只返回新增数，
     * 调用方一律显示「已加入背诵」，全是重复时用户会以为反馈已进队列。
     */
    suspend fun addFixesAsCards(fixes: List<String>, module: String): IngestResult {
        if (fixes.isEmpty()) return IngestResult()
        // 跨会话判重：库里的正面也按 CardText.key 归一化，标点/空白不同的旧卡同样算重复。
        val plan = FeedbackIngest.planFixes(
            fixes,
            db.cards().allFronts().mapTo(HashSet()) { CardText.key(it) },
        )
        if (plan.accepted.isEmpty()) return plan.ingest
        db.cards().insertAll(plan.accepted.map {
            Card(
                type = "error",
                front = it.front,
                back = it.back,
                example = "",
                exampleCn = "",
                tags = "review,练习批注",
                source = "error",
            )
        })
        enforceGeneratedCap()
        enforceFeedbackCap()
        return plan.ingest
    }

    /** 逐句改法 → 改错卡（type=cloze, source=fix）：正面原句，背面改写与中文问题。 */
    suspend fun addSentenceFixesAsCards(
        items: List<Triple<String, String, String>>,
        module: String,
    ): IngestResult {
        if (items.isEmpty()) return IngestResult()
        // 「改写」和原句只差大小写/空白/句末标点时，卡片正反面相同：这属于内容不可用，
        // 在 FeedbackIngest 里与重复分开计数，结果页才能分清「没东西可加」和「已经加过了」。
        val plan = FeedbackIngest.planSentences(
            items,
            db.cards().allFronts().mapTo(HashSet()) { CardText.key(it) },
        )
        if (plan.accepted.isEmpty()) return plan.ingest
        db.cards().insertAll(plan.accepted.map {
            Card(
                type = "cloze",
                front = it.front,
                back = it.back,
                tags = "review,逐句改法",
                source = "fix",
            )
        })
        enforceFeedbackCap()
        return plan.ingest
    }

    /** AI 生成卡上限：超出时按「未复习优先、最旧优先」淘汰。 */
    private suspend fun enforceGeneratedCap() {
        val over = db.cards().countGenerated() - AI_CARD_CAP
        if (over > 0) db.cards().evictGenerated(over)
    }

    /** 反馈卡上限：只淘汰已复习过的（策略见 FeedbackCap），未复习的反馈一张不删。 */
    private suspend fun enforceFeedbackCap() {
        val victims = FeedbackCap.victims(db.cards().feedbackCards())
        if (victims.isNotEmpty()) db.cards().deleteByIds(victims)
    }

    /** 听读错题入库：题干做正面，正确答案 + 解析做背面；去重后与 AI 池共用上限。 */
    suspend fun addBankWrong(rows: List<BankWrong>, module: String): Int {
        if (rows.isEmpty()) return 0
        val seen = db.cards().allFronts().mapTo(HashSet()) { CardText.key(it) }
        val cards = rows.mapNotNull { row ->
            val head = CardText.front(row.prompt, CardText.BANK_MAX)
            if (head.isBlank()) return@mapNotNull null
            if (!seen.add(CardText.key(head))) return@mapNotNull null
            Card(
                type = "error",
                front = head,
                back = buildString {
                    append("正确答案：").append(row.correct)
                    if (row.explain.isNotBlank()) append("\n").append(row.explain)
                },
                example = "",
                exampleCn = "",
                tags = "bank," + if (module == "listening") "听力" else "阅读",
                source = "bank",
            )
        }
        if (cards.isEmpty()) return 0
        db.cards().insertAll(cards)
        enforceGeneratedCap()
        return cards.size
    }

    /** 阅读查词加入生词本：正面=单词，背面=音标+释义；重复添加返回 false。 */
    suspend fun addLookupWord(word: String, phonetic: String, translation: String): Boolean {
        val front = CardText.front(word, CardText.SHORT_MAX).lowercase()
        if (front.isBlank()) return false
        val key = CardText.key(front)
        if (db.cards().allFronts().any { CardText.key(it) == key }) return false
        val back = buildString {
            if (phonetic.isNotBlank()) append("/").append(phonetic).append("/\n")
            append(translation)
        }
        db.cards().insertAll(
            listOf(
                Card(
                    type = "vocab",
                    front = front,
                    back = back,
                    example = "",
                    exampleCn = "",
                    tags = "查词",
                    source = "lookup",
                )
            )
        )
        return true
    }

    /** 跟读漏读词入库：词做正面，说明做背面；去重后与 AI 池共用上限。 */
    suspend fun addShadowWords(words: List<String>): Int {
        val unique = words.map { CardText.front(it, CardText.SHORT_MAX).lowercase() }
            .filter { it.isNotBlank() }
            .distinctBy { CardText.key(it) }
        val seen = db.cards().allFronts().mapTo(HashSet()) { CardText.key(it) }
        val cards = unique.mapNotNull { w ->
            if (!seen.add(CardText.key(w))) return@mapNotNull null
            Card(
                type = "error",
                front = w,
                back = "跟读中漏读或读错，注意发音与重音",
                example = "",
                exampleCn = "",
                tags = "shadow,跟读",
                source = "shadow",
            )
        }
        if (cards.isEmpty()) return 0
        db.cards().insertAll(cards)
        enforceGeneratedCap()
        return cards.size
    }

    /** AI 生词/搭配卡：正面=搭配，背面=中文释义，例句单独存；按 front 去重后进入 AI 池。 */
    suspend fun addVocabCards(items: List<com.txw.ielts.llm.VocabItem>): Int {
        if (items.isEmpty()) return 0
        // 已存在的同 front 卡：AI 生词卡以「本次（套用用户修改后）的释义/例句」为准更新，SRS 进度不动。
        val existing = db.cards().all().associateBy { CardText.key(it.front) }
        val seen = existing.keys.toMutableSet()
        val updates = ArrayList<Triple<Long, String, String>>()
        val cards = items.mapNotNull { v ->
            val front = CardText.front(v.phrase, CardText.SHORT_MAX).lowercase()
            if (front.isBlank()) return@mapNotNull null
            val key = CardText.key(front)
            val old = existing[key]
            if (old != null) {
                if (old.source == "ai_vocab") {
                    updates.add(Triple(old.id, v.meaning.trim(), v.example.trim()))
                }
                return@mapNotNull null
            }
            if (!seen.add(key)) return@mapNotNull null
            Card(
                type = "collocation",
                front = front,
                back = v.meaning.trim(),
                example = v.example.trim(),
                exampleCn = "",
                tags = "ai,生词卡",
                source = "ai_vocab",
            )
        }
        if (updates.isNotEmpty()) {
            updates.forEach { (id, back, example) -> db.cards().updateText(id, back, example) }
        }
        if (cards.isEmpty()) return 0
        db.cards().insertAll(cards)
        enforceGeneratedCap()
        return cards.size
    }

    suspend fun generatedStats(): Pair<Int, Int> = db.cards().countGenerated() to AI_CARD_CAP

    suspend fun stats(): Triple<Int, Int, Int> = Triple(
        db.cards().countDue(System.currentTimeMillis()),
        db.cards().countNew(),
        db.cards().countAll(),
    )
}
