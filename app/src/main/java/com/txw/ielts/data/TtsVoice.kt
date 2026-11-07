package com.txw.ielts.data

/**
 * TTS 音色保留规则（纯逻辑，便于单测）。
 *
 * 系统里装了好引擎（如 Kokoro）时，用户真正想要的是引擎当前选中的那个音色；
 * 代码里无脑 `setLanguage(Locale.US)` 会把引擎切回它自己的默认音色，听感和朗读页不一致。
 * 所以只在「当前不是英语」时才切语言，已经是英语就原样保留。
 */
object TtsVoice {

    /** 当前语音不是英语（或拿不到）时才需要切到 en-US。 */
    fun needsLanguageSwitch(currentLocaleTag: String?): Boolean {
        val tag = currentLocaleTag?.trim()?.lowercase().orEmpty()
        if (tag.isEmpty()) return true
        return !(tag == "en" || tag.startsWith("en-") || tag.startsWith("en_"))
    }

    /** 日志里用的短名：引擎里的 voice 名可能很长。 */
    fun shortName(voiceName: String?, engine: String? = null): String {
        val voice = voiceName?.trim().orEmpty()
        val eng = engine?.trim().orEmpty()
        return when {
            voice.isEmpty() && eng.isEmpty() -> "unknown"
            voice.isEmpty() -> eng
            eng.isEmpty() -> voice
            else -> "$eng/$voice"
        }
    }
}
