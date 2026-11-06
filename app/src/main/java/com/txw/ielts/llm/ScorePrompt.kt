package com.txw.ielts.llm

/**
 * 评分类请求的 user 消息装配（纯逻辑，云端与本地共用）。
 *
 * 为什么要有：云端 `LlmClient.evaluateSpeaking` 里 `transcriptBody` 算好了却没用上，
 * 真正拼进提示词的是原始 `transcript`——ASR 复读时转写能到几万字，请求直接超时长/超量，
 * 用户看到的是「云端评分超时」，而本地路径因为用了预算反而正常：同一段回答在两条通道上
 * 表现不一致，且云端这条是纯粹的浪费。
 *
 * 把「留开头与结尾、砍中间」的装配规则收进纯函数，两条通道调同一份：
 * - 作文/报告/听力转写再长也一定在 [PromptBudget.SCORE_INPUT_TOKENS] 内；
 * - 字数、语速、填充词都按**原文**算，裁剪只影响模型看到的内容，不影响客观指标；
 * - 历史弱点提示只在有记录时追加，避免空段把模型带偏。
 */
object ScorePrompt {

    /** 口语转写里的填充词：与语速一起作为 P/FC 的文本外证据。 */
    private val FILLER = Regex("\\b(um+|uh+|er+m?|hmm+|ah+)\\b", RegexOption.IGNORE_CASE)

    private val WS = Regex("\\s+")

    fun wordCount(text: String): Int = text.split(WS).count { it.isNotBlank() }

    /** 语速；时长非法（0 或负）时返回 0，不编造一个数。 */
    fun wordsPerMinute(words: Int, seconds: Int): Int =
        if (seconds > 0) (words * 60.0 / seconds).toInt() else 0

    fun fillerCount(transcript: String): Int = FILLER.findAll(transcript).count()

    /** 学员历史弱点：让模型重点检查老毛病是否复发（个性化反馈闭环）。 */
    fun weaknessHint(weaknesses: List<String>): String =
        if (weaknesses.isEmpty()) "" else
            "\n\nThis student's recurring weaknesses recorded from past attempts: ${weaknesses.joinToString("; ")}. " +
                "Check specifically whether these problems appear in this attempt. If they do, list them FIRST in top_fixes " +
                "and state clearly whether the student is improving on them."

    /** 写作 Task 2：题目与作文都按预算裁，字数按原文算。 */
    fun writing(
        topic: String,
        essay: String,
        weaknesses: List<String> = emptyList(),
        maxTokens: Int = PromptBudget.SCORE_INPUT_TOKENS,
    ): String = buildString {
        append("Task: ${PromptBudget.clipText(topic, maxTokens)}")
        append("\nWord count: ${wordCount(essay)}")
        append("\n\nEssay:\n${PromptBudget.clipText(essay, maxTokens)}")
        append(weaknessHint(weaknesses))
    }

    /** 写作 Task 1：图表数据同样可能被整段粘贴，两个输入都要裁。 */
    fun writingTask1(
        chart: String,
        essay: String,
        maxTokens: Int = PromptBudget.SCORE_INPUT_TOKENS,
    ): String = buildString {
        append("Chart data:\n${PromptBudget.clipText(chart, maxTokens)}")
        append("\n\nWord count: ${wordCount(essay)}")
        append("\n\nReport:\n${PromptBudget.clipText(essay, maxTokens)}")
    }

    /**
     * 口语：转写只把裁过的正文发给模型，字数/语速/填充词仍取自原文。
     * 这三个指标是 Pronunciation/Fluency 唯一的客观证据，不能被裁剪影响。
     */
    fun speaking(
        part: String,
        question: String,
        transcript: String,
        seconds: Int,
        weaknesses: List<String> = emptyList(),
        maxTokens: Int = PromptBudget.SCORE_INPUT_TOKENS,
    ): String {
        val words = wordCount(transcript)
        val wpm = wordsPerMinute(words, seconds)
        val fillers = fillerCount(transcript)
        return buildString {
            append("Part: $part\nQuestion: $question\nSpeaking time: ${seconds}s\n")
            append("Word count: $words\nWords per minute: $wpm\nFiller words (um/uh/er): $fillers\n\n")
            append("Transcript (from offline ASR, punctuation unreliable):\n")
            append(PromptBudget.clipText(transcript, maxTokens))
            append(weaknessHint(weaknesses))
        }
    }
}
