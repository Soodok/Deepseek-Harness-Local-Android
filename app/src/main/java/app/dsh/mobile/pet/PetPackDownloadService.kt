package app.dsh.mobile.pet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import app.dsh.mobile.R
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch

/**
 * 资源包后台下载服务。
 *
 * 为什么必须是一个前台服务，而不是 Activity 里起个线程：
 *  - 资源包动辄几百 MB，用户不可能一直停在设置页；
 *  - 普通后台线程所在进程随时可能被系统回收，下载直接断在半路；
 *  - 用户需要从通知栏看到"还在下 / 下完了 / 失败了"，并能在锁屏状态下跟进。
 *
 * 服务负责下载 + 安装的完整链路，进度通过 [PetPackDownloadBus] 广播给设置页；
 * 设置页只是订阅者，退出、被杀都不影响任务本身。
 */
class PetPackDownloadService : Service() {

    private var worker: Thread? = null
    private var lastNotifyAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 通知栏的「取消」按钮
        if (intent?.action == ACTION_CANCEL) {
            PetPackDownloadBus.requestCancel()
            return START_NOT_STICKY
        }

        val pack = intent?.getStringExtra(EXTRA_PACK)?.let { packFromJson(it) }
        if (pack == null) {
            Log.w(TAG, "no pack payload, stopping")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (worker?.isAlive == true) {
            Log.i(TAG, "task already running, ignore duplicate start")
            return START_NOT_STICKY
        }

        // startForeground 必须在 5s 内完成，先挂一条"准备中"，再干活。
        // 顺手把首选源算出来：候选取的是第一个（代理优先），让用户一开机就看到
        // 这次是走代理还是直连，而不用等第一个字节下来。
        val firstSource = PetPackIndexClient
            .sources(this, pack.parts.firstOrNull()?.url ?: pack.url)
            .firstOrNull()
        startInForeground(
            buildProgress(pack.name, 0, indeterminate = true, preparing = true, source = firstSource),
        )
        PetPackDownloadBus.resetCancel()
        PetPackDownloadBus.update {
            PetPackTask(
                phase = PetPackPhase.DOWNLOADING,
                packId = pack.id,
                packName = pack.name,
                downloaded = PetPackDownloader.localProgress(this, pack),
                total = pack.size,
                source = firstSource,
            )
        }

        worker = Thread({ runTask(pack, startId) }, "dsh-pet-pack-bg").apply { start() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        worker = null
        super.onDestroy()
    }

    // ------------------------------------------------------------ 任务主体

    private fun runTask(pack: RemotePack, startId: Int) {
        val app = applicationContext
        // 锁屏/熄屏时 CPU 可能进深度休眠，网络读会被拉长到几十秒一次。
        // 拿一把有时限的 partial wakelock，任务结束必释放（设上限防泄漏）。
        val wake = (getSystemService(POWER_SERVICE) as? PowerManager)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dsh:pet-pack-download")
            ?.apply {
                setReferenceCounted(false)
                runCatching { acquire(MAX_WAKE_MS) }
            }
        try {
            val dl = awaitDownload(app, pack)
            if (dl.cancelled) {
                finishCancelled(startId)
                return
            }
            val file = dl.file
            if (!dl.ok || file == null) {
                finishFailed(startId, dl.message ?: "unknown")
                return
            }
            install(app, pack, file, startId)
        } catch (e: Throwable) {
            Log.w(TAG, "task crashed: ${e.message}", e)
            finishFailed(startId, e.message ?: e.javaClass.simpleName)
        } finally {
            runCatching { if (wake?.isHeld == true) wake.release() }
        }
    }

    private fun awaitDownload(ctx: Context, pack: RemotePack): DownloadResult {
        val latch = CountDownLatch(1)
        var result = DownloadResult(false, null, "no result")
        var lastPct = -1
        PetPackDownloader.download(
            ctx,
            pack,
            onSource = { source ->
                // 源切换（代理失败回落直连）是低频关键事件：立刻刷通知，
                // 别等下一个百分比——那时用户已经在看「经代理」下着的数据流了。
                val prev = PetPackDownloadBus.state.value.source
                PetPackDownloadBus.update { it.copy(source = source) }
                if (prev?.url != source.url) {
                    notifyProgress(pack.name, PetPackDownloadBus.state.value.percent, false, force = true)
                }
            },
            onProgress = { done, total ->
                // 百分比没变就不推：分卷下载每秒几十次回调，逐个更新会让 UI 抽搐
                val pct = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else 0
                if (pct != lastPct) {
                    lastPct = pct
                    PetPackDownloadBus.update {
                        it.copy(downloaded = done, total = total, phase = PetPackPhase.DOWNLOADING)
                    }
                    notifyProgress(pack.name, pct, indeterminate = false)
                }
            },
        ) { r ->
            result = r
            latch.countDown()
        }
        latch.await()
        return result
    }

    private fun install(ctx: Context, pack: RemotePack, file: java.io.File, startId: Int) {
        PetPackDownloadBus.update { it.copy(phase = PetPackPhase.INSTALLING) }
        notifyProgress(pack.name, 100, indeterminate = true)

        val latch = CountDownLatch(1)
        var result: PetPackImportResult? = null
        PetPackInstaller.install(
            ctx,
            Uri.fromFile(file),
            preferredId = PetPrefs.safeId(pack.id),
            replaceExisting = true,
            onProgress = {},
        ) { r ->
            result = r
            latch.countDown()
        }
        latch.await()
        runCatching { file.delete() }

        val r = result
        if (r?.ok == true) {
            r.packId?.let { PetPrefs.setPackId(ctx, it) }
            PetOverlayService.reload(ctx)
            PetPackDownloadBus.update { it.copy(phase = PetPackPhase.DONE, downloaded = 1, total = 1) }
            finishWith(buildResult(pack.name, getString(R.string.pet_pack_bg_done_title)), startId)
        } else {
            finishFailed(startId, r?.message ?: "install failed")
        }
    }

    // ------------------------------------------------------------ 收尾

    private fun finishCancelled(startId: Int) {
        PetPackDownloadBus.update { it.copy(phase = PetPackPhase.CANCELLED) }
        finishWith(
            buildResult(
                PetPackDownloadBus.state.value.packName,
                getString(R.string.pet_pack_bg_cancelled),
            ),
            startId,
        )
    }

    private fun finishFailed(startId: Int, reason: String) {
        PetPackDownloadBus.update { it.copy(phase = PetPackPhase.FAILED, error = reason) }
        finishWith(
            buildResult(
                PetPackDownloadBus.state.value.packName,
                getString(R.string.pet_pack_download_failed, reason),
            ),
            startId,
        )
    }

    /** 把前台通知换成"结果通知"（可划掉、点开进设置页），再退出前台。 */
    private fun finishWith(n: Notification, startId: Int) {
        runCatching { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, n) }
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_DETACH)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(false)
        }
        stopSelf(startId)
    }

    // ------------------------------------------------------------ 通知

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.pet_pack_bg_channel),
                    // LOW：下载是长任务，不该每 1% 响一次
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = getString(R.string.pet_pack_bg_channel_desc)
                    setShowBadge(false)
                },
            )
        }
    }

    private fun openSettings(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, PetSettingsActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP,
        ),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun cancelAction(): Notification.Action = Notification.Action.Builder(
        Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
        getString(android.R.string.cancel),
        PendingIntent.getService(
            this,
            1,
            Intent(this, PetPackDownloadService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        ),
    ).build()

    /**
     * 进度通知。
     *
     * 线路标注（经代理 / 直连）放在**标题**而不是正文尾部：正文里包名 + 百分比
     * 已经占满一行，竖屏窄屏（魅族 18 实测）会把尾部的「· 经代理」整个截掉，
     * 等于没写。标题行右侧空间更宽，且不受"某个包的包名特别长"影响。
     * 展开通知时再用 BigText 补全完整一行。
     *
     * @param preparing 刚起服务、还没开始下第一个字节 —— 这时候说"正在安装"是错的。
     * @param source 显式指定线路；null 时按当前任务状态取。
     */
    private fun buildProgress(
        name: String,
        pct: Int,
        indeterminate: Boolean,
        preparing: Boolean = false,
        source: PackSource? = PetPackDownloadBus.state.value.source,
    ): Notification {
        val text = when {
            preparing -> getString(R.string.pet_pack_bg_preparing, name)
            indeterminate -> getString(R.string.pet_pack_install_progress, name)
            else -> getString(R.string.pet_pack_downloading, name, pct)
        }
        val baseTitle = getString(R.string.pet_pack_bg_title)
        // 安装阶段已经不在下载了，线路没意义，别留着误导
        val title = if (!indeterminate && source != null) {
            getString(R.string.pet_pack_bg_title_src, baseTitle, sourceLabel(source))
        } else {
            baseTitle
        }
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_pet)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, pct, indeterminate)
            .setContentIntent(openSettings())
            .addAction(cancelAction())
        if (!indeterminate) {
            // 收起态只够放包名和百分比，完整一行（含线路）留给下拉展开
            builder.setStyle(
                Notification.BigTextStyle().bigText(
                    getString(R.string.pet_pack_bg_downloading_src, name, pct, sourceLabel(source)),
                ),
            )
        }
        // 「经代理」只说明走了前缀，没说明走的是哪家；把 host 挂到应用名旁边补上。
        if (source?.viaProxy == true) builder.setSubText(source.host)
        return builder.build()
    }

    private fun sourceLabel(src: PackSource?): String = when {
        src == null -> getString(R.string.pet_pack_src_checking)
        src.viaProxy -> getString(R.string.pet_pack_src_proxy)
        else -> getString(R.string.pet_pack_src_direct)
    }

    private fun buildResult(name: String, text: String): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_pet)
            .setContentTitle(name.ifBlank { getString(R.string.pet_pack_bg_title) })
            .setContentText(text)
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(openSettings())
            .build()

    /** 进度更新节流：距离上次不足 800ms 就不刷（100% 和线路切换例外，要立刻显示）。 */
    private fun notifyProgress(
        name: String,
        pct: Int,
        indeterminate: Boolean,
        force: Boolean = false,
    ) {
        val now = SystemClock.elapsedRealtime()
        if (!force && !indeterminate && pct < 100 && now - lastNotifyAt < NOTIFY_MIN_MS) return
        lastNotifyAt = now
        runCatching {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(
                NOTIF_ID,
                buildProgress(name, pct, indeterminate),
            )
        }
    }

    private fun startInForeground(n: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    // ------------------------------------------------------------ 载荷序列化

    companion object {
        private const val TAG = "DshPetPackSvc"
        private const val CHANNEL_ID = "dsh_pet_pack_download"
        private const val NOTIF_ID = 0x9E01
        private const val ACTION_CANCEL = "app.dsh.mobile.pet.PACK_DOWNLOAD_CANCEL"
        private const val EXTRA_PACK = "pack_json"
        private const val NOTIFY_MIN_MS = 800L
        private const val MAX_WAKE_MS = 60L * 60 * 1000

        /**
         * 起一个后台下载任务。
         *
         * 返回 false 表示已有任务在跑（重复点击），调用方据此提示即可。
         */
        fun start(ctx: Context, pack: RemotePack): Boolean {
            if (PetPackDownloadBus.state.value.running) return false
            val i = Intent(ctx, PetPackDownloadService::class.java)
                .putExtra(EXTRA_PACK, packToJson(pack))
            if (Build.VERSION.SDK_INT >= 26) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
            return true
        }

        private fun packToJson(pack: RemotePack): String = JSONObject().apply {
            put("id", pack.id)
            put("name", pack.name)
            put("resolution", pack.resolution ?: "")
            put("fps", pack.fps)
            put("animations", pack.animations)
            put("size", pack.size)
            put("sha256", pack.sha256)
            put("font", pack.font ?: "")
            put("url", pack.url)
            put(
                "parts",
                JSONArray().apply {
                    pack.parts.forEach { p ->
                        put(
                            JSONObject().apply {
                                put("url", p.url)
                                put("size", p.size)
                                put("sha256", p.sha256)
                            },
                        )
                    }
                },
            )
        }.toString()

        private fun packFromJson(raw: String): RemotePack? = runCatching {
            val o = JSONObject(raw)
            val parts = ArrayList<PackPartSpec>()
            val arr = o.optJSONArray("parts")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val p = arr.getJSONObject(i)
                    parts += PackPartSpec(
                        url = p.getString("url"),
                        size = p.optLong("size"),
                        sha256 = p.optString("sha256"),
                    )
                }
            }
            RemotePack(
                id = o.getString("id"),
                name = o.optString("name"),
                resolution = o.optString("resolution").ifBlank { null },
                fps = o.optInt("fps"),
                animations = o.optInt("animations"),
                size = o.optLong("size"),
                sha256 = o.optString("sha256"),
                font = o.optString("font").ifBlank { null },
                url = o.optString("url"),
                parts = parts,
            )
        }.getOrNull()
    }
}
