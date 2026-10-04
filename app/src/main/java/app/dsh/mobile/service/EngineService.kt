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
import app.dsh.mobile.R
import app.dsh.mobile.engine.EngineSupervisor
import app.dsh.mobile.engine.SoundManager
import app.dsh.mobile.engine.TaskManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * 引擎前台服务。
 *
 * 类型选择 specialUse（Android 14+ 生效）：dataSync 类型有 6 小时系统限时，
 * 长任务会被强杀；specialUse 无此限制且 sideload 分发无需 Play 审核豁免。
 *
 * Phase 2 enhancements:
 * - Detailed notification with engine state summary line
 * - Sound feedback for engine start/stop events
 * - Progress tracking for long-running AI tasks
 */
class EngineService : Service() {

    private var stateJob: Job? = null
    private val stateScope by lazy { CoroutineScope(Dispatchers.Main) }

    /** Phase 2: Progress tracking for long AI tasks */
    private val _taskProgress = MutableStateFlow<TaskProgress?>(null)
    val taskProgress: StateFlow<TaskProgress?> = _taskProgress

    data class TaskProgress(
        val taskId: String,
        val title: String,
        val progress: Int,
        val max: Int,
        val message: String,
    )

    companion object {
        private const val CHANNEL_ID = "engine"
        private const val CHANNEL_ID_DETAILS = "engine_details"
        private const val CHANNEL_ID_TASKS = "engine_tasks"
        private const val NOTIF_ID = 42
        private const val NOTIF_ID_DETAILS = 43
        private const val NOTIF_ID_TASK = 44

        @Volatile private var running: EngineService? = null

        const val ACTION_EXIT = "app.dsh.mobile.service.action.EXIT"
        const val ACTION_START_ENGINE = "app.dsh.mobile.service.action.START"
        const val ACTION_STOP_ENGINE = "app.dsh.mobile.service.action.STOP"

        /** 便捷启动入口（供 Activity 调用） */
        fun start(context: Context) {
            val app = context.applicationContext as? app.dsh.mobile.DshApp
            if (app?.supervisor?.isUserStopped() == true) {
                android.util.Log.i("EngineService", "skip auto-start: user explicitly exited")
                return
            }
            context.startForegroundService(Intent(context, EngineService::class.java))
        }

        /**
         * 把 AI 的消息临时顶到前台服务通知的小字上（POST_NOTIFICATIONS 未授予时的降级通道：
         * FGS 通知豁免该权限，始终可见）。返回 false = 服务未在运行。
         * 下一次引擎状态变化会覆盖回常规文案。
         */
        fun pushAgentNotice(text: String): Boolean = running?.let { svc ->
            runCatching {
                val n = svc.buildNotification(text)
                if (Build.VERSION.SDK_INT >= 34) {
                    svc.startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                } else {
                    svc.startForeground(NOTIF_ID, n)
                }
                true
            }.getOrDefault(false)
        } ?: false

        /** Phase 2: Update task progress in notification */
        fun updateTaskProgress(taskId: String, title: String, progress: Int, max: Int, message: String) {
            running?.let { svc ->
                val progressState = TaskProgress(taskId, title, progress, max, message)
                svc._taskProgress.value = progressState
                svc.updateTaskNotification(progressState)
            }
        }

        /** Phase 2: Clear task progress notification */
        fun clearTaskProgress(taskId: String) {
            running?.let { svc ->
                if (svc._taskProgress.value?.taskId == taskId) {
                    svc._taskProgress.value = null
                    val nm = svc.getSystemService(NotificationManager::class.java)
                    nm.cancel(NOTIF_ID_TASK)
                }
            }
        }
    }

