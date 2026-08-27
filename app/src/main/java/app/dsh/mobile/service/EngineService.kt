package app.dsh.mobile.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import app.dsh.mobile.DshApp
import app.dsh.mobile.MainActivity
import app.dsh.mobile.R
import app.dsh.mobile.engine.EngineSupervisor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * 引擎前台服务。
 *
 * 类型选择 specialUse（Android 14+ 生效）：dataSync 类型有 6 小时系统限时，
 * 长任务会被强杀；specialUse 无此限制且 sideload 分发无需 Play 审核豁免。
 */
class EngineService : Service() {

    private var stateJob: Job? = null
    private val stateScope by lazy { CoroutineScope(Dispatchers.Main) }

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 通知栏「退出」按钮：用户显式要求彻底停止（Termux 同款交互）
        if (intent?.action == ACTION_EXIT) {
            exitCompletely()
            return START_NOT_STICKY
        }

        val app = application as DshApp

        startAsForeground()

        // Android 13+ 通知权限需动态请求；无权限时前台服务仍合法，只是通知不可见
        if (Build.VERSION.SDK_INT >= 33) {
            MainActivity.maybeRequestNotificationPermission(this)
        }

        app.supervisor.start(app.appScope)

        // 状态回写到常驻通知
        if (stateJob == null) {
            stateJob = stateScope.launch {
                app.supervisor.state.collect { updateNotification(it) }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stateJob?.cancel()
        stateJob = null
        stateScope.cancel()
        (application as DshApp).supervisor.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startAsForeground() {
        val notification = buildNotification(getString(R.string.status_starting))
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_engine),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = getString(R.string.notif_channel_engine_desc) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private var lastNotifText: String = ""

    private fun buildNotification(text: String): Notification {
        val pending = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        // Termux 式「退出」按钮：通知展开后可见（折叠态部分 ROM 精简 action）
        val exitPending = PendingIntent.getService(
            this, 1,
            Intent(this, EngineService::class.java).setAction(ACTION_EXIT),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val exitAction = Notification.Action.Builder(
            android.graphics.drawable.Icon.createWithResource(
                this, android.R.drawable.ic_menu_close_clear_cancel,
            ),
            getString(R.string.notif_action_exit),
            exitPending,
        ).build()
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(pending)
            .addAction(exitAction)
            .build()
    }

    private fun updateNotification(state: EngineSupervisor.State) {
        val text = when (state) {
            is EngineSupervisor.State.Healthy -> getString(R.string.status_healthy)
            is EngineSupervisor.State.Backoff ->
                getString(R.string.status_backoff, state.delayMs / 1000, state.attempt)
            is EngineSupervisor.State.Failed -> state.reason
            else -> return
        }
        if (text == lastNotifText) return
        lastNotifText = text
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    /** 彻底退出：杀引擎 → 移除通知 → 停服务（onDestroy 里的兜底清理幂等） */
    private fun exitCompletely() {
        stateJob?.cancel()
        stateJob = null
        (application as DshApp).supervisor.stop()
        if (Build.VERSION.SDK_INT >= 33) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    companion object {
        private const val CHANNEL_ID = "engine"
        private const val NOTIF_ID = 42

        /** 通知「退出」按钮触发动作 */
        const val ACTION_EXIT = "app.dsh.mobile.service.action.EXIT"

        /** 便捷启动入口（供 Activity 调用） */
        fun start(context: Context) {
            context.startForegroundService(Intent(context, EngineService::class.java))
        }
    }
}
