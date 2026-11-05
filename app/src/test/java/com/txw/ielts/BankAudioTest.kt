package com.txw.ielts

import com.txw.ielts.data.BankAudio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 删除 AI 套题时只清理私有目录下的 wav，不能误删 assets 内置音频或其它文件。 */
class BankAudioTest {

    @Test
    fun `裸文件名原样返回`() {
        assertEquals("ai-123.wav", BankAudio.ownedWavName("ai-123.wav"))
    }

    @Test
    fun `带目录时只取文件名`() {
        assertEquals("ai-123.wav", BankAudio.ownedWavName("bank/ai-123.wav"))
        assertEquals("x.wav", BankAudio.ownedWavName("../../bank/x.wav"))
    }

    @Test
    fun `非 wav 或空串不清理`() {
        assertNull(BankAudio.ownedWavName(""))
        assertNull(BankAudio.ownedWavName("bank/lesson.mp3"))
        assertNull(BankAudio.ownedWavName("bank/"))
    }
}