    override fun onCreate() {
        running = this
        super.onCreate()
        createChannels()
        SoundManager.init(this)
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

        // Phase 2: Listen for task progress updates
        stateScope.launch {
            taskProgress.collect { updateTaskNotification(it) }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        running = null
        stateJob?.cancel()
        stateJob = null
        stateScope.cancel()
        (application as DshApp).supervisor.stop()
        SoundManager.shutdown()
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

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)

        // Main engine channel
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_engine),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = getString(R.string.notif_channel_engine_desc) }
        nm.createNotificationChannel(channel)

        // Phase 2: Detailed status channel (higher importance for important events)
        val detailsChannel = NotificationChannel(
            CHANNEL_ID_DETAILS,
            getString(R.string.notif_channel_details),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = getString(R.string.notif_channel_details_desc) }
        nm.createNotificationChannel(detailsChannel)

        // Phase 2: Task notifications channel
        val tasksChannel = NotificationChannel(
            CHANNEL_ID_TASKS,
            getString(R.string.agent_task_title),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = getString(R.string.notif_channel_engine_desc) }
        nm.createNotificationChannel(tasksChannel)
    }

    private var lastNotifText: String = ""

    private fun buildNotification(text: String): Notification {
        // SINGLE_TOP：MainActivity 是 singleTask，复用已有实例走 onNewIntent，
        // 杜绝通知点击新建实例压出"双界面"（实测事故）
        val pending = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP,
            ),
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

    /** Phase 2: Build a detailed notification with extra lines and progress */
    private fun buildDetailedNotification(
        text: String,
        summary: String? = null,
        progress: TaskProgress? = null,
    ): Notification {
        val pending = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP,
            ),
            PendingIntent.FLAG_IMMUTABLE,
        )
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

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(pending)
            .addAction(exitAction)

        if (summary != null) {
            builder.setSubText(summary)
        }
        if (progress != null) {
            builder.setProgress(progress.max, progress.progress, progress.max <= 0)
        }

        return builder.build()
    }

    private fun updateNotification(state: EngineSupervisor.State) {
        val text = state.toNotificationText(this)
        if (text == lastNotifText) return
        lastNotifText = text

        when (state) {
            is EngineSupervisor.State.Healthy -> {
                SoundManager.playEvent(SoundManager.SoundEvent.ENGINE_START, this)
            }
            is EngineSupervisor.State.Stopped -> {
                SoundManager.playEvent(SoundManager.SoundEvent.ENGINE_STOP, this)
            }
            is EngineSupervisor.State.Failed -> {
                SoundManager.playEvent(SoundManager.SoundEvent.WARNING, this)
            }
            else -> {}
        }

        // Detailed notification with summary line
        val summary = state.toSummaryText(this)
        val notification = buildDetailedNotification(text, summary)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    /** Phase 2: Update or clear the task progress notification */
    private fun updateTaskNotification(progress: TaskProgress?) {
        val nm = getSystemService(NotificationManager::class.java)
        if (progress == null) {
            nm.cancel(NOTIF_ID_TASK)
            return
        }
        val pending = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP,
            ),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL_ID_TASKS)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.notif_task_running, progress.title))
            .setOngoing(true)
            .setContentIntent(pending)
            .setProgress(progress.max, progress.progress, progress.max <= 0)
            .build()
        nm.notify(NOTIF_ID_TASK, notification)
    }

    /**
     * Phase 2: Post a one-shot detailed notification (non-ongoing) for
     * important engine events, e.g. task completion. Uses the details channel.
     */
    fun postDetailedNotification(title: String, text: String, progress: TaskProgress? = null) {
        val pending = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP,
            ),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = Notification.Builder(this, CHANNEL_ID_DETAILS)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(pending)
        if (progress != null) {
            builder.setProgress(progress.max, progress.progress, false)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(System.currentTimeMillis().toInt() and 0x7FFFFFFF, builder.build())
    }

    /** Phase 2: Notify task completed/failed with sound */
    fun notifyTaskCompleted(taskId: String, title: String) {
        SoundManager.playEvent(SoundManager.SoundEvent.TASK_COMPLETE, this)
        postDetailedNotification(
            getString(R.string.notif_task_completed, title),
            getString(R.string.agent_task_completed, title)
        )
    }

    fun notifyTaskFailed(taskId: String, title: String, error: String) {
        SoundManager.playEvent(SoundManager.SoundEvent.TASK_FAILED, this)
        postDetailedNotification(
            getString(R.string.notif_task_failed, title),
            error
        )
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
}

/** Extension to convert EngineSupervisor.State to notification text */
private fun EngineSupervisor.State.toNotificationText(ctx: Context): String = when (this) {
    is EngineSupervisor.State.Healthy -> ctx.getString(R.string.status_healthy)
    is EngineSupervisor.State.SafeMode -> ctx.getString(R.string.status_safe_mode)
    is EngineSupervisor.State.Backoff -> ctx.getString(R.string.status_backoff, delayMs / 1000, attempt)
    is EngineSupervisor.State.Failed -> reason
    is EngineSupervisor.State.Installing -> ctx.getString(R.string.status_installing)
    is EngineSupervisor.State.Starting -> ctx.getString(R.string.status_starting)
    is EngineSupervisor.State.Idle -> ctx.getString(R.string.status_idle)
    is EngineSupervisor.State.Stopped -> ctx.getString(R.string.status_idle)
}

/** Extension to convert EngineSupervisor.State to a summary line for the notification */
private fun EngineSupervisor.State.toSummaryText(ctx: Context): String = when (this) {
    is EngineSupervisor.State.Healthy -> ctx.getString(R.string.notif_engine_healthy_summary)
    is EngineSupervisor.State.SafeMode -> "Safe mode active"
    is EngineSupervisor.State.Backoff -> "Restarting (attempt $attempt)"
    is EngineSupervisor.State.Failed -> "Failed: $reason"
    is EngineSupervisor.State.Installing -> ctx.getString(R.string.notif_engine_installing_summary)
    is EngineSupervisor.State.Starting -> ctx.getString(R.string.notif_engine_starting_summary)
    is EngineSupervisor.State.Idle -> "Waiting to start"
    is EngineSupervisor.State.Stopped -> "Engine stopped"
}
