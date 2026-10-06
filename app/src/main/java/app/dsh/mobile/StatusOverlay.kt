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
 * 对齐设置页设计语言：深色表面 #1C232C + 16dp 圆角 + 细描边 + 强调色 #7DD3FC；
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
    private const val COLLAPSED_BALL_DP = 44

    /**
     * 状态点直径（dp）—— **折叠态与展开态用同一个值**。
     * 主人要求「让两个球在变化大小不变」：展开/收起时这颗点不能忽大忽小。
     * （旧代码折叠 22dp / 展开 10dp，切换时明显跳一下。）
     */
    private const val DOT_DP = 14

    /** 松手后吸附边缘的动画时长 */
    private const val SNAP_MS = 180L

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
    private const val COLOR_WORKING = 0xFF7DD3FC.toInt()   // 强调蓝
    private const val COLOR_DONE = 0xFF81C995.toInt()      // 完成绿
    private const val COLOR_RECORDING = 0xFFF28B82.toInt() // 录音红

    fun isAvailable(): Boolean = DshAccessibilityService.instance != null

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
        // 动作行（AI 正在干什么）：统一走 refreshActionVisibility（档位 ≥1 且有活干才显示）
        refreshActionVisibility()
        // 离开详情档就停掉周期刷新（档位 2 期间由 refreshSessionList 自己续期）
        if (expandLevel != 2) stopListRefresh()
        sessionsBox?.visibility = if (expandLevel >= 2) View.VISIBLE else View.GONE

        r?.let { v ->
            val pad = if (collapsed) dp(ctx, 4) else dp(ctx, 8)
            v.setPadding(
                if (collapsed) dp(ctx, 4) else dp(ctx, 10),
                pad,
                if (collapsed) dp(ctx, 4) else dp(ctx, 12),
                pad,
            )
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
                setTextColor(0xFF7DD3FC.toInt())
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

    fun hide(svc: AccessibilityService?) {
        main {
            val r = root ?: return@main
            runCatching {
                (svc?.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.removeView(r)
            }
            root = null; params = null; statusText = null; actionText = null; statusDot = null
            hidden = true
            Log.i(TAG, "overlay hidden by user")
        }
    }

    /** 用户隐藏后重新允许显示（引擎重启等场景调用） */
    fun resetHidden() { hidden = false }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 待执行的「回空闲」任务（必须可取消，见 scheduleIdle 注释） */
    @Volatile private var idleReset: Runnable? = null

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
            statusText?.text = text
            statusDot?.background?.setTint(COLOR_WORKING)
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

    /** 回到空闲态（灰色点 + 空闲文案） */
    fun setIdle() {
        main {
            statusText?.text = appCtx?.getString(R.string.overlay_idle) ?: "Idle"
            statusDot?.background?.setTint(COLOR_IDLE)
            // 空闲 = 没有「正在干什么」：动作行收起（文字保留，下次干活时还能看到最近一次）
            working = false
            refreshActionVisibility()
            // 只收回「因干活自动展开」的那一次；用户手动展开的档位不擅自动
            if (autoExpanded) {
                autoExpanded = false
                expandLevel = 0
                applyExpandLevel()
            }
        }
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

    private fun dp(ctx: Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()
}
