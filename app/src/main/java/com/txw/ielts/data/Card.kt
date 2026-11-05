package com.txw.ielts.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

@Entity(tableName = "cards")
data class Card(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String = "word",          // word | collocation | topic | error
    val front: String = "",
    val back: String = "",
    val example: String = "",
    val exampleCn: String = "",
    val tags: String = "",              // 逗号分隔
    val source: String = "seed",        // seed | error
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

    @Query("SELECT COUNT(*) FROM cards WHERE dueAt > 0 AND dueAt <= :now")
    suspend fun countDue(now: Long): Int

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

    @Query("SELECT * FROM cards WHERE reps = 0 AND dueAt = 0 AND source NOT IN ('bank','shadow') ORDER BY RANDOM() LIMIT :limit")
    suspend fun fresh(limit: Int): List<Card>

    /** 错题专项：只听未学过（dueAt=0）或已到期的错题；学过且未到期的不再重复出现。 */
    @Query("SELECT * FROM cards WHERE source IN ('bank','shadow') AND (dueAt = 0 OR dueAt <= :now) ORDER BY dueAt ASC, createdAt DESC LIMIT :limit")
    suspend fun wrongCards(now: Long, limit: Int): List<Card>

    @Query("SELECT COUNT(*) FROM cards WHERE source IN ('bank','shadow') AND (dueAt = 0 OR dueAt <= :now)")
    suspend fun countWrong(now: Long): Int

    /** 可淘汰池：排除内置卡（seed）、练习批注改正卡（error）与逐句改法卡（fix）——改正卡还没练就被删等于反馈丢失。 */
    @Query("SELECT COUNT(*) FROM cards WHERE source NOT IN ('seed','error','fix')")
    suspend fun countGenerated(): Int

    @Query("SELECT COUNT(*) FROM cards WHERE front = :front")
    suspend fun countByFront(front: String): Int

    /** 淘汰：先删从未复习的，再删复习次数最少的；内置卡、练习批注与逐句改法卡永不删除。 */
    @Query("DELETE FROM cards WHERE id IN (SELECT id FROM cards WHERE source NOT IN ('seed','error','fix') ORDER BY reps ASC, createdAt ASC LIMIT :n)")
    suspend fun evictGenerated(n: Int)

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
