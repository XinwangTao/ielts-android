package com.txw.ielts.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

@Entity(tableName = "cards")
data class Card(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String = "word",          // word | collocation | topic | error | cloze | vocab（展示文案见 CardType）
    val front: String = "",
    val back: String = "",
    val example: String = "",
    val exampleCn: String = "",
    val tags: String = "",              // 逗号分隔
    val source: String = "seed",        // seed | error | fix | bank | shadow | lookup | ai_vocab
    val createdAt: Long = System.currentTimeMillis(),
    // SM-2 状态
    val ease: Double = 2.5,
    val intervalDays: Int = 0,
    val reps: Int = 0,
    val lapses: Int = 0,
    val dueAt: Long = 0L,               // 0 = 未学过的新卡
    val lastReviewed: Long = 0L,
)

@Dao
interface CardDao {
    @Query("SELECT COUNT(*) FROM cards")
    suspend fun countAll(): Int

    @Query("SELECT * FROM cards")
    suspend fun all(): List<Card>

    /**
     * 每日复习队列的到期卡张数。
     *
     * 口径必须与 [due] 完全一致：听读/跟读错题卡（source=bank/shadow）只进错题专项，
     * 不会出现在每日队列里。之前这里没排除它们，语料页和首页「今日任务」会把
     * 到期错题算成「待复习 N 张」，点进去队列却是空的（buildQueue 的 due 排除了错题）。
     */
    @Query("SELECT COUNT(*) FROM cards WHERE dueAt > 0 AND dueAt <= :now AND source NOT IN ('bank','shadow')")
    suspend fun countDue(now: Long): Int

    /** 新卡总数：含练习反馈卡（它们同样按新卡复习，只是在队列里优先安排）。 */
    @Query("SELECT COUNT(*) FROM cards WHERE reps = 0 AND dueAt = 0 AND source NOT IN ('bank','shadow')")
    suspend fun countNew(): Int

    @Query("SELECT COUNT(*) FROM cards WHERE reps >= 3")
    suspend fun countMastered(): Int

    @Query("SELECT COUNT(*) FROM cards WHERE reps = 1 AND lastReviewed >= :start")
    suspend fun countFirstLearnedSince(start: Long): Int

    @Query("SELECT COUNT(*) FROM cards WHERE lastReviewed >= :start")
    suspend fun countReviewedSince(start: Long): Int

    /** 每日队列不含错题卡（source=bank/shadow 只在错题专项出现）。 */
    @Query("SELECT * FROM cards WHERE dueAt > 0 AND dueAt <= :now AND source NOT IN ('bank','shadow') ORDER BY dueAt LIMIT :limit")
    suspend fun due(now: Long, limit: Int): List<Card>

    /**
     * 普通新卡（随机）。练习反馈卡（error/fix）单独取，见 [freshFeedback]：
     * 它们混在 RANDOM() 里就抢不到每日那十几个新卡名额。
     */
    @Query("SELECT * FROM cards WHERE reps = 0 AND dueAt = 0 AND source NOT IN ('bank','shadow','error','fix') ORDER BY RANDOM() LIMIT :limit")
    suspend fun fresh(limit: Int): List<Card>

    /** 未复习的练习反馈卡（批注/逐句改法），最近一次练习的排前面。 */
    @Query("SELECT * FROM cards WHERE reps = 0 AND dueAt = 0 AND source IN ('error','fix') ORDER BY createdAt DESC LIMIT :limit")
    suspend fun freshFeedback(limit: Int): List<Card>

    /** 还没复习过的练习反馈卡张数：语料页用它说明「本次有新收到的纠错待复现」。 */
    @Query("SELECT COUNT(*) FROM cards WHERE reps = 0 AND dueAt = 0 AND source IN ('error','fix')")
    suspend fun countFreshFeedback(): Int

    /** 普通新卡张数（不含错题与练习反馈卡），与 [fresh] 同口径。 */
    @Query("SELECT COUNT(*) FROM cards WHERE reps = 0 AND dueAt = 0 AND source NOT IN ('bank','shadow','error','fix')")
    suspend fun countFreshOthers(): Int

    /** 错题专项：只听未学过（dueAt=0）或已到期的错题；学过且未到期的不再重复出现。 */
    @Query("SELECT * FROM cards WHERE source IN ('bank','shadow') AND (dueAt = 0 OR dueAt <= :now) ORDER BY dueAt ASC, createdAt DESC LIMIT :limit")
    suspend fun wrongCards(now: Long, limit: Int): List<Card>

    @Query("SELECT COUNT(*) FROM cards WHERE source IN ('bank','shadow') AND (dueAt = 0 OR dueAt <= :now)")
    suspend fun countWrong(now: Long): Int

    /** 全部听读/跟读错题（不限是否到期）：错题本趋势看的是存量与遗忘次数。 */
    @Query("SELECT * FROM cards WHERE source IN ('bank','shadow')")
    suspend fun wrongBook(): List<Card>

    /** 可淘汰池：排除内置卡（seed）、练习批注改正卡（error）与逐句改法卡（fix）——改正卡还没练就被删等于反馈丢失。 */
    @Query("SELECT COUNT(*) FROM cards WHERE source NOT IN ('seed','error','fix')")
    suspend fun countGenerated(): Int

    /** 全部正面：AI 批注/错题入库时用 CardText.key 归一化后做跨会话判重（标点、空白差异也不重复建卡）。 */
    @Query("SELECT front FROM cards")
    suspend fun allFronts(): List<String>

    /** 淘汰：先删从未复习的，再删复习次数最少的；内置卡、练习批注与逐句改法卡永不删除。 */
    @Query("DELETE FROM cards WHERE id IN (SELECT id FROM cards WHERE source NOT IN ('seed','error','fix') ORDER BY reps ASC, createdAt ASC LIMIT :n)")
    suspend fun evictGenerated(n: Int)

    /** 反馈卡（练习批注 error + 逐句改法 fix）：数量上限单独管理，见 FeedbackCap。 */
    @Query("SELECT * FROM cards WHERE source IN ('error','fix')")
    suspend fun feedbackCards(): List<Card>

    @Query("DELETE FROM cards WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("SELECT type, COUNT(*) AS n FROM cards GROUP BY type")
    suspend fun countByType(): List<TypeCount>

    /** 用户手动删除单张卡（AI 生成串项、或不想要的卡）。 */
    @Query("DELETE FROM cards WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Insert
    suspend fun insertAll(cards: List<Card>)

    @Query("UPDATE cards SET ease=:ease, intervalDays=:interval, reps=:reps, lapses=:lapses, dueAt=:dueAt, lastReviewed=:last WHERE id=:id")
    suspend fun updateSrs(id: Long, ease: Double, interval: Int, reps: Int, lapses: Int, dueAt: Long, last: Long)
}

data class TypeCount(val type: String, val n: Int)
