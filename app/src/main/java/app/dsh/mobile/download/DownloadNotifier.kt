package app.dsh.mobile.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.graphics.drawable.Icon
import app.dsh.mobile.R

/**
 * 后台下载任务的进度通知公共实现（通道、进度条、展开样式、结果、取消动作）。
 *
 * 这是下载基座的第一层（见 [DownloadSession]）：三种下载场景 —— pet 资源、
 * 运行时、APK —— 通知长得一样、行为也该一样，不该各写一份再慢慢漂移。
 *
 * 已沉淀的经验（来自 `PetPackDownloadService` 的实测）：
 *  - 线路标注（经代理 / 直连）放**标题**，不放正文尾部（窄屏 `· 经代理` 会被整个截掉）；
 *  - 进度刷新 <800ms 且百分比未变时不刷（节流在 [DownloadSession] 里统一做）；
 *  - 完成通知必须 `setOngoing(false)` + `setAutoCancel(true)`，否则用户划不掉。
 */
object DownloadNotifier {

    /** 与 pet（0x9E01）、engine、agent、lan_web 的通知 ID 错开。 */
    const val ID_APK = 0x9E10
    const val ID_RUNTIME = 0x9E11

    const val CHANNEL_ID = "app_update"

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    ctx.getString(R.string.app_update_channel),
                    // LOW：下载是长任务，不该每 1% 响一次
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = ctx.getString(R.string.app_update_channel_desc)
                    setShowBadge(false)
                },
            )
        }
    }

    /** 标题上的线路标注；null（未知/未开始）时不加后缀。 */
    fun titleWithSource(ctx: Context, baseTitle: String, viaProxy: Boolean?): String = when (viaProxy) {
        null -> baseTitle
        true -> ctx.getString(R.string.app_update_title_proxy, baseTitle)
        false -> ctx.getString(R.string.app_update_title_direct, baseTitle)
    }

    fun progress(
        ctx: Context,
        id: Int,
        title: String,
        text: String,
        bigText: String? = null,
        pct: Int = 0,
        indeterminate: Boolean = true,
        click: PendingIntent? = null,
        cancel: PendingIntent? = null,
    ): Notification {
        val builder = Notification.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, pct.coerceIn(0, 100), indeterminate)
        if (click != null) builder.setContentIntent(click)
        if (cancel != null) {
            builder.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(ctx, android.R.drawable.ic_menu_close_clear_cancel),
                    ctx.getString(R.string.app_update_cancel),
                    cancel,
                ).build(),
            )
        }
        if (!bigText.isNullOrBlank()) {
            builder.setStyle(Notification.BigTextStyle().bigText(bigText))
        }
        return builder.build()
    }

    fun result(
        ctx: Context,
        id: Int,
        title: String,
        text: String,
        click: PendingIntent? = null,
    ): Notification {
        val builder = Notification.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(false)
            .setAutoCancel(true)
        if (click != null) builder.setContentIntent(click)
        return builder.build()
    }

    fun post(ctx: Context, id: Int, notification: Notification) {
        runCatching {
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(id, notification)
        }
    }

    fun cancel(ctx: Context, id: Int) {
        runCatching {
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(id)
        }
    }
}
