package com.txw.ielts.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File

data class DictEntry(val word: String, val phonetic: String, val translation: String, val lemma: String)

/**
 * 离线词典（ECDICT，MIT 协议）：filesDir/dict/ecdict.db，缺失时尝试从
 * 外部私有目录（/sdcard/Android/data/<pkg>/files/dict/，adb 可直写）拷入。
 * 查询为单词精确匹配 + 简单词形还原（-ing/-ed/-ies/-s 降缀），零网络零额度。
 */
object DictionaryStore {

    fun dictFile(context: Context): File = File(File(context.filesDir, "dict").apply { mkdirs() }, "ecdict.db")

    fun isReady(context: Context): Boolean {
        val f = dictFile(context)
        if (f.exists() && f.length() > 10_000L) return true
        val ext = File(
            File(context.getExternalFilesDir(null), "dict").apply { mkdirs() },
            "ecdict.db",
        )
        if (ext.exists() && ext.length() > 10_000L) {
            return runCatching {
                ext.copyTo(f, overwrite = true)
                f.length() > 10_000L
            }.getOrDefault(false)
        }
        return false
    }

    fun lookup(context: Context, raw: String): DictEntry? {
        if (!isReady(context)) return null   // 触发外部目录 → 内部存储的一次性拷贝
        val f = dictFile(context)
        if (!f.exists()) return null
        val w = raw.trim().lowercase()
        if (w.isBlank()) return null
        return runCatching {
            val db = SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY)
            db.use {
                fun direct(word: String): DictEntry? =
                    db.rawQuery(
                        "SELECT phonetic, translation FROM dict WHERE word = ? LIMIT 1",
                        arrayOf(word),
                    ).use { c ->
                        if (c.moveToFirst()) DictEntry(w, c.getString(0) ?: "", c.getString(1) ?: "", word)
                        else null
                    }

                direct(w)?.let { return it }

                // 词形还原：consulting → consult / advise；studies → study 等
                val cands = buildList {
                    when {
                        w.endsWith("ies") -> add(w.dropLast(3) + "y")
                        w.endsWith("ied") -> add(w.dropLast(3) + "y")
                        w.endsWith("ing") -> { add(w.dropLast(3)); add(w.dropLast(3) + "e") }
                        w.endsWith("ed") -> { add(w.dropLast(1)); add(w.dropLast(2) + "e"); add(w.dropLast(2)) }
                        w.endsWith("es") -> add(w.dropLast(2))
                        w.endsWith("s") -> add(w.dropLast(1))
                    }
                }.distinct().filter { it.length >= 3 }
                for (c in cands) {
                    direct(c)?.let { hit ->
                        return DictEntry(w, hit.phonetic, hit.translation, c)
                    }
                }
                null
            }
        }.getOrNull()
    }
}
