package app.dsh.mobile.download

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import app.dsh.mobile.update.UpdateTasks

/**
 * 后台下载任务的基座（v1.4.37，先建壳、后收编）。
 *
 * ## 为什么要有它
 * 这个 App 里有三种"后台下载几百 MB"的任务：pet 资源包、运行时、APK 安装包。
 * 三份实现各自把同一套外壳写了一遍 —— 前台服务托管、5 秒内 startForeground、
 * wakelock、进度节流、通知渲染、取消、结果通知、`stopForeground(DETACH)`。
 * 它们的**取数策略**确实不同（分卷 / 下载+解压 / 单文件+校验），但外壳没有任何
 * 理由不同；重复的后果是修一个坑要修三遍（线路标注被窄屏截断这个问题就只修在了 pet）。
 *
 * ## 边界：抽"壳"不抽"芯"
 * 子类只实现两件事：
 *  - [ui]：这次任务在通知栏长什么样（标题、通知 ID、点击目标）；
 *  - [runTask]：**阻塞**执行任务，过程中用 [report] 推进度，返回 [TaskResult]。
 *
 * 基座负责其余全部：线程、wakelock、节流、取消标志、闸门、结果收尾。
 *
 * ## 迁移计划
 * - 已完成：APK 下载、运行时换装（本版）
 * - 待迁移：`pet/PetPackDownloadService`（P1）。它的分卷下载器与安装流程保留，
 *   只把外壳换成本类 —— 因为它现在还在正常工作，本版不动它，避免把已验证的
 *   链路和自动更新一起改坏。
 */
abstract class DownloadSession : Service() {

    /** 用户主动取消（不是网络失败）：子类应尽早返回 [TaskResult.Cancelled]。 */
    class Cancelled : Exception("cancelled by user")

    private var worker: Thread? = null

    @Volatile
    private var cancelRequested = false

    @Volatile
    private var viaProxy: Boolean? = null

    private var lastNotifyAt = 0L
    private var lastPct = -1
    private var ui: DownloadUi? = null

    /** 这次任务的通知外观。在 `onStartCommand` 里被调用一次并缓存。 */
    protected abstract fun ui(): DownloadUi

    /** 阻塞执行任务（在后台线程里调用）。用 [report] 推进度。 */
    protected abstract fun runTask(): TaskResult

    /** 与其它更新任务互斥的闸门；返回 null 表示不参与互斥。 */
    protected open fun gate(): UpdateTasks.Task? = null

    /** 任务结束时回调（含取消/失败），用于更新偏好与状态总线。 */
    protected open fun onFinished(result: TaskResult) {}

    // ------------------------------------------------------------ 对子类暴露

    protected fun isCancelRequested(): Boolean = cancelRequested

    protected fun checkCancelled() {
        if (cancelRequested) throw Cancelled()
    }

    /** 线路标注（经代理 / 直连）；`null` = 未知，标题不加后缀。 */
    protected fun reportSource(proxy: Boolean?) {
        viaProxy = proxy
    }

    /**
     * 更新进度。内部做 800ms + 百分比双重节流（100% 立刻刷，否则用户会看到卡在 99%）。
     */
    protected fun report(
        pct: Int,
        text: String,
        bigText: String? = null,
        indeterminate: Boolean = false,
        force: Boolean = false,
    ) {
        val u = ui ?: return
        val now = SystemClock.elapsedRealtime()
        // 与 pet 实测一致的规则：<800ms 不刷，但 100% 一定刷（否则看起来卡在 99%）
        if (!force && !indeterminate && pct < 100 && now - lastNotifyAt < NOTIFY_MIN_MS) return
        lastNotifyAt = now
        lastPct = pct
        DownloadNotifier.post(
            this,
            u.notifId,
            DownloadNotifier.progress(
                ctx = this,
                id = u.notifId,
                title = DownloadNotifier.titleWithSource(this, u.title, viaProxy),
                text = text,
                bigText = bigText,
                pct = pct,
                indeterminate = indeterminate,
                click = u.click,
                cancel = if (u.allowCancel && !indeterminate) cancelAction(u.notifId) else null,
            ),
        )
    }

    /** 动态开关「取消」动作（运行时在解压阶段关掉：半解压的目录比多等 3 分钟更糟）。 */
    protected fun setCancelable(allow: Boolean) {
        ui = ui?.copy(allowCancel = allow)
    }

    /** 挂一条"准备中"（不确定进度），必须在 5s 内调用 —— 基座已在 onStartCommand 里先挂好。 */
    protected fun showPreparing(text: String) {
        val u = ui ?: return
        DownloadNotifier.post(
            this,
            u.notifId,
            DownloadNotifier.progress(
                this,
                u.notifId,
                DownloadNotifier.titleWithSource(this, u.title, viaProxy),
                text,
                indeterminate = true,
                click = u.click,
            ),
        )
    }

