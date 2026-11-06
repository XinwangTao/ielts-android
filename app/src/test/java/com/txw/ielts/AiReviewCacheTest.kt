package com.txw.ielts

import com.txw.ielts.llm.AiReview
import com.txw.ielts.llm.AiReviewCache
import com.txw.ielts.llm.ReviewTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AI 复盘缓存：持久化格式、输入指纹、过期与「新增练习」判定。
 * 这些纯函数决定冷启动时直接展示旧复盘，还是提示用户重新跑一次 30-60 秒的端侧推理。
 */
class AiReviewCacheTest {

    private val now = 1_800_000_000_000L
    private val hour = 3_600_000L
    private val day = 24L * hour

    private val review = AiReview(
        summary = "最近写作 6.0，TR 只有 4.0-5.0 是最弱项。",
        tasks = listOf(
            ReviewTask("写一篇 Task 2", "TR 拖分", "限时 40 分钟写满 270 词"),
            ReviewTask("改 5 句主谓一致", "高频错误", "逐句改正并朗读"),
        ),
        encouragement = "42 天足够补上写作。",
    )

    private fun cache(
        createdAt: Long = now - 60_000L,
        sessions: Int = 3,
        fingerprint: String = AiReviewCache.fingerprint("summary-A"),
    ) = AiReviewCache(review, createdAt, fingerprint, sessions)

    @Test
    fun `JSON 往返保留任务与元数据`() {
        val restored = AiReviewCache.fromJson(cache().toJson())
        assertEquals(cache(), restored)
    }

    @Test
    fun `整段退原文的复盘也能缓存`() {
        val rawOnly = AiReview("", emptyList(), "", raw = "模型只回了纯文本：先补 Task 2 的字数。")
        val restored = AiReviewCache.fromJson(AiReviewCache(rawOnly, now, "fp", 0).toJson())
        assertEquals(rawOnly, restored!!.review)
    }

    @Test
    fun `坏 JSON 或缺少元数据返回 null`() {
        assertNull(AiReviewCache.fromJson(null))
        assertNull(AiReviewCache.fromJson(""))
        assertNull(AiReviewCache.fromJson("not json"))
        // 没有指纹无法判断输入是否变化
        assertNull(AiReviewCache.fromJson("""{"created_at":123,"summary":"x"}"""))
        // 没有时间无法判断是否过期
        assertNull(AiReviewCache.fromJson("""{"fingerprint":"abc","summary":"x"}"""))
    }

    @Test
    fun `空复盘不落盘`() {
        val empty = AiReviewCache(AiReview("", emptyList(), ""), now, "fp", 0)
        assertNull(AiReviewCache.fromJson(empty.toJson()))
    }

    @Test
    fun `输入指纹忽略首尾空白且区分内容`() {
        assertEquals(AiReviewCache.fingerprint("  abc  "), AiReviewCache.fingerprint("abc"))
        assertNotEquals(AiReviewCache.fingerprint("abc"), AiReviewCache.fingerprint("abd"))
        assertEquals(16, AiReviewCache.fingerprint("abc").length)
    }

    @Test
    fun `输入没变且在有效期内可复用`() {
        val c = cache()
        assertTrue(c.isReusable(c.fingerprint, now))
        assertFalse(c.isReusable("other", now))
        // 正好到达 24 小时即视为过期：隔天再显示旧建议容易误导。
        assertFalse(c.isReusable(c.fingerprint, now + AiReviewCache.DEFAULT_TTL_MS))
    }

    @Test
    fun `新增练习时提示多出几条`() {
        // 新增练习会改变复盘摘要，因此指纹也会变；条数只用来把「多了几条」说清楚。
        val hint = cache(sessions = 3).hint("changed-fingerprint", currentSessionsCount = 5, now = now)
        assertFalse(hint.reusable)
        assertEquals("1 分钟前生成", hint.ageLabel)
        assertTrue(hint.note, hint.note.contains("2 条新练习"))
    }

    @Test
    fun `练习数没变但输入变了时提示目标或日期变更`() {
        val hint = cache().hint("changed-fingerprint", currentSessionsCount = 3, now = now)
        assertFalse(hint.reusable)
        assertTrue(hint.note, hint.note.contains("目标或考试日期"))
    }

    @Test
    fun `超过 24 小时提示重新生成`() {
        val hint = cache(createdAt = now - 25 * hour).hint(cache().fingerprint, 3, now)
        assertFalse(hint.reusable)
        assertTrue(hint.note, hint.note.contains("超过 24 小时"))
    }

    @Test
    fun `数据没变时文案说明可直接使用`() {
        val c = cache()
        val hint = c.hint(c.fingerprint, currentSessionsCount = 3, now = now)
        assertTrue(hint.reusable)
        assertTrue(hint.note, hint.note.contains("没变"))
    }

    @Test
    fun `生成时间标签按分钟小时天分档`() {
        assertEquals("刚刚生成", cache(createdAt = now).ageLabel(now))
        assertEquals("30 分钟前生成", cache(createdAt = now - 30 * 60_000L).ageLabel(now))
        assertEquals("3 小时前生成", cache(createdAt = now - 3 * hour).ageLabel(now))
        assertEquals("2 天前生成", cache(createdAt = now - 2 * day).ageLabel(now))
        // 系统时间被往回调时不出现负数
        assertEquals("刚刚生成", cache(createdAt = now + hour).ageLabel(now))
    }
}
