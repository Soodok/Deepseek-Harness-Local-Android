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

        running = this
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

        // 🔴 v1.2.73 修主人实测「引擎明明在线，小字一直显示正在启动引擎」：
        // startAsForeground() 每次都把通知重置成 status_starting，而 StateFlow 只在
        // 状态**变化**时才发事件 —— MainActivity.onResume() 每次都调 EngineService.start()，
        // 于是「重进 onStartCommand → 通知被重置为启动中 → 已 Healthy 的状态不再重发」
        // → 小字永远卡住。这里立刻按当前状态强制刷一次，闭环。
        updateNotification(app.supervisor.state.value, force = true)

        // 状态回写到常驻通知
        if (stateJob == null) {
            stateJob = stateScope.launch {
                app.supervisor.state.collect { updateNotification(it) }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = null
        stateJob?.cancel()
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
        // SINGLE_TOP：MainActivity 是 singleTask，复用已有实例走 onNewIntent，
        // 杜绝通知点击新建实例压出「双界面」（实测事故）
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

    private fun updateNotification(state: EngineSupervisor.State, force: Boolean = false) {
        // ⚠️ v1.2.73：**每个状态都要有文案**。旧版只有 Healthy/Backoff/Failed 三种，
        // 其余（Idle/Installing/Starting/Stopped）直接 `return` 丢弃 —— 表现为
        // 「引擎明明已就绪，小字还停在正在启动引擎」：startAsForeground() 先写了
        // status_starting，而随后的 Starting 事件被丢弃、Healthy 事件又被
        // `if (text == lastNotifText) return` 之类的时序问题挡住时，小字就永远不更新。
        val text = when (state) {
            is EngineSupervisor.State.Healthy -> getString(R.string.status_healthy)
            is EngineSupervisor.State.SafeMode -> getString(R.string.status_healthy)
            is EngineSupervisor.State.Installing -> getString(R.string.status_installing)
            is EngineSupervisor.State.Starting -> getString(R.string.status_starting)
            is EngineSupervisor.State.Backoff ->
                getString(R.string.status_backoff, state.delayMs / 1000, state.attempt)
            is EngineSupervisor.State.Failed -> state.reason
            is EngineSupervisor.State.Stopped -> getString(R.string.status_exiting)
            else -> getString(R.string.status_starting)
        }
        if (!force && text == lastNotifText) return
        lastNotifText = text
        // ⚠️ v1.2.44：前台服务通知必须用 startForeground 再发一次来更新 ——
        // 用 NotificationManager.notify() 更新 FGS 通知在 Android 13+ 常被静默忽略
        // （用户实测：通知标题正确但小字永远停在「正在启动引擎」），且该路径还依赖
        // POST_NOTIFICATIONS 权限；startForeground 对 FGS 通知始终有效。
        val n = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    /**
     * 彻底退出：杀引擎 → 移除通知 → 停服务。
     *
     * 🔴 v1.2.73 修主人实测「点退出完全没有反应」：
     * `supervisor.stop()` 内部是 `EngineProcess.stop(graceMs = 10_000)` ——
     * **阻塞式**等引擎退出（最长 10 秒，实测 TERM 后还要 `pumpThread.join(1000)`），
     * 而它跑在 **onStartCommand 的主线程**上。于是点下退出后通知栏十几秒纹丝不动，
     * 用户完全感知不到任何反馈。
     *
     * 现在：① 先立刻把通知文案改成「正在退出…」（有反馈）；
     *      ② 真正耗时的停止动作丢到后台线程，且宽限压到 3 秒（不干等）；
     *      ③ 完成后回主线程移除通知并停服务。
     */
    private fun exitCompletely() {
        stateJob?.cancel()
        stateJob = null
        // ① 立刻反馈
        runCatching { pushStatusText(getString(R.string.status_exiting)) }
        // ② 后台真正停止（阻塞动作不能在主线程）
        Thread({
            runCatching { (application as DshApp).supervisor.stop(graceMs = 3_000) }
            // ③ 回主线程收尾
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                runCatching {
                    if (Build.VERSION.SDK_INT >= 33) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                    } else {
                        @Suppress("DEPRECATION")
                        stopForeground(true)
                    }
                }
                stopSelf()
            }
        }, "engine-exit").apply { isDaemon = true; start() }
    }

    /** 直接把通知小字改成指定文案（不走状态机，用于退出这类即时反馈） */
    private fun pushStatusText(text: String) {
        lastNotifText = text
        val n = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    companion object {
        private const val CHANNEL_ID = "engine"
        private const val NOTIF_ID = 42

        /** 运行中的服务实例（供能力桥在通知权限缺失时降级顶替前台通知文案） */
        @Volatile private var running: EngineService? = null

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

        /** 通知「退出」按钮触发动作 */
        const val ACTION_EXIT = "app.dsh.mobile.service.action.EXIT"

        /** 便捷启动入口（供 Activity 调用） */
        fun start(context: Context) {
            // 用户刚从通知栏显式退出 → 跳过生命周期自动拉起（否则「退出不了」，
            // MainActivity.onResume 会立刻把服务拉回来 ✗）。想再开：主界面点「启动」。
            val app = context.applicationContext as? app.dsh.mobile.DshApp
            if (app?.supervisor?.isUserStopped() == true) {
                android.util.Log.i("EngineService", "skip auto-start: user explicitly exited")
                return
            }
            context.startForegroundService(Intent(context, EngineService::class.java))
        }
    }
}
