package app.dsh.mobile

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * AI 状态悬浮窗（v1.2.57 初版，v1.2.58 重做外观 + 补上拖动）。
 *
 * ## 为什么用 TYPE_ACCESSIBILITY_OVERLAY
 * 由无障碍服务自身持有，**不需要用户再授权「显示在其他应用上层」**。
 *
 * ## 交互（全部实测可用）
 *  · **拖动**：按住任意位置拖动移动（松手吸附到左右边缘，避免长期挡内容）
 *  · **点击**：展开/收起详情行（点与拖由移动阈值区分：<10dp 视为点击）
 *  · **长按**：隐藏（用户明确不要时尊重）
 *
 * ⚠️ v1.2.58 修正：初版注释写了「拖动：移动位置」但**根本没实现**——
 * 文档承诺了功能却没做，用户实测发现「不能移动」。本次补上。
 *
 * ## 外观
 * 对齐设置页设计语言：深色表面 #1C232C + 16dp 圆角 + 细描边 + 强调色随用户配色方案（默认 #7DD3FC）；
 * 左侧状态点用颜色区分状态（强调色=执行中 / 绿=完成 / 灰=空闲）。
 */
object StatusOverlay {

    private const val TAG = "StatusOverlay"

    /** 判定为「点击」而非「拖动」的位移阈值（dp） */
    private const val TAP_SLOP_DP = 10

    /**
     * 折叠态悬浮球直径（dp）。
     * 旧版折叠态只有「22dp 圆点 + 4dp 内边距」= 30dp，实测窗口 95x77 像素
     * 是个**椭圆**且触摸只认中间那个点，所以看起来「像个按钮」（主人实测反馈）。
     * 44dp：够大能点准（远超 40dp 的最小触摸目标），又不至于占屏。
     */
    // v1.2.157 用户反馈：44dp 的球在展开成面板时"旁边延展、整体内缩"（球比面板高）。
    // 改为 26dp 紧凑圆点：展开后是自然的变大，观感平滑；中间状态点大小不变（DOT_DP）。
    private const val COLLAPSED_BALL_DP = 26

    /**
     * 状态点直径（dp）—— **折叠态与展开态用同一个值**。
     * 主人要求「让两个球在变化大小不变」：展开/收起时这颗点不能忽大忽小。
     * （旧代码折叠 22dp / 展开 10dp，切换时明显跳一下。）
     */
    private const val DOT_DP = 14

    /** 展开态面板宽度（dp）。C 版设计稿定为 224。 */
    private const val PANEL_WIDTH_DP = 224

    /** 松手后吸附边缘的动画时长 */
    private const val SNAP_MS = 180L

    /**
     * 最后一次 AI 动作之后多久自动收起悬浮窗（v1.7.4，用户要求）。
     *
     * 用户原话：「只有 AI 启用无障碍操控的时候，那个无障碍悬浮窗才会出现，AI 结束操纵后
     * 约 1 分钟消失」。60s 给的是「刚做完，我想看一眼它停在哪」的时间窗 —— 再短会
     * 让人来不及看清结果，再长就回到「一直挂着挡事」的老问题。
     */
    const val AUTO_HIDE_MS = 60_000L

    @Volatile private var root: View? = null
    @Volatile private var params: WindowManager.LayoutParams? = null
    @Volatile private var statusText: TextView? = null
    @Volatile private var actionText: TextView? = null
    @Volatile private var statusDot: View? = null
    @Volatile private var micView: android.widget.ImageView? = null
    @Volatile private var sessionsBox: LinearLayout? = null
    /** 展开档位：0=折叠(仅圆点) / 1=状态+麦克风 / 2=再加动作+会话列表 */
    @Volatile private var expandLevel = 0
    @Volatile private var hidden = false
    /** 待执行的自动收起任务（v1.7.4，每次动作都会重排） */
    @Volatile private var autoHide: Runnable? = null
    @Volatile private var appCtx: Context? = null
    @Volatile private var wm: WindowManager? = null

    /**
     * 麦克风点击回调（由 MainActivity 注入：需要 Activity 才能申请 RECORD_AUDIO 权限）。
     * 为 null 时按钮点击无效果（权限未就绪）。
     */
    @Volatile var onMicClick: ((Context) -> Unit)? = null

    /** 识别中标记（用于把状态点染成录音色） */
    @Volatile private var recording = false

    /**
     * 是否「有活干」（执行中 / 录音 / 完成提示）。
     * 动作行（AI 正在干什么）只在有活干 + 展开态时显示 ——
     * 空闲时不该还挂着上一次的动作文案（主人要的是「现在在干什么」）。
     */
    @Volatile private var working = false

    /**
     * 是否是我们**自己**因为「AI 开始干活」而自动展开的。
     * 只有这种情况才会在回空闲时自动收成球 —— 用户手动展开的档位绝不擅自改动。
     */
    @Volatile private var autoExpanded = false

    /** 会话列表的自动刷新任务（档位 2 显示期间周期性刷新，见 refreshSessionList） */
    @Volatile private var listRefresh: Runnable? = null

    /** 当前是否处于「聆听」状态（麦克风按钮的开关语义依赖它） */
    fun isListening(): Boolean = recording

    /** 状态点配色（与 drawable 默认色一致，运行时切换） */
    private const val COLOR_IDLE = 0xFF9AA0A6.toInt()      // 中性灰
    /** 强调色（v1.4.23 起跟随用户配色方案，不再是编译期常量） */
    private val colorWorking: Int
        get() {
            val ctx = appCtx ?: return 0xFF7DD3FC.toInt()
            return Accent.current(ctx).argb
        }
    private const val COLOR_DONE = 0xFF81C995.toInt()      // 完成绿
    private const val COLOR_RECORDING = 0xFFF28B82.toInt() // 录音红

    fun isAvailable(): Boolean = DshAccessibilityService.instance != null

    /** 窗口此刻是否挂在屏幕上（v1.7.9：设置页的「显示/收起」要按真实状态切文案）。 */
    fun isShown(): Boolean = root != null && !hidden

