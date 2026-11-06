package com.txw.ielts.llm

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * AI 复盘结果的本地快照。
 *
 * 端侧 4B 模型复盘一次要 30-60 秒；原来结果只存在 Compose 的 remember 里，
 * 旋转屏或进程被杀后整块丢失，用户只能再等一遍，也可能再花一次云端额度。
 * 这里把结果连同「输入指纹 + 练习条数 + 生成时间」写进 SharedPreferences：
 * - 冷启动直接显示上次复盘，不自动跑模型；
 * - 练习/目标/考试日期变了或超过 24 小时，界面提示「建议重新生成」；
 * - 输入没变时 [isReusable] 为真，调用方可跳过生成，省时间也省额度。
 */
data class AiReviewCache(
    val review: AiReview,
    val createdAt: Long,
    /** 生成时模型看到的复盘摘要（[fingerprint]），用来判断输入有没有变。 */
    val fingerprint: String,
    /** 生成时的全部练习条数：新增练习时文案能说清「多了几条」。 */
    val sessionsCount: Int,
) {
    fun ageMs(now: Long): Long = (now - createdAt).coerceAtLeast(0L)

    fun isReusable(currentFingerprint: String, now: Long, ttlMs: Long = DEFAULT_TTL_MS): Boolean =
        fingerprint == currentFingerprint && ageMs(now) < ttlMs

    /** 复盘卡顶部的一句话：生成多久了 + 这份结果还能不能直接用。 */
    fun hint(currentFingerprint: String, currentSessionsCount: Int, now: Long): ReviewCacheHint {
        val reusable = isReusable(currentFingerprint, now)
        val note = when {
            !reusable && ageMs(now) >= DEFAULT_TTL_MS -> "上次复盘已超过 24 小时，建议重新生成"
            !reusable && currentSessionsCount > sessionsCount ->
                "上次复盘后有 ${currentSessionsCount - sessionsCount} 条新练习，建议重新生成"
            !reusable -> "练习内容、目标或考试日期有更新，建议重新生成"
            else -> "练习数据没变，这份复盘仍然适用"
        }
        return ReviewCacheHint(ageLabel(now), note, reusable)
    }

    fun ageLabel(now: Long): String {
        val age = ageMs(now)
        return when {
            age < 60_000L -> "刚刚生成"
            age < 3_600_000L -> "${age / 60_000L} 分钟前生成"
            age < 86_400_000L -> "${age / 3_600_000L} 小时前生成"
            else -> "${age / 86_400_000L} 天前生成"
        }
    }

    fun toJson(): String = JSONObject().apply {
        put("created_at", createdAt)
        put("fingerprint", fingerprint)
        put("sessions", sessionsCount)
        put("summary", review.summary)
        put("encouragement", review.encouragement)
        // 只有整段退原文时才存 raw，避免结构化结果把模型原文再写一遍。
        if (review.summary.isBlank() && review.tasks.isEmpty() && review.encouragement.isBlank()) {
            put("raw", review.raw.take(MAX_RAW))
        }
        put("tasks", JSONArray().apply {
            review.tasks.forEach { t ->
                put(JSONObject().put("title", t.title).put("why", t.why).put("how", t.how))
            }
        })
    }.toString()

    companion object {
        const val DEFAULT_TTL_MS = 24L * 60 * 60 * 1000
        private const val MAX_RAW = 4000

        fun fromJson(raw: String?): AiReviewCache? {
            if (raw.isNullOrBlank()) return null
            val o = runCatching { JSONObject(raw) }.getOrNull() ?: return null
            val created = o.optLong("created_at", 0L)
            val fp = o.optString("fingerprint", "").trim()
            if (created <= 0L || fp.isBlank()) return null
            val tasks = o.optJSONArray("tasks")?.let { a ->
                (0 until a.length()).mapNotNull { i ->
                    runCatching {
                        val x = a.getJSONObject(i)
                        ReviewTask(
                            title = x.optString("title", "").trim(),
                            why = x.optString("why", "").trim(),
                            how = x.optString("how", "").trim(),
                        )
                    }.getOrNull()
                }
            }.orEmpty().filter { it.title.isNotBlank() }.take(5)
            val cache = AiReviewCache(
                review = AiReview(
                    summary = o.optString("summary", "").trim(),
                    tasks = tasks,
                    encouragement = o.optString("encouragement", "").trim(),
                    raw = o.optString("raw", "").trim(),
                ),
                createdAt = created,
                fingerprint = fp,
                sessionsCount = o.optInt("sessions", 0).coerceAtLeast(0),
            )
            return cache.takeIf { !it.review.isEmpty }
        }

        /** 输入指纹（SHA-256 前 16 位十六进制）：只比较「模型看到的内容有没有变」。 */
        fun fingerprint(summary: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(summary.trim().toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { b ->
                (b.toInt() and 0xff).toString(16).padStart(2, '0')
            }.take(16)
        }
    }
}

/** 复盘卡的辅助文案：生成时间 + 是否建议重做。 */
data class ReviewCacheHint(
    val ageLabel: String,
    val note: String,
    /** true = 输入没变且未过期，可以直接用这份结果，不必再跑模型。 */
    val reusable: Boolean,
)
