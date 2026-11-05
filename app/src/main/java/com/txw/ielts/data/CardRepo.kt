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

    /** 到期卡优先，再补新卡（受每日上限约束）。 */
    suspend fun buildQueue(dailyNew: Int, limit: Int = 60): List<Card> {
        val now = System.currentTimeMillis()
        val due = db.cards().due(now, limit)
        val fresh = db.cards().fresh((dailyNew - countStudiedToday()).coerceAtLeast(0))
        return due + fresh
    }

    /** 错题专项：只刷听读判分与跟读产生的错题卡，不受每日新卡上限约束。 */
    suspend fun buildWrongQueue(limit: Int = 60): List<Card> =
        db.cards().wrongCards(System.currentTimeMillis(), limit)

    suspend fun wrongCount(): Int = db.cards().countWrong(System.currentTimeMillis())

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

    /** 今天还能学几张新卡（受每日上限约束）。 */
    suspend fun todayNewAvailable(dailyNew: Int): Int {
        val left = (dailyNew - countStudiedToday()).coerceAtLeast(0)
        return minOf(db.cards().countNew(), left)
    }

    private fun startOfToday(): Long = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, 0)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis

    suspend fun grade(card: Card, grade: Grade) {
        val s = Sm2.next(card, grade)
        db.cards().updateSrs(card.id, s.ease, s.intervalDays, s.reps, s.lapses, s.dueAt, System.currentTimeMillis())
    }

    /** 手动删除一张卡；返回后调用方负责把它从当前学习队列移除。 */
    suspend fun delete(card: Card) = db.cards().deleteById(card.id)

    /** 把一次练习的批注转成复习卡，不调用模型；去重后写入 AI 池，超上限自动淘汰。 */
    suspend fun addFixesAsCards(fixes: List<String>, module: String): Int {
        if (fixes.isEmpty()) return 0
        val seen = HashSet<String>()
        val cards = fixes.mapNotNull { raw ->
            val text = raw.trim()
            val head = CardText.front(raw, CardText.FIX_MAX)
            if (head.isBlank()) return@mapNotNull null
            if (!seen.add(CardText.key(head))) return@mapNotNull null
            if (db.cards().countByFront(head) > 0) return@mapNotNull null
            Card(
                type = "error",
                front = head,
                back = text,
                example = "",
                exampleCn = "",
                tags = "review,练习批注",
                source = "error",
            )
        }
        if (cards.isEmpty()) return 0
        db.cards().insertAll(cards)
        enforceGeneratedCap()
        return cards.size
    }

    /** 逐句改法 → 改错卡（type=cloze, source=fix）：正面原句，背面改写与中文问题。 */
    suspend fun addSentenceFixesAsCards(items: List<Triple<String, String, String>>, module: String): Int {
        if (items.isEmpty()) return 0
        val cards = items.mapNotNull { (sentence, issue, better) ->
            if (sentence.isBlank() || better.isBlank()) return@mapNotNull null
            val front = "改错：" + CardText.front(sentence, 160)
            if (db.cards().countByFront(front) > 0) return@mapNotNull null
            Card(
                type = "cloze",
                front = front,
                back = better.trim() + if (issue.isNotBlank()) "\n\n（${issue.trim()}）" else "",
                tags = "review,逐句改法",
                source = "fix",
            )
        }
        if (cards.isEmpty()) return 0
        db.cards().insertAll(cards)
        return cards.size
    }

    /** AI 生成卡上限：超出时按「未复习优先、最旧优先」淘汰。 */
    private suspend fun enforceGeneratedCap() {
        val over = db.cards().countGenerated() - AI_CARD_CAP
        if (over > 0) db.cards().evictGenerated(over)
    }

    /** 听读错题入库：题干做正面，正确答案 + 解析做背面；去重后与 AI 池共用上限。 */
    suspend fun addBankWrong(rows: List<BankWrong>, module: String): Int {        if (rows.isEmpty()) return 0
        val seen = HashSet<String>()
        val cards = rows.mapNotNull { row ->
            val head = CardText.front(row.prompt, CardText.BANK_MAX)
            if (head.isBlank()) return@mapNotNull null
            if (!seen.add(CardText.key(head))) return@mapNotNull null
            if (db.cards().countByFront(head) > 0) return@mapNotNull null
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
        if (db.cards().countByFront(front) > 0) return false
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
        val cards = unique.mapNotNull { w ->
            if (db.cards().countByFront(w) > 0) return@mapNotNull null
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
        val seen = HashSet<String>()
        val cards = items.mapNotNull { v ->
            val front = CardText.front(v.phrase, CardText.SHORT_MAX).lowercase()
            if (front.isBlank()) return@mapNotNull null
            if (!seen.add(CardText.key(front))) return@mapNotNull null
            if (db.cards().countByFront(front) > 0) return@mapNotNull null
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
