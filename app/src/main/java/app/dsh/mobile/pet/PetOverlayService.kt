package app.dsh.mobile.pet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import app.dsh.mobile.LocaleHelper
import app.dsh.mobile.R
import app.dsh.mobile.service.EngineService
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 桌宠前台服务：唯一持有悬浮窗、接收器、动画包和通知的地方。
 *
 * 与 StatusOverlay 的区别：
 *  - StatusOverlay 依附无障碍服务，使用无障碍专用的 overlay 窗口类型；
 *  - 这里独立前台服务、TYPE_APPLICATION_OVERLAY，需要用户授予「显示在其他应用上层」。
 *
 * 生命周期：
 *  - 用户在设置页开启 → startForegroundService；
 *  - 服务被系统回收 → START_STICKY，前提是 pet_enabled=true；
 *  - 用户「收起」/显式退出引擎 → 清偏好并 stopSelf；
 *  - 引擎崩溃重启时不主动停，桌宠继续等待新事件。
 */
class PetOverlayService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null

    private var petView: PetView? = null
    private var petParams: WindowManager.LayoutParams? = null
    private var pack: PetPackConfig? = null

    private var bubbleView: TextView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var bubbleUntilMs = 0L
    private var bubbleHide: Runnable? = null

    private var menu: PetActionMenu? = null
    private var server: PetBridgeServer? = null
    private var controller: PetActionController? = null

    private var fling: Runnable? = null
    private var dragStartX = 0
    private var dragStartY = 0
    private var dragDownRawX = 0f
    private var dragDownRawY = 0f
    private var lastNotifText: String? = null
    private var screenOn = true

    private val watchdog = object : Runnable {
        override fun run() {
            controller?.onTick(EngineService.isRunning())
            handler.postDelayed(this, WATCHDOG_MS)
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenOn = false
                    petView?.pauseDrawing(true)
                    hideBubble()
                }
                Intent.ACTION_SCREEN_ON -> {
                    screenOn = true
                    petView?.pauseDrawing(false)
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        screenOn = (getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
        createChannel()
        startAsForeground()
        PetStatusBus.update { PetStatus(running = true, port = PetProtocol.PORT) }

        if (!Settings.canDrawOverlays(this)) {
            PetPrefs.setEnabled(this, false)
            PetStatusBus.update {
                it.copy(lastError = s(R.string.pet_error_no_permission), running = false)
            }
            Log.w(TAG, "overlay permission missing")
            stopSelf()
            return
        }

        runCatching {
            registerReceiver(screenReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            })
        }.onFailure { Log.w(TAG, "screen receiver: ${it.message}") }

        attachPet()
        if (petView == null) return
        startServer()
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, WATCHDOG_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                PetPrefs.setEnabled(this, false)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RELOAD -> {
                reloadPet()
                return if (PetPrefs.enabled(this)) START_STICKY else START_NOT_STICKY
            }
        }

        if (!PetPrefs.enabled(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!Settings.canDrawOverlays(this)) {
            PetPrefs.setEnabled(this, false)
            PetStatusBus.update {
                it.copy(lastError = s(R.string.pet_error_no_permission), running = false)
            }
            stopSelf()
            return START_NOT_STICKY
        }
        startAsForeground()
        if (petView == null) attachPet()
        if (petView == null) return START_NOT_STICKY
        if (server?.isRunning != true) {
            server?.stop()
            server = null
        }
        if (server == null) startServer()
        updateNotification()
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        hideBubble()
        clampAndUpdate()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stopFling()
        menu?.dismiss()
        menu = null
        hideBubble()
        controller?.reset()
        controller = null
        removePet()
        server?.stop()
        server = null
        runCatching { unregisterReceiver(screenReceiver) }
        if (instance === this) {
            PetStatusBus.reset()
            instance = null
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) stopForeground(STOP_FOREGROUND_REMOVE)
            else @Suppress("DEPRECATION") stopForeground(true)
        }
        super.onDestroy()
    }

    // ------------------------------------------------------------ 悬浮窗

    private fun attachPet() {
        val w = wm ?: return
        if (petView != null) return
        pack = PetPack.load(this)
        val sizeDp = PetPrefs.sizeDp(this)
        // 大档位可能超出屏幕：按屏宽/屏高可用区域收缩，保持 16:9。
        val dm = resources.displayMetrics
        val maxByWidth = (dm.widthPixels - dp(16)).coerceAtLeast(64)
        val maxByHeight = ((dm.heightPixels - dp(56)) * 16 / 9).coerceAtLeast(64)
        val sizePx = (sizeDp * dm.density).toInt().coerceIn(64, minOf(maxByWidth, maxByHeight))
        val heightPx = (sizePx * 9 / 16).coerceAtLeast(36)

        // 注意：apply 里的裸 `pack` 会解析成 PetView 自己的同名属性，
        // 写成 `this.pack = pack` 就是自赋值 —— 视图永远拿不到动画包，只会画占位鲸鱼。
        val loadedPack = pack
        val view = PetView(this).apply {
            this.pack = loadedPack
            face = PetView.Face.IDLE
        }
        val p = WindowManager.LayoutParams(
            sizePx,
            heightPx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = PetPrefs.x(this@PetOverlayService)
            y = PetPrefs.y(this@PetOverlayService)
        }

        view.onPetClick = { controller?.onUserTap() }
        view.onPetLongPress = { showMenu() }
        view.onDragStart = { downX, downY ->
            stopFling()
            dragDownRawX = downX
            dragDownRawY = downY
            dragStartX = p.x
            dragStartY = p.y
        }
        view.onDragMove = { rawX, rawY ->
            p.x = dragStartX + (rawX - dragDownRawX).roundToInt()
            p.y = dragStartY + (rawY - dragDownRawY).roundToInt()
            clampParams(p, view)
            runCatching { w.updateViewLayout(view, p) }
            followBubble()
        }
        view.onDragEnd = { vx, vy ->
            if (abs(vx) > FLING_MIN_VX || abs(vy) > FLING_MIN_VY) startFling(vx, vy)
            else settle()
        }

        runCatching {
            w.addView(view, p)
            petView = view
            petParams = p
        }.onFailure {
            Log.w(TAG, "add pet view failed: ${it.message}")
            view.release()
            PetStatusBus.update { it.copy(lastError = it.lastError ?: "overlay_failed") }
            stopSelf()
            return
        }

        controller = PetActionController(
            context = this,
            packProvider = { pack },
            onPlay = { slug, loop -> view.play(slug, loop) },
            onFace = { face -> view.face = face },
            onBubble = { text -> if (text.isNullOrBlank()) hideBubble() else showBubble(text) },
        ).also { it.start() }

        view.post {
            if (p.x < 0 || p.y < 0) {
                val dm = resources.displayMetrics
                p.x = dm.widthPixels - sizePx - dp(24)
                p.y = dp(100)
            }
            clampParams(p, view)
            runCatching { w.updateViewLayout(view, p) }
            PetPrefs.setPosition(this, p.x, p.y)
        }

        if (!screenOn) view.pauseDrawing(true)
        updateStatusPack(view)
    }

    private fun reloadPet() {
        controller?.reset()
        controller = null
        pack = PetPack.load(this)
        removePet()
        hideBubble()
        attachPet()
        updateNotification()
    }

    private fun removePet() {
        val v = petView ?: return
        val w = wm
        petView = null
        petParams = null
        runCatching { v.release() }
        runCatching { w?.removeView(v) }
    }

    private fun clampAndUpdate() {
        val v = petView ?: return
        val p = petParams ?: return
        clampParams(p, v)
        runCatching { wm?.updateViewLayout(v, p) }
        PetPrefs.setPosition(this, p.x, p.y)
    }

    private fun clampParams(p: WindowManager.LayoutParams, view: View) {
        val dm = resources.displayMetrics
        val vw = if (view.width > 0) view.width else p.width
        val vh = if (view.height > 0) view.height else p.height
        val maxX = (dm.widthPixels - vw).coerceAtLeast(0)
        val maxY = (dm.heightPixels - vh).coerceAtLeast(0)
        p.x = p.x.coerceIn(0, maxX)
        p.y = p.y.coerceIn(0, maxY)
    }

    private fun settle() {
        val p = petParams ?: return
        PetPrefs.setPosition(this, p.x, p.y)
        stopFling()
    }

    private fun startFling(vx: Float, vy: Float) {
        val p = petParams ?: return
        val view = petView ?: return
        val w = wm ?: return
        stopFling()
        val dm = resources.displayMetrics
        val vw = if (view.width > 0) view.width else p.width
        val vh = if (view.height > 0) view.height else p.height
        val bounds = PetBounds(
            minX = 0.0,
            maxX = (dm.widthPixels - vw).coerceAtLeast(0).toDouble(),
            minY = 0.0,
            maxY = (dm.heightPixels - vh).coerceAtLeast(0).toDouble(),
        )
        val body = PetBody(p.x.toDouble(), p.y.toDouble(), vx.toDouble(), vy.toDouble())
        val r = object : Runnable {
            override fun run() {
                val step = PetPhysics.step(body, 1.0 / 60.0, bounds)
                p.x = body.x.roundToInt()
                p.y = body.y.roundToInt()
                runCatching { w.updateViewLayout(view, p) }
                followBubble()
                if (step.atRest) {
                    fling = null
                    settle()
                } else {
                    handler.postDelayed(this, 16L)
                }
            }
        }
        fling = r
        handler.post(r)
    }

    private fun stopFling() {
        fling?.let { handler.removeCallbacks(it) }
        fling = null
    }

    // ------------------------------------------------------------ 气泡

    private fun showBubble(text: String) {
        if (!screenOn) return
        val w = wm ?: return
        val d = resources.displayMetrics.density
        val tv = bubbleView ?: TextView(this).apply {
            setTextColor(0xFF16223B.toInt())
            textSize = 13f
            includeFontPadding = false
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
            maxWidth = (232 * d).toInt()
            setPadding((14 * d).toInt(), (9 * d).toInt(), (14 * d).toInt(), (9 * d).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 16f * d
                setColor(0xF7FFFFFF.toInt())
                setStroke((1.5f * d).toInt(), 0x664E6BFF)
            }
        }.also { bubbleView = it }
        tv.text = text
        val maxW = (resources.displayMetrics.widthPixels * 0.8f).toInt()
        tv.measure(
            View.MeasureSpec.makeMeasureSpec(maxW, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val bw = tv.measuredWidth.coerceAtLeast((48 * d).toInt())
        val bh = tv.measuredHeight.coerceAtLeast((28 * d).toInt())

        val lp = bubbleParams ?: WindowManager.LayoutParams(
            bw,
            bh,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        lp.width = bw
        lp.height = bh
        placeBubble(lp, bw, bh)
        bubbleUntilMs = System.currentTimeMillis() + BUBBLE_HOLD_MS
        bubbleHide?.let { handler.removeCallbacks(it) }
        val hide = Runnable {
            bubbleHide = null
            hideBubble()
        }
        bubbleHide = hide
        handler.postDelayed(hide, BUBBLE_HOLD_MS)

        if (bubbleParams == null) {
            runCatching {
                w.addView(tv, lp)
                bubbleParams = lp
            }.onFailure {
                Log.w(TAG, "bubble addView failed: ${it.message}")
                bubbleView = null
                bubbleParams = null
            }
        } else {
            runCatching { w.updateViewLayout(tv, lp) }
        }
    }

    private fun placeBubble(lp: WindowManager.LayoutParams, bw: Int, bh: Int) {
        val p = petParams ?: return
        val dm = resources.displayMetrics
        val d = resources.displayMetrics.density
        val pad = (8 * d).toInt()
        val petW = petView?.width?.takeIf { it > 0 } ?: p.width
        var x = p.x + petW / 2 - bw / 2
        x = x.coerceIn(pad, (dm.widthPixels - bw - pad).coerceAtLeast(pad))
        var y = p.y - bh - (6 * d).toInt()
        if (y < (24 * d).toInt()) y = p.y + (petView?.height ?: p.height) + (6 * d).toInt()
        lp.x = x
        lp.y = y
    }

    private fun followBubble() {
        val tv = bubbleView ?: return
        val lp = bubbleParams ?: return
        if (System.currentTimeMillis() > bubbleUntilMs) {
            hideBubble()
            return
        }
        placeBubble(lp, tv.measuredWidth, tv.measuredHeight)
        runCatching { wm?.updateViewLayout(tv, lp) }
    }

    private fun hideBubble() {
        bubbleHide?.let { handler.removeCallbacks(it) }
        bubbleHide = null
        val tv = bubbleView ?: return
        bubbleView = null
        bubbleParams = null
        runCatching { wm?.removeView(tv) }
    }

    // ------------------------------------------------------------ 长按菜单

    private fun showMenu() {
        val w = wm ?: return
        menu?.dismiss()
        val view = petView
        val params = petParams
        val anchor = if (view != null && params != null) {
            Rect(params.x, params.y, params.x + view.width, params.y + view.height)
        } else {
            null
        }
        menu = PetActionMenu(
            ctx = LocaleHelper.wrap(this),
            wm = w,
            pack = pack,
            anchor = anchor,
            onAction = { slug -> controller?.playFromMenu(slug) },
            onReset = { resetPosition() },
            onFlip = { petView?.flip() },
            onSettings = {
                runCatching {
                    startActivity(
                        Intent(this, PetSettingsActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            },
            onStop = {
                PetPrefs.setEnabled(this, false)
                stopSelf()
            },
            onDismiss = { menu = null },
        ).also { it.show() }
    }

    private fun resetPosition() {
        val p = petParams ?: return
        val v = petView ?: return
        val dm = resources.displayMetrics
        p.x = dm.widthPixels - v.width - dp(24)
        p.y = dp(100)
        clampParams(p, v)
        runCatching { wm?.updateViewLayout(v, p) }
        PetPrefs.setPosition(this, p.x, p.y)
        hideBubble()
    }

    // ------------------------------------------------------------ 接收器

    private fun startServer() {
        if (server != null) return
        server = PetBridgeServer(
            onEvent = { event ->
                handler.post {
                    PetStatusBus.update {
                        it.copy(
                            connected = true,
                            listening = true,
                            lastKind = event.kind,
                            lastEventAt = event.receivedAt,
                            lastError = null,
                        )
                    }
                    controller?.onEvent(event)
                    updateNotification()
                }
            },
            onError = { code ->
                handler.post {
                    PetStatusBus.update {
                        it.copy(
                            listening = false,
                            connected = false,
                            lastError = if (code == "port_busy") {
                                s(R.string.pet_error_port_busy)
                            } else {
                                code
                            },
                        )
                    }
                    updateNotification()
                }
            },
        ).also { it.start() }

        val listening = server?.isRunning == true
        PetStatusBus.update {
            it.copy(
                listening = listening,
                lastError = if (listening) null else it.lastError,
            )
        }
    }

    // ------------------------------------------------------------ 通知/状态

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    s(R.string.pet_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = s(R.string.pet_channel_desc)
                    setShowBadge(false)
                },
            )
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, PetSettingsActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP,
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, PetOverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = notificationText()
        lastNotifText = text
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_pet)
            .setContentTitle(s(R.string.pet_notif_title))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_pet),
                    s(R.string.pet_notif_stop),
                    stop,
                ).build(),
            )
            .build()
    }

    private fun notificationText(): String {
        val st = PetStatusBus.state.value
        return when {
            st.lastError == s(R.string.pet_error_port_busy) ->
                s(R.string.pet_notif_port_busy)
            st.connected -> s(R.string.pet_notif_connected)
            else -> s(R.string.pet_notif_waiting)
        }
    }

    private fun startAsForeground() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun updateNotification() {
        val text = notificationText()
        if (text == lastNotifText) return
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun updateStatusPack(view: PetView) {
        val count = pack?.available?.size ?: 0
        val label = if (pack == null) s(R.string.pet_pack_none)
        else s(R.string.pet_pack_count, count)
        PetStatusBus.update {
            it.copy(
                running = true,
                packLabel = label,
                placeholder = pack == null,
                listening = server?.isRunning == true,
            )
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** 服务没有 Activity 的 locale 包装，用户文案统一走 LocaleHelper。 */
    private fun s(id: Int): String = LocaleHelper.wrap(this).getString(id)

    private fun s(id: Int, vararg args: Any): String =
        LocaleHelper.wrap(this).getString(id, *args)

    companion object {
        private const val TAG = "DshPetService"
        private const val CHANNEL_ID = "pet_overlay"
        private const val NOTIF_ID = 0x5E71
        private const val BUBBLE_HOLD_MS = 3_600L
        private const val WATCHDOG_MS = 30_000L
        private const val FLING_MIN_VX = 220f
        private const val FLING_MIN_VY = 220f

        const val ACTION_STOP = "app.dsh.mobile.pet.action.STOP"
        const val ACTION_RELOAD = "app.dsh.mobile.pet.action.RELOAD"

        @Volatile private var instance: PetOverlayService? = null

        fun isRunning(): Boolean = instance != null

        fun start(ctx: Context) {
            val i = Intent(ctx, PetOverlayService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            PetPrefs.setEnabled(ctx, false)
            runCatching { ctx.stopService(Intent(ctx, PetOverlayService::class.java)) }
        }

        fun reload(ctx: Context) {
            if (!isRunning()) return
            runCatching {
                ctx.startService(
                    Intent(ctx, PetOverlayService::class.java).setAction(ACTION_RELOAD),
                )
            }
        }

        /** MainActivity.onResume 调用：只在用户已开启且仍有悬浮窗权限时恢复。 */
        fun restoreIfEnabled(ctx: Context) {
            if (!PetPrefs.enabled(ctx)) return
            if (!Settings.canDrawOverlays(ctx)) return
            if (isRunning()) return
            runCatching { start(ctx) }
                .onFailure { Log.w(TAG, "restore failed: ${it.message}") }
        }

        fun showTestBubble(text: String) {
            val svc = instance ?: return
            svc.handler.post { svc.showBubble(text) }
        }
    }
}
