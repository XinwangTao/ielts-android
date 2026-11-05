package com.txw.ielts.llm

import com.txw.ielts.data.Session
import org.json.JSONObject
import java.util.Locale

/**
 * 把最近 7 天的练习记录压成给本地模型看的摘要（AI 复盘输入）。
 *
 * 抽成纯函数是为了可测：写作/口语看 overall，听读没有 Band，只有 correct/total，
 * 直接取 overall 会得到 0.0，模型会误以为「阅读 0 分」。
 */
object ReviewSummary {

    private const val WEEK_MS = 7L * 86_400_000L

    fun build(
        sessions: List<Session>,
        targetBand: Float,
        weaknesses: List<Pair<String, Int>>,
        now: Long = System.currentTimeMillis(),
    ): String {
        val recent = sessions
            .filter { it.status == "done" && it.startedAt >= now - WEEK_MS }
            .sortedBy { it.startedAt }
            .takeLast(10)
        val lines = recent.map { s ->
            val o = runCatching { JSONObject(s.scoresJson) }.getOrNull()
            val score = when (s.module) {
                "reading", "listening" -> {
                    val label = if (s.module == "reading") "阅读" else "听力"
                    val correct = o?.optInt("correct", 0) ?: 0
                    val total = o?.optInt("total", 0) ?: 0
                    "$label $correct/$total"
                }
                else -> {
                    val band = o?.optDouble("overall", 0.0) ?: 0.0
                    if (band > 0) "Band " + String.format(Locale.US, "%.1f", band) else "未出分"
                }
            }
            val fixes = runCatching {
                JSONObject(s.fixesJson).optJSONArray("fixes")?.let { a ->
                    (0 until a.length()).map { a.get(it).toString() }.filter { it.isNotBlank() }.take(2)
                } ?: emptyList()
            }.getOrDefault(emptyList())
            "- ${s.module}: $score; fixes: ${fixes.joinToString("; ").ifBlank { "无" }}"
        }.ifEmpty { listOf("- 最近 7 天没有完成的练习") }
        val weak = weaknesses.take(5).joinToString(", ") { "${it.first} (${it.second})" }
        return buildString {
            append("Recent sessions (last 7 days):\n")
            append(lines.joinToString("\n"))
            append("\nTop weaknesses: ").append(weak.ifBlank { "暂无" })
            append("\nTarget Band: ").append(targetBand)
        }
    }
}
