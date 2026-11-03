package com.txw.ielts

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

/** 本地后台评分的完成/失败通知（图：ielts-local-llm_workflow 的「后台评分 + 通知」）。 */
object Notify {

    private const val CHANNEL = "score"
    private const val ID_DONE = 1001
    private const val ID_FAIL = 1002

    private fun manager(context: Context): NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager(context).createNotificationChannel(
                NotificationChannel(CHANNEL, "评分完成", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "本地模型评分完成的提醒" }
            )
        }
    }

    private fun openApp(context: Context, sessionId: Long): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            // 点击通知直达对应结果页，而不是回到首页再自己找。
            putExtra("result_session_id", sessionId)
        }
        return PendingIntent.getActivity(
            context, sessionId.toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun scoreDone(context: Context, overall: Double, sessionId: Long) {
        ensureChannel(context)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("本地评分完成")
            .setContentText("Overall Band %.1f，点开查看解析".format(overall))
            .setContentIntent(openApp(context, sessionId))
            .setAutoCancel(true)
            .build()
        runCatching { manager(context).notify(ID_DONE, n) }
    }

    fun scoreFailed(context: Context, reason: String, sessionId: Long = -1L) {
        ensureChannel(context)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("本地评分失败")
            .setContentText(reason.take(80))
            .setContentIntent(openApp(context, sessionId))
            .setAutoCancel(true)
            .build()
        runCatching { manager(context).notify(ID_FAIL, n) }
    }
}