    /**
     * 显示悬浮窗。
     * @param svc 无障碍服务实例（提供 overlay 窗口类型）
     */
    fun show(svc: AccessibilityService) {
        main {
            if (root != null || hidden) return@main
            try {
                val ctx = svc.applicationContext
                appCtx = ctx
                val w = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                wm = w

                val view = LayoutInflater.from(ctx).inflate(R.layout.overlay_status, null)
                val status = view.findViewById<TextView>(R.id.overlayStatus)
                val action = view.findViewById<TextView>(R.id.overlayAction)
                val dot = view.findViewById<View>(R.id.overlayDot)
                val mic = view.findViewById<android.widget.ImageView>(R.id.overlayMic)
                val sessions = view.findViewById<LinearLayout>(R.id.overlaySessions)
                // v1.6.27 C 版：顶栏的播放/暂停按钮与提示行都写在 XML 里
                val playPause = view.findViewById<android.widget.ImageView>(R.id.overlayPlayPause)
                val hint = view.findViewById<TextView>(R.id.overlayHint)
                val bar = view.findViewById<LinearLayout>(R.id.overlayBar)
                val ask = view.findViewById<LinearLayout>(R.id.overlayAsk)

                status.text = ctx.getString(R.string.overlay_idle)
                dot.background.setTint(COLOR_IDLE)

                // 语音输入（v1.2.65）：麦克风用**显式触摸处理**（与 VoicePanel.bindTap 同因）——
                // overlay 窗口里 View 的 clickable 派发链路不可靠，setOnClickListener 实测不触发。
                // 且它在根容器 setOnTouchListener 之前吃掉自己的事件，避免被当成「点空白升档」。
                mic.setOnTouchListener { _, ev ->
                    Log.i(TAG, "mic touch: action=${ev.actionMasked} at (${ev.x.toInt()},${ev.y.toInt()})")
                    when (ev.actionMasked) {
                        MotionEvent.ACTION_DOWN -> true
                        MotionEvent.ACTION_UP -> {
                            onMicClick?.invoke(ctx); true
                        }
                        MotionEvent.ACTION_CANCEL -> true
                        else -> true
                    }
                }

                val p = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    // 默认放**右侧**：多数 App 的左侧是导航栏/图标栏，悬浮窗压在上面会挡住操作。
                    // ⚠️ 初值必须保守 —— 见下方 post 里的修正。
                    x = dp(ctx, 8)
                    y = dp(ctx, 120)
                }

                // ⚠️ 顺序有讲究（实测踩坑）：
                //  ① 先赋字段（applyExpandLevel / bindHandle 依赖它们）
                //  ② addView（先挂到 window，布局才有意义）
                //  ③ 在 post 里应用初始档位 —— 此时已完成首次测量，
                //     子 View 才有真实边界可绑手柄（否则绑到 [0,0,0,0] 的控件上）
                statusText = status; actionText = action; statusDot = dot
                micView = mic; sessionsBox = sessions
                pauseBtn = playPause; hintView = hint; askBox = ask; barView = bar
                askDivider = view.findViewById(R.id.overlayAskDivider)
                root = view; params = p

                w.addView(view, p)

                view.post {
                    expandLevel = 0
                    applyExpandLevel(view)
                }

                // ⚠️ 右对齐必须在**测量完成后**做。
                // 旧实现 addView 之后立刻读 view.width —— 此时尚未 measure/layout，
                // 宽度恒为 0，于是 x 被算成 screenW - 0 - margin = 1059（1080 宽的屏），
                // 整个悬浮窗被推到屏幕外，用户完全看不见（模拟器实测日志：
                // "overlay shown at 1059,315 (0x0)"）。用 post 等布局完成再回算。
                view.post {
                    val margin = dp(ctx, 8)
                    val vw = if (view.width > 0) view.width else dp(ctx, 120)
                    p.x = (ctx.resources.displayMetrics.widthPixels - vw - margin)
                        .coerceAtLeast(margin)
                    runCatching { w.updateViewLayout(view, p) }
                    Log.i(TAG, "overlay shown at ${p.x},${p.y} (${view.width}x${view.height})")
                }
            } catch (e: Exception) {
                Log.w(TAG, "show failed: ${e.message}")
            }
        }
    }

    /**
     * 绑定手势目标（每次档位变化后都要重绑）。
     *
     * ## 点击语义（v1.2.66 按主人实测反馈修正）
     *  · 折叠态：**整颗球**点一下 → 展开
     *  · 展开态：**状态文字**点一下 → 继续展开（详情档），到顶再点循环回球
     *  · 展开态：**面板其余部分**（包括那颗圆点、内边距）点一下 → **收起回球**
     *
     * 为什么补第三条：此前展开态只有那一小块状态文字绑了手势，面板其余处**完全没反应**
     * （根容器的手势在折叠态被摘掉了）—— 主人实测「第二阶段点不回去，必须点到第三阶段
     * 才能点回去」。那颗圆点长得就是折叠态的球，点它 = 收起来，才是符合直觉的预期。
     *
     * ## 事件分发的坑（v1.2.65 实测）
     * 落在子 View 边界内的事件由**子 View 优先**处理，父容器手势只是兜底 ——
     * 所以根容器绑手势不会抢走麦克风（麦克风自己消费自己的），
     * 但绑过手势的 View 会吃掉事件：换档位时必须把旧目标的监听摘干净，
     * 否则会出现「点了没反应」或「麦克风永久失效」。
     */
    private fun bindHandle(view: View, p: WindowManager.LayoutParams, ctx: Context) {
        val statusTv = view.findViewById<TextView>(R.id.overlayStatus)
        val dotV = view.findViewById<View>(R.id.overlayDot)
        // 先摘掉全部旧监听 + **复位 clickable**（档位切换会换目标）
        //
        // 🔴 修主人实测的「球心点不动、点旁边才行」：
        // 状态点在某些档位会当手柄（attachGesture 会给它 isClickable = true）。
        // 只清监听、不复位 isClickable 的话，那颗点会**自己吃掉触摸**
        // （clickable 的 View 在 onTouchEvent 里消费 DOWN，父容器再也收不到），
        // 表现就是「点球心（= 圆点所在位置）毫无反应，点球的其他位置正常」。
        listOfNotNull(view, statusTv, dotV).forEach { v ->
            v.setOnTouchListener(null)
            v.setOnLongClickListener(null)
            v.isClickable = false
            v.isLongClickable = false
        }
        val expanded = expandLevel > 0 && statusTv != null && statusTv.visibility == View.VISIBLE
        if (expanded) {
            // 面板空白处（含状态点）：点一下 = 收起回球
            attachGesture(view, p, ctx, onClick = { collapseToBall() })
            // 状态文字：点一下 = 继续展开（循环）；长按 = 隐藏
            statusTv?.let { tv ->
                attachGesture(
                    tv, p, ctx,
                    onClick = { cycleExpandLevel() },
                    onLongPress = { hide(DshAccessibilityService.instance) },
                )
            }
        } else {
            // 折叠态：整颗球都能点/拖；长按 = 隐藏
            attachGesture(
                view, p, ctx,
                onClick = { cycleExpandLevel() },
                onLongPress = { hide(DshAccessibilityService.instance) },
            )
        }
    }

    /** 收起回折叠球（展开态点面板空白处的语义） */
    private fun collapseToBall() {
        autoExpanded = false
        if (expandLevel == 0) return
        expandLevel = 0
        applyExpandLevel()
    }

    /**
     * 给一个目标 View 装上「拖动 / 点击 / 长按」手势。
     * 位移小于 [TAP_SLOP_DP] 视为点击（调 onClick），超过则拖动，松手吸附到最近边缘。
     */
    private fun attachGesture(
        target: View,
        p: WindowManager.LayoutParams,
        ctx: Context,
        onClick: () -> Unit,
        onLongPress: (() -> Unit)? = null,
    ) {
        val slop = dp(ctx, TAP_SLOP_DP).toFloat()
        var downX = 0f; var downY = 0f
        var startX = 0; var startY = 0
        var dragging = false
        var moved = false
        target.isClickable = true
        target.setOnTouchListener { _, ev ->
            Log.i(TAG, "handle touch: action=${ev.actionMasked} at (${ev.x.toInt()},${ev.y.toInt()})")
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY
                    startX = p.x; startY = p.y
                    dragging = true; moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging) return@setOnTouchListener false
                    val dx = ev.rawX - downX
                    val dy = ev.rawY - downY
                    if (!moved && (kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop)) {
                        moved = true
                    }
                    if (moved) {
                        p.x = startX + dx.toInt()
                        p.y = startY + dy.toInt()
                        runCatching { wm?.updateViewLayout(root ?: target, p) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    dragging = false
                    if (moved) {
                        snapToEdge(p, ctx)
                    } else {
                        onClick()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> { dragging = false; true }
                else -> false
            }
        }
        // 长按隐藏（View 自带的长按不会与拖动冲突：拖动会先触发 MOVE 取消长按）
        if (onLongPress != null) {
            target.setOnLongClickListener { onLongPress(); true }
        }
    }

    /** 松手后吸附到最近的水平边缘（避免长期悬在屏幕中央挡内容） */
    private fun snapToEdge(p: WindowManager.LayoutParams, ctx: Context) {
        val view = root ?: return
        val screenW = ctx.resources.displayMetrics.widthPixels
        val margin = dp(ctx, 8)
        // 宽度兜底：测量未完成时 view.width 会是 0，直接用会把窗口推到屏外
        val vw = if (view.width > 0) view.width else dp(ctx, 120)
        val target = if (p.x + vw / 2 < screenW / 2) margin else screenW - vw - margin
        animateX(p, target)
    }

    /** 简单的逐帧位移动画（避免引入动画库；60fps 下约 180ms 走完） */
    private fun animateX(p: WindowManager.LayoutParams, targetX: Int) {
        val view = root ?: return
        val start = p.x
        val steps = (SNAP_MS / 16).toInt().coerceAtLeast(1)
        var i = 0
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val runnable = object : Runnable {
            override fun run() {
                i++
                val t = i.toFloat() / steps
                // ease-out：起步快、收尾缓，手感更自然
                val eased = 1f - (1f - t) * (1f - t)
                p.x = start + ((targetX - start) * eased).toInt()
                runCatching { wm?.updateViewLayout(view, p) }
                if (i < steps) handler.postDelayed(this, 16)
            }
        }
        handler.post(runnable)
    }

    /**
     * 三段式展开（v1.2.65）：每点一下升一档，到顶后再点回到折叠态。
     *
     *  0 折叠：只有圆形状态点（最省空间，半透明）
     *  1 展开：状态点 + 状态文字 + 麦克风按钮
     *  2 详情：在展开基础上 + 最近动作 + 活跃会话列表
     */
    private fun cycleExpandLevel() {
        autoExpanded = false   // 用户自己动的档位 → 不再自动收回
        expandLevel = (expandLevel + 1) % 3
        applyExpandLevel()
        if (expandLevel == 2) refreshSessionList()
    }

    /** 按当前档位应用可见性（所有控件都在同一窗口里，只切 visibility） */
    /**
     * 按当前档位应用可见性（所有控件都在同一窗口里，只切 visibility）。
     * @param r 根 View（显式传入：本方法可能在 root 字段赋值前就被调用）
     */
    private fun applyExpandLevel(r: View? = root) {
        val ctx = appCtx ?: return
        // 折叠态：隐藏文字/麦克风/动作/会话，只留圆点；同时收紧内边距让它接近圆形
        val collapsed = expandLevel == 0
        statusText?.visibility = if (collapsed) View.GONE else View.VISIBLE
        micView?.visibility = if (collapsed) View.GONE else View.VISIBLE
        // ⚠️ v1.6.27：新加的提示行与播放按钮也必须跟着收。
        // 漏掉它们，折叠成小球时会从球里溢出来 ——
        // 实测就是「绿圈下面粘着一小块白面板」。
        hintView?.visibility = if (collapsed) View.GONE else hintView?.visibility ?: View.GONE
        pauseBtn?.visibility = if (collapsed) View.GONE else View.VISIBLE
        // 动作行（AI 正在干什么）：统一走 refreshActionVisibility（档位 ≥1 且有活干才显示）
        refreshActionVisibility()
        // 离开详情档就停掉周期刷新（档位 2 期间由 refreshSessionList 自己续期）
        if (expandLevel != 2) stopListRefresh()
        sessionsBox?.visibility = if (expandLevel >= 2) View.VISIBLE else View.GONE

        r?.let { v ->
            // v1.6.27：展开态根容器不留 padding —— 顶栏、抽屉、
            // 底部动作行各自带内边距，这样抽屉的浅底才能直接贴到面板两侧。
            val pad = if (collapsed) dp(ctx, 4) else 0
            v.setPadding(pad, pad, pad, pad)
            // 折叠态 = 苹果 AssistiveTouch 式悬浮球（球体+内环，见 bg_overlay_ball）；
            // 展开后回到圆角卡片。旧版是拉伸成椭圆的深灰块，观感像按钮。
            runCatching {
                v.setBackgroundResource(
                    if (collapsed) R.drawable.bg_overlay_ball
                    else R.drawable.bg_overlay_panel
                )
            }
            // 折叠态强制**正圆**（52dp）：球里只有一个小圆点，内容撑不满控件，
            // 必须靠 minimumWidth/Height 才能量出正方形（旧版实测 95x77 = 椭圆）。
            // 展开态交还给内容自适应（置 0）。
            // ⚠️ v1.6.27：展开态必须给**确定宽度**。
            // C 版布局里顶栏/抽屉/底部行都是 match_parent，
            // 而根容器是 wrap_content → match_parent 无法解析，窗口直接撑成整屏（实测 1080px）。
            // ⚠️ v1.6.27：宽高直接定在**窗口参数**上，不靠 wrap_content 推。
            // 展开态子控件是 match_parent（靠它把按钮推到右边），
            // 而 wrap_content 遇到 match_parent 无法解析 → 窗口被撑成整屏（实测 1080px）。
            val panelW = if (collapsed) dp(ctx, COLLAPSED_BALL_DP) else dp(ctx, PANEL_WIDTH_DP)
            val panelH = if (collapsed) dp(ctx, COLLAPSED_BALL_DP)
                         else android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            params?.let { wp ->
                if (wp.width != panelW || wp.height != panelH) {
                    wp.width = panelW
                    wp.height = panelH
                    runCatching { wm?.updateViewLayout(v, wp) }
                }
            }
            val minSize = if (collapsed) dp(ctx, COLLAPSED_BALL_DP) else 0
            if (v.minimumWidth != minSize || v.minimumHeight != minSize) {
                v.minimumWidth = minSize
                v.minimumHeight = minSize
            }
            // 折叠态让状态点落在**球心**（内容居中）；展开态回到默认靠左上排布。
            // 根容器是 LinearLayout（overlay_status.xml），gravity 决定子项对齐方式。
            (v as? LinearLayout)?.gravity =
                if (collapsed) Gravity.CENTER else Gravity.NO_GRAVITY
            statusDot?.let { d ->
                val lp = d.layoutParams
                // 两种形态同一尺寸（见 DOT_DP 注释）：切换时这颗点不会忽大忽小
                val size = dp(ctx, DOT_DP)
                var changed = false
                if (lp.width != size || lp.height != size) {
                    lp.width = size; lp.height = size; changed = true
                }
                // 折叠态去掉右侧间距 —— 那 7dp 是给展开态状态文字留的，
                // 留着会把居中的圆点顶偏，不在球心
                (lp as? LinearLayout.LayoutParams)?.let { mlp ->
                    val m = if (collapsed) 0 else dp(ctx, 7)
                    if (mlp.marginEnd != m) {
                        mlp.marginEnd = m
                        changed = true
                    }
                }
                if (changed) d.layoutParams = lp
            }
            // ⚠️ 每次档位变化后**重绑手柄**：档位决定哪些子 View 可见，
            // 手柄必须绑在当前可见的那个上（否则点击落空 —— 实测踩坑）。
            bindHandle(v, params ?: return@let, ctx)

            // v1.2.157：详情档显示「退出 daemon」按钮（有活跃任务才可见，轮询控制）
            if (expandLevel >= 2) {
                ensureDaemonButton(ctx)
            } else {
                cancelDaemonPoll()
                daemonBtn?.visibility = View.GONE
            }
            // 播放/暂停按钮在顶栏，档位 1 起就要有（它是常驻控制）。
            if (expandLevel >= 1) {
                ensurePauseButton(ctx)
            } else {
                cancelAllowPoll()
            }
            // 提问区从档位 1 就生效。
            // 它不是「详情」，而是「需要你做事」——藏在档位 2 里等于没问。
            if (expandLevel >= 1) {
                ensureAskArea(ctx)
            } else {
                cancelAskPoll()
                askBox?.visibility = View.GONE
                askDivider?.visibility = View.GONE
                askShownId = -1L
            }

            // ⚠️ 必须请求重新布局：改 visibility 不会自动重新测量，
            // 新显示的控件会保持 [0,0,0,0]（尺寸为 0 → 收不到触摸）。
            // 实测：level 1 时 status/mic 都是 vis=0 但 [0,0,0,0]，点麦克风完全无响应。
            v.requestLayout()
            v.post {
                // 布局完成后再刷一次手柄绑定（此时子 View 才有真实边界）
                bindHandle(v, params ?: return@post, ctx)
                Log.i(TAG, "  after layout: status=[${statusText?.left},${statusText?.right}] mic=[${micView?.left},${micView?.right}]")
            }
        }
        Log.i(TAG, "expand level = $expandLevel")
        // 诊断：打印关键子控件的实际位置与可见性（确认麦克风到底在哪）
        statusText?.let {
            Log.i(TAG, "  status: vis=${it.visibility} [${it.left},${it.top},${it.right},${it.bottom}]")
        }
        micView?.let {
            Log.i(TAG, "  mic: vis=${it.visibility} [${it.left},${it.top},${it.right},${it.bottom}]")
        }
        statusDot?.let {
            Log.i(TAG, "  dot: vis=${it.visibility} [${it.left},${it.top},${it.right},${it.bottom}]")
        }
    }

    /**
     * 详情档内容：**上一次 AI 做了什么 + 什么时候做的**（v1.2.88）。
     *
     * 主人要求：「下面展开之后，就直接显示 AI 上一次做了什么以及上一次 AI 是在什么时候做的」——
     * 取代原来的「活跃会话列表」（那个的活跃时间一直不准，主人已明确说不管它了）。
     *
     * 数据来自 [app.dsh.mobile.engine.LastAction]（持久化，重启后仍能看到上次动作）。
     */
    private fun refreshSessionList() {
        val ctx = appCtx ?: return
        val box = sessionsBox ?: return
        val last = app.dsh.mobile.engine.LastAction.read(ctx)
        main {
            box.removeAllViews()
            if (last == null) {
                box.addView(TextView(ctx).apply {
                    text = ctx.getString(R.string.overlay_no_last_action)
                    textSize = 11f
                    setTextColor(0xFF9AA0A6.toInt())
                })
                return@main
            }
            // 第一行：AI 上次做了什么
            box.addView(TextView(ctx).apply {
                text = last.action
                textSize = 11f
                setTextColor(0xFFB8C0CC.toInt())
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            // 第二行：什么时候做的
            box.addView(TextView(ctx).apply {
                val when_ = android.text.format.DateUtils.getRelativeTimeSpanString(last.at)
                text = ctx.getString(R.string.overlay_last_action_at, when_)
                textSize = 10f
                setTextColor(colorWorking)
                setPadding(0, dp(ctx, 3), 0, 0)
            })
        }
    }

    /** 停掉会话列表的周期刷新 */
    private fun stopListRefresh() {
        listRefresh?.let { runCatching { mainHandler.removeCallbacks(it) } }
        listRefresh = null
    }

    /** 档位 2 显示期间每 3 秒重新读一次会话列表（离开档位即停） */
    private fun scheduleListRefresh() {
        stopListRefresh()
        if (expandLevel != 2) return
        val r = Runnable { refreshSessionList() }
        listRefresh = r
        mainHandler.postDelayed(r, 3_000L)
    }

    // ---------- daemon 控制按钮（v1.2.157 用户反馈：daemon 卡住时用户无法主动取消）----------
    /** 详情档的「退出 daemon」按钮：有活跃/待帮助任务才显示，点击即 /daemon/stop */
    @Volatile private var daemonBtn: android.widget.Button? = null
    @Volatile private var daemonPoll: Runnable? = null

    private fun ensureDaemonButton(ctx: Context) {
        val v = root ?: return
        if (daemonBtn == null) {
            val b = android.widget.Button(ctx)
            b.text = ctx.getString(R.string.overlay_exit_daemon)
            b.textSize = 11f
            b.setPadding(dp(ctx, 8), dp(ctx, 2), dp(ctx, 8), dp(ctx, 2))
            b.setOnClickListener {
                daemonBtn?.visibility = View.GONE
                Thread {
                    runCatching {
                        val tok = app.dsh.mobile.engine.AgentBridge.currentToken() ?: return@Thread
                        val c = (java.net.URL("http://127.0.0.1:3083/daemon/stop")
                            .openConnection() as java.net.HttpURLConnection).apply {
                            requestMethod = "POST"
                            setRequestProperty("X-DSH-Token", tok)
                            connectTimeout = 3000; readTimeout = 3000
                        }
                        c.inputStream.close()
                    }
                }.apply { isDaemon = true }.start()
            }
            daemonBtn = b
            (v as? android.widget.LinearLayout)?.addView(b)
        }
        // v1.2.159 修 bug（用户反馈：daemon 不在活动时按钮也亮着）：
        // 此前这里**立即置 VISIBLE**，而轮询线程在拿不到 token 时会提前 return、
        // 从不回写可见性 → 按钮永久亮着。现在：先隐藏，只由轮询结果决定显隐。
        daemonBtn?.visibility = View.GONE
        scheduleDaemonPoll()
    }

    // ---------- 播放 / 暂停按钮（v1.6.27 C 版）----------
    //
    // 一个按钮表达两件事，像音乐播放器：
    //   绿底 + 暂停图标 = AI 现在能动，点一下叫它停；
    //   红底 + 播放图标 = AI 已停住，点一下放行。
    // 图标直接说明「点下去会发生什么」，不需要文字标签。
    //
    // 为什么不用滑动开关：这件事不是「某个设置项开着还是关着」，
    // 而是「现在要不要让它继续动」—— 用户点它时的意图是「停住」，不是「切到另一边」。
    @Volatile private var pauseBtn: android.widget.ImageView? = null
    /** 状态行下方的提示（只有 错误/完成/等你回答 三档有内容）。 */
    @Volatile private var hintView: TextView? = null
    /** 顶栏容器。展开态 match_parent（带弹性空隙把按钮推到右边），
     *  折叠态必须 wrap_content —— 否则它会把整个窗口撑成屏宽，
     *  圆球变成一条横贯全屏的白椭圆（实测踩到）。 */
    @Volatile private var barView: LinearLayout? = null
    @Volatile private var allowPoll: Runnable? = null
    @Volatile private var allowOn: Boolean = true
    @Volatile private var pauseReady: Boolean = false

    /**
     * AI 主动收尾后置位（v1.6.35）。
     *
     * 关掉「允许 AI 操作」之后，AI 仍然有权**提问**和**收尾写摘要**；
     * 收尾完状态区必须回到绿色（用户要求），否则界面一直挂着红的「已暂停」，
     * 看起来像卡住了。下次有动作进来时清掉。
     */
    @Volatile private var finishedGreen: Boolean = false

    /** 按钮就绪：按当前状态画一次，并开始轮询真实值。 */
    private fun ensurePauseButton(ctx: Context) {
        val b = pauseBtn ?: return
        // ⚠️ 不能再用 pauseReady 守卫（v1.7.8 真机）：hide() 会 removeView 并把
        // root 置空，下一次 show() 是**重新 inflate** 的新 ImageView，而 pauseReady
        // 只在 teardown（引擎退出）时复位 → 新按钮上没有触摸监听 → 点它等于点面板
        // 空白处，只会「收起悬浮窗」（用户实测的原话）。setOnTouchListener 本身幂等，
        // 每次都挂一遍最省心。
        run {
            pauseReady = true
            // 显式触摸：与麦克风同一写法（overlay 里可点控件都走这条）
            b.setOnTouchListener { view, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { view.alpha = 0.6f; true }
                    MotionEvent.ACTION_UP -> {
                        view.alpha = 1f
                        toggleAllow()
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> { view.alpha = 1f; true }
                    else -> false
                }
            }
        }
        renderAllow()
        scheduleAllowPoll()
    }

    /** 开关在线上与本地之间切换。 */
    private fun toggleAllow() {
        val want = !allowOn
        allowOn = want
        renderAllow()
        Thread {
            runCatching { postBridge("/task/allow", allowBody(want)) }
        }.apply { isDaemon = true }.start()
    }

    private fun allowBody(on: Boolean): String {
        val q = "\""
        return "{" + q + "allowed" + q + ":" + on + "}"
    }

    /** 按 allowOn 切图标与底色。 */
    private fun renderAllow() {
        val ctx = appCtx ?: return
        val b = pauseBtn ?: return
        b.setImageResource(if (allowOn) R.drawable.ic_pause_white else R.drawable.ic_play_white)
        b.setBackgroundResource(
            if (allowOn) R.drawable.bg_overlay_pb_green else R.drawable.bg_overlay_pb_red
        )
        b.contentDescription = ctx.getString(
            if (allowOn) R.string.overlay_allow_ai_on else R.string.overlay_allow_ai_off
        )
        // 切换后立刻重绘状态区：暂停时红点+「已暂停」，恢复时回到真实任务状态。
        applyState(stateKind)
    }

    /** 每 2 秒问一次桥：真实的 allowed 是什么（模型也可以自己改）。 */
    private fun scheduleAllowPoll() {
        cancelAllowPoll()
        val r = Runnable {
            allowPoll = null
            if (expandLevel < 1) return@Runnable
            runCatching {
                daemonPollExec.execute {
                    val body = runCatching { getBridge("/task/allow") }.getOrNull()
                    val on = runCatching {
                        org.json.JSONObject(body ?: "{}").optBoolean("allowed", true)
                    }.getOrDefault(true)
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        allowOn = on
                        renderAllow()
                        if (expandLevel >= 1) scheduleAllowPoll()
                    }
                }
            }
        }
        allowPoll = r
        mainHandler.postDelayed(r, 2_000L)
    }

    private fun cancelAllowPoll() {
        allowPoll?.let { runCatching { mainHandler.removeCallbacks(it) } }
        allowPoll = null
    }

    /** 停止请求的 JSON 体（文案来自 PhoneTask，避免本文件出现中文字面量）。 */

    // ---------- 向用户提问（v1.6.24）----------
    //
    // 为什么必须落到悬浮窗：模型卡在需要用户决定的地方时，只做个 HTTP 端点
    // 等于把负担丢回给它自己 —— 用户手上没有任何地方能回答。
    // 这里给的是「点一下就好」：选项是按钮，也可以自由输入。

    @Volatile private var askBox: android.widget.LinearLayout? = null
    /** v1.7.1：抽屉顶上那条 1dp 分隔线。抽屉底色改透明之后，靠它和顶栏分开。 */
    @Volatile private var askDivider: android.view.View? = null
    @Volatile private var askPoll: Runnable? = null
    /** 当前展示的提问 id，避免同一问题反复重建视图。 */
    @Volatile private var askShownId: Long = -1L

    private fun ensureAskArea(ctx: Context) {
        val v = root ?: return
        if (askBox == null) {
            askBox = v.findViewById(R.id.overlayAsk)
        }
        if (askDivider == null) {
            askDivider = v.findViewById(R.id.overlayAskDivider)
        }
        scheduleAskPoll()
    }

    /**
     * 临时把悬浮窗变可聚焦 / 恢复不可聚焦。
     *
     * 悬浮窗默认 FLAG_NOT_FOCUSABLE（不抢输入焦点、不抦输入法）。
     * 但自由输入框需要真焦点才能输入。两者不能共存，
     * 所以只在输入框获焦期间切换。
     *
     * 恢复时顺带关输入法 —— 不关的话键盘会一直占着屏幕下半截。
     */
    private fun setOverlayFocusable(focusable: Boolean) {
        val v = root ?: return
        val p = params ?: return
        val want = if (focusable) {
            p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            p.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        if (want == p.flags) return
        p.flags = want
        runCatching { wm?.updateViewLayout(v, p) }
        if (!focusable) {
            runCatching {
                val imm = appCtx?.getSystemService(Context.INPUT_METHOD_SERVICE)
                    as? android.view.inputmethod.InputMethodManager
                imm?.hideSoftInputFromWindow(v.windowToken, 0)
            }
        }
    }

    private fun cancelAskPoll() {
        askPoll?.let { runCatching { mainHandler.removeCallbacks(it) } }
        askPoll = null
    }

    /** 每 2 秒问一次桥：有没有待回答的问题 → 有就画出来。 */
    private fun scheduleAskPoll() {
        cancelAskPoll()
        val r = Runnable {
            askPoll = null
            if (expandLevel < 1) return@Runnable
            runCatching {
                daemonPollExec.execute {
                    val body = runCatching { getBridge("/ask") }.getOrNull()
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        if (expandLevel >= 1) renderAsk(body)
                        if (expandLevel >= 1) scheduleAskPoll()
                    }
                }
            }
        }
        askPoll = r
        mainHandler.postDelayed(r, 2_000L)
    }

    /** 画一个问题（没有就清空）。 */
    private fun renderAsk(body: String?) {
        val ctx = appCtx ?: return
        val box = askBox ?: return
        val o = runCatching { org.json.JSONObject(body ?: "{}") }.getOrNull()
        val pending = o?.optBoolean("pending", false) == true
        if (!pending) {
            if (askShownId != -1L) {
                box.removeAllViews()
                box.visibility = View.GONE
                askDivider?.visibility = View.GONE
                askShownId = -1L
                // 问题结束了 → 回空闲档，否则状态行会一直停在「等你回答」。
                applyState(OverlayState.Kind.IDLE)
                (root)?.requestLayout()
            }
            return
        }
        val id = o!!.optLong("id", -1L)
        if (id == askShownId) return   // 同一问题不重建，避免每 2 秒闪一下
        askShownId = id
        box.removeAllViews()
        box.visibility = View.VISIBLE
        askDivider?.visibility = View.VISIBLE

        // 有问题了 → 状态行也得变成「等你回答」。
        // 否则用户只看到多了一个框，状态行还写着「空闲」，很容易错过。
        applyState(OverlayState.resolve(
            asking = true,
            lastFailed = stateKind == OverlayState.Kind.ERROR,
            working = false,
            justFinished = false,
        ))

        // 问题正文
        box.addView(TextView(ctx).apply {
            text = o.optString("text", "")
            textSize = 12f
            setTextColor(color(ctx, R.color.text_primary))
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
            setLineSpacing(0f, 1.15f)
        })

        // 选项：淡强调底的小胶囊。整行宽是为了好点中，不是偷懒排版。
        val opts = o.optJSONArray("options")
        if (opts != null) {
            for (i in 0 until opts.length()) {
                val label = opts.optString(i, "").trim()
                if (label.isEmpty()) continue
                box.addView(pill(ctx, label, R.drawable.bg_overlay_option,
                    R.color.accent, 11f, topMargin = 6) {
                    answerAsk(label, fromOption = true)
                })
            }
        }

        // 自由输入 + 发送：选项只是建议，用户可能想说别的
        if (o.optBoolean("freeText", true)) {
            val row = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                setPadding(0, dp(ctx, 4), 0, 0)
            }
            val input = android.widget.EditText(ctx).apply {
                hint = ctx.getString(R.string.overlay_ask_input_hint)
                setHintTextColor(color(ctx, R.color.text_muted))
                textSize = 11f
                setTextColor(color(ctx, R.color.text_primary))
                maxLines = 1
                setSingleLine(true)
                // 必须显式设背景：EditText 默认那条 Material 下划线又粗又蓝，
                // 在白色悬浮卡片上非常突兀。
                setBackgroundResource(R.drawable.bg_overlay_input)
                setPadding(dp(ctx, 9), dp(ctx, 6), dp(ctx, 9), dp(ctx, 6))
                layoutParams = android.widget.LinearLayout.LayoutParams(0, -2, 1f)
                // ⚠️ 悬浮窗默认 FLAG_NOT_FOCUSABLE：不去掉它，EditText 收不到输入，
                // 输入法也不会弹，而且点击会直接穿到下层界面（实测：
                // 点下去直接跳进了设置里的「通话和短信」）。
                // 所以点它时临时把窗口变成可聚焦，失去焦点时恢复。
                setOnFocusChangeListener { _, hasFocus -> setOverlayFocusable(hasFocus) }
                setOnTouchListener { v, ev ->
                    if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                        setOverlayFocusable(true)
                        v.requestFocus()
                        val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE)
                            as? android.view.inputmethod.InputMethodManager
                        imm?.showSoftInput(v, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                    }
                    false   // 交给 EditText 自己处理光标与选择
                }
            }
            val send = pill(ctx, ctx.getString(R.string.overlay_ask_send),
                R.drawable.bg_overlay_send, R.color.on_accent, 11f) {
                val t = input.text.toString().trim()
                if (t.isNotEmpty()) answerAsk(t, fromOption = false)
            }.apply {
                // 发送按钮不该整行宽 —— 输入框要占剩下的空间。
                layoutParams = android.widget.LinearLayout.LayoutParams(-2, -2).apply {
                    marginStart = dp(ctx, 6)
                }
            }
            row.addView(input)
            row.addView(send)
            box.addView(row)
        }

        // 不答：纯文字，不抢视觉 —— 它是最弱的一个动作。
        box.addView(TextView(ctx).apply {
            text = ctx.getString(R.string.overlay_ask_dismiss)
            textSize = 10f
            setTextColor(color(ctx, R.color.text_muted))
            gravity = android.view.Gravity.CENTER
            setPadding(0, dp(ctx, 7), 0, 0)
            setOnTouchListener { v, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { v.alpha = 0.6f; true }
                    MotionEvent.ACTION_UP -> {
                        v.alpha = 1f
                        answerAsk("", fromOption = false, cancel = true)
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> { v.alpha = 1f; true }
                    else -> false
                }
            }
        })

        (root)?.requestLayout()
    }

    /** 把用户的答案送回桥。 */
    private fun answerAsk(text: String, fromOption: Boolean, cancel: Boolean = false) {
        Thread {
            runCatching {
                if (cancel) {
                    postBridge("/ask/cancel", "{}")
                } else {
                    postBridge("/ask/answer", askAnswerBody(text, fromOption))
                }
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                // 不能先把 askShownId 置 -1：renderAsk 的清空分支正是靠它非 -1 才知道「屏上还有东西要收」。
                // 先置 -1 会让它以为没东西可收，问题框永远停在屏幕上（实测踩到）。
                renderAsk(null)
            }
        }.apply { isDaemon = true }.start()
    }

    /** 答案的 JSON 体（引号必须转义，文案不在这里硬编码）。 */
    private fun askAnswerBody(text: String, fromOption: Boolean): String {
        val q = "\""
        return "{" + q + "text" + q + ":" + org.json.JSONObject.quote(text) +
            "," + q + "fromOption" + q + ":" + (fromOption) + "}"
    }

    /** 向本机桥发一个 GET，返回响应体（失败返回 null）。 */
    private fun getBridge(path: String): String? {
        val tok = app.dsh.mobile.engine.AgentBridge.currentToken() ?: return null
        val c = (java.net.URL("http://127.0.0.1:3083" + path)
            .openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("X-DSH-Token", tok)
            connectTimeout = 2000; readTimeout = 2000
        }
        return try {
            c.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            null
        } finally {
            runCatching { c.disconnect() }
        }
    }
    /**
     * 停止请求的 JSON 体。
     *
     * 原因文案从 PhoneTask 取（它在 i18n 白名单里），
     * 避免同一句话在两个文件里各写一遍。
     */
    private fun stopBody(): String {
        val q = "\""
        return "{" + q + "reason" + q + ":" + q + PhoneTask.OVERLAY_STOP_REASON + q + "}"
    }

    private fun postBridge(path: String, json: String) {
        val tok = app.dsh.mobile.engine.AgentBridge.currentToken() ?: return
        val c = (java.net.URL("http://127.0.0.1:3083" + path)
            .openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("X-DSH-Token", tok)
            setRequestProperty("Content-Type", "application/json")
            connectTimeout = 3000; readTimeout = 3000
        }
        try {
            c.outputStream.use { it.write(json.toByteArray()) }
            c.inputStream.close()
        } finally {
            runCatching { c.disconnect() }
        }
    }

    private fun cancelDaemonPoll() {
        daemonPoll?.let { runCatching { mainHandler.removeCallbacks(it) } }
        daemonPoll = null
    }

    /** daemon 状态轮询专用单线程（daemon 线程，随进程退出，不需显式关闭） */
    private val daemonPollExec = java.util.concurrent.Executors.newSingleThreadExecutor { t ->
        Thread(t, "dsh-daemon-poll").apply { isDaemon = true }
    }

    /** 每 2 秒问一次桥：daemon 有没有在跑任务 → 控制按钮显隐 */
    private fun scheduleDaemonPoll() {
        cancelDaemonPoll()
        val r = Runnable {
            daemonPoll = null
            val b = daemonBtn ?: return@Runnable
            // ⚠️ 不要每次轮询都 new Thread：展开态下这是**每 2 秒创建一条线程**，
            // 而它只是在后台跑一个 2s 超时的本地 HTTP GET。悬浮层常驻期间持续这样做
            // 属无谓开销。改为共用一条轮询线程。
            runCatching {
                daemonPollExec.execute {
                    val active = runCatching {
                        val tok = app.dsh.mobile.engine.AgentBridge.currentToken()
                        if (tok == null) false else {
                            val c = (java.net.URL("http://127.0.0.1:3083/daemon/status")
                                .openConnection() as java.net.HttpURLConnection).apply {
                                setRequestProperty("X-DSH-Token", tok)
                                connectTimeout = 2000; readTimeout = 2000
                            }
                            try {
                                // stream 必须关闭，且连接要断开：2s 一次轮询下
                                // 不 disconnect 会持续占用连接资源。
                                val body = c.inputStream.bufferedReader().use { it.readText() }
                                org.json.JSONObject(body).optBoolean("active", false)
                            } finally {
                                runCatching { c.disconnect() }
                            }
                        }
                    }.getOrDefault(false)
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        if (expandLevel >= 2) b.visibility = if (active) View.VISIBLE else View.GONE
                        if (expandLevel >= 2) scheduleDaemonPoll()
                    }
                }
            }
        }
        daemonPoll = r
        mainHandler.postDelayed(r, 2_000L)
    }

    /**
     * AI 正在干活时调用：确保悬浮窗在、并把自动收起的时间往后推（v1.7.4）。
     *
     * 调用点是 [AgentBridge.taskGate]（每个被放行的无障碍动作）—— 用「动作」而不是
     * 「任务开始」来驱动，是因为任务可能持续几分钟，用户想看到的是「此刻它在动」；
     * 任务空闲超时、模型长时间思考这些情况都不需要额外判定：没有动作 = 60s 后自己收。
     */
    fun showForWork(svc: AccessibilityService?) {
        if (svc == null) return
        // 用户上次长按/自动收起会置 hidden=true 粘住进程，这里必须先解除。
        resetHidden()
        show(svc)
        scheduleAutoHide()
    }

    /** 重排自动收起：每次动作都从「现在」重新算 [AUTO_HIDE_MS]。 */
    fun scheduleAutoHide(delayMs: Long = AUTO_HIDE_MS) {
        val svc = app.dsh.mobile.DshAccessibilityService.instance
        autoHide?.let { runCatching { mainHandler.removeCallbacks(it) } }
        if (svc == null) { autoHide = null; return }
        val r = Runnable {
            autoHide = null
            // 用户把「允许 AI 操作」关掉时**不收起** —— 那个绿色/红色按钮就在悬浮窗上，
            // 收掉等于把唯一的开关也收走了，用户再也没法打开（实测过这个死锁）。
            if (!app.dsh.mobile.PhoneTask.shared().isAllowed()) {
                scheduleAutoHide(AUTO_HIDE_MS)
                return@Runnable
            }
            // 等着用户接管 / 等着用户回答问题时不收：那正是需要他看的地方。
            if (app.dsh.mobile.Handoff.current()?.state == app.dsh.mobile.Handoff.State.WAITING ||
                askShownId != -1L
            ) {
                scheduleAutoHide(15_000L)
                return@Runnable
            }
            hide(svc)
            Log.i(TAG, "overlay auto-hidden (idle ${AUTO_HIDE_MS / 1000}s)")
        }
        autoHide = r
        mainHandler.postDelayed(r, delayMs)
    }

    /** 取消待执行的自动收起（引擎退出、用户手动操作等）。 */
    fun cancelAutoHide() {
        autoHide?.let { runCatching { mainHandler.removeCallbacks(it) } }
        autoHide = null
    }

    fun hide(svc: AccessibilityService?) {
        cancelAutoHide()
        main {
            val r = root ?: return@main
            runCatching {
                (svc?.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.removeView(r)
            }
            root = null; params = null; statusText = null; actionText = null; statusDot = null
            // 视图已被 removeView：一并放掉对它的引用，避免下一轮误用旧 ImageView
            // （暂停按钮的监听见 ensurePauseButton 的说明）。
            pauseBtn = null
            micView = null
            hintView = null
            barView = null
            pauseReady = false
            hidden = true
            Log.i(TAG, "overlay hidden by user")
        }
    }

    /**
     * 引擎退出时的彻底清理（v1.2.157，用户实测反馈修复）：
     * 退出引擎后悬浮窗仍在后台——球还挂着、`listRefresh` 每 3 秒继续读会话列表
     * （引擎已死 → 每次都失败重试）、`idleReset` 定时器也在排着 → 进程持续活跃耗电。
     * 这里一次清干净：停周期刷新、取消回空闲、移除窗口视图、状态复位。
     */
    fun teardown(svc: AccessibilityService?) {
        cancelAutoHide()
        main {
            stopListRefresh()
            cancelIdleReset()
            working = false
            expandLevel = 0
            autoExpanded = false
            cancelDaemonPoll()
            runCatching { daemonBtn?.let { (root as? android.widget.LinearLayout)?.removeView(it) } }
            cancelAllowPoll()
            pauseBtn = null
            pauseReady = false
            hintView = null
            cancelAskPoll()
            askBox = null
            askDivider = null
            askShownId = -1L
            daemonBtn = null
            hide(svc)
            Log.i(TAG, "overlay torn down (engine exited)")
        }
    }

    /** 用户隐藏后重新允许显示（引擎重启等场景调用） */
    fun resetHidden() { hidden = false }

    // ---------- 截图避让（v1.2.164，用户反馈"截图会带上悬浮窗，遮挡 AI 视野"）----------
    // ⚠️ 实现选择：**不能**用 hide()/show() —— 那两个会 removeView + 完整重建视图
    // （见 hide 的 root=null 与 show 的 inflate），而截图在有帧差检测时要每秒采几十帧
    // → 球会疯狂闪烁 + 大量 GC。改用**透明度归零**：视图保留在窗口里，只是不可见，
    // 截图拿到的就是干净画面，恢复时一次赋值即可，零重建。
    @Volatile private var hiddenForCapture = false

    /** 为截图临时隐藏（alpha=0，不销毁视图） */
    fun beginCaptureAvoid(svc: AccessibilityService?) {
        main {
            val r = root ?: return@main
            if (hiddenForCapture) return@main
            hiddenForCapture = true
            r.alpha = 0f
        }
        // 给渲染管线一帧时间（alpha 生效后再截，否则可能截到半透明残影）
        Thread.sleep(35L)
    }

    /** 截图完成后恢复可见 */
    fun endCaptureAvoid(svc: AccessibilityService?) {
        main {
            if (!hiddenForCapture) return@main
            hiddenForCapture = false
            root?.alpha = 1f
        }
    }



    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 待执行的「回空闲」任务（必须可取消，见 scheduleIdle 注释） */
    @Volatile private var idleReset: Runnable? = null

    /** 待执行的「平滑收起」任务（v1.2.185，见 setIdle 注释） */
    @Volatile private var collapsePending: Runnable? = null

    /**
     * 取消待执行的「回空闲」。
     *
     * 🔴 这里修的是主人实测反馈的回归：「AI 正在做无障碍操作，悬浮窗却一直显示空闲」。
     * 根因：`flashComplete`/`flashNotice` 用 `postDelayed` 排了一个「N 秒后 setIdle()」，
     * **从不取消**。流程实际是「上一轮结束 → flashComplete 排 6s 定时 → 用户马上又让 AI 干活
     * → setStatus(「执行中…」) 写入新状态 → 那个过期定时器到点触发 → 又把状态覆盖成「空闲」」。
     * 表现就是「AI 明明在动，悬浮窗永远空闲」。
     */
    private fun cancelIdleReset() {
        idleReset?.let { runCatching { mainHandler.removeCallbacks(it) } }
        idleReset = null
        // v1.2.185：新状态到来时，取消尚未执行的「平滑收起」——
        // 否则刚排队的收起会在新任务开始后把悬浮窗缩掉。
        cancelCollapse()
    }

    /**
     * 动作行显隐的**唯一判定点**：
     * 展开态（档位 ≥1）且有活干且有文案才显示。
     *
     * 旧代码在 flashComplete/setListening 里直接 `visibility = VISIBLE`，
     * 绕过了档位规则 —— 折叠态下球会被那行字撑成椭圆（实测）。
     */
    private fun refreshActionVisibility() {
        val a = actionText ?: return
        a.visibility =
            if (expandLevel >= 1 && working && a.text.isNotEmpty()) View.VISIBLE else View.GONE
    }

    /** 排一个「holdMs 后回空闲」，可被后续任何新状态取消 */
    private fun scheduleIdle(holdMs: Long) {
        cancelIdleReset()
        val r = Runnable { idleReset = null; setIdle() }
        idleReset = r
        mainHandler.postDelayed(r, holdMs)
    }

    /** 更新状态行（如「执行中」），并把状态点染成强调色 */
    fun setStatus(text: String) {
        main {
            cancelIdleReset()   // 新状态来了 → 作废"回空闲"定时器（否则会把这里覆盖掉）
            working = true
            // ⚠️ v1.6.27：正在等用户回答时，「执行中」不能把它盖掉。
            // 用户需要先看到「轮到我了」，而不是「它又在干活了」。
            if (askShownId != -1L) {
                refreshActionVisibility()
                return@main
            }
            statusText?.text = text
            statusDot?.background?.setTint(colorWorking)
            // 若此刻还是折叠球（档位 0），自动展开一档 ——
            // 主人要的是「AI 在干什么」能直接看到（旧版只有一颗球，什么都不显示）
            if (expandLevel == 0) {
                expandLevel = 1
                autoExpanded = true
                applyExpandLevel()
            }
            refreshActionVisibility()   // 档位 ≥1 就能看到 AI 在干什么
        }
    }

    /**
     * 更新最近动作行（如 `tap-text "WLAN"`）。
     * v1.2.67：展开态（档位 ≥1）就显示 —— 主人要求「能看到 AI 正在干什么」，
     * 之前只有档位 2 才显示，等于要点两下才看得到最近动作。
     */
    /**
     * daemon（后台任务）执行进度（v1.2.159，用户反馈"daemon 在跑但无障碍里看不到在干什么"）。
     * 由 LocalLoopExecutor 每步调用：状态行显示「执行中」，动作行显示当前那一步在做什么。
     */
    fun setDaemonAction(text: String) {
        // v1.2.188（审查 P2）：daemon 新动作到达 → 取消待执行的"平滑收起"，
        // 否则 60ms 窗口内会被 setIdle 的收尾覆盖成"空闲"。
        cancelIdleReset()
        main {
            working = true
            actionText?.text = text
            refreshActionVisibility()
        }
    }

    fun setAction(text: String) {
        main {
            cancelIdleReset()
            actionText?.text = text
            refreshActionVisibility()
            // 落盘（v1.2.88）：详情档要显示「上次做了什么 + 什么时候」，
            // 进程重启后仍要能看到，所以每次动作都记一次时间戳
            appCtx?.let { app.dsh.mobile.engine.LastAction.record(it, text) }
        }
    }

    /**
     * 回到空闲态（灰色点 + 空闲文案）。
     *
     * ⚠️ v1.2.185（用户反馈「发送之后语音悬浮框收起时会闪一两下」）：
     * 收起 = **悬浮窗尺寸变化**（展开卡片 → 圆形球：文字/动作行隐藏、padding、
     * minWidth/Height、背景全变），而 View 属性动画管不到窗口尺寸，直接改会在
     * 1~2 帧里看到整体跳变。
     *
     * 处理：把**所有**会改变尺寸的改动（文案、动作行、档位）都挪到 alpha=0 期间，
     * 再淡入。并延迟 60ms 避开语音面板移除那一帧（两个 overlay 窗口同时重排时
     * 闪得更明显）。延迟期间若已有新任务/新状态，[cancelCollapse] 会取消这次收起。
     */
    fun setIdle() {
        main {
            val r = root
            // ★ 只有「自动展开」的那一档才自动收回，且止于档位 1。
            // 用户自己展开到档位 2 是为了看「停止 AI」按钮——
            // AI 一停手就把界面收回去，按钮就消失了，用户根本点不到。
            val needFade = autoExpanded && expandLevel == 1 && r != null

            /** 真正的空闲布局（调用时必须处于 alpha=0 或本来就不需要淡出）。 */
            fun applyIdleNow() {
                statusText?.text = appCtx?.getString(R.string.overlay_idle) ?: "Idle"
                statusDot?.background?.setTint(COLOR_IDLE)
                working = false
                refreshActionVisibility()
                // 同上：只收回自动展开的档位 1。
                if (autoExpanded && expandLevel == 1) {
                    autoExpanded = false
                    expandLevel = 0
                    applyExpandLevel()
                }
            }

            if (!needFade) {
                applyIdleNow()
                return@main
            }

            // 立刻标记，避免重复排队；真正的布局改动在淡出后执行
            autoExpanded = false
            cancelCollapse()
            val job = Runnable {
                collapsePending = null
                val view = root ?: return@Runnable
                view.animate().cancel()
                view.animate().alpha(0f).setDuration(80L).withEndAction {
                    // 此刻不可见 → 安全地改尺寸
                    statusText?.text = appCtx?.getString(R.string.overlay_idle) ?: "Idle"
                    statusDot?.background?.setTint(COLOR_IDLE)
                    working = false
                    refreshActionVisibility()
                    expandLevel = 0
                    applyExpandLevel()
                    view.animate().alpha(1f).setDuration(140L).start()
                }.start()
            }
            collapsePending = job
            mainHandler.postDelayed(job, 60L)
        }
    }

    /** 取消待执行的「平滑收起」（任何新状态都应调用） */
    private fun cancelCollapse() {
        collapsePending?.let { runCatching { mainHandler.removeCallbacks(it) } }
        collapsePending = null
    }

    /**
     * 语音识别状态（v1.2.58）：把状态点染成录音色，动作行显示中间识别结果。
     * @param listening true=正在听，false=结束
     * @param partial 实时识别文本（listening 时刷新）
     */
    fun setListening(listening: Boolean, partial: String = "") {
        main {
            recording = listening
            val ctx = appCtx ?: return@main
            if (listening) {
                cancelIdleReset()   // 同上：录音状态不能被过期的"回空闲"顶掉
                working = true
                statusText?.text = ctx.getString(R.string.overlay_listening)
                statusDot?.background?.setTint(COLOR_RECORDING)
                actionText?.text = partial.ifBlank { ctx.getString(R.string.overlay_speak_now) }
                refreshActionVisibility()
            } else {
                setIdle()
            }
        }
    }

    /** 识别失败/提示（短暂显示，然后回空闲） */
    fun flashNotice(msg: String, holdMs: Long = 3_000L) {
        main {
            val s = statusText ?: return@main
            working = true
            s.text = msg
            statusDot?.background?.setTint(COLOR_RECORDING)
            refreshActionVisibility()
            scheduleIdle(holdMs)   // 可取消：期间的任何新状态都会作废它
        }
    }

    /**
     * 对话完成提示：绿色高亮 + 短暂显示，到点自动回空闲。
     */
    fun flashComplete(summary: String, holdMs: Long = 6_000L) {
        main {
            val s = statusText ?: return@main
            working = true
            s.text = summary
            statusDot?.background?.setTint(COLOR_DONE)
            actionText?.let {
                if (it.text.isEmpty()) it.text = appCtx?.getString(R.string.overlay_turn_done).orEmpty()
            }
            refreshActionVisibility()
            // ⚠️ 必须可取消：这个 6 秒定时器若不被作废，会在「用户马上又让 AI 干活」时
            // 把新写入的「执行中」覆盖成「空闲」——主人实测反馈的正是这个现象。
            scheduleIdle(holdMs)
        }
    }

    /** 默认完成文案（供无 Context 的调用方，如桥接层 handler） */
    fun flashCompleteDefault(holdMs: Long = 6_000L) {
        flashComplete(appCtx?.getString(R.string.overlay_turn_done) ?: "Done", holdMs)
    }

    /** 「执行中 · 点击」 文案（供无 Context 的调用方） */
    fun labelWorkingTap(): String {
        val c = appCtx ?: return "Working"
        return c.getString(R.string.overlay_working, c.getString(R.string.a11y_action_tap))
    }

    /** 通用版状态文案：动作类型由调用方给（点击/滑动/输入/导航/读屏…） */
    fun labelWorking(action: String): String {
        val c = appCtx ?: return "Working"
        return c.getString(R.string.overlay_working, action)
    }

    private fun main(block: () -> Unit) {
        runCatching { android.os.Handler(android.os.Looper.getMainLooper()).post(block) }
    }

    // ---------- 五态状态色（v1.6.27）----------
    //
    // 之前只有「空闲」和「执行中」两种表达，用户在三种真实情形下无从判断：
    // 动作失败了（点还是强调色）、AI 在等你回答、刚做完一件事。
    // 现在拆五档，每档固定配色与一句解释。
    @Volatile private var stateKind: OverlayState.Kind = OverlayState.Kind.IDLE

    /**
     * 暂停态渲染：状态点、文案、提示行全部走红色。
     *
     * 为什么单独一支：暂停时任务状态（执行中/出错/完成）已经不重要 ——
     * 不会有任何动作发生。此刻最该让用户确认的是「它现在动不了」和「怎么恢复」。
     * 文案直接复用按钮已有的两条字符串，不另造新词，避免同一句话两处维护。
     */
    private fun renderPaused(ctx: Context) {
        val red = color(ctx, R.color.action_pause)
        statusDot?.background?.setTint(red)
        statusText?.text = ctx.getString(R.string.overlay_allow_ai_off_short)
        statusText?.setTextColor(red)
        hintView?.let { h ->
            h.text = ctx.getString(R.string.overlay_allow_ai_off)
            h.setTextColor(red)
            h.visibility = View.VISIBLE
        }
    }

    /**
     * 套用某一档状态。
     *
     * 只改「状态点颜色 + 状态文案 + 提示行」，不碰档位与动作行。
     */
    private fun applyState(kind: OverlayState.Kind) {
        val ctx = appCtx ?: return
        stateKind = kind
        // 暂停优先于任务状态：AI 已经不能动了，这才是用户最需要看见的事。
        // 但 AI 已经主动收尾（写完摘要）之后不再染红 —— 那是「已结束」不是「被暂停」，
        // 挂着红会让人以为卡住了（v1.6.35，用户要求收尾后回到绿色）。
        if (!allowOn && !finishedGreen) {
            renderPaused(ctx)
            (root)?.requestLayout()
            return
        }
        val accent = Accent.current(ctx).argb
        val tint = OverlayState.colorOf(kind, accent)
        statusDot?.background?.setTint(tint)
        statusText?.text = ctx.getString(labelOf(kind))
        statusText?.setTextColor(
            if (kind == OverlayState.Kind.IDLE) color(ctx, R.color.text_primary) else tint
        )
        val hintRes = hintOf(kind)
        hintView?.let { h ->
            if (hintRes == 0) {
                h.visibility = View.GONE
            } else {
                h.text = ctx.getString(hintRes)
                h.setTextColor(tint)
                h.visibility = View.VISIBLE
            }
        }
        // 非空闲状态自动展开一档 —— 出错了或者等着回话，用户必须看得见。
        if (OverlayState.needsAttention(kind) && expandLevel == 0) {
            expandLevel = 1
            autoExpanded = true
            applyExpandLevel()
        }
        (root)?.requestLayout()
    }

    private fun labelOf(kind: OverlayState.Kind): Int = when (kind) {
        OverlayState.Kind.IDLE -> R.string.overlay_state_idle
        OverlayState.Kind.WORKING -> R.string.overlay_state_working
        OverlayState.Kind.ERROR -> R.string.overlay_state_error
        OverlayState.Kind.DONE -> R.string.overlay_state_done
        OverlayState.Kind.ASK -> R.string.overlay_state_ask
    }

    private fun hintOf(kind: OverlayState.Kind): Int = when (kind) {
        OverlayState.Kind.ERROR -> R.string.overlay_hint_error
        OverlayState.Kind.DONE -> R.string.overlay_hint_done
        OverlayState.Kind.ASK -> R.string.overlay_hint_ask
        else -> 0
    }

    /** 动作刚被放行、还在飞 —— 切「执行中」档（蓝色）。 */
    fun noteActionStarted() {
        finishedGreen = false   // 又有动作了：绿色收尾状态作废
        main {
            applyState(OverlayState.resolve(
                asking = askShownId != -1L,
                lastFailed = false,
                working = true,
                justFinished = false,
            ))
        }
    }

    /**
     * 一个动作做完了，但任务还在继续 —— 保持「执行中」档。
     *
     * 为什么单独开一个方法而不是复用 [noteActionStarted]：语义不同。
     * 那个是「动作刚开始、还在飞」，这个是「动作结束了、后面还有」。
     * 两者在悬浮窗上都是蓝色执行中，但混用会让后面改文案的人分不清。
     * v1.7.0 起：动作成功不再直接切完成绿（见 AgentBridge 的 decision 分支）。
     */
    fun noteWorking() {
        finishedGreen = false
        main {
            applyState(OverlayState.resolve(
                asking = askShownId != -1L,
                lastFailed = false,
                working = true,
                justFinished = false,
            ))
        }
    }

    /** 刚做的动作失败了（切错误档）。 */
    fun noteActionFailed() {
        main {
            applyState(OverlayState.resolve(
                asking = askShownId != -1L,
                lastFailed = true,
                working = false,
                justFinished = false,
            ))
        }
    }

    /**
     * AI 主动收尾（写摘要结束任务）。
     *
     * 关掉「允许 AI 操作」之后仍然允许收尾，收尾完状态区回到绿色。
     */
    fun noteFinished() {
        finishedGreen = true
        main {
            applyState(OverlayState.resolve(
                asking = askShownId != -1L,
                lastFailed = false,
                working = false,
                justFinished = true,
            ))
        }
    }

    /** 刚做完一件事（切完成档）。 */
    fun noteActionDone() {
        main {
            applyState(OverlayState.resolve(
                asking = askShownId != -1L,
                lastFailed = false,
                working = false,
                justFinished = true,
            ))
        }
    }

    /**
     * 把刚刚点的位置显示出来。
     *
     * 转发给 [PointMarker]。服务不在就安静地不画 —— 画点是附加，
     * 不该因为画不出来就拒绝动作。
     */
    fun showPoint(x: Float, y: Float, label: String = "") {
        val svc = DshAccessibilityService.instance ?: return
        PointMarker.show(svc, x, y, label)
    }

    /** 取主题色（自动跟随日/夜）。 */
    private fun color(ctx: Context, res: Int): Int = ctx.resources.getColor(res, ctx.theme)

    /**
     * 悬浮窗里的小胶囊按钮。
     *
     * 为什么不直接 new Button：系统默认按钮带自己的
     * 灰底、内边距与最小尺寸，套在白色圆角面板里会变成
     * 几个突兀的灰块。用 TextView + 自定义背景才能跟面板同一套语言。
     */
    private fun pill(
        ctx: Context,
        label: String,
        bgRes: Int,
        textColorRes: Int,
        sizeSp: Float,
        topMargin: Int = 0,
        onClick: () -> Unit,
    ): TextView = TextView(ctx).apply {
        text = label
        textSize = sizeSp
        gravity = android.view.Gravity.CENTER
        setTextColor(color(ctx, textColorRes))
        setBackgroundResource(bgRes)
        setPadding(dp(ctx, 10), dp(ctx, 7), dp(ctx, 10), dp(ctx, 7))
        layoutParams = android.widget.LinearLayout.LayoutParams(-1, -2).apply {
            this.topMargin = dp(ctx, topMargin)
        }
        // 显式触摸：与麦克风按钮同一写法（它在 overlay 里靠这个才能响），
        // 而且 alpha 反馈比 isPressed 更稳（自带背景的 TextView 不一定有 pressed 态）。
        setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { v.alpha = 0.65f; true }
                MotionEvent.ACTION_UP -> { v.alpha = 1f; onClick(); true }
                MotionEvent.ACTION_CANCEL -> { v.alpha = 1f; true }
                else -> false
            }
        }
    }

    private fun dp(ctx: Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()
}
