package com.txw.ielts

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

/**
 * 16 kHz / 单声道 / PCM 16-bit WAV 录音器。
 * 直接产出 ASR 需要的裸 PCM，避免 MediaRecorder 出 AAC 后再解码。
 */
class WavRecorder(private val outFile: File) {

    companion object {
        const val SAMPLE_RATE = 16_000
    }

    private val minBuf = AudioRecord.getMinBufferSize(
        SAMPLE_RATE,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT
    ).coerceAtLeast(SAMPLE_RATE)

    private var record: AudioRecord? = null
    private var worker: Thread? = null
    @Volatile private var running = false
    var bytesWritten: Long = 0
        private set

    @SuppressLint("MissingPermission")
    fun start() {
        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuf * 2
        )
        check(rec.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord 初始化失败" }
        record = rec
        val raf = RandomAccessFile(outFile, "rw")
        raf.setLength(0)
        raf.write(ByteArray(44)) // WAV 头占位，停止时回填
        running = true
        bytesWritten = 0
        worker = thread(name = "wav-recorder") {
            val buf = ShortArray(minBuf)
            rec.startRecording()
            raf.use { f ->
                while (running) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n > 0) {
                        val bytes = ByteArray(n * 2)
                        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(buf, 0, n)
                        f.write(bytes)
                        bytesWritten += bytes.size
                    }
                }
                f.seek(0)
                f.write(wavHeader(bytesWritten))
            }
        }
    }

    fun stop() {
        running = false
        worker?.join(3_000)
        record?.runCatching {
            stop()
            release()
        }
        record = null
    }

    private fun wavHeader(dataLen: Long): ByteArray {
        val totalLen = dataLen + 36
        val byteRate = SAMPLE_RATE * 1 * 2
        return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(totalLen.toInt()); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
            putInt(SAMPLE_RATE); putInt(byteRate); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(dataLen.toInt())
        }.array()
    }
}
