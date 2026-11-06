package com.txw.ielts

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 端侧 Whisper 转写封装（sherpa-onnx）。
 * 模型目录需包含：<name>-encoder.int8.onnx、<name>-decoder.int8.onnx、<name>-tokens.txt
 */
class WhisperAsr(
    private val context: Context,
    private val modelDir: File,
    private val prefix: String,          // 例如 "base.en" 或 "tiny.en"
    private val threads: Int = 4
) {
    private var recognizer: OfflineRecognizer? = null

    fun load(): Long {
        if (recognizer != null) return 0
        Guard.start(context)
        try {
        val t0 = System.currentTimeMillis()
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = WavRecorder.SAMPLE_RATE, featureDim = 80),
            modelConfig = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = File(modelDir, "$prefix-encoder.int8.onnx").absolutePath,
                    decoder = File(modelDir, "$prefix-decoder.int8.onnx").absolutePath,
                    language = "en",
                    task = "transcribe",
                ),
                tokens = File(modelDir, "$prefix-tokens.txt").absolutePath,
                numThreads = threads,
                debug = false,
                provider = "cpu",
            ),
            decodingMethod = "greedy_search",
        )
        // 注意：不要传 AssetManager，否则 sherpa 会去 assets 里找文件；
        // 给绝对路径时必须让 assetManager 为 null，否则直接 exit(255)。
        recognizer = OfflineRecognizer(config = config)
        return System.currentTimeMillis() - t0
        } finally {
            Guard.stop(context)
        }
    }

    /** 返回 (识别文本, 识别耗时毫秒) */
    fun transcribe(wav: File): Pair<String, Long> {
        // 转写也是长 CPU 任务：切后台时同样用媒体前台服务钉住进程。
        Guard.start(context)
        try {
            val rec = recognizer ?: error("模型未加载")
            val samples = readWavSamples(wav)
            val t0 = System.currentTimeMillis()
            val text = StringBuilder()
            // whisper 的离线解码按 30s 窗口工作，长音频整段送进去只会出第一段，
            // 这里按 28s 切片（0.5s 重叠，避免切断单词）后逐段识别再拼接。
            val chunk = 28 * WavRecorder.SAMPLE_RATE
            val overlap = WavRecorder.SAMPLE_RATE / 2
            var start = 0
            var index = 0
            while (start < samples.size) {
                val end = minOf(start + chunk, samples.size)
                val piece = samples.copyOfRange(start, end)
                val stream = rec.createStream()
                stream.acceptWaveform(piece, WavRecorder.SAMPLE_RATE)
                rec.decode(stream)
                val part = rec.getResult(stream).text.trim()
                stream.release()
                if (part.isNotBlank()) {
                    val merged = if (index == 0) part else mergeOverlap(text.toString(), part)
                    text.setLength(0)
                    text.append(merged)
                }
                Log.i("SPIKE", "chunk#$index ${start / WavRecorder.SAMPLE_RATE}s-${end / WavRecorder.SAMPLE_RATE}s chars=${part.length}")
                index++
                if (end == samples.size) break
                start = end - overlap
            }
            return text.toString() to (System.currentTimeMillis() - t0)
        } finally {
            Guard.stop(context)
        }
    }

    fun release() {
        recognizer?.release()
        recognizer = null
    }

    /** 切片有 0.5s 重叠，拼接时把上一段的尾部与下一段的头部按词对齐去重。 */
    private fun mergeOverlap(prev: String, next: String, maxWords: Int = 12): String {
        if (prev.isBlank()) return next
        val a = prev.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val b = next.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val limit = minOf(maxWords, a.size, b.size)
        for (k in limit downTo 2) {
            val tail = a.takeLast(k).joinToString(" ").lowercase().trim(',', '.', ' ')
            val head = b.take(k).joinToString(" ").lowercase().trim(',', '.', ' ')
            if (tail == head) return (a.dropLast(k) + b).joinToString(" ")
        }
        return "$prev $next"
    }

    private fun readWavSamples(file: File): FloatArray {        val raw = file.readBytes()
        require(raw.size > 44) { "音频文件为空：${file.name}" }
        val pcm = raw.copyOfRange(44, raw.size)
        val shorts = ShortArray(pcm.size / 2)
        ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
        val out = FloatArray(shorts.size)
        for (i in shorts.indices) out[i] = shorts[i] / 32768.0f
        Log.i("SPIKE", "audio samples=${out.size} duration_s=${"%.1f".format(out.size / WavRecorder.SAMPLE_RATE.toFloat())}")
        return out
    }
}
