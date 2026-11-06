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
    private val micRefs = AtomicInteger(0)

    fun start(context: Context) {
        if (refs.incrementAndGet() != 1) return
        ensureService(context, mic = micRefs.get() > 0)
    }

    /**
     * 录音期间额外声明 microphone 类型：Android 9+ 普通后台 App 拿不到麦克风，
     * 切到后台后 AudioRecord 会读到静音；带 microphone 类型的前台服务才允许后台采集。
     */
    fun startMic(context: Context) {
        val micNow = micRefs.incrementAndGet()
        if (refs.incrementAndGet() == 1) {
            ensureService(context, mic = true)
        } else if (micNow == 1) {
            // 服务已在跑（比如评分后台保活）：再投递一次 intent，把前台类型补上 microphone。
            ensureService(context, mic = true)
        }
    }

    fun stop(context: Context) = release(context)

    fun stopMic(context: Context) {
        micRefs.updateAndGet { if (it > 0) it - 1 else 0 }
        release(context)
    }

    fun micActive(): Boolean = micRefs.get() > 0

    /**
     * 服务被系统单独回收（不是调用方 stop）时同步清零引用。
     * 否则 refs 会停在 >0，下一次 start() 以为服务还在、不再启动，保活从此失效。
     */
    internal fun onServiceDestroyed() {
        refs.set(0)
        micRefs.set(0)
    }

    private fun release(context: Context) {
        if (refs.decrementAndGet() > 0) return
        refs.set(0)
        // 录音还在就继续保活，不能因为评分结束把麦克风保护一起停掉。
        if (micRefs.get() > 0) return
        runCatching { context.stopService(Intent(context, GuardService::class.java)) }
    }

    private fun ensureService(context: Context, mic: Boolean) {
        runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, GuardService::class.java).putExtra("mic", mic),
            )
        }.onFailure {
            // 只撤销本次引用；直接置 0 会把另一个仍在跑的任务的保活一起算丢，
            // 它结束时 stop() 就会把服务停掉。
            refs.updateAndGet { if (it > 0) it - 1 else 0 }
        }
    }
}

class GuardService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable { stopSelf() }
    private var track: AudioTrack? = null
    private var screenWake: android.os.PowerManager.WakeLock? = null
    private var overlay: android.view.View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val mic = intent?.getBooleanExtra("mic", false) == true || Guard.micActive()
        // Android 14 起，从后台启动 microphone 类型前台服务会抛异常；录音由 UI 触发时
        // 正常都在前台，但口语对练的准备倒计时可能在用户切走后自动开录，必须兜住。
        try {
            startForegroundCompat(mic)
        } catch (e: Exception) {
            android.util.Log.w("Guard", "startForeground(mic=$mic) failed: ${e.message}")
            runCatching { startForegroundCompat(mic = false) }.onFailure {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        startSilence()
        addOverlay()
        // 屏幕熄灭时 ColorOS 仍会限制后台 App 的 CPU（实测 cgroup 已是 foreground，MNN 仍 0 token）。
        // 评分/AI 生成期间保持屏幕 dim 常亮，宁可多耗电也要把任务跑完。
        if (screenWake == null) {
            val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
            screenWake = pm.newWakeLock(
                android.os.PowerManager.SCREEN_DIM_WAKE_LOCK or
                    android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "ielts:guard-screen",
            ).apply {
                setReferenceCounted(false)
                acquire(MAX_HOLD_MS)
            }
        }
        // 兜底：调用方忘记 stop 时最多占用 MAX_HOLD_MS。
        // 为什么不是 15 分钟：后台冷加载实测可达 5 分钟，评分 fixes 为空还会重试一次，
        // 单次评分可能超过 15 分钟；兜底先到会在后半段撤掉保护。
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, MAX_HOLD_MS)
        // 不自动重启：进程被杀时引擎/录音已经不存在，系统把服务拉起来只会白占
        // 最多 30 分钟的屏幕唤醒锁；下次真正开始任务时调用方会重新 start。
        return START_NOT_STICKY
    }

    private fun startForegroundCompat(mic: Boolean) {
        // minSdk 26：通知渠道无需再判断版本。
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "后台任务保护", NotificationManager.IMPORTANCE_MIN)
        )
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("雅思随身练")
            .setContentText(if (mic) "录音中（后台保活）" else "评分 / AI 生成进行中")
            .setOngoing(true)
            .setShowWhen(false)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && mic) {
            // microphone 类型仅 API 30+ 存在；30 以下仍以 mediaPlayback 保活。
            startForeground(
                NOTIF_ID, n,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    /**
     * 1x1 透明悬浮窗：ColorOS 的厂商层按「App 是否有可见窗口」冻结，单靠前台服务不够。
     * 需要 SYSTEM_ALERT_WINDOW；未授权时静默跳过（测试可 `adb appops set ... SYSTEM_ALERT_WINDOW allow`）。
     */
    private fun addOverlay() {
        if (overlay != null) return
        if (!android.provider.Settings.canDrawOverlays(this)) return
        runCatching {
            val wm = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
            val v = android.view.View(this).apply {
                setBackgroundColor(android.graphics.Color.argb(1, 0, 0, 0))
            }
            val params = android.view.WindowManager.LayoutParams(
                1, 1,
                android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                android.graphics.PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = android.view.Gravity.TOP or android.view.Gravity.START
            }
            wm.addView(v, params)
            overlay = v
        }
    }

    private fun removeOverlay() {
        val v = overlay ?: return
        runCatching {
            (getSystemService(WINDOW_SERVICE) as android.view.WindowManager).removeView(v)
        }
        overlay = null
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
        removeOverlay()
        runCatching { screenWake?.release() }
        screenWake = null
        runCatching { track?.stop() }
        runCatching { track?.release() }
        track = null
        Guard.onServiceDestroyed()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "guard"
        private const val NOTIF_ID = 2002

        /** 单次任务最长保护时长：覆盖「冷加载 5 分钟 + 主生成 + 重试生成」的最坏情况。 */
        private const val MAX_HOLD_MS = 30 * 60_000L
    }
}
