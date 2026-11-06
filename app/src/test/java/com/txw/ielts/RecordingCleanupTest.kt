package com.txw.ielts

import com.txw.ielts.data.RecordingCleanup
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingCleanupTest {

    private val now = 1_700_000_000_000L
    private val day = 86_400_000L

    @Test
    fun `旧且无引用才删`() {
        val files = listOf(
            "/f/a.wav" to now - 8 * day,   // 旧、无引用 → 删
            "/f/b.wav" to now - 1 * day,   // 新、无引用 → 留
            "/f/c.wav" to now - 30 * day,  // 旧、有引用 → 留
        )
        assertEquals(
            listOf("/f/a.wav"),
            RecordingCleanup.pickDeletable(files, setOf("/f/c.wav"), now, RecordingCleanup.KEEP_STARTUP_MS),
        )
    }

    @Test
    fun `手动清理保留 1 天内的新录音`() {
        val files = listOf(
            "/f/new.wav" to now - 3_600_000L,
            "/f/old.wav" to now - 25 * 3_600_000L,
        )
        assertEquals(
            listOf("/f/old.wav"),
            RecordingCleanup.pickDeletable(files, emptySet(), now, RecordingCleanup.KEEP_MANUAL_MS),
        )
    }

    @Test
    fun `全部被引用时一个都不删`() {
        val files = listOf("/f/a.wav" to now - 100 * day, "/f/b.wav" to now - 100 * day)
        assertEquals(
            emptyList<String>(),
            RecordingCleanup.pickDeletable(files, setOf("/f/a.wav", "/f/b.wav"), now, RecordingCleanup.KEEP_MANUAL_MS),
        )
    }

    @Test
    fun `恰好等于保留期边界时不删`() {
        val files = listOf("/f/edge.wav" to now - RecordingCleanup.KEEP_MANUAL_MS)
        assertEquals(
            emptyList<String>(),
            RecordingCleanup.pickDeletable(files, emptySet(), now, RecordingCleanup.KEEP_MANUAL_MS),
        )
    }
}
