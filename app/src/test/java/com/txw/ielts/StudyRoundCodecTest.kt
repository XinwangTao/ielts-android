package com.txw.ielts

import com.txw.ielts.data.Card
import com.txw.ielts.data.Grade
import com.txw.ielts.data.StudyRound
import com.txw.ielts.data.StudyRoundCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StudyRoundCodecTest {

    private val cards = listOf(
        Card(id = 1, front = "alpha", back = "A"),
        Card(id = 2, type = "cloze", front = "beta", back = "B"),
    )

    @Test
    fun `整轮快照往返一致`() {
        val round = StudyRound(queue = cards, index = 1, revealed = true, events = listOf(1L to Grade.GOOD))
        val back = StudyRoundCodec.decode(StudyRoundCodec.encode(round))!!
        assertEquals(2, back.queue.size)
        assertEquals("cloze", back.queue[1].type)
        assertEquals(1, back.index)
        assertTrue(back.revealed)
        assertEquals(listOf(1L to Grade.GOOD), back.events)
    }

    @Test
    fun `索引越界夹取`() {
        val raw = StudyRoundCodec.encode(StudyRound(queue = cards, index = 99))
        assertEquals(1, StudyRoundCodec.decode(raw)!!.index)
    }

    @Test
    fun `坏数据与空串返回 null`() {
        assertNull(StudyRoundCodec.decode(null))
        assertNull(StudyRoundCodec.decode(""))
        assertNull(StudyRoundCodec.decode("不是 JSON"))
        assertNull(StudyRoundCodec.decode("""{"q":[]}"""))
        // 未知评级被忽略，但卡片仍能恢复。
        val raw = """{"i":0,"q":[{"id":1,"front":"x"}],"e":[[1,"NOPE"]]}"""
        val r = StudyRoundCodec.decode(raw)!!
        assertEquals(1, r.queue.size)
        assertTrue(r.events.isEmpty())
    }
}
