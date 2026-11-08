package app.dsh.mobile

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

/**
 * 任务完成通知（v1.6.25）。
 *
 * 只在「用户没被带回会话」时使用 —— [TaskReport] 已经判定过他看不见。
 * 所以这条通知的语气是「有结果了，来点一下」，不是「任务成功」。
 *
 * 通道单独开，不复用下载通道：用户关掉下载通知时不该连带关掉这个。
 */
object TaskReportNotifier {

    const val CHANNEL_ID = "task_report"
    const val ID = 0x9E20

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    ctx.getString(R.string.task_report_channel),
                    // DEFAULT：这是「有事找你」，不该像下载进度那样安静。
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = ctx.getString(R.string.task_report_channel_desc)
                    setShowBadge(true)
                },
            )
        }
    }

    /** 发一条完成通知。返回是否真的发出去了。 */
    fun post(ctx: Context, body: TaskReport.Body): Boolean = runCatching {
        ensureChannel(ctx)
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return false

        // 点通知 = 回到 App 的会话界面。
        val open = Intent(ctx, Class.forName("app.dsh.mobile.MainActivity")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pi = PendingIntent.getActivity(
            ctx, ID, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val text = if (body.steps > 0) {
            ctx.getString(R.string.task_report_body_steps, body.summary, body.steps)
        } else {
            body.summary
        }

        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(ctx.getString(R.string.task_report_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            // 完成通知必须能划掉，否则会一直堆在通知栏。
            .setOngoing(false)
            .setAutoCancel(true)
            .build()
        nm.notify(ID, n)
        true
    }.getOrDefault(false)

    fun cancel(ctx: Context) {
        runCatching {
            ctx.getSystemService(NotificationManager::class.java)?.cancel(ID)
        }
    }
}