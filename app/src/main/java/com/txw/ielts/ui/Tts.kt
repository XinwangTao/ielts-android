package com.txw.ielts.ui

import android.speech.tts.TextToSpeech
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.txw.ielts.data.TtsVoice
import java.util.Locale

/** 系统 TTS；引擎缺失时 available=false，界面自动隐藏朗读按钮。 */
@Composable
fun rememberTts(): Pair<Boolean, (String) -> Unit> {
    val context = LocalContext.current
    var engine by remember { mutableStateOf<TextToSpeech?>(null) }
    var available by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        var instance: TextToSpeech? = null
        instance = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                // 已经是英语语音就别动它：用户/引擎选好的音色（如 Kokoro）比默认音色好得多
                val current = runCatching { instance?.voice?.locale?.toLanguageTag() }.getOrNull()
                val lang = if (TtsVoice.needsLanguageSwitch(current)) {
                    instance?.setLanguage(Locale.US)
                } else {
                    TextToSpeech.LANG_AVAILABLE
                }
                available = lang != TextToSpeech.LANG_MISSING_DATA && lang != TextToSpeech.LANG_NOT_SUPPORTED
                engine = instance
            } else {
                available = false
            }
        }
        onDispose {
            instance?.stop()
            instance?.shutdown()
        }
    }

    val speak: (String) -> Unit = { text ->
        if (available && text.isNotBlank()) {
            engine?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "card")
        }
    }
    return available to speak
}
