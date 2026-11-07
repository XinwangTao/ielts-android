package com.txw.ielts

import com.txw.ielts.data.AnswerKey
import org.junit.Assert.assertEquals
import org.junit.Test

/** 判分归一化：全角/半角、大小写、标点与空白差异都不能把正确答案判错。 */
class AnswerKeyTest {

    @Test
    fun `全角数字与字母折回半角`() {
        assertEquals("25", AnswerKey.normalize("２５"))
        assertEquals("abc", AnswerKey.normalize("ＡＢＣ"))
        assertEquals("b2", AnswerKey.normalize("Ｂ２"))
    }

    @Test
    fun `全角空格与中文标点折断后不影响判分`() {
        assertEquals("hello world", AnswerKey.normalize("hello\u3000world"))
        assertEquals("hello world", AnswerKey.normalize("hello，world！"))
    }

    @Test
    fun `大小写与多余空白归一`() {
        assertEquals("the united kingdom", AnswerKey.normalize("  The   United   Kingdom  "))
    }

    @Test
    fun `撇号与弯引号不参与判分`() {
        // 输入法打出的弯引号与直引号、以及干脆不打撇号，都应对同一个答案判对。
        assertEquals("dont stop", AnswerKey.normalize("Don’t-stop."))
        assertEquals(AnswerKey.normalize("don't"), AnswerKey.normalize("dont"))
    }

    @Test
    fun `千分位逗号不改变数字答案`() {
        assertEquals("1000", AnswerKey.normalize("1,000"))
        assertEquals("6 30", AnswerKey.normalize("6:30"))
    }

    @Test
    fun `重音字母按基本字母判分`() {
        assertEquals("cafe", AnswerKey.normalize("café"))
    }
}