    // ------------------------------------------------------------ 生命周期

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            // 只有下载阶段可取消；解压/安装阶段子类会忽略这个标志
            cancelRequested = true
            Log.i(TAG, "${javaClass.simpleName}: cancel requested")
            return START_NOT_STICKY
        }
        if (worker?.isAlive == true) {
            Log.i(TAG, "${javaClass.simpleName}: task already running, ignore duplicate start")
            return START_NOT_STICKY
        }

        DownloadNotifier.ensureChannel(this)
        val u = ui()
        ui = u
        // 5 秒内必须 startForeground，否则 ANR/被杀
        startInForeground(
            DownloadNotifier.progress(
                this,
                u.notifId,
                u.title,
                u.preparingText,
                indeterminate = true,
                click = u.click,
            ),
        )

        val gateTask = gate()
        if (gateTask != null && !UpdateTasks.acquire(gateTask)) {
            Log.i(TAG, "${javaClass.simpleName}: another update task is running")
            finish(
                DownloadNotifier.result(
                    this,
                    u.notifId,
                    u.title,
                    getString(app.dsh.mobile.R.string.app_update_busy),
                    u.click,
                ),
                startId,
            )
            return START_NOT_STICKY
        }

        cancelRequested = false
        worker = Thread({
            val result = try {
                runTask()
            } catch (e: Cancelled) {
                TaskResult.Cancelled
            } catch (e: Throwable) {
                Log.w(TAG, "${javaClass.simpleName}: task crashed", e)
                TaskResult.Failure(u.title, e.message ?: e.javaClass.simpleName, u.click)
            }
            try {
                onFinished(result)
            } catch (e: Throwable) {
                Log.w(TAG, "onFinished failed: ${e.message}", e)
            }
            if (gateTask != null) UpdateTasks.release(gateTask)
            finishWith(result, u, startId)
        }, "dsh-download-${javaClass.simpleName}").apply { start() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        worker = null
        super.onDestroy()
    }

    // ------------------------------------------------------------ 收尾

    private fun finishWith(result: TaskResult, u: DownloadUi, startId: Int) {
        val notif: Notification = when (result) {
            is TaskResult.Success -> DownloadNotifier.result(
                this,
                u.notifId,
                result.title,
                result.text,
                result.click ?: u.click,
            )
            is TaskResult.Failure -> DownloadNotifier.result(
                this,
                u.notifId,
                result.title,
                result.text,
                result.click ?: u.click,
            )
            TaskResult.Cancelled -> DownloadNotifier.result(
                this,
                u.notifId,
                u.title,
                getString(app.dsh.mobile.R.string.app_update_cancelled),
                u.click,
            )
        }
        finish(notif, startId)
    }

    /** 把前台通知换成可划掉的结果通知，再退出前台。 */
    private fun finish(n: Notification, startId: Int) {
        DownloadNotifier.post(this, ui?.notifId ?: 0, n)
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_DETACH)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(false)
        }
        stopSelf(startId)
    }

    private fun startInForeground(n: Notification) {
        val id = ui?.notifId ?: 0
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(id, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(id, n)
        }
    }

    private fun cancelAction(notifId: Int): PendingIntent = PendingIntent.getService(
        this,
        notifId,
        Intent(this, javaClass).setAction(ACTION_CANCEL),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** 拿一把有时限的 partial wakelock；锁屏 CPU 深睡会把网络读拉长到几十秒。 */
    protected fun acquireWakeLock(tag: String): PowerManager.WakeLock? =
        (getSystemService(POWER_SERVICE) as? PowerManager)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dsh:$tag")
            ?.apply {
                setReferenceCounted(false)
                runCatching { acquire(MAX_WAKE_MS) }
            }

    protected fun releaseWakeLock(wake: PowerManager.WakeLock?) {
        runCatching { if (wake?.isHeld == true) wake.release() }
    }

    companion object {
        private const val TAG = "DownloadSession"
        private const val NOTIFY_MIN_MS = 800L
        private const val MAX_WAKE_MS = 60L * 60 * 1000
        const val ACTION_CANCEL = "app.dsh.mobile.download.CANCEL"
    }
}

/**
 * 任务在通知栏的外观（与"怎么下载"无关，故与 [DownloadSession.runTask] 分离）。
 *
 * @param notifId 通知 ID（各任务需错开：pet 0x9E01 / APK 0x9E10 / 运行时 0x9E11）
 * @param title 基础标题（经代理/直连的标注由基座追加）
 * @param preparingText 刚起服务、还没拿到第一个字节时的文案
 * @param click 点通知的跳转目标
 * @param allowCancel 是否提供「取消」动作（运行时在解压阶段会自己关掉）
 */
data class DownloadUi(
    val notifId: Int,
    val title: String,
    val preparingText: String,
    val click: PendingIntent?,
    val allowCancel: Boolean = true,
)

/** 任务结果：只描述"要显示什么"，不携带业务数据（业务数据留在子类字段里）。 */
sealed interface TaskResult {
    data class Success(
        val title: String,
        val text: String,
        val click: PendingIntent? = null,
    ) : TaskResult

    data class Failure(
        val title: String,
        val text: String,
        val click: PendingIntent? = null,
    ) : TaskResult

    data object Cancelled : TaskResult
}
