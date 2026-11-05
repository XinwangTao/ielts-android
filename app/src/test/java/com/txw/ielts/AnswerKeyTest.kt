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
    fun `撇号保留 其余标点变空格`() {
        assertEquals("don t stop", AnswerKey.normalize("Don’t-stop."))
        assertEquals("it's", AnswerKey.normalize("it's"))
    }
}
