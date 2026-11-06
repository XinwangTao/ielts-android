package com.txw.ielts

import com.txw.ielts.data.ShadowProgressCodec
import com.txw.ielts.data.ShadowProgressState
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShadowProgressTest {

    @Test
    fun `编码后能原样解回`() {
        val state = ShadowProgressState(2, mapOf(0 to "hello", 1 to "world"))
        val back = ShadowProgressCodec.decode(ShadowProgressCodec.encode(state), maxIndex = 5)
        assertEquals(2, back!!.index)
        assertEquals(mapOf(0 to "hello", 1 to "world"), back.transcripts)
    }

    @Test
    fun `越界索引夹到合法范围`() {
        val obj = JSONObject().put("i", 99).put("t", JSONObject().put("0", "x"))
        assertEquals(3, ShadowProgressCodec.decode(obj, maxIndex = 3)!!.index)
        assertEquals(0, ShadowProgressCodec.decode(obj, maxIndex = -1)!!.index)
    }

    @Test
    fun `坏键忽略，空对象视为无进度`() {
        val obj = JSONObject().put("i", 1).put("t", JSONObject().put("abc", "x").put("1", "ok"))
        val back = ShadowProgressCodec.decode(obj, maxIndex = 5)!!
        assertEquals(mapOf(1 to "ok"), back.transcripts)
        assertNull(ShadowProgressCodec.decode(JSONObject(), maxIndex = 5))
        assertNull(ShadowProgressCodec.decode(null, maxIndex = 5))
    }
}
