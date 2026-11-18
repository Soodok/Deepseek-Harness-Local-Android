package app.dsh.mobile

import android.app.Activity
import android.content.Intent
import android.view.PixelCopy
import android.os.Bundle
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.android.FlutterActivityLaunchConfigs
import io.flutter.embedding.android.RenderMode
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

/**
 * 独立 Flutter 聊天入口。只桥接引擎状态和 WebView 回退，不复制 WebUI 会话文件解析。
 */
class NativeChatActivity : FlutterActivity() {
    private var channel: MethodChannel? = null

    /**
     * 当前这个原生会话对应的 DSH 会话标识。
     *
     * 由 Flutter 侧在真正打开某个会话时经 [CHANNEL] 的 `setSession` 上报，
     * 不在这里猜：会话身份只有引擎与工作区知道。切回 Web 时把它带回去，
     * 让 Web 打开同一个会话，而不是"最近的一个"。
     */
    private var sessionId: String? = null

    /**
     * 引擎冷启动期间露出窗口底色，而不是先铺一块不透明黑。
     *
     * Flutter 的 surface 默认是 opaque：从 Activity 创建到 Flutter 画出第一帧之间，
     * 屏幕上就是这块**引擎自己的**黑色，主题里的 windowBackground 根本看不到。
     * 实测（beta.52 → beta.53 三次录屏）这个空窗是 1.9–3.7 秒——Web 是深色页、
     * 会话页是浅色页，中间夹一段纯黑就是一次硬切，与「自然消散、渐变浮现」相反。
     *
     * 只把引擎 surface 改成 transparent 还不够：windowBackground 若是不透明色，空窗里
     * 就是那个颜色（beta.53 第一版是纯黑 4 秒、beta.52 是 #151517 1.9 秒，两次录屏
     * 逐帧亮度都掉到 16.x）。所以 Theme.Dsh.NativeChat 同时把窗口设成
     * windowIsTranslucent=true + 透明底：空窗里露出来的是**被覆盖的 Web 页面本身**，
     * 新页面 220ms 淡入叠在上面，这才是真正的交叉淡化。
     *
     * 注意：这个 Flutter 版本（3.50 / embedding 1.0.0-371f77202a）已经没有
     * io.flutter.embedding.android.SplashScreenDrawable 这个 meta-data 了（反查 aar
     * 里的类表确认过），启动图只能靠窗口/主题承载。
     */
    override fun getBackgroundMode(): FlutterActivityLaunchConfigs.BackgroundMode =
        FlutterActivityLaunchConfigs.BackgroundMode.transparent

    /**
     * 用 TextureView 渲染，而不是默认的 SurfaceView。
     *
     * 这一条是「原生渐变浮现」能不能成立的分水岭：SurfaceView 的画面由
     * SurfaceFlinger 单独合成在一个独立图层上，**不参与宿主窗口的 alpha**。
     * 于是 Dart 侧无论把 opacity 从 0 淡到 1、窗口动画怎么配，屏幕上都是
     * 「第一帧直接整块出现」——beta.53～beta.56 六轮录屏逐帧亮度都是
     * 31.5 → 228～233 的单帧跳变，中间一帧过渡都没有，原因就在这里。
     *
     * TextureView 是普通 View，画面被合成进窗口图层，alpha 才真的作用到内容上。
     * 代价是比 SurfaceView 多一次纹理拷贝；对话页静态内容为主，可以接受。
     */
    override fun getRenderMode(): RenderMode = RenderMode.texture


    /**
     * 引擎真正把第一帧画到屏幕上之后，才通知 Dart 侧开始入场淡化。
     *
     * 这是整段切换里最关键的一环：Flutter 自己的报时（initState / post-frame）在
     * 引擎冷启时不可靠 —— 这一页起来要 5–8 秒，等它真被看见，260ms/2s 的淡化早跑完了，
     * 屏幕上第一眼就是不透明整帧（实测 w3/v2/t2 三个包逐帧亮度都是 31.5 → 228–229，
     * 中间一帧过渡都没有）。onFlutterUiDisplayed 是 embedding 报告"首帧已显示"的
     * 回调，从这里起跳，渐显才真的落在用户看得见的那段时间上。
     */
    override fun onFlutterUiDisplayed() {
        super.onFlutterUiDisplayed()
        firstFrameDrawn = true
        announceVisibleOnce()
    }

