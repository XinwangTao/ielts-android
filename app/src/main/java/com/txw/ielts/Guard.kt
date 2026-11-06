package com.txw.ielts

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicInteger

/**
 * 后台任务保护：用「媒体播放」前台服务 + 一段静音 AudioTrack 把进程钉在前台。
 *
 * 为什么需要：OPPO/ColorOS 在 App 窗口不可见（熄屏/切后台）时会把进程降到 background cgroup，
 * 即便有 dataSync 前台服务 + wake lock，native 推理仍会停在 0 CPU（docs/oppo-background-scoring-findings.md §九）。
 * 媒体播放型前台服务在 ColorOS 的优先级高于普通 FGS，配合真实音频轨道，通常能避免被冻结。
 *
 * 用引用计数：评分/AI 生成可能重叠，最后一个结束才停服务。
 */
object Guard {
    private val refs = AtomicInteger(0)

    fun start(context: Context) {
        if (refs.incrementAndGet() != 1) return
        runCatching {
            ContextCompat.startForegroundService(context, Intent(context, GuardService::class.java))
        }.onFailure {
            refs.set(0)
        }
    }

    fun stop(context: Context) {
        if (refs.decrementAndGet() > 0) return
        refs.set(0)
        runCatching { context.stopService(Intent(context, GuardService::class.java)) }
    }
}

class GuardService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable { stopSelf() }
    private var track: AudioTrack? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        startSilence()
        // 兜底：任何调用方忘记 stop 时，最多占用 15 分钟。
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, 15 * 60_000L)
        return START_STICKY
    }

    private fun startForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "后台任务保护", NotificationManager.IMPORTANCE_MIN)
            )
        }
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("雅思随身练")
            .setContentText("本地评分 / AI 生成进行中")
            .setOngoing(true)
            .setShowWhen(false)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    /** 1 秒静音 PCM 循环播放；只为一个真实的音频轨道，让系统按媒体任务对待本进程。 */
    private fun startSilence() {
        if (track != null) return
        runCatching {
            val t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(8000)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(8000 * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            t.write(ShortArray(8000), 0, 8000)
            t.setLoopPoints(0, 8000, -1)
            t.play()
            track = t
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(timeout)
        runCatching { track?.stop() }
        runCatching { track?.release() }
        track = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "guard"
        private const val NOTIF_ID = 2002
    }
}
