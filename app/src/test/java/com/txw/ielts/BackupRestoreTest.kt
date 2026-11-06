package com.txw.ielts

import com.txw.ielts.data.BackupRestore
import com.txw.ielts.data.Card
import com.txw.ielts.data.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BackupRestoreTest {

    private val export = """
        {
          "version": 1,
          "sessions": [
            {"module":"writing","topic":"T1","startedAt":100,"status":"done","task":"task2",
             "transcript":"","essayText":"essay","scoresJson":"{}","audioPath":"/f/a.wav"},
            {"module":"","topic":"坏条目"},
            {"topic":"缺 module"}
          ],
          "cards": [
            {"type":"vocab","front":"practical value","back":"实用价值"},
            {"front":""},
            "不是对象"
          ],
          "aiSets": [{"id":"ai-1"}],
          "settings": {"targetBand": 6.5}
        }
    """.trimIndent()

    @Test
    fun `解析并跳过坏条目`() {
        val p = BackupRestore.parse(export)
        assertEquals(1, p.version)
        assertEquals(1, p.sessions.size)
        assertEquals(1, p.cards.size)
        assertEquals(2, p.skippedSessions)
        assertEquals(2, p.skippedCards)
        assertEquals("writing", p.sessions[0].module)
        assertEquals("task2", p.sessions[0].task)
        // 音频不随备份走：路径必须清空，避免结果页出现「点播放没反应」。
        assertNull(p.sessions[0].audioPath)
        assertEquals(0L, p.sessions[0].id)
        assertEquals(0L, p.cards[0].id)
        assertEquals(1, p.aiSets?.length())
        assertEquals(6.5, p.settings?.optDouble("targetBand") ?: 0.0, 0.0)
    }

    @Test
    fun `缺 status 默认 done`() {
        val p = BackupRestore.parse("""{"version":1,"sessions":[{"module":"speaking","topic":"P2","startedAt":1}]}""")
        assertEquals("done", p.sessions.single().status)
    }

    @Test
    fun `版本过高拒绝`() {
        try {
            BackupRestore.parse("""{"version":2}""")
            fail("应该拒绝")
        } catch (e: BackupRestore.InvalidBackup) {
            assertTrue(e.message!!.contains("版本"))
        }
    }

    @Test
    fun `非 JSON 与空文件拒绝`() {
        for (bad in listOf("", "   ", "不是 JSON")) {
            try {
                BackupRestore.parse(bad)
                fail("应该拒绝：$bad")
            } catch (e: BackupRestore.InvalidBackup) {
                // ok
            }
        }
    }

    @Test
    fun `预演去掉与本机重复的条目`() {
        val plan = BackupRestore.parse(export)
        val kept = BackupRestore.keepNew(
            plan,
            existingSessionKeys = setOf(BackupRestore.sessionKey(Session(module = "writing", topic = "T1", startedAt = 100))),
            existingCardKeys = setOf(BackupRestore.cardKey(Card(type = "vocab", front = "Practical Value"))),
        )
        assertTrue(kept.sessions.isEmpty())
        assertTrue(kept.cards.isEmpty())
        // 没被过滤的项仍然保留。
        val kept2 = BackupRestore.keepNew(plan, emptySet(), emptySet())
        assertEquals(1, kept2.sessions.size)
        assertEquals(1, kept2.cards.size)
    }
}
