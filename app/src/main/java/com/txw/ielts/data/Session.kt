package com.txw.ielts.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/** 一次练习记录（写作或口语）。音频先落盘，评分失败时 status=pending_eval 可重试。 */
@Entity(tableName = "sessions")
data class Session(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val module: String = "writing",
    val topic: String = "",
    val startedAt: Long = System.currentTimeMillis(),
    val durationSec: Int = 0,
    val audioPath: String? = null,
    val transcript: String = "",
    val essayText: String = "",
    val scoresJson: String = "",
    val fixesJson: String = "",
    val polished: String = "",
    val status: String = "done",
    /**
     * 题型："task1" / "task2"。旧数据（v2 及之前）为空串，读时回退到猜题。
     * 此前题型不入库，Ray 页/评分/重试全靠 `guessTask(topic)` 正则猜和
     * scoresJson 是否含 "TA" 反推——猜错就把 Task 1 图表作文按 Task 2 议论文评分。
     */
    val task: String = "",
)

@Dao
interface SessionDao {
    @Insert
    suspend fun insert(session: Session): Long

    @Query("SELECT * FROM sessions ORDER BY startedAt DESC")
    fun observeAll(): Flow<List<Session>>

    @Query("SELECT * FROM sessions ORDER BY startedAt DESC LIMIT :n")
    suspend fun recent(n: Int): List<Session>

    /** 仍被会话引用的录音路径（DISTINCT）：清理时不必把整行 Session 拉进内存，也不会被条数截断。 */
    @Query("SELECT DISTINCT audioPath FROM sessions WHERE audioPath IS NOT NULL AND audioPath <> ''")
    suspend fun audioPaths(): List<String>

    /** 导入预览去重用：与 BackupRestore.sessionKey 同格式（以  分隔）。 */
    @Query("SELECT module || char(1) || topic || char(1) || startedAt FROM sessions")
    suspend fun dedupeKeys(): List<String>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun byId(id: Long): Session?

    @Query("UPDATE sessions SET transcript=:t, essayText=:e, scoresJson=:s, fixesJson=:f, polished=:p, status=:st WHERE id=:id")
    suspend fun updateResult(id: Long, t: String, e: String, s: String, f: String, p: String, st: String)

    /**
     * 只在仍是 pending_local 时才回写失败：迟到的失败任务不能把另一个 job 已写好的分数/批改清空。
     * @return 实际更新的行数（0 表示记录已不是待评分状态，应放弃覆盖）。
     */
    @Query("UPDATE sessions SET transcript=:t, essayText=:e, scoresJson=:s, fixesJson=:f, polished=:p, status=:st WHERE id=:id AND status='pending_local'")
    suspend fun updateResultIfPending(id: Long, t: String, e: String, s: String, f: String, p: String, st: String): Int
}

@Database(entities = [Session::class, Card::class], version = 4, exportSchema = true)
abstract class AppDb : RoomDatabase() {
    abstract fun sessions(): SessionDao
    abstract fun cards(): CardDao

    companion object {
        @Volatile private var instance: AppDb? = null

        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `cards` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`type` TEXT NOT NULL, `front` TEXT NOT NULL, `back` TEXT NOT NULL, " +
                        "`example` TEXT NOT NULL, `exampleCn` TEXT NOT NULL, `tags` TEXT NOT NULL, " +
                        "`source` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `ease` REAL NOT NULL, " +
                        "`intervalDays` INTEGER NOT NULL, `reps` INTEGER NOT NULL, " +
                        "`lapses` INTEGER NOT NULL, `dueAt` INTEGER NOT NULL, `lastReviewed` INTEGER NOT NULL)"
                )
            }
        }

        private val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `sessions` ADD COLUMN `task` TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `cards` ADD COLUMN `firstLearnedAt` INTEGER NOT NULL DEFAULT 0")
                // 旧数据拿不到真正的首次学习时间：有复习记录的卡用 lastReviewed 近似。
                // 迁移后的第一个自然日可能把「昨天学过」的卡仍算成今天，之后计数才完全准确。
                db.execSQL("UPDATE `cards` SET `firstLearnedAt` = `lastReviewed` WHERE `reps` > 0 AND `lastReviewed` > 0")
            }
        }

        fun get(context: Context): AppDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext, AppDb::class.java, "ielts.db"
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build().also { instance = it }
        }
    }
}
