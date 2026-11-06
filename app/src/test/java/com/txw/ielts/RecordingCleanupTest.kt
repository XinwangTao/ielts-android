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

    @Test
    fun `对练 turns 里的录音路径并入引用`() {
        val json = """[{"q":"Q","a":"A","p":"/f/partner_1.wav"},{"p":""},{"p":"/f/partner_2.wav"}]"""
        assertEquals(
            setOf("/f/partner_1.wav", "/f/partner_2.wav"),
            RecordingCleanup.partnerRefs(json),
        )
        // 坏 JSON / 空值按「没有引用」处理，不抛异常。
        assertEquals(emptySet<String>(), RecordingCleanup.partnerRefs("不是 JSON"))
        assertEquals(emptySet<String>(), RecordingCleanup.partnerRefs(null))
    }
}
