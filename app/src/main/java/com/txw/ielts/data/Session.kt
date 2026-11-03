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
)

@Dao
interface SessionDao {
    @Insert
    suspend fun insert(session: Session): Long

    @Query("SELECT * FROM sessions ORDER BY startedAt DESC")
    fun observeAll(): Flow<List<Session>>

    @Query("SELECT * FROM sessions ORDER BY startedAt DESC LIMIT :n")
    suspend fun recent(n: Int): List<Session>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun byId(id: Long): Session?

    @Query("UPDATE sessions SET transcript=:t, essayText=:e, scoresJson=:s, fixesJson=:f, polished=:p, status=:st WHERE id=:id")
    suspend fun updateResult(id: Long, t: String, e: String, s: String, f: String, p: String, st: String)
}

@Database(entities = [Session::class, Card::class], version = 2, exportSchema = false)
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

        fun get(context: Context): AppDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext, AppDb::class.java, "ielts.db"
            ).addMigrations(MIGRATION_1_2).build().also { instance = it }
        }
    }
}