    /**
     * 窗口真正拿到焦点，才说明这一页已经在屏幕上、用户看得见了。
     *
     * 实测（beta.57，TextureView 生效之后）：只等 onFlutterUiDisplayed 起跳是不够的 ——
     * 那个回调早于内容真正上屏约 1.5–2 秒（活动切换 + 首次合成），短淡化（320/520ms）
     * 全在那段看不见的时间里跑完，屏幕上仍是单帧硬切（t8.mp4 逐帧 31.5 → 233.7）。
     * 把 3000ms 的探针放慢才看得见渐变（t5.mp4：31.8 → 81 → 191 → 205 → 218 → 231 → 233），
     * 正说明淡化本身是有效的、只是起点太早。
     *
     * 于是改成「首帧已画 + 窗口已获得焦点」两个条件同时成立才起跳。
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            windowFocused = true
            announceVisibleOnce()
        }
    }

    /**
     * 用窗口 alpha 做入场淡化。
     *
     * 为什么不再依赖 Flutter 画透明度：这一页是冷启动进来的，切换那几秒 Flutter 的
     * 帧循环被拖到 200–400ms 一帧，beta.59 的日志显示宿主首帧信号 00:25:00.68 到达、
     * controller 从 0 起跳，到 00:25:07.58 才产出第一个 tick —— 7 秒里一帧都没画，
     * 于是第一个 tick 直接把进度算成 1.0，屏幕上仍是一帧从透明到不透明的硬切。
     * 按帧推进能缓解（beta.60 录到 31.7 → 152 → 233 的两跳），但仍受制于帧率。
     *
     * 窗口 alpha 由系统合成器逐帧应用，与 Flutter 的帧率无关：本页主题是
     * translucent + 透明底，窗口 alpha 从 0 涨到 1，下面那页（Web）就一直透出来，
     * 形成一次真正的交叉淡化。TextureView（见 getRenderMode）保证内容画在窗口图层里，
     * 因此窗口 alpha 对内容生效。
     */
    private fun startWindowFade() {
        if (windowFaded) return
        windowFaded = true
        // 尊重系统的「移除动画/减少动效」：动画倍率为 0 时直接到位，不做装饰性淡化。
        val scale = runCatching {
            android.provider.Settings.Global.getFloat(contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE)
        }.getOrDefault(1f)
        if (scale <= 0f) {
            setWindowAlpha(1f)
            return
        }
        val duration = (WINDOW_FADE_MS * scale).toLong().coerceIn(120L, 1200L)
        setWindowAlpha(0f)
        // 手写阶梯而不是 ValueAnimator：切进来的头一两秒主线程被引擎初始化独占，
        // ValueAnimator 的帧回调排不上队。实测（beta.62 日志）start 之后 1.2 秒里只
        // 回调了一次、进度还是 0.0，随后被兜底直接推成不透明 —— 屏幕上仍是硬切。
        //
        // 而且**每一步至少推进 1/FADE_STEPS**，不按墙钟折算：
        // 主线程卡成什么样都不影响「淡化一共跨过多少个可见台阶」。
        // 先按墙钟试过（beta.63 tH.mp4）：第一步算出来 t=0.08 就卡住不动了，
        // 页面以 8% 透明度挂了十几秒，看着像一页幽灵。
        val startAt = longArrayOf(0L)
        val step = object : Runnable {
            private var progress = 0f
            override fun run() {
                if (isFinishing || isDestroyed) return
                if (startAt[0] == 0L) startAt[0] = android.os.SystemClock.uptimeMillis()
                val elapsed = (android.os.SystemClock.uptimeMillis() - startAt[0]).toFloat()
                val byClock = (elapsed / duration).coerceIn(0f, 1f)
                val byStep = progress + 1f / FADE_STEPS
                progress = if (byClock > byStep) byClock else byStep
                if (progress > 1f) progress = 1f
                setWindowAlpha(progress)
                if (progress < 1f) handler.postDelayed(this, STEP_DELAY_MS)
            }
        }
        handler.post(step)
    }

