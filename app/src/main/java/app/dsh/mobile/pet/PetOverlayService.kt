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
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import app.dsh.mobile.LocaleHelper
import app.dsh.mobile.R
import app.dsh.mobile.service.EngineService
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

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
    /** 工作/终态气泡的占位截止时间：碎碎念在它之前不得顶掉工作气泡。 */
    private var workBubbleUntilMs = 0L
    /** 包内气泡字体缓存（按 包id/字体名 失效，换包时重建）。 */
    private var typeface: Typeface? = null
    private var typefaceKey: String? = null
    private var bubbleUntilMs = 0L
    private var bubbleHide: Runnable? = null

    private var menu: PetActionMenu? = null
    private var server: PetBridgeServer? = null
    private var controller: PetActionController? = null
    private var chatter: PetChatter? = null

    /** 动画链：负责"现在播什么、播完接着播什么"（见 PetAnimChain）。 */
    private var chain: PetAnimChain? = null

    // move 分支的位移驱动：选中移动动画后按动画时间轴插值推进窗口
    private var moveDrive: Runnable? = null
    private var pendingMove: Pair<PetMoveAction, PetMovePlanner.Plan>? = null
    private var pendingMoveDurationMs = 0L

    // 点击音效
    private var soundPool: android.media.SoundPool? = null
    private var clickSoundId = 0

    // 漫游状态（横向）
    private var roaming = false
    private var roamDir = 1
    private var walking = false
    private var walkUntilMs = 0L
    private var nextRoamAt = 0L
    private var roamRunnable: Runnable? = null

    // 贴边躲藏状态：side -1=左 +1=右；pending=贴边倒计时中；hidden=已缩进半身
    private var edgeSide = 0
    private var edgePendingHide = false
    private var edgeHidden = false
    private var edgeHideRunnable: Runnable? = null
    private var hideAnimRunnable: Runnable? = null
    private var isDragging = false

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
            chatter?.onTick()
            if (controller?.isTurnActive() == true) chatter?.onWorking()
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

        initSound()
        chatter = PetChatter(this) { text ->
            // 单槽优先级（桌面版同款）：工作/终态气泡在场时不被碎碎念顶掉
            if (System.currentTimeMillis() >= workBubbleUntilMs) showBubble(text)
        }

        attachPet()
        if (petView == null) return
        startServer()
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, WATCHDOG_MS)
        startRoaming()
        handler.postDelayed({ chatter?.onBoot() }, 900L)
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
        stopMoveDrive()
        roaming = false
        walking = false
        roamRunnable = null
        edgeHideRunnable = null
        hideAnimRunnable = null
        chain?.stop()
        chain = null
        menu?.dismiss()
        menu = null
        hideBubble()
        controller?.reset()
        controller = null
        chatter = null
        releaseSound()
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
        // 必须先有资源包：没有包就不显示桌宠（不再画任何占位图形）。
        pack = PetPack.load(this)
        val loadedPack = pack ?: run {
            Log.i(TAG, "no animation pack installed; pet stays hidden")
            // 没有包也要刷新状态，让设置页与桥接知道「缺资源包」而不是「服务没起」。
            PetPrefs.setPackId(this, null)
            PetStatusBus.update {
                it.copy(
                    running = true,
                    packLabel = s(R.string.pet_pack_none),
                    placeholder = true,
                    listening = server?.isRunning == true,
                )
            }
            return
        }
        val packId = PetPrefs.packId(this) ?: return
        val sizeDp = PetPrefs.sizeDp(this)
        // 大档位可能超出屏幕：按屏宽/屏高可用区域收缩，保持 16:9。
        val dm = resources.displayMetrics
        val maxByWidth = (dm.widthPixels - dp(16)).coerceAtLeast(64)
        val maxByHeight = ((dm.heightPixels - dp(56)) * 16 / 9).coerceAtLeast(64)
        val sizePx = (sizeDp * dm.density).toInt().coerceIn(64, minOf(maxByWidth, maxByHeight))
        val heightPx = (sizePx * 9 / 16).coerceAtLeast(36)

        // 注意：apply 里的裸 `pack` 会解析成 PetView 自己的同名属性，
        // 写成 `this.pack = pack` 就是自赋值 —— 视图永远拿不到动画包。
        val view = PetView(this).apply {
            this.pack = loadedPack
            webpDir = PetPrefs.packWebpDir(this@PetOverlayService, packId)
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

        view.onPetClick = {
            peekOut()
            playClickSound()
            chatter?.onTap()
            chain?.playClick()
        }
        view.onPetLongPress = { showMenu() }
        view.onDragStart = { downX, downY ->
            stopFling()
            stopHideAnim()
            stopMoveDrive()
            peekOut()
            chatter?.onInteracted()
            chain?.playDrag()
            isDragging = true
            dragDownRawX = downX
            dragDownRawY = downY
            dragStartX = p.x
            dragStartY = p.y
        }
        view.onDragMove = { rawX, rawY ->
            p.x = dragStartX + (rawX - dragDownRawX).roundToInt()
            p.y = dragStartY + (rawY - dragDownRawY).roundToInt()
            if (!edgeHidden) clampParams(p, view)
            runCatching { w.updateViewLayout(view, p) }
            followBubble()
        }
        view.onDragEnd = { vx, vy ->
            isDragging = false
            chain?.endDrag()
            if (abs(vx) > FLING_MIN_VX || abs(vy) > FLING_MIN_VY) {
                chatter?.onFling()
                startFling(vx, vy)
            } else {
                settle()
            }
        }

        // 一次性动画播到结尾 → 交给动画链接下一段，画面停在最后一帧不会闪黑
        view.onAnimationEnd = { chain?.onAnimationEnd() }

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

        chain = PetAnimChain(
            packProvider = { pack },
            onPlay = { slug, loop ->
                view.play(slug, loop)
                val pending = pendingMove
                if (pending != null) {
                    startMoveDrive(pending.first, pendingMoveDurationMs, pending.second)
                    pendingMove = null
                } else if (moveDrive != null) {
                    // 播放的已经不是刚启用位移的那段动画了（动画链已推进 / 被插播打断）：
                    // 位移就地收尾，千万不要让它继续按旧时间轴跑，否则会把窗口拉回起点
                    stopMoveDrive()
                }
            },
            onMirror = { m -> view.setMirror(m) },
            onMoveRequest = { action, durationMs -> requestMove(action, durationMs) },
        )

        controller = PetActionController(
            context = this,
            packProvider = { pack },
            onWork = { slot -> chain?.setWorkStatus(slot) },
            onFace = { face -> view.face = face },
            onBubble = { text ->
                if (text.isNullOrBlank()) {
                    workBubbleUntilMs = 0
                    hideBubble()
                } else {
                    showWorkBubble(text)
                }
            },
        ).also { it.start() }

        chain?.start()

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
        updateStatusPack()
    }

    private fun reloadPet() {
        stopMoveDrive()
        chain?.stop()
        chain = null
        controller?.reset()
        controller = null
        pack = PetPack.load(this)
        // 换了包就重读气泡字体（不同包可能带不同字体）
        typefaceKey = null
        typeface = null
        removePet()
        hideBubble()
        cancelEdgeHide()
        stopHideAnim()
        edgeHidden = false
        edgeSide = 0
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
        if (edgeHidden) return // 缩进状态允许窗口半身在屏幕外
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
        maybeSnapToEdge()
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

    // ------------------------------------------------------------ move 分支位移
    //
    // 与桌面版同口径：选到"移动"档时，位移由**动画播放时间轴**驱动（线性插值，
    // 首尾各有 leadSec/tailSec 的原地窗口），而不是另走一条匀速计时器。

    /**
     * 规划一次移动。成功只记下计划，等 [PetAnimChain] 真正开始播这段动画时才推进窗口
     * （否则位移会跑在动画前面）。
     */
    private fun requestMove(action: PetMoveAction, durationMs: Long): Boolean {
        val v = petView ?: return false
        val p = petParams ?: return false
        if (isDragging || moveDrive != null) return false
        val viewW = if (v.width > 0) v.width else p.width
        if (viewW <= 0) return false
        // 镜像时角色面朝屏幕左侧 → 往左走；与漫游里 setMirror(roamDir < 0) 同一口径
        val dir = if (chain?.isMirrored == true) -1 else 1
        val distScale = viewW.toDouble() / PetMovePlanner.PET_REF_WIDTH
        val plan = PetMovePlanner.plan(
            cx = p.x,
            viewW = viewW,
            screenW = resources.displayMetrics.widthPixels,
            dir = dir,
            action = action,
            distScale = distScale,
        ) ?: return false
        pendingMove = action to plan
        pendingMoveDurationMs = durationMs
        return true
    }

    /**
     * 按动画时间轴推进窗口。
     *
     * **时间基准必须是驱动自己的 startTimeMs，不能读 view.elapsedMs()**：后者在每段新动画
     * 开始时都会被重置。移动动画播完的瞬间动画链会立刻播下一段，此时若驱动还没停，
     * 下一 tick 就会算出 elapsed≈0，插值函数返回 startX —— 桌宠当场闪回起点。
     */
    private fun startMoveDrive(action: PetMoveAction, durationMs: Long, plan: PetMovePlanner.Plan) {
        stopMoveDrive()
        val v = petView ?: return
        val p = petParams ?: return
        val w = wm ?: return
        val startX = p.x
        val startTimeMs = SystemClock.uptimeMillis()
        val durationSec = durationMs.coerceIn(500L, 30_000L) / 1000.0
        val r = object : Runnable {
            override fun run() {
                val view = petView ?: return
                val params = petParams ?: return
                if (moveDrive !== this) return
                if (isDragging || fling != null) {
                    stopMoveDrive()
                    return
                }
                val viewW = if (view.width > 0) view.width else params.width
                val maxX = (resources.displayMetrics.widthPixels - viewW).coerceAtLeast(0)
                val elapsedSec = (SystemClock.uptimeMillis() - startTimeMs) / 1000.0
                params.x = PetMovePlanner.interpolateX(startX, plan, action, elapsedSec, durationSec)
                    .coerceIn(0, maxX)
                runCatching { w.updateViewLayout(view, params) }
                followBubble()
                if (elapsedSec >= durationSec) {
                    // 收尾时直接落到目标点，避免最后一 tick 的取整误差被留在半路
                    params.x = plan.targetX.coerceIn(0, maxX)
                    runCatching { w.updateViewLayout(view, params) }
                    followBubble()
                    stopMoveDrive()
                    PetPrefs.setPosition(this@PetOverlayService, params.x, params.y)
                    return
                }
                handler.postDelayed(this, MOVE_TICK_MS)
            }
        }
        moveDrive = r
        handler.post(r)
    }

    private fun stopMoveDrive() {
        moveDrive?.let { handler.removeCallbacks(it) }
        moveDrive = null
        pendingMove = null
    }

    // ------------------------------------------------------------ 漫游

    /** 横向漫游：闲时匀速散步，走不出去就在屏幕边界自动掉头。 */
    private fun startRoaming() {
        if (roaming) return
        roaming = true
        val r = object : Runnable {
            override fun run() {
                if (!roaming) return
                handler.postDelayed(this, ROAM_TICK_MS)
                val view = petView ?: return
                val p = petParams ?: return
                val w = wm ?: return
                if (!screenOn || isDragging || fling != null || edgeHidden || edgePendingHide) return
                if (menu?.isShowing == true) return
                // move 分支正在用动画时间轴推进位移时，匀速漫游让路，避免两个驱动抢同一个窗口
                if (moveDrive != null) return
                if (controller?.isTurnActive() == true) {
                    if (walking) {
                        walking = false
                        nextRoamAt = SystemClock.uptimeMillis() + ROAM_PAUSE_MIN_MS
                    }
                    return
                }
                // 原来这里用 view.isBusy 挡漫游，但动画链现在每段都是"播一遍即换"
                // （与桌面版 setOnce(true) 一致），isBusy 恒为真会把漫游彻底挡死。
                // 改为按语义判断：只在"脚下有动作"的待机/移动类动画时散步；
                // 播"吃面条/睡午觉"这类原地动作时不横向平移。
                val cfg = pack
                val cur = chain?.currentAnim
                if (cfg != null && cur != null &&
                    cur !in cfg.idle && cfg.moves.none { it.slug == cur }
                ) return
                val now = SystemClock.uptimeMillis()
                if (!walking) {
                    if (now < nextRoamAt) return
                    walking = true
                    roamDir = if (Random.nextBoolean()) 1 else -1
                    // 走链而不是直接操作 View：朝向是单一状态，noMirror 过滤与 move 方向都依赖它
                    chain?.setMirror(roamDir < 0)
                    walkUntilMs = now + ROAM_WALK_MIN_MS + Random.nextLong(ROAM_WALK_VAR_MS)
                    return
                }
                if (now > walkUntilMs) {
                    walking = false
                    nextRoamAt = now + ROAM_PAUSE_MIN_MS + Random.nextLong(ROAM_PAUSE_VAR_MS)
                    return
                }
                val dm = resources.displayMetrics
                val maxX = (dm.widthPixels - view.width).coerceAtLeast(0)
                val step = (ROAM_SPEED_DP * dm.density * ROAM_TICK_MS / 1000f).roundToInt()
                val nx = p.x + roamDir * step
                if (nx <= 0 || nx >= maxX) {
                    // 走不出去：掉头
                    roamDir = -roamDir
                    chain?.setMirror(roamDir < 0)
                    p.x = nx.coerceIn(0, maxX)
                } else {
                    p.x = nx
                }
                runCatching { w.updateViewLayout(view, p) }
                followBubble()
            }
        }
        roamRunnable = r
        nextRoamAt = SystemClock.uptimeMillis() + ROAM_FIRST_DELAY_MS
        handler.postDelayed(r, ROAM_FIRST_DELAY_MS)
    }

    private fun stopRoaming() {
        roaming = false
        walking = false
        roamRunnable?.let { handler.removeCallbacks(it) }
        roamRunnable = null
    }

    // ------------------------------------------------------------ 贴边躲藏

    /**
     * 落点在屏幕左右边缘附近就吸附贴边，并启动 12s 缩进倒计时。
     * 返回是否发生了吸附。
     */
    private fun maybeSnapToEdge(): Boolean {
        val p = petParams ?: return false
        val v = petView ?: return false
        val dm = resources.displayMetrics
        val maxX = (dm.widthPixels - v.width).coerceAtLeast(0)
        val threshold = dp(EDGE_SNAP_DP)
        val side = when {
            p.x <= threshold -> -1
            p.x >= maxX - threshold -> 1
            else -> 0
        }
        if (side == 0) {
            cancelEdgeHide()
            return false
        }
        if (edgeSide == side && edgePendingHide) return true
        edgeSide = side
        edgeHidden = false
        p.x = if (side < 0) 0 else maxX
        runCatching { wm?.updateViewLayout(v, p) }
        PetPrefs.setPosition(this, p.x, p.y)
        chatter?.onEdge()
        scheduleEdgeHide()
        return true
    }

    private fun scheduleEdgeHide() {
        cancelEdgeHide()
        edgePendingHide = true
        val r = Runnable {
            edgeHideRunnable = null
            hideToEdge()
        }
        edgeHideRunnable = r
        handler.postDelayed(r, EDGE_HIDE_MS)
    }

    private fun cancelEdgeHide() {
        edgeHideRunnable?.let { handler.removeCallbacks(it) }
        edgeHideRunnable = null
        edgePendingHide = false
    }

    /** 静置 12s：把窗口推出屏幕一半，只露半个身子。 */
    private fun hideToEdge() {
        val v = petView ?: return
        val p = petParams ?: return
        if (isDragging || fling != null || menu?.isShowing == true) {
            scheduleEdgeHide()
            return
        }
        edgePendingHide = false
        edgeHidden = true
        val dm = resources.displayMetrics
        val target = if (edgeSide < 0) {
            -(v.width / 2)
        } else {
            (dm.widthPixels - v.width) + v.width / 2
        }
        animatePetX(target)
    }

    /** 碰一下 / 拖一下：从缩进状态探出来，回到贴边位。 */
    private fun peekOut() {
        if (!edgeHidden && !edgePendingHide) return
        cancelEdgeHide()
        stopHideAnim()
        val wasHidden = edgeHidden
        edgeHidden = false
        val v = petView ?: return
        val p = petParams ?: return
        val dm = resources.displayMetrics
        val maxX = (dm.widthPixels - v.width).coerceAtLeast(0)
        p.x = if (edgeSide < 0) 0 else maxX
        runCatching { wm?.updateViewLayout(v, p) }
        if (wasHidden) chatter?.onPeeked()
    }

    private fun animatePetX(target: Int) {
        stopHideAnim()
        val v = petView ?: return
        val p = petParams ?: return
        val w = wm ?: return
        val startX = p.x
        if (startX == target) return
        val steps = 12
        var i = 0
        val r = object : Runnable {
            override fun run() {
                if (petView == null || petParams == null) return
                i++
                p.x = startX + (target - startX) * i / steps
                runCatching { w.updateViewLayout(v, p) }
                followBubble()
                if (i < steps) {
                    hideAnimRunnable = this
                    handler.postDelayed(this, 24L)
                } else {
                    hideAnimRunnable = null
                }
            }
        }
        hideAnimRunnable = r
        handler.post(r)
    }

    private fun stopHideAnim() {
        hideAnimRunnable?.let { handler.removeCallbacks(it) }
        hideAnimRunnable = null
    }

    // ------------------------------------------------------------ 点击音效

    private fun initSound() {
        runCatching {
            val attrs = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            soundPool = android.media.SoundPool.Builder()
                .setMaxStreams(2)
                .setAudioAttributes(attrs)
                .build()
            assets.openFd("pet/click.wav").use { afd ->
                clickSoundId = soundPool?.load(afd, 1) ?: 0
            }
        }.onFailure {
            Log.w(TAG, "sound init failed: ${it.message}")
            clickSoundId = 0
        }
    }

    private fun playClickSound() {
        if (!PetPrefs.soundEnabled(this)) return
        val pool = soundPool ?: return
        if (clickSoundId != 0) runCatching { pool.play(clickSoundId, 0.8f, 0.8f, 1, 0, 1f) }
    }

    private fun releaseSound() {
        runCatching { soundPool?.release() }
        soundPool = null
        clickSoundId = 0
    }

    // ------------------------------------------------------------ 气泡

    /** 工作/终态气泡：占住气泡槽 10s，期间碎碎念让路（桌面版单槽优先级）。 */
    private fun showWorkBubble(text: String) {
        workBubbleUntilMs = System.currentTimeMillis() + BUBBLE_HOLD_MS
        showBubble(text)
    }

    private fun showBubble(text: String) {
        if (!screenOn) return
        val w = wm ?: return
        val d = resources.displayMetrics.density
        // 字号随宠物宽度缩放：桌面版口径是 0.0455 × 宠物宽（462 宽时 21px），
        // 内边距/圆角/叠入深度也都是 em 或 size 的固定比例，见 shared/fonts.ts。
        val petWpx = (petView?.width?.takeIf { it > 0 }
            ?: petParams?.width
            ?: (PetPrefs.DEFAULT_SIZE_DP * d).toInt()).toFloat()
        val fpx = (petWpx * BUBBLE_FONT_RATIO).coerceIn(10f * d, 28f * d)
        val tf = petTypeface()

        val tv = bubbleView ?: TextView(this).apply {
            setTextColor(0xFF16223B.toInt())
            includeFontPadding = false
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
            background = GradientDrawable()
        }.also { bubbleView = it }
        tv.text = text
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, fpx)
        // 行高 1.6em：Android 单行默认约 1.2em，倍数换算后取 1.6/1.2
        tv.setLineSpacing(0f, BUBBLE_LINE_HEIGHT / 1.2f)
        val padX = (fpx * BUBBLE_PAD_X).toInt()
        val padY = (fpx * BUBBLE_PAD_Y).toInt()
        tv.setPadding(padX, padY, padX, padY)
        val radius = fpx * BUBBLE_RADIUS
        val bg = (tv.background as? GradientDrawable) ?: GradientDrawable().also { tv.background = it }
        bg.cornerRadius = radius
        bg.setColor(0xF7FFFFFF.toInt())
        bg.setStroke(maxOf(1f, radius * 0.1f).toInt(), 0x664E6BFF)
        if (tf != null) tv.typeface = tf

        val maxW = minOf(
            (resources.displayMetrics.widthPixels * 0.8f).toInt(),
            (petWpx * 1.6f).toInt(),
        )
        tv.maxWidth = maxW
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

    /** 资源包自带的气泡字体（上首软糖体）；包内没有字体时返回 null 走系统字体。 */
    private fun petTypeface(): Typeface? {
        val cfg = pack ?: return null
        val id = cfg.spec.id
        val key = "$id/${cfg.font ?: ""}"
        if (key == typefaceKey) return typeface
        typefaceKey = key
        typeface = run {
            val dir = PetPrefs.packFontDir(this, id)
            val named = cfg.font?.let { File(dir, it) }?.takeIf { it.isFile }
            val any = named ?: dir.listFiles { f ->
                f.isFile && (f.name.endsWith(".ttf", true) || f.name.endsWith(".otf", true))
            }?.minByOrNull { it.name }
            any?.let { f -> runCatching { Typeface.createFromFile(f) }.getOrNull() }
        }
        return typeface
    }

    private fun placeBubble(lp: WindowManager.LayoutParams, bw: Int, bh: Int) {
        val p = petParams ?: return
        val dm = resources.displayMetrics
        val d = resources.displayMetrics.density
        val pad = (8 * d).toInt()
        val petW = petView?.width?.takeIf { it > 0 } ?: p.width
        var x = p.x + petW / 2 - bw / 2
        x = x.coerceIn(pad, (dm.widthPixels - bw - pad).coerceAtLeast(pad))
        // 气泡底边压进宠物盒顶边 0.108×尺寸（桌面版 CSS 的 bottom:calc(100% - size*0.108)）
        val overlap = (petW * BUBBLE_OVERLAP_RATIO).toInt()
        var y = p.y - bh + overlap
        if (y < (24 * d).toInt()) y = p.y + (petView?.height ?: p.height) - overlap
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
            onAction = { slug -> chain?.playOnce(slug) },
            onReset = { resetPosition() },
            onFlip = { chain?.flip() },
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

    private fun updateStatusPack() {
        val count = pack?.available?.size ?: 0
        val name = PetPrefs.packId(this) ?: ""
        val label = when {
            pack == null -> s(R.string.pet_pack_none)
            name.isBlank() -> s(R.string.pet_pack_count, count)
            else -> "$name · " + s(R.string.pet_pack_count, count)
        }
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
        private const val BUBBLE_HOLD_MS = 10_000L // 桌面版 BUBBLE_DURATION_MS = 10s

        // 气泡度量：与桌面版 src/shared/fonts.ts 同源
        //  - 字号 0.0455 × 宠物宽（462 宽时 21px）
        //  - 行高 1.6em、内边距 0.4835em、底边叠入宠物盒 0.108 × 尺寸
        private const val BUBBLE_FONT_RATIO = 0.0455f
        private const val BUBBLE_PAD_X = 0.4835f
        private const val BUBBLE_PAD_Y = 0.4835f
        private const val BUBBLE_LINE_HEIGHT = 1.6f
        private const val BUBBLE_RADIUS = 0.62f
        private const val BUBBLE_OVERLAP_RATIO = 0.108f
        private const val WATCHDOG_MS = 30_000L
        private const val FLING_MIN_VX = 220f
        private const val FLING_MIN_VY = 220f

        // 漫游
        private const val ROAM_TICK_MS = 32L
        private const val ROAM_SPEED_DP = 26f // 每秒约 26dp
        /** move 分支位移驱动的刷新间隔（约 60fps）。 */
        private const val MOVE_TICK_MS = 16L
        private const val ROAM_WALK_MIN_MS = 2_500L
        private const val ROAM_WALK_VAR_MS = 3_500L
        private const val ROAM_PAUSE_MIN_MS = 4_000L
        private const val ROAM_PAUSE_VAR_MS = 7_000L
        private const val ROAM_FIRST_DELAY_MS = 6_000L

        // 贴边躲藏
        private const val EDGE_SNAP_DP = 28
        private const val EDGE_HIDE_MS = 12_000L

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
