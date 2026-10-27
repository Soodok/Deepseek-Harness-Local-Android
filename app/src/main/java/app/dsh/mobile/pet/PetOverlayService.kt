package app.dsh.mobile.pet

import android.app.ActivityManager
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
import android.graphics.RectF
import android.graphics.Typeface
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
import java.util.concurrent.Executors
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

    /**
     * 资源包目录维护专用线程（旧包迁移、半成品清扫）。
     *
     * 这些活会遍历/复制/删除几百 MB 目录，绝不能落在主线程（onCreate 里做整包复制
     * 就是分钟级 ANR）。单线程保证"迁移"与"清扫"不会互相踩；daemon 保证不退服务。
     */
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "dsh-pet-pack-io").apply { isDaemon = true }
    }

    private var petView: PetView? = null
    private var petParams: WindowManager.LayoutParams? = null
    private var pack: PetPackConfig? = null

    /**
     * 画布（整幅 16:9 素材）尺寸，px。窗口只开角色那一块，所以窗口尺寸 ≠ 画布尺寸；
     * 裁窗锚点、解码宽度、气泡字号都必须按画布算。
     */
    private var canvasPxW = 0
    private var canvasPxH = 0
    /** 已应用到窗口的透明边距比例（画布内 left/top/right/bottom，0~1）。 */
    private val appliedInsets = RectF()
    private val tmpInsets = RectF()

    private var bubbleView: TextView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null

    // 气泡的排版结果（量一次存下来，拖动时只做定位、不重复测量）
    private var bubbleW = 0
    private var bubbleH = 0
    private var bubblePadX = 0
    private var bubblePadY = 0
    private var bubbleTailH = 0
    /** 投影留白（随字号缩放）：气泡实体在窗口里内缩这么多，模糊不会撞上窗口边界。 */
    private var bubbleInset = 0
    private var bubbleTailUp = false
    private var bubbleMaxW = 0

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
    /** 播放失败限流表：slug → 上次上报时刻（见 reportPlaybackError）。 */
    private val playErrorAt = HashMap<String, Long>()

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
                    // 息屏后漫游没有任何意义：停掉自排队，别每 32ms 醒一次空转
                    stopRoaming()
                    petView?.pauseDrawing(true)
                    hideBubble()
                }
                Intent.ACTION_SCREEN_ON -> {
                    screenOn = true
                    petView?.pauseDrawing(false)
                    if (petView != null) startRoaming()
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

        attachPetAsync(bootLine = true)
    }

    /**
     * 挂载桌宠：先在 [io] 线程把资源包目录整理干净（旧包迁移 + 半成品清扫），
     * 再回主线程挂窗口。
     *
     * 以前这两步是同步跑在 `onCreate` 里的——`ensure` 会在主线程遍历/复制/删除
     * 几百 MB 目录，`renameTo` 失败退化成整包复制就是分钟级 ANR。
     */
    private fun attachPetAsync(bootLine: Boolean) {
        io.execute {
            runCatching { PetPack.ensureHeavy(this) }
                .onFailure { Log.w(TAG, "pack dir maintenance failed: ${it.message}") }
            handler.post {
                // 服务已经销毁（或换了个实例）就别再往上挂窗口
                if (instance !== this) return@post
                attachPet()
                if (petView == null) return@post
                afterMounted(bootLine)
            }
        }
    }

    /** 窗口挂上之后的公共收尾：桥接端口、看门狗、漫游、开机台词。 */
    private fun afterMounted(bootLine: Boolean) {
        if (server?.isRunning != true) startServer()
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, WATCHDOG_MS)
        if (!roaming) startRoaming()
        if (bootLine) handler.postDelayed({ chatter?.onBoot() }, 900L)
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
        // 重活已在 onCreate 走过一轮；这里没有窗口就再走一次异步挂载（内部幂等）。
        // 不能再用 petView 是否为空立刻决定 START_NOT_STICKY：挂载已经变成异步，
        // 此刻它必然还是 null，会把刚启动的服务误判成"挂不上"。
        if (petView == null) attachPetAsync(bootLine = false)
        updateNotification()
        return if (PetPrefs.enabled(this)) START_STICKY else START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        hideBubble()
        clampAndUpdate()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        io.shutdownNow()
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

    // ------------------------------------------------------------ 桥插件门禁

    /**
     * 桥插件门禁：true = 允许画角色。判定口径与设置页同源，见
     * [PetPluginDetector.bridgeGateOpen]。
     */
    private fun bridgeAllowed(): Boolean = PetPluginDetector.bridgeGateOpen(this)

    /**
     * 门禁拦下时的统一处理：前台服务照跑、状态可查，只是不画角色。
     *
     * 不 stopSelf：停掉的话设置页会显示"未开启"，而用户的开关明明是开的 ——
     * 状态里用 bridgeMissing 区分"在跑但被门禁挡住"，比"看起来没启动"诚实得多。
     * 也不去启新的桥接端口；但**已经在跑的那个保持不动**——插件可能只是被暂时停用，
     * 端口留着，用户重新启用后不用重启服务就能恢复。
     */
    private fun holdForBridge() {
        val snap = PetPluginDetector.detect(this)
        Log.w(
            TAG,
            "bridge gate: pet stays hidden (installed=${snap.bridgeInstalled}" +
                " enabled=${snap.bridgeEnabled} versionOk=${snap.bridgeVersionOk})",
        )
        // 可能是"已经在跑、后来插件被停用"的路径：把窗口与驱动一起收干净，别留幽灵在漫游
        stopRoaming()
        stopFling()
        stopMoveDrive()
        stopHideAnim()
        cancelEdgeHide()
        chain?.stop()
        chain = null
        controller?.reset()
        controller = null
        removePet()
        hideBubble()
        edgeHidden = false
        edgeSide = 0
        PetStatusBus.update {
            it.copy(
                running = true,
                bridgeMissing = true,
                placeholder = true,
                listening = server?.isRunning == true,
            )
        }
        updateNotification()
    }

    // ------------------------------------------------------------ 悬浮窗

    /**
     * 解码边长上限：低内存机型（`memoryClass` < 192MB）压到 720，正常机型 1280。
     *
     * 素材只降不升（见 `PetView.Api28.decode`），所以这个上限只在"大尺寸档位 + 高分辨率包"
     * 的组合上真正起作用；压到 720 能把单帧解码缓冲砍掉一半以上，代价是大屏下略糊。
     */
    private fun decodeCeilForDevice(): Int {
        val memoryClass = runCatching {
            (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).memoryClass
        }.getOrDefault(192)
        val ceil = if (memoryClass < 192) 720 else 1280
        // 内存/清晰度出问题时，第一件事是确认走的是哪一档；不打这行就只能靠猜。
        Log.i(TAG, "decodeCeil=${ceil}px memoryClass=${memoryClass}MB")
        return ceil
    }

    private fun attachPet() {
        val w = wm ?: return
        if (petView != null) return
        // 挂载路径上的每个早退都留一条记录：以前"宠物不出现"在这里是彻底静默的，
        // 只能靠 dumpsys 猜是没包、没 packId 还是 addView 失败。
        Log.i(TAG, "attachPet: enter")
        // 门禁唯一卡点：onCreate / onStartCommand / reloadPet 三条路最后都汇到这里。
        // 以前只在设置页那一个弹窗里查桥插件，MainActivity.onResume→restoreIfEnabled()
        // 与 START_STICKY 重建都能绕过它，于是"没装桥插件也能开桌宠"。
        if (!bridgeAllowed()) {
            holdForBridge()
            return
        }
        // 必须先有资源包：没有包就不显示桌宠（不再画任何占位图形）。
        pack = PetPack.load(this)
        val loadedPack = pack ?: run {
            Log.i(TAG, "no animation pack installed; pet stays hidden")
            // 没有包也要刷新状态，让设置页与桥接知道「缺资源包」而不是「服务没起」。
            PetPrefs.setPackId(this, null)
            PetStatusBus.update {
                it.copy(
                    running = true,
                    bridgeMissing = false,
                    packLabel = s(R.string.pet_pack_none),
                    placeholder = true,
                    listening = server?.isRunning == true,
                )
            }
            return
        }
        val packId = PetPrefs.packId(this) ?: run {
            Log.w(TAG, "attachPet: no pack id; pet stays hidden")
            return
        }
        val sizeDp = PetPrefs.sizeDp(this)
        // 大档位可能超出屏幕：按屏宽/屏高可用区域收缩，保持 16:9。
        val dm = resources.displayMetrics
        val maxByWidth = (dm.widthPixels - dp(16)).coerceAtLeast(64)
        val maxByHeight = ((dm.heightPixels - dp(56)) * 16 / 9).coerceAtLeast(64)
        val sizePx = (sizeDp * dm.density).toInt().coerceIn(64, minOf(maxByWidth, maxByHeight))
        val heightPx = (sizePx * 9 / 16).coerceAtLeast(36)

        // 尺寸档位说的是**画布**（整幅 16:9 素材）；窗口只开角色那一块。
        // 边距是上次量出来的（同一台设备同一素材是稳定的），没有就先用整幅画布，
        // 等 View 量出第一段动画再裁 —— 见 applyContentSize()。
        canvasPxW = sizePx
        canvasPxH = heightPx
        appliedInsets.set(0f, 0f, 0f, 0f)
        PetPrefs.packInsets(this, packId)?.let { appliedInsets.set(it) }
        val winW = bodySize(canvasPxW, appliedInsets.left, appliedInsets.right)
        val winH = bodySize(canvasPxH, appliedInsets.top, appliedInsets.bottom)

        // 注意：apply 里的裸 `pack` 会解析成 PetView 自己的同名属性，
        // 写成 `this.pack = pack` 就是自赋值 —— 视图永远拿不到动画包。
        val view = PetView(this).apply {
            this.pack = loadedPack
            webpDir = PetPrefs.packWebpDir(this@PetOverlayService, packId)
            face = PetView.Face.IDLE
            canvasHintPx = canvasPxW
            // 低内存机型把解码边长压到 720：素材只降不升，单帧占用直接砍掉一半以上
            decodeCeilPx = decodeCeilForDevice()
            // 先吃上次量到的边距：窗口一开就是对的，后续测量只做 min（只涨不缩）
            seedCanvasInsets(appliedInsets)
            onContentInsetsChanged = { applyContentSize() }
        }
        val p = WindowManager.LayoutParams(
            winW,
            winH,
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
        // 播放失败（缺素材/解码失败）以前是静默 return，现场查不出原因；这里进日志 + 状态总线
        view.onPlaybackError = { slug, reason -> reportPlaybackError(slug, reason) }
        // 连续解码失败到上限：素材整包坏了，停链比 400ms 一跳地空转诚实。
        // 具体原因已经由上面那条 onPlaybackError 写进 lastError，这里不再覆盖。
        view.onPlaybackBroken = {
            Log.w(TAG, "playback broken: stop chain and keep last frame")
            chain?.stop()
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

        // 挂载快照：排查"宠物不出来/窗口尺寸不对"时，一眼就能对上是哪一环不对
        Log.i(
            TAG,
            "attached pack=$packId sizeDp=$sizeDp canvas=${canvasPxW}x$canvasPxH" +
                " window=${p.width}x${p.height} at ${p.x},${p.y}" +
                " insets=${appliedInsets.left},${appliedInsets.top}," +
                "${appliedInsets.right},${appliedInsets.bottom}" +
                " idle=${loadedPack.idle.size} clicks=${loadedPack.clicks.size}" +
                " cats=${loadedPack.categories.size}",
        )

        view.post {
            if (p.x < 0 || p.y < 0) {
                val dm = resources.displayMetrics
                // 右上角起手：贴的是窗口（= 角色矩形）右边缘，留 24dp 空
                p.x = dm.widthPixels - p.width - dp(24)
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
        // 甩抛中换包：fling 会继续对着已被移除的 view 反复 updateViewLayout（被 runCatching
        // 吞掉），收尾时还会拿旧坐标 settle()，把新宠物往边上吸一下。和 holdForBridge 同口径。
        stopFling()
        stopMoveDrive()
        chain?.stop()
        chain = null
        controller?.reset()
        controller = null
        pack = PetPack.load(this)
        // 换包后 slug 相同但素材不同，旧的失败限流记录不能再拦着新包上报
        playErrorAt.clear()
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
        // 换包后桥接端口照旧要活着（门禁没变），只在真挂上了才补一次收尾
        if (petView != null) afterMounted(bootLine = false)
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
        // 以 LayoutParams 为准：裁窗时 p.width 已经改成新值，而 view.width 要等下一次
        // 布局才更新，用旧值夹取会把刚算好的 x 又推回原地（角色当场抖一下）。
        val vw = if (p.width > 0) p.width else view.width
        val vh = if (p.height > 0) p.height else view.height
        val maxX = (dm.widthPixels - vw).coerceAtLeast(0)
        val maxY = (dm.heightPixels - vh).coerceAtLeast(0)
        p.x = p.x.coerceIn(0, maxX)
        p.y = p.y.coerceIn(0, maxY)
    }

    /** 画布按两边透明边距裁出来的窗口尺寸（px），至少 16px、不超过画布。 */
    private fun bodySize(canvas: Int, insetA: Float, insetB: Float): Int {
        if (canvas <= 0) return 1
        val k = 1f - insetA - insetB
        val v = (canvas * k).roundToInt().coerceIn(1, canvas)
        return v.coerceAtLeast(minOf(16, canvas))
    }

    /**
     * 把悬浮窗裁到角色矩形（透明边距不参与窗口 → 不吃触摸，角色也能贴到屏幕边）。
     *
     * 素材是 16:9 的整幅视频，角色只占中间一块：300p 包实测左右各约 30%、上 15%、
     * 下 6% 全是透明。整幅开窗时这块透明区会把下面 App 的点击整片吞掉（实测同一行
     * 设置项点角色的"窗口范围"没反应、点范围外才生效），漫游/贴边也只能停在离屏幕
     * 边 30% 窗宽的地方。
     *
     * 裁窗以**画布原点**为锚：窗口缩小多少，左上角就往里挪多少，角色的屏幕落点
     * 完全不动，所以用户看不到跳变。边距只会在并集变大时变小，窗口只涨不缩。
     */
    private fun applyContentSize() {
        val v = petView ?: return
        val p = petParams ?: return
        val w = wm ?: return
        if (canvasPxW <= 0 || canvasPxH <= 0) return
        // 量不出来就维持整幅画布（老行为），绝不让宠物被裁掉半边
        if (!v.canvasInsets(tmpInsets)) return

        val newW = bodySize(canvasPxW, tmpInsets.left, tmpInsets.right)
        val newH = bodySize(canvasPxH, tmpInsets.top, tmpInsets.bottom)
        if (newW == p.width && newH == p.height) return

        // 画布原点在屏幕上的位置不变 → 换算出新的窗口左上角
        val canvasX = p.x - (canvasPxW * appliedInsets.left).roundToInt()
        val canvasY = p.y - (canvasPxH * appliedInsets.top).roundToInt()
        appliedInsets.set(tmpInsets)
        p.width = newW
        p.height = newH
        p.x = canvasX + (canvasPxW * appliedInsets.left).roundToInt()
        p.y = canvasY + (canvasPxH * appliedInsets.top).roundToInt()
        clampParams(p, v)
        runCatching { w.updateViewLayout(v, p) }
        PetPrefs.setPosition(this, p.x, p.y)
        PetPrefs.packId(this)?.let { PetPrefs.setPackInsets(this, it, appliedInsets) }
        followBubble()
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
        val vw = if (p.width > 0) p.width else view.width
        val vh = if (p.height > 0) p.height else view.height
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
        // 行进距离按**画布**宽度换算（config 里的 minDist/maxDist 以 462px 画布为基准）；
        // 窗口已裁到角色矩形，用窗口宽会让每一步都短掉一半以上。
        val scaleBase = if (canvasPxW > 0) canvasPxW else viewW
        val distScale = scaleBase.toDouble() / PetMovePlanner.PET_REF_WIDTH
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
                // 息屏时不自排队：这一跳出去就没人再叫醒它，直到 SCREEN_ON 重新 startRoaming
                if (!screenOn) return
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
        val dm = resources.displayMetrics
        val d = dm.density
        val sp = dm.scaledDensity
        // 字号口径：按**角色**宽度取比例（窗口 = 角色矩形），再用 sp 兜住可读性上下限。
        // 之前按画布宽算（画布是角色的 2.5 倍），大尺寸档位下字号能到 70+px，气泡比
        // 宠物还宽、字几乎撑满框 —— 像块牌子而不像"说话"。
        val bodyW = petBodyWidth().toFloat().coerceAtLeast(1f)
        val fpx = (bodyW * BUBBLE_FONT_RATIO).coerceIn(
            BUBBLE_MIN_SP * sp,
            BUBBLE_MAX_SP * sp,
        )
        bubblePadX = (fpx * BUBBLE_PAD_X).roundToInt()
        bubblePadY = (fpx * BUBBLE_PAD_Y).roundToInt()
        val tf = petTypeface()

        val tv = bubbleView ?: TextView(this).apply {
            setTextColor(0xFF2B2B2B.toInt())
            includeFontPadding = false
            maxLines = BUBBLE_MAX_LINES
            ellipsize = android.text.TextUtils.TruncateAt.END
            // 软影 + 尾巴都要靠软件渲染：硬件画布会忽略 setShadowLayer 的模糊，
            // 气泡窗口是一块静止的小窗，软件渲染开销可以忽略。
            setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            background = PetBubbleDrawable()
        }.also { bubbleView = it }
        val bg = tv.background as PetBubbleDrawable
        // 圆角按单行正文区高度收敛成胶囊；尾巴高度由它算好后回填给内边距与定位
        bg.configure(fpx, fpx * BUBBLE_LINE_HEIGHT + bubblePadY * 2f)
        bubbleTailH = bg.tailHeight.roundToInt().coerceAtLeast(1)
        bubbleInset = bg.shadowInset.roundToInt().coerceAtLeast(1)

        tv.text = text
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, fpx)
        // 行高 1.5em：Android 单行默认约 1.2em，倍数换算后取 1.5/1.2
        tv.setLineSpacing(0f, BUBBLE_LINE_HEIGHT / 1.2f)
        if (tf != null) tv.typeface = tf else tv.typeface = Typeface.DEFAULT

        bubbleMaxW = minOf(
            (dm.widthPixels * 0.74f).toInt(),
            (bodyW * BUBBLE_MAX_W_RATIO).toInt(),
        ).coerceAtLeast((56 * d).toInt())
        tv.maxWidth = bubbleMaxW

        bubbleTailUp = false
        measureBubble(tv, bubbleTailUp)
        // 顶上塞不下就翻到脚下、尾巴朝上 —— 内边距要跟着换边，所以再量一次
        if (wantsTailUp()) {
            bubbleTailUp = true
            measureBubble(tv, bubbleTailUp)
        }
        val bw = bubbleW
        val bh = bubbleH

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
        // 目录 id 必须用**包目录 id**（PetPrefs.packId，如 "imported"）。
        // 之前用 cfg.spec.id —— 那是 pet-config.json 里 pets[0].id（恒为 "main"），
        // 拼出来是 packs/main/font/（不存在），包字体永远找不到、静默回落系统字体。
        val id = PetPrefs.packId(this) ?: cfg.spec.id
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

    /** 角色窗口宽度 px（窗口已经裁到角色矩形，所以它就是"看得见的宠物"宽度）。 */
    private fun petBodyWidth(): Int =
        petView?.width?.takeIf { it > 0 } ?: petParams?.width?.takeIf { it > 0 } ?: canvasPxW

    private fun petBodyHeight(): Int =
        petView?.height?.takeIf { it > 0 } ?: petParams?.height?.takeIf { it > 0 } ?: canvasPxH

    /**
     * 按朝向摆好内边距再量一次：尾巴朝下 → 底部多留尾巴的高度；朝上则留在顶部。
     * 量的结果写进 [bubbleW] / [bubbleH]。
     */
    private fun measureBubble(tv: TextView, tailUp: Boolean) {
        val top = bubblePadY + bubbleInset + if (tailUp) bubbleTailH else 0
        val bottom = bubblePadY + bubbleInset + if (tailUp) 0 else bubbleTailH
        tv.setPadding(bubblePadX + bubbleInset, top, bubblePadX + bubbleInset, bottom)
        tv.maxWidth = bubbleMaxW
        tv.measure(
            View.MeasureSpec.makeMeasureSpec(bubbleMaxW, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        bubbleW = tv.measuredWidth
        bubbleH = tv.measuredHeight
    }

    /**
     * 头顶放不放得下：气泡**正文**底边贴在角色头顶，尾巴尖只轻轻压过去
     * [BUBBLE_TAIL_PRESS] 倍尾巴高；窗口本身比气泡实体各边多出 [bubbleInset] 的投影余量。
     */
    private fun wantsTailUp(): Boolean {
        val p = petParams ?: return false
        val y = p.y + tailPressPx() - (bubbleH - bubbleInset)
        return y + bubbleInset < dp(BUBBLE_TOP_LIMIT_DP)
    }

    /** 尾巴尖压进角色头顶的深度 px（越小越"悬空"，越大越"贴住"）。 */
    private fun tailPressPx(): Int = (bubbleTailH * (1f - BUBBLE_TAIL_PRESS)).roundToInt()

    /**
     * 摆气泡。三条硬规矩：
     *  1. **不压动画**：气泡实体（不含投影留白）的底边贴在角色头顶上方，只有尾巴尖压过去一点点；
     *  2. **尾巴指向角色**：尾巴水平位置 = 角色中线在气泡内的投影，横向被夹到屏幕里也还指着人；
     *  3. 头顶塞不下就翻到脚下，尾巴朝上（内边距由 [measureBubble] 换边）。
     */
    private fun placeBubble(lp: WindowManager.LayoutParams, bw: Int, bh: Int) {
        val p = petParams ?: return
        val dm = resources.displayMetrics
        val d = dm.density
        val pad = (8 * d).toInt()
        val bodyW = petBodyWidth()
        val bodyH = petBodyHeight()
        val inset = bubbleInset

        // x：以角色中线为轴居中，再夹进屏幕（夹了以后尾巴位置自己会跟上）
        var x = p.x + bodyW / 2 - bw / 2
        x = x.coerceIn(pad, (dm.widthPixels - bw - pad).coerceAtLeast(pad))

        // y：窗口底边 = 尾巴尖 + 投影余量
        var y = p.y + tailPressPx() - (bh - inset)
        var up = false
        if (y + inset < dp(BUBBLE_TOP_LIMIT_DP)) {
            // 宠物贴着状态栏：翻到脚下，尾巴朝上
            y = p.y + bodyH - tailPressPx() - inset
            up = true
        }
        lp.x = x
        lp.y = y

        val bg = bubbleView?.background as? PetBubbleDrawable
        bg?.setTailUp(up)
        // 尾巴尖要指在角色中线上：气泡被屏幕夹偏了，尾巴也得跟着挪
        val bodyLeft = x + inset
        val bodyPxW = (bw - inset * 2).coerceAtLeast(1)
        bg?.setTailFraction((p.x + bodyW / 2f - bodyLeft) / bodyPxW)
    }

    private fun followBubble() {
        val tv = bubbleView ?: return
        val lp = bubbleParams ?: return
        if (System.currentTimeMillis() > bubbleUntilMs) {
            hideBubble()
            return
        }
        // 拖到屏幕顶端时朝向要反过来：换边后高度会变，得重新量
        if (wantsTailUp() != bubbleTailUp) {
            bubbleTailUp = !bubbleTailUp
            measureBubble(tv, bubbleTailUp)
            lp.width = bubbleW
            lp.height = bubbleH
        }
        placeBubble(lp, bubbleW, bubbleH)
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
            // 门禁优先于其他一切状态：被挡住时"已连接/等待"都会误导
            st.bridgeMissing -> s(R.string.pet_notif_bridge_missing)
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
                // 走到这里说明角色已经挂上了，门禁必然是过的：把"被门禁挡住"的旧标记清掉，
                // 否则设置页会一直显示"已暂停：桥插件未就绪"而宠物明明在屏幕上。
                bridgeMissing = false,
                packLabel = label,
                placeholder = pack == null,
                listening = server?.isRunning == true,
            )
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /**
     * 播放失败：写日志 + 进状态总线，同一段 [PLAY_ERROR_THROTTLE_MS] 内只报一次。
     *
     * 为什么要限流：链是"选段 → 播 → 播完再选"，坏段会被反复抽到；不限流的话
     * 一次解码事故能在几秒内刷出几十条，把真正有用的上下文冲掉。
     */
    private fun reportPlaybackError(slug: String, reason: String) {
        val now = SystemClock.uptimeMillis()
        val last = playErrorAt[slug]
        if (last != null && now - last < PLAY_ERROR_THROTTLE_MS) return
        if (playErrorAt.size > 64) playErrorAt.clear()
        playErrorAt[slug] = now
        Log.w(TAG, "playback error: slug=$slug reason=$reason")
        PetStatusBus.update { it.copy(lastError = "play:$slug:$reason") }
    }

    /** 服务没有 Activity 的 locale 包装，用户文案统一走 LocaleHelper。 */
    private fun s(id: Int): String = LocaleHelper.wrap(this).getString(id)

    private fun s(id: Int, vararg args: Any): String =
        LocaleHelper.wrap(this).getString(id, *args)

    companion object {
        private const val TAG = "DshPetService"
        private const val CHANNEL_ID = "pet_overlay"
        private const val NOTIF_ID = 0x5E71
        private const val BUBBLE_HOLD_MS = 10_000L // 桌面版 BUBBLE_DURATION_MS = 10s

        // 气泡度量。字号口径与桌面版 fonts.ts 同源（0.0455 × 基准宽 462 ≈ 21px），
        // 但基准从「画布宽」换成「**角色宽**」：画布是角色的 2.5 倍，按画布算出来的字
        // 在大档位下能到 70+px，气泡比宠物还宽。系数 0.075 ≈ 桌面版比例 × 角色占比校正，
        // 再用 sp 上下限兜住极端档位（小宠物不糊成一坨、大屏不变成标语牌）。
        private const val BUBBLE_FONT_RATIO = 0.075f
        private const val BUBBLE_MIN_SP = 11f
        private const val BUBBLE_MAX_SP = 19f
        // 对齐 dsh-pet web：padding 0.4835em/0.6593em、line-height 1.6
        private const val BUBBLE_PAD_X = 0.6593f
        private const val BUBBLE_PAD_Y = 0.4835f
        private const val BUBBLE_LINE_HEIGHT = 1.6f
        private const val BUBBLE_MAX_LINES = 3
        /** 气泡最大宽度 = 角色宽 × 该比例（再被屏宽 74% 收口）。 */
        private const val BUBBLE_MAX_W_RATIO = 1.35f
        /** 尾巴尖压进角色头顶的比例（相对尾巴高度）：贴住但不吃掉脸。 */
        private const val BUBBLE_TAIL_PRESS = 0.85f
        /** 头顶留白下限 dp：低于它就把气泡翻到脚下（避开状态栏）。 */
        private const val BUBBLE_TOP_LIMIT_DP = 24
        private const val WATCHDOG_MS = 30_000L
        /** 同一段动画的播放失败上报间隔：避免坏段被反复抽到时刷爆日志。 */
        private const val PLAY_ERROR_THROTTLE_MS = 5_000L
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

        /**
         * MainActivity.onResume 调用：只在硬门禁全过（已开启 + 悬浮窗权限 +
         * 桥插件就绪 + 资源包就绪）时才恢复 —— 条件不齐就不拉起服务，
         * 与设置页的开启门禁同源（[PetPluginDetector.canEnable]），不能跳过。
         */
        fun restoreIfEnabled(ctx: Context) {
            if (!PetPrefs.enabled(ctx)) return
            if (!PetPluginDetector.canEnable(ctx)) return
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
