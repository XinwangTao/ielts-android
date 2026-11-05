package com.txw.ielts.data

/**
 * AI 生成听力套题的音频文件归属。
 *
 * 音频由 TTS 合成后写在私有目录 filesDir/bank，套题删除时只删了 ai_bank.json 条目，
 * wav 会一直残留（每套几分钟音频，长期用下来很占空间）。这里只认 wav 的文件名，
 * 且调用方只会拿文件名去拼私有目录，assets 内置音频与路径穿越都不会被误删。
 */
object BankAudio {
    /** 可从私有目录清理的音频文件名；空串或非 wav 返回 null。 */
    fun ownedWavName(audio: String): String? =
        audio.substringAfterLast('/')
            .takeIf { it.isNotBlank() && it.endsWith(".wav", ignoreCase = true) }
}