    private fun setWindowAlpha(alpha: Float) {
        if (isFinishing || isDestroyed) return
        val attrs = window.attributes
        if (attrs.alpha == alpha) return
        attrs.alpha = alpha
        window.attributes = attrs
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    private var windowFaded = false

    private var pendingFirstFrame = false
    private var firstFrameDrawn = false
    private var windowFocused = false
    private var announced = false

    private fun announceVisibleOnce() {
        if (announced || !firstFrameDrawn || !windowFocused) return
        announced = true
        // 有底图就溶底图：内容已经画完了，alpha 只是叠在静态画面上，不受冷启动帧率牵连。
        // 没有底图（拍图失败/旧系统）才退回窗口 alpha 阶梯，至少不是纯黑。
        if (backdropView != null) {
            // 覆盖层留着不动，等 Flutter 自己把同一张图画出来再撤（releaseBackdrop）。
            // 为什么不在这一层溶：覆盖层是独立子窗口，底下是 Flutter 的另一个 Surface，
            // 两者之间没有可用的混合过渡 —— beta.59~beta.70 十几轮录屏全部证明了这点
            // （底图淡出的那一秒里底下是黑的，然后一帧跳到 233.8）。
            // 真正的淡化交给 Flutter：它把同一张图画在自己那一层的最上面，
            // 再由同一个合成器把它淡掉。
            handler.postDelayed({ if (!isFinishing && !isDestroyed) startBackdropWatch() }, 1200)
        } else {
            // 底图还在 post 队列里没挂上：让它在挂上那一刻自己收尾；
            // 真挂不上（权限/异常）时由那条失败分支退回到窗口淡化。
            backdropFadeRequested = true
        }
        // 用 pending 兜住时序：回调可能早于 configureFlutterEngine 里的 channel 赋值。
        val target = channel
        if (target == null) {
            pendingFirstFrame = true
        } else {
            target.invokeMethod("firstFrameShown", null)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 从 Web 切过来时主界面已经知道当前会话，直接承接，不经过任何选择页。
        sessionId = intent?.getStringExtra(EXTRA_SESSION_ID)?.takeIf { it.isNotBlank() }
        // 主界面在点击那一刻拍下的 Web 页面，铺在整个窗口最上层当入场底图。
        // 冷启期间（实测 4–7 秒）屏幕上就不再是黑屏，而是「刚才那一页」。
        backdropPath = intent?.getStringExtra(EXTRA_BACKDROP)?.takeIf { it.isNotBlank() }
        backdropPath?.let { attachBackdrop(it) }
    }

    /** 入场底图：铺满窗口的 ImageView，盖在 Flutter 视图之上，首帧就绪后溶解掉。 */
    private var backdropView: android.widget.ImageView? = null
    private var backdropParams: android.view.WindowManager.LayoutParams? = null

    /** 入场底图 PNG 的落盘路径。 */
    private var backdropPath: String? = null
    private var backdropManager: android.view.WindowManager? = null

    /** 首帧已就绪、但底图还没挂上：等它挂上再溶。 */
    private var backdropFadeRequested = false

    /** 观察窗口缓冲的后台线程（见 startBackdropWatch）。 */
    private var backdropWatchThread: android.os.HandlerThread? = null

    /**
     * 把主界面拍下的那一屏铺在窗口最上层。
     *
     * 解码同步做：PNG 解一张 1080×2340 只要几十毫秒，换来的是「第一帧里就有底图」，
     * 不会出现先黑一下再补图的闪跳。
     */
    private fun attachBackdrop(path: String) {
        // 底图必须是**独立子窗口**，不能是 Activity 视图树里的子 View。
        //
        // 两轮实测把它钉死了：底图挂在 android.R.id.content 与 decorView 上都一样，
        // logcat 能证明它确实存在（backdrop attached ... 3.4 秒后 fade out ... 2.4 秒后
        // removed），可录屏里那几秒亮度始终是 31.7、然后一帧跳到 233.8，中间没有过渡。
        // 原因是 Flutter 的画面由引擎自己的 Surface 承载，在窗口合成里压在 Activity
        // 视图树之上：底图放在视图树顶端也仍在它下面，首帧一画上去就整块盖掉，
        // 改 alpha 自然看不见。
        //
        // TYPE_APPLICATION_PANEL 子窗口挂在同一个窗口 token 上，才真正压在 Flutter
        // Surface 之上，改它的窗口 alpha 才能真正做「旧页溶进新页」。
        val bitmap = runCatching {
            android.graphics.BitmapFactory.decodeFile(path)
        }.getOrNull()
        if (bitmap == null) {
            // 拍图失败就退回原来的窗口淡化，至少不是纯黑。
            logFade("backdrop decode failed: " + path)
            return
        }
        val image = android.widget.ImageView(this)
        image.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
        image.setImageBitmap(bitmap)
        image.setBackgroundColor(0xFF151517.toInt())
        val lp = android.view.WindowManager.LayoutParams(
            android.view.WindowManager.LayoutParams.MATCH_PARENT,
            android.view.WindowManager.LayoutParams.MATCH_PARENT,
            android.view.WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
            android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            android.graphics.PixelFormat.TRANSLUCENT,
        )
        lp.gravity = android.view.Gravity.TOP or android.view.Gravity.START
        // 等到窗口真正 attach 之后再加：onCreate 里 decorView.windowToken 还是 null，
        // 直接 addView 抛 BadTokenException（beta.63 logcat: "token null is not valid"）。
        // post 到 decorView 上，run 的时候窗口已经挂到 WindowManager 了。
        window.decorView.post {
            @Suppress("DEPRECATION")
            lp.token = window.decorView.windowToken
            val manager = getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager
            val added = runCatching { manager.addView(image, lp) }
                .onFailure { logFade("backdrop overlay add threw: " + it) }
                .isSuccess
            if (!added) {
                logFade("backdrop overlay add failed, falling back to the window fade")
                image.setImageDrawable(null)
                backdropFadeRequested = false
                startWindowFade()
                return@post
            }
            backdropView = image
            backdropParams = lp
            backdropManager = manager
            logFade("backdrop overlay attached: " + bitmap.width + "x" + bitmap.height)
            // 底图是 post 上去的，可能晚于首帧信号：真到了就直接把这笔账结掉。
            if (backdropFadeRequested) {
                backdropFadeRequested = false
                fadeOutBackdrop()
            }
        }
    }

    /**
     * 首帧画好之后把底图溶掉。
     *
     * 这一步与之前的「窗口 alpha 淡化」关键区别：要露出来的内容**已经画完了**，
     * 所以 alpha 只是叠在一张静态画面上，不再受 200–400ms 一帧的冷启动帧率牵连。
     */
    private fun fadeOutBackdrop() {
        val image = backdropView ?: return
        backdropView = null
        val scale = animatorScale()
        if (scale <= 0f) {
            removeBackdrop(image)
            return
        }
        val duration = (BACKDROP_FADE_MS * scale).toLong().coerceIn(200L, 1600L)
        logFade("backdrop fade out ${duration}ms")
        // 手写台阶而不是 animate()：首帧刚画完那一瞬主线程还在收尾（实测 animate 的
        // 260ms 动画拖到 614ms 才结束、中间一帧都没上屏，屏幕上就是一次硬跳）。
        // 每 STEPS 分之一直接设一次 alpha，墙面时间到了就收尾，卡住也不会永远挂着。
        val steps = BACKDROP_FADE_STEPS
        val stepDelay = (duration / steps).coerceAtLeast(16L)
        val step = object : Runnable {
            private var index = 0
            override fun run() {
                if (isFinishing || isDestroyed) return
                index++
                // 纯按台阶推进，不看墙钟。beta.67 录到 41.2 / 44.7 两级之后直接跳到底：
                // 冷启时主线程被占住，等它回来时墙钟早已超过总时长，按时间折算的进度
                // 一步就是 1.0，中间几级全被跳过。台阶法保证「一定跨过这么多级」。
                val progress = (index.toFloat() / steps).coerceIn(0f, 1f)
                setBackdropAlpha((1f - progress).coerceIn(0f, 1f))
                if (progress < 1f) handler.postDelayed(this, stepDelay) else removeBackdrop(image)
            }
        }
        handler.postDelayed(step, stepDelay)
    }

    /**
     * 盯着 Activity 窗口的缓冲，直到新画面真的画进去，再把底图溶掉。
     *
     * 为什么要「看」而不是「等固定时长」：beta.65/66 两次录屏都显示，底图开始淡出
     * 的那一秒里底下仍然是黑的（k11.mp4 帧 100 → 44.6 → 31.5 → 一帧跳到 233.9），
     * 说明 onFlutterUiDisplayed 并不等于「新画面已经进了窗口缓冲」。这里直接每 80ms
     * 从窗口中心取一小块像素，与底图同位置比较；连续两次明显不同就认为新页已经上屏。
     *
     * 取样放在独立 HandlerThread 上，冷启时主线程正忙，不能再去挤它。
     */
    private fun startBackdropWatch() {
        val image = backdropView ?: return
        val bitmap = (image.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap ?: return
        val root = window.decorView
        val rw = root.width
        val rh = root.height
        if (rw <= 0 || rh <= 0) {
            handler.postDelayed({ if (!isFinishing && !isDestroyed) startBackdropWatch() }, 60)
            return
        }
        val size = 48
        val left = (rw - size) / 2
        val top = (rh - size) / 2
        val rect = android.graphics.Rect(left, top, left + size, top + size)
        val reference = IntArray(size * size)
        val sx = bitmap.width.toFloat() / rw
        val sy = bitmap.height.toFloat() / rh
        for (y in 0 until size) {
            for (x in 0 until size) {
                val bx = ((left + x) * sx).toInt().coerceIn(0, bitmap.width - 1)
                val by = ((top + y) * sy).toInt().coerceIn(0, bitmap.height - 1)
                reference[y * size + x] = bitmap.getPixel(bx, by)
            }
        }
        stopBackdropWatch()
        val thread = android.os.HandlerThread("backdrop-watch").also { it.start() }
        backdropWatchThread = thread
        val watchHandler = android.os.Handler(thread.looper)
        val sample = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val startedAt = android.os.SystemClock.uptimeMillis()
        var hits = 0
        val poll = object : Runnable {
            override fun run() {
                if (backdropView == null) {
                    stopBackdropWatch()
                    return
                }
                if (android.os.SystemClock.uptimeMillis() - startedAt > BACKDROP_WATCH_TIMEOUT_MS) {
                    logFade("backdrop watch timed out")
                    runOnUiThread { fadeOutBackdrop() }
                    stopBackdropWatch()
                    return
                }
                val self = this
                runCatching {
                    PixelCopy.request(
                        window,
                        rect,
                        sample,
                        { result: Int ->
                            if (result != PixelCopy.SUCCESS) {
                                watchHandler.postDelayed(self, BACKDROP_WATCH_INTERVAL_MS)
                                return@request
                            }
                            var diff = 0L
                            var luma = 0L
                            for (i in 0 until size * size) {
                                val a = sample.getPixel(i % size, i / size)
                                val b = reference[i]
                                diff += Math.abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)).toLong()
                                diff += Math.abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)).toLong()
                                diff += Math.abs((a and 0xFF) - (b and 0xFF)).toLong()
                                luma += (((a shr 16) and 0xFF) + ((a shr 8) and 0xFF) + (a and 0xFF)) / 3
                            }
                            val mean = diff / (size * size * 3)
                            val brightness = luma / (size * size)
                            // 真正可靠的信号是「窗口内容变亮了」：原生会话页是浅色底
                            // （实测整屏亮度 223，Web 深色页 33），Flutter 第一帧一旦进到
                            // 窗口缓冲，中心区亮度就会跳上去。只用「与底图不同」判断会误报
                            // ——窗口里原来的东西本来就未必与底图一致（beta.67 k12 就是
                            // 提前触发，底图在内容出现前就化完了，屏幕上留一帧黑）。
                            // 只看亮度。底图在**独立子窗口**里，主窗口的 PixelCopy 读不到它，
                            // 读到的是「底图下面那层」——Flutter 还没画时就是纯黑（luma=0）。
                            // beta.69 误用过「与底图不同」这一条：黑窗口当然与深色 Web 底图
                            // 不同，于是 mean=46 提前触发溶解（k16.mp4 帧 191 起 44.7 → 31.5
                            // → 一帧跳到 233.8）。亮度才是唯一可靠信号。
                            val looksDrawn = brightness >= BACKDROP_DRAWN_LUMA
                            hits = if (looksDrawn) hits + 1 else 0
                            if (hits >= 3) {
                                logFade("backdrop watch: drawn mean=" + mean + " luma=" + brightness)
                                runOnUiThread { fadeOutBackdrop() }
                                stopBackdropWatch()
                            } else {
                                watchHandler.postDelayed(self, BACKDROP_WATCH_INTERVAL_MS)
                            }
                        },
                        watchHandler,
                    )
                }.onFailure { logFade("backdrop watch failed: " + it) }
            }
        }
        watchHandler.post(poll)
    }

    private fun stopBackdropWatch() {
        backdropWatchThread?.quitSafely()
        backdropWatchThread = null
    }
    private fun removeBackdrop(image: android.widget.ImageView) {
        // 不要 recycle：窗口移除之后，ViewRootImpl 队列里还有一帧要绘制这同一个
        // BitmapDrawable，recycle 会让它抛
        // "Canvas: trying to use a recycled bitmap" 直接弄死进程
        // （beta.64 实测：backdrop removed 之后 0.6 秒 FATAL EXCEPTION、进程 died）。
        // 先解绑 drawable，再交给 GC —— 一张 10MB 的 ARGB 位图不值得用崩溃风险去省。
        image.setImageDrawable(null)
        runCatching { backdropManager?.removeView(image) }
        backdropManager = null
        backdropParams = null
        logFade("backdrop removed")
    }

    /**
     * 改子窗口的 alpha，而不是 ImageView 自己的 view alpha。
     *
     * 子窗口有独立 Surface，view alpha 只是它的内部绘制参数；改窗口 alpha 才是由系统
     * 合成器按层混合，与「窗口淡入淡出」同一套机制。
     */
    private fun setBackdropAlpha(alpha: Float) {
        val image = backdropView ?: return
        val lp = backdropParams ?: return
        val manager = backdropManager ?: return
        if (lp.alpha == alpha) return
        lp.alpha = alpha
        runCatching { manager.updateViewLayout(image, lp) }
    }

    /**
     * 把入场底图按 PNG 交给 Flutter；没有底图就回 null。
     *
     * 直接读**落盘的那份文件**，不用覆盖层里的 Bitmap：Dart 侧的 main() 在
     * configureFlutterEngine 之后立刻就来问，而覆盖层是 post 到 decorView 上才加的，
     * 那时 Bitmap 可能还没准备好（beta.71 实测：Flutter 拿到 null，于是没画底图，
     * 只等覆盖层被撤，屏幕上仍是一次硬切）。文件在 onCreate 时就已经存在。
     */
    private fun backdropPng(): ByteArray? {
        val path = backdropPath ?: return null
        return runCatching { java.io.File(path).readBytes() }.getOrNull()
    }

    private fun animatorScale(): Float = runCatching {
        android.provider.Settings.Global.getFloat(
            contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
        )
    }.getOrDefault(1f)

    private fun logFade(message: String) {
        android.util.Log.i("NativeChatFade", message)
    }

    /**
     * 待保存的分享文本。用户先在系统文件选择器里定好位置，回调里才写入，
     * 不用应用自己的目录冒充"下载"。
     */
    private var pendingSaveText: String? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        channel = MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            CHANNEL,
        ).also { methodChannel ->
            methodChannel.setMethodCallHandler { call, result ->
                when (call.method) {
                    "readAppearance" -> {
                        val prefs = getSharedPreferences("native_chat_ui", MODE_PRIVATE)
                        result.success(mapOf("mode" to prefs.getString("mode", "dark"), "mood" to prefs.getString("mood", "mist")))
                    }
                    "consumeFirstFrame" -> {
                        // Dart 侧订阅前先问一句：首帧是不是已经显示过了？
                        val already = pendingFirstFrame || announced
                        pendingFirstFrame = false
                        result.success(already)
                    }
                    "writeAppearance" -> {
                        val mode = call.argument<String>("mode")
                        val mood = call.argument<String>("mood")
                        if (mode !in setOf("system", "light", "dark") || mood !in setOf("mist", "sand", "sage", "plum")) {
                            result.error("appearance/invalid", "Unknown appearance value", null)
                        } else {
                            result.success(getSharedPreferences("native_chat_ui", MODE_PRIVATE).edit().putString("mode", mode).putString("mood", mood).commit())
                        }
                    }
                    "readDisplaySize" -> {
                        val prefs = getSharedPreferences("native_chat_ui", MODE_PRIVATE)
                        result.success(prefs.getString("displaySize", "standard"))
                    }
                    "writeDisplaySize" -> {
                        val size = call.argument<String>("size")
                        if (size !in setOf("compact", "standard", "relaxed")) {
                            result.error("display/invalid", "Unknown display size", null)
                        } else {
                            result.success(
                                getSharedPreferences("native_chat_ui", MODE_PRIVATE)
                                    .edit().putString("displaySize", size).commit(),
                            )
                        }
                    }
                    "engineInfo" -> result.success(engineInfo())
                    "webUrl" -> result.success(supervisorOrNull()?.webUrl().orEmpty())
                    "engineState" -> result.success(engineState())
                    "openRuntimeSettings" -> {
                        openRuntimeSettings(call.argument<String>("target"))
                        result.success(true)
                    }
                    "openSshManager" -> { openSshManager(call.argument<String>("platform")); result.success(true) }
                    "openResource" -> {
                        val uri = call.argument<String>("uri")?.let { android.net.Uri.parse(it) }
                        val allowed = uri != null && uri.scheme in setOf("http", "https") && !uri.host.isNullOrEmpty() && uri.userInfo.isNullOrEmpty()
                        result.success(allowed && runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }.isSuccess)
                    }
                    "openSettingsSection" -> result.success(openSettingsSection(call.argument<String>("section")))
                    "shareText" -> result.success(shareText(call.argument<String>("text"), call.argument<String>("subject")))
                    "saveText" -> result.success(saveText(call.argument<String>("text"), call.argument<String>("suggestedName")))
                    // 首屏会话：从 Web 切过来时带进来的那个；没有就为 null，
                    // Flutter 会按引擎自己的目录决定打开哪一个。
                    "initialSession" -> result.success(sessionId)
                    // Flutter 真正打开会话后上报，供"切回 Web"带回同一会话。
                    "setSession" -> {
                        sessionId = call.argument<String>("sessionId")?.takeIf { it.isNotBlank() }
                        result.success(true)
                    }
                    "backdropImage" -> {
                        val bytes = backdropPng()
                        logFade("backdrop png requested, bytes=" + (bytes?.size ?: -1))
                        result.success(bytes)
                    }
                    "releaseBackdrop" -> {
                        // Flutter 已经在自己那一层画出同一张图了，这里一次性撤掉覆盖层：
                        // 两层是同一个像素内容，撤掉的瞬间看不出跳变。
                        runCatching { backdropView?.let { backdropManager?.removeView(it) } }
                        backdropView = null
                        backdropManager = null
                        backdropParams = null
                        stopBackdropWatch()
                        handler.removeCallbacksAndMessages(null)
                        logFade("backdrop released by flutter")
                        result.success(true)
                    }
                    "openWebFallback" -> {
                        openWebFallback()
                        result.success(true)
                    }
                    else -> result.notImplemented()
                }
            }
        }
    }

    /** 文件选择器的结果：拿到 Uri 才写，取消就丢弃待存文本。 */
    @Deprecated("FlutterActivity 继承 android.app.Activity，结果走这个回调")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_SAVE_TEXT) {
            val text = pendingSaveText
            pendingSaveText = null
            val uri = data?.data
            if (resultCode != Activity.RESULT_OK || uri == null || text == null) return
            val ok = runCatching {
                contentResolver.openOutputStream(uri, "w")?.use { out ->
                    out.write(text.toByteArray(Charsets.UTF_8))
                    out.flush()
                } ?: error("output stream unavailable")
            }.isSuccess
            android.widget.Toast.makeText(
                this,
                if (ok) "已保存" else "保存失败，请换一个位置重试",
                android.widget.Toast.LENGTH_LONG,
            ).show()
            return
        }
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stopBackdropWatch()
        super.onDestroy()
    }

    override fun cleanUpFlutterEngine(flutterEngine: FlutterEngine) {
        channel?.setMethodCallHandler(null)
        channel = null
        super.cleanUpFlutterEngine(flutterEngine)
    }

    /** 纯文本分享面板；返回是否真的弹出了选择器。 */
    private fun shareText(text: String?, subject: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            subject?.takeIf { it.isNotBlank() }?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
        }
        return runCatching {
            startActivity(Intent.createChooser(intent, "分享对话内容"))
            true
        }.getOrDefault(false)
    }

    /** 让用户用系统文件选择器保存纯文本；回调里才写文件。 */
    private fun saveText(text: String?, suggestedName: String?): Boolean {
        if (text.isNullOrBlank()) return false
        pendingSaveText = text
        val name = suggestedName?.takeIf { it.isNotBlank() } ?: "dsh-conversation.txt"
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, name)
        }
        return runCatching {
            startActivityForResult(intent, REQUEST_SAVE_TEXT)
            true
        }.getOrElse {
            pendingSaveText = null
            false
        }
    }

    private fun supervisorOrNull(): app.dsh.mobile.engine.EngineSupervisor? =
        (application as? DshApp)?.let { app ->
            runCatching { app.supervisor }.getOrNull()
        }

    private fun engineInfo(): Map<String, Any> {
        val supervisor = supervisorOrNull()
        val mode = app.dsh.mobile.engine.EngineModeSelector.lastMode
        val resolved = app.dsh.mobile.engine.EngineModeSelector.resolved
        val runtimeMode = if (!resolved) "unknown" else when (mode) {
            app.dsh.mobile.engine.EngineModeSelector.Mode.NATIVE -> "native"
            app.dsh.mobile.engine.EngineModeSelector.Mode.LINUX -> "linux"
            app.dsh.mobile.engine.EngineModeSelector.Mode.NATIVE_FALLBACK -> "native_fallback"
        }
        val distro = app.dsh.mobile.engine.EngineModeSelector.lastDistro
        return mapOf(
            "webUrl" to (supervisor?.webUrl().orEmpty()),
            "available" to (supervisor?.hasLoadableUrl() == true),
            "state" to engineState(supervisor),
            "runtimeMode" to runtimeMode,
            "runtimeResolved" to resolved,
            "runtimeReason" to app.dsh.mobile.engine.EngineModeSelector.lastReason,
            "distro" to distro,
        )
    }

    private fun engineState(supervisor: app.dsh.mobile.engine.EngineSupervisor? = supervisorOrNull()): String =
        supervisor?.state?.value?.let { it::class.simpleName ?: "unknown" } ?: "uninitialized"

    private fun openRuntimeSettings(target: String? = null) {
        val destination = target ?: when (app.dsh.mobile.engine.EngineModeSelector.lastMode) {
            app.dsh.mobile.engine.EngineModeSelector.Mode.LINUX -> "linux"
            else -> "native"
        }
        when (destination) {
            "linux" ->
                startActivity(Intent(this, LinuxActivity::class.java))
            else -> startActivity(Intent(this, PermissionCenterActivity::class.java).apply {
                putExtra(PermissionCenterActivity.EXTRA_GROUP, PermissionCenterActivity.GROUP_ENGINE)
            })
        }
    }

    private fun openSettingsSection(section: String?): Boolean {
        val destination = when (section) {
            "plugins" -> Intent(this, PluginStoreActivity::class.java)
            "extensions" -> Intent(this, ExtensionStoreActivity::class.java)
            "containers" -> Intent(this, LinuxActivity::class.java)
            "language" -> Intent(this, SettingsActivity::class.java).putExtra("native_chat_open_language", true)
            "permissions" -> Intent(this, PermissionCenterActivity::class.java)
            "diagnostics" -> Intent(this, LogViewerActivity::class.java)
            "backup" -> Intent(this, BackupActivity::class.java)
            "about" -> Intent(this, AboutActivity::class.java)
            else -> return false
        }
        return runCatching { startActivity(destination) }.isSuccess
    }

    /** SSH workspace routing is owned by the Web shell; no native deep link is verified. */
    private fun openSshManager(@Suppress("UNUSED_PARAMETER") platform: String?) {
        openWebFallback()
    }

    /**
     * 切回 Web 主界面，并把当前会话一并交回去。
     *
     * 主界面收到后会让 Web 的同一份会话真实打开（不是刷新到最近会话）；
     * 没有会话标识时不带 extra，另一个方向也不会因此猜一个。
     */
    private fun openWebFallback() {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                sessionId?.let { putExtra(EXTRA_SESSION_ID, it) }
            },
        )
    }

    companion object {
        const val CHANNEL = "app.dsh.mobile/native_chat"

        /** 入场窗口淡化的基准时长（会被系统动画倍率缩放）。 */
        private const val WINDOW_FADE_MS = 900

        /** 淡化至少跨过的台阶数：主线程再卡，也一定能看到这么多级渐变。 */
        private const val FADE_STEPS = 8

        /**
         * 台阶之间的间隔。
         *
         * 不能取 16ms：窗口 alpha 的变化要经过 relayout + 合成才上屏，模拟器上一次合成
         * 实测要 40–100ms，8 级台阶挤在 128ms 里会被合成器合并成一两级
         * （beta.64 tI.mp4：31.8 → 57.7 挂 300ms → 234，中间几级根本没上屏）。
         * 110ms 一层既让每一级都真的被合成一次，整段也只有 0.9 秒。
         */
        private const val STEP_DELAY_MS = 110L

        /** 入场底图溶解时长（会被系统动画倍率缩放）。 */
        private const val BACKDROP_FADE_MS = 1000L

        /** 观察窗口缓冲的取样间隔。 */
        private const val BACKDROP_WATCH_INTERVAL_MS = 80L

        /** 观察窗口缓冲的最大等待：超时就按旧逻辑直接溶，不让用户看太久旧页。 */
        private const val BACKDROP_WATCH_TIMEOUT_MS = 9000L

        /** 取样区中心像素与底图对应像素的平均分量差，超过它才算「新画面已经上屏」。 */
        private const val BACKDROP_CHANGE_THRESHOLD = 8L

        /** 中心区平均亮度达到它就认为浅色页面已经画出来了。 */
        private const val BACKDROP_DRAWN_LUMA = 12L

        /** 溶解跨过的台阶数：与窗口 alpha 同理，多给几级才有肉眼可见的渐变。 */
        private const val BACKDROP_FADE_STEPS = 20

        /**
         * 主界面拍下的入场底图（PNG 绝对路径）。
         *
         * 传路径不传 Bitmap：整屏 1080×2340 的 Bitmap 过 Intent 会撞
         * TransactionTooLargeException，而 PNG 只有一两百 KB。
         */
        const val EXTRA_BACKDROP = "app.dsh.mobile.extra.BACKDROP"
        private const val REQUEST_SAVE_TEXT = 5071

        /** 两个方向共用的会话承接 extra：Web→原生带进来，原生→Web 带回去。 */
        const val EXTRA_SESSION_ID = "app.dsh.mobile.extra.SESSION_ID"
    }
}