package com.txw.ielts

import com.txw.ielts.data.TtsVoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsVoiceTest {

    @Test
    fun `英语语音不再切换语言`() {
        assertFalse(TtsVoice.needsLanguageSwitch("en-US"))
        assertFalse(TtsVoice.needsLanguageSwitch("en-GB"))
        assertFalse(TtsVoice.needsLanguageSwitch("EN"))
        assertFalse(TtsVoice.needsLanguageSwitch("en_us"))
    }

    @Test
    fun `非英语或拿不到时切语言`() {
        assertTrue(TtsVoice.needsLanguageSwitch(null))
        assertTrue(TtsVoice.needsLanguageSwitch(""))
        assertTrue(TtsVoice.needsLanguageSwitch("zh-CN"))
        assertTrue(TtsVoice.needsLanguageSwitch("english"))  // 非法 tag 视为未知
    }

    @Test
    fun `短名拼接`() {
        assertEquals("unknown", TtsVoice.shortName(null, null))
        assertEquals("kokoro", TtsVoice.shortName("", "kokoro"))
        assertEquals("voice1", TtsVoice.shortName("voice1", ""))
        assertEquals("kokoro/voice1", TtsVoice.shortName("voice1", "kokoro"))
    }
}
