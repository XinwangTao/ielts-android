package com.txw.ielts

import com.txw.ielts.data.BackupFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupFilesTest {

    private val files = (1..8).map { i -> "/b/backup_$i.json" to i.toLong() }

    @Test
    fun `保留最近 5 个，删除更早的 3 个`() {
        val del = BackupFiles.selectDeletionCandidates(files)
        assertEquals(setOf("/b/backup_1.json", "/b/backup_2.json", "/b/backup_3.json"), del.toSet())
    }

    @Test
    fun `数量不足时不删`() {
        assertTrue(BackupFiles.selectDeletionCandidates(files.take(5)).isEmpty())
        assertTrue(BackupFiles.selectDeletionCandidates(emptyList()).isEmpty())
    }

    @Test
    fun `keep 为 0 表示全删，负数同 0`() {
        assertEquals(8, BackupFiles.selectDeletionCandidates(files, 0).size)
        assertEquals(8, BackupFiles.selectDeletionCandidates(files, -1).size)
    }

    @Test
    fun `撞名时追加序号`() {
        assertEquals("a.json", BackupFiles.uniqueName(emptySet(), "a.json"))
        assertEquals("a_2.json", BackupFiles.uniqueName(setOf("a.json"), "a.json"))
        assertEquals("a_3.json", BackupFiles.uniqueName(setOf("a.json", "a_2.json"), "a.json"))
        // 没有扩展名也能处理。
        assertEquals("backup_2", BackupFiles.uniqueName(setOf("backup"), "backup"))
    }
}
