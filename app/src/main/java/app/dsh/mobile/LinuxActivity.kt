package app.dsh.mobile

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import app.dsh.mobile.engine.EngineConfig
import app.dsh.mobile.engine.LinuxContainerRunner
import app.dsh.mobile.engine.LinuxEngineLauncher
import app.dsh.mobile.engine.LinuxEnvironmentManager
import app.dsh.mobile.engine.LinuxPackageManager
import app.dsh.mobile.engine.LinuxRootfsCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Linux 工作区 —— 总览页、终端、包管理与一键安装的统一入口。
 *
 * ## 产品定位
 *
 * 这不是「插件中心里的又一个扩展」，而是和主界面并列的**一等公民**：它给用户
 * 一台装在手机里的完整 Linux 机器。与原生模式的差别不是快慢，而是**能力边界**
 * ——原生模式只跑 DSH 引擎，这里能 `apk add` 装任何软件、能编译 C/C++、
 * 能跑 python/numpy、能起 nginx/redis、能转码视频。
 *
 * ## 页面结构（全部程序化构建，不引入 RecyclerView）
 *
 * 沿用 `ExtensionStoreActivity` 的做法：清单项目数量有限且在同一次会话内不会
 * 上千条，程序化构建更简单也更好控制样式一致性。
 *
 * ```
 * ┌ 状态卡 ────────────────────────────┐
 * │ 运行器 / rootfs 版本 / 占用 / 文件数  │
 * └────────────────────────────────────┘
 * ┌ 操作 ──────────────────────────────┐
 * │ [部署/修复环境] [安装 node] [装开发工具]│
 * └────────────────────────────────────┘
 * ┌ 命令（可交互）───────────────────────┐
 * │ > _                                 │
 * │ 输出区（等宽字体，可滚）                │
 * └────────────────────────────────────┘
 * ```
 *
 * ## 三条硬纪律（都在代码里显式体现）
 *
 * 1. **所有耗时操作走 IO 线程**：解包 rootfs、apk 装包都在后台；UI 只更新控件。
 * 2. **失败必须显示原始报错**：不吞异常、不显示「操作失败」这种无法排查的文案。
 * 3. **不静默降级**：环境不可用时明确说明缺什么、该做什么，不假装一切正常。
 */
class LinuxActivity : ThemedActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var env: LinuxEnvironmentManager
    private lateinit var runner: LinuxContainerRunner
    private lateinit var pkg: LinuxPackageManager
    private lateinit var launcher: LinuxEngineLauncher

    private lateinit var statusCard: LinearLayout
    private lateinit var distroCard: LinearLayout
    private lateinit var outputView: TextView
    private lateinit var cmdInput: EditText
    private lateinit var busyBar: ProgressBar
    private var modeRow: View? = null
    private var modeStatus: TextView? = null
    private var modeSwitch: android.widget.Switch? = null
    private var frameSurface: app.dsh.mobile.engine.LinuxFrameSurface? = null

    /**
     * 触摸回传通道。
     *
     * 为什么挂在 Activity 而不是 FrameSurface 内部：`inputd` 的生命周期属于
     * 「用户点了开始/停止」，与画面控件的 attach/detach 不同步 —— 旋转屏幕会让
     * Surface 重建，但输入服务不该跟着重启（重启会丢掉正在进行的拖动）。
     */
    private var inputChannel: app.dsh.mobile.engine.LinuxInputChannel? = null
    /** 触摸总开关：用户点「开始」之后才把手指动作送进容器 */
    private var inputEnabled = false


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        env = LinuxEnvironmentManager.get(this)
        runner = LinuxContainerRunner.get(this)
        pkg = LinuxPackageManager.get(this)
        launcher = LinuxEngineLauncher.get(this)

        setContentView(buildRoot())
        refreshStatus()
        // 运行器部署是纯本地操作（从 APK 抽 27KB 到 filesDir），耗时几十毫秒，
        // 且不依赖 rootfs。必须在这里主动做一次 —— 否则首屏永远显示「运行器：缺失」，
        // 用户点部署之前都不知道它其实随时可用（实测踩到）。
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    EngineConfig.deployLinuxRunner(this@LinuxActivity)
                }.onFailure { e ->
                    android.util.Log.w("LinuxActivity", "runner deploy failed: ${e.message}")
                }
            }
            refreshStatus()
        }
    }

    override fun onDestroy() {
        runCatching { frameSurface?.release() }
        frameSurface = null
        scope.cancel()
        super.onDestroy()
    }

    // ================= 界面构建 =================

    private fun buildRoot(): View {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(getColor(R.color.bg))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        scroll.addView(root)

        root.addView(title())

        statusCard = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(statusCard)

        root.addView(sectionLabel(getString(R.string.linux_distro_section)))
        distroCard = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(distroCard)

        root.addView(sectionLabel(getString(R.string.linux_actions)))
        root.addView(actionsCard())

        busyBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            visibility = View.GONE
            isIndeterminate = true
            progressTintList = android.content.res.ColorStateList.valueOf(Accent.current(this@LinuxActivity).argb)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(4)
            ).apply { topMargin = dp(8) }
        }
        root.addView(busyBar)

        root.addView(sectionLabel(getString(R.string.linux_screen)))
        root.addView(screenCard())

        root.addView(sectionLabel(getString(R.string.linux_console)))
        root.addView(consoleCard())

        return scroll
    }

    /**
     * 「容器画面」卡片：把容器里帧服务渲染出的画面实时显示出来。
     *
     * 这是整条图形链路在 App 侧的最后一环。数据通路见 [LinuxFrameSurface]：
     * 容器 framesrv 用宿主 GPU 渲染 → 写共享文件 → 这里 mmap → 贴到 SurfaceView。
     *
     * 卡片自带两个按钮：
     *   - 「开始画面」启动容器里的 framesrv（如果已在跑则不重复启动）
     *   - 「停止」杀掉它并释放映射
     *
     * ⚠️ 这两步必须有用户动作触发：帧服务会持续占用 GPU 与一个容器进程，
     * 不能在进页面时自动跑（实测 1080p 下 GPU 常驻会让整机明显发热）。
     */
    private fun screenCard(): View = card().apply {
        // 画布比例：先给 16:9 的初始高度，等真映射到帧后按实际尺寸调整
        frameSurface = app.dsh.mobile.engine.LinuxFrameSurface(this@LinuxActivity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(180)
            )
            onFrameSize = { w, h ->
                // 按容器实际输出比例重排，避免拉伸
                val lp = layoutParams as LinearLayout.LayoutParams
                val widthPx = resources.displayMetrics.widthPixels - dp(60)
                lp.height = (widthPx.toLong() * h / w).toInt().coerceIn(dp(120), dp(720))
                layoutParams = lp
            }
            /*
             * ★触摸回传：把手指动作写进共享文件，容器内 inputd 轮询读走后
             *   通过 XTEST 注入。实测端到端 p50 = 1.3ms（镜像仓 benchmark §18）。
             *
             * 必须 return true（消费掉事件）：返回 false 会让事件继续冒泡给
             * 外层 ScrollView，导致「想拖容器里的窗口，结果页面滚动了」。
             *
             * 只在 inputEnabled 为真时消费 —— 否则用户想滚动页面去看下面的
             * 按钮都做不到（画面控件占满宽度，抓住就是死区）。
             */
            setOnTouchListener { v, ev ->
                if (!inputEnabled) {
                    false
                } else {
                    val ch = inputChannel
                    val dy = displayRect()
                    if (ch != null && ch.handleTouch(ev, dy, frameWidth, frameHeight)) {
                        v.performClick()  // 无障碍：让 TalkBack 也知道这里被点过
                        true
                    } else {
                        true  // 开关开着但帧还没就绪：照样消费，避免误触发页面滚动
                    }
                }
            }
        }
        addView(frameSurface)
        addView(TextView(this@LinuxActivity).apply {
            text = getString(R.string.linux_screen_hint)
            textSize = 12f
            setTextColor(getColor(R.color.text_secondary))
            setPadding(0, dp(8), 0, dp(6))
        })
        addView(actionRow(
            getString(R.string.linux_screen_start),
            getString(R.string.linux_screen_start_sub),
        ) { startFrameServer() })
        addView(actionRow(
            getString(R.string.linux_screen_stop),
            getString(R.string.linux_screen_stop_sub),
        ) { stopFrameServer() })
        addView(actionRow(
            getString(R.string.linux_input_enable),
            getString(R.string.linux_input_enable_sub),
        ) { setInputEnabled(true) })
        addView(actionRow(
            getString(R.string.linux_input_disable),
            getString(R.string.linux_input_disable_sub),
        ) { setInputEnabled(false) })
    }

    /**
     * 开关触摸回传。
     *
     * 打开时同时把容器内 `inputd` 拉起来（幂等：先 pkill 再起，避免残留两份
     * 抢同一个共享文件）。关闭只置标志、**不杀 inputd** —— 用户可能只是暂时
     * 想滚动页面，杀进程会让下次打开多等一次启动。
     */
    private fun setInputEnabled(on: Boolean) = runAsync(null) {
        if (!on) {
            inputEnabled = false
            return@runAsync getString(R.string.linux_input_disabled)
        }
        // 通道在打开时映射；失败就明确告诉用户，别静默失效
        val ch = inputChannel ?: app.dsh.mobile.engine.LinuxInputChannel(this@LinuxActivity)
            .also { inputChannel = it }
        if (!ch.open()) {
            return@runAsync getString(
                R.string.linux_input_failed, ch.lastError ?: "open failed"
            )
        }
        val shared = app.dsh.mobile.engine.LinuxInputChannel.containerPath(packageName)
        /*
         * DISPLAY 必须显式给 :99 —— 与 startFrameServer() 里 Xvfb 的 display 号
         * 同一个。不写死而用 `${DISPLAY:-:0}` 是不行的：`runner.shell()` 起的
         * 是全新的 shell，继承不到 App 那边设置的环境，最终会退到 `:0` 而
         * 那个 display 上根本没有 X 服务器，inputd 报 `INPUTD_FAIL open display`。
         */
        val res = runner.shell(
            "pkill -f inputd 2>/dev/null; sleep 0.2; " +
                "nohup env DISPLAY=:99 inputd $shared 1 " +
                ">/tmp/inputd.log 2>&1 & echo STARTED",
            timeoutMs = 15_000L,
        )
        if (!res.ok) {
            return@runAsync getString(R.string.linux_input_failed, res.combined())
        }
        inputEnabled = true
        getString(R.string.linux_input_enabled) + " → " + shared
    }

    /**
     * 在容器里启动帧服务。
     *
     * 输出走 `nohup ... &` 后台，不阻塞 UI；帧服务自己写共享文件，
     * 这里只管把进程拉起来。
     */
    private fun startFrameServer() = runAsync(null) {
        // 先清掉可能残留的旧进程，避免两个帧服务抢同一个共享文件。
        // 共享文件路径与 LinuxFrameSurface.defaultFrameFile 必须一致。
        val file = app.dsh.mobile.engine.LinuxFrameSurface.defaultFrameFile(this@LinuxActivity)
        // 容器里看到的是 bind 进来的宿主私有目录；传宿主路径给 framesrv
        // 是不行的（容器内那路径不存在），所以传 /data/data/<pkg>/files 形式，
        // 该目录已由 buildBinds 映射进容器。
        val shared = "/data/data/${packageName}/files/${file.name}"
        /*
         * ★走 X11 路线：Xvfb + 真实程序 + x11frsrv 抓合成结果。
         *
         * 为什么不用原来的 `/framesrv`：那个是自己用 EGL 画内容，能证明
         * 「容器能用宿主 GPU」，但**不是用户在操作的画面** —— 用户点不到
         * 里面的任何东西。只有抓 X 服务器上真实程序的输出，「触摸」才有意义。
         *
         * 启动顺序有依赖（实测踩过）：Xvfb 必须先起来，否则 x11frsrv 报
         * `X11FRSRV_FAIL open display`；X 的 socket 目录得先清，否则上一次
         * 残留的 abstract socket 会让新实例报
         * `Cannot establish any listening sockets`（换 display 号没用，得清目录）。
         */
        val cmd = buildString {
            append("pkill -f x11frsrv 2>/dev/null; pkill -f framesrv 2>/dev/null; ")
            append("pkill -f 'Xvfb :99' 2>/dev/null; sleep 0.3; ")
            append("rm -rf /tmp/.X11-unix; mkdir -p /tmp/.X11-unix; ")
            append("nohup Xvfb :99 -screen 0 1280x720x24 -nolock >/tmp/xvfb.log 2>&1 & ")
            append("sleep 2; ")
            // 示例程序：一个能明确显示「这是容器里的真实程序」的终端。
            // 用 -e 直接跑内容，不用 &（后台起的 xterm 不会画东西，实测）。
            append("export DISPLAY=:99; ")
            append("nohup xterm -T 'DSH Container' -geometry 100x30 ")
            append("-e sh -c 'echo CONTAINER READY; uname -m; cat /etc/alpine-release 2>/dev/null; exec sh' ")
            append(">/dev/null 2>&1 & ")
            append("sleep 2; ")
            append("nohup /x11frsrv $shared 1280 720 30 >/tmp/x11frsrv.log 2>&1 & ")
            append("sleep 1; echo STARTED")
        }
        val res = runner.shell(cmd, timeoutMs = 40_000L)
        if (res.ok) {
            getString(R.string.linux_screen_started) + " → " + shared
        } else {
            getString(R.string.linux_screen_start_failed, res.combined())
        }
    }

    /** 停掉帧服务（容器内 pkill）。画面停在最后一帧，不黑屏。 */
    private fun stopFrameServer() = runAsync(null) {
        val res = runner.shell(
            "pkill -f x11frsrv; pkill -f framesrv; echo STOPPED",
            timeoutMs = 10_000L,
        )
        if (res.ok) getString(R.string.linux_screen_stopped)
        else getString(R.string.linux_screen_stop_failed, res.combined())
    }

    private fun title(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(ImageView(this@LinuxActivity).apply {
            setImageResource(R.drawable.ic_ext_android_tools)
            setColorFilter(Accent.current(this@LinuxActivity).argb)
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
        })
        addView(TextView(this@LinuxActivity).apply {
            text = getString(R.string.linux_title)
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(getColor(R.color.text_primary))
            setPadding(dp(10), 0, 0, 0)
        })
        setPadding(0, 0, 0, dp(12))
    }

    private fun sectionLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Accent.current(this@LinuxActivity).argb)
        setPadding(dp(4), dp(18), dp(4), dp(8))
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.bg_card)
        setPadding(dp(14), dp(12), dp(14), dp(12))
    }

    private fun actionsCard(): View = card().apply {
        addView(actionRow(
            getString(R.string.linux_action_deploy),
            getString(R.string.linux_action_deploy_sub),
        ) { deploy(force = false) })
        addView(actionRow(
            getString(R.string.linux_action_repair),
            getString(R.string.linux_action_repair_sub),
        ) { deploy(force = true) })
        addView(actionRow(
            getString(R.string.linux_action_node),
            getString(R.string.linux_action_node_sub),
        ) { installPackages(listOf("nodejs")) })
        addView(actionRow(
            getString(R.string.linux_action_devtools),
            getString(R.string.linux_action_devtools_sub),
        ) { installPackages(listOf("nodejs", "git", "make", "gcc", "musl-dev")) })
        addView(actionRow(
            getString(R.string.linux_action_terminal),
            getString(R.string.linux_action_terminal_sub),
        ) { openLinuxTerminal() })
        // 引擎运行模式开关：这是整个页面最关键的开关 —— 决定 App 的 DSH 引擎
        // 跑在原生 Android 还是这个 Linux 容器里。放在操作卡最下方，因为它的
        // 前提是上面的环境已就绪。
        modeRow = modeRow()
        addView(modeRow)
    }

    /**
     * 打开**容器内**的真 PTY 终端。
     *
     * 复用宿主 TerminalActivity（Tab 管理 / 字体 / 快捷键 / 会话恢复都是现成的），
     * 只靠一个 Intent extra 让它改在 Alpine 里起 shell。
     * 环境没就绪时不在这一层判断 —— TerminalActivity 会自己显示「未就绪」，
     * 判断只留一处，避免两边的就绪条件将来走偏。
     */
    private fun openLinuxTerminal() {
        val i = Intent(this, TerminalActivity::class.java)
            .putExtra(TerminalActivity.EXTRA_IN_LINUX, true)
        runCatching { startActivity(i) }.onFailure {
            appendOutput("x ${it.message ?: it.javaClass.simpleName}")
        }
    }

    /**
     * 引擎模式开关行。
     *
     * ⚠️ 必须显式展示「当前是原生还是 Linux」，包括**回退状态**。
     * 用户开了 Linux 模式但环境没就绪时我们会回退原生，若不显示，用户会
     * 以为自己正在用完整 Linux 环境（装了 gcc 却发现编不了）。
     */
    private fun modeRow(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(12), dp(4), dp(12))
            isClickable = true
            isFocusable = true
            val tv = android.util.TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
            if (tv.resourceId != 0) setBackgroundResource(tv.resourceId)
            setOnClickListener { toggleEngineMode() }
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(TextView(this).apply {
            text = getString(R.string.linux_engine_mode)
            textSize = 15f
            setTextColor(getColor(R.color.text_primary))
        })
        modeStatus = TextView(this).apply {
            textSize = 12f
            setTextColor(getColor(R.color.text_secondary))
            setPadding(0, dp(2), 0, 0)
        }
        col.addView(modeStatus)
        row.addView(col)
        modeSwitch = android.widget.Switch(this).apply {
            isClickable = false   // 整行可点，switch 只作指示
            isFocusable = false
        }
        row.addView(modeSwitch)
        return row
    }

    /** 刷新模式开关与说明（每次状态变化后调用） */
    private fun refreshModeRow() {
        val enabled = app.dsh.mobile.engine.EngineModeSelector.isLinuxEnabled(this)
        modeSwitch?.isChecked = enabled
        val running = app.dsh.mobile.engine.EngineModeSelector.lastMode
        val reason = app.dsh.mobile.engine.EngineModeSelector.lastReason
        modeStatus?.text = when {
            !enabled -> getString(R.string.linux_mode_native)
            running == app.dsh.mobile.engine.EngineModeSelector.Mode.LINUX ->
                getString(R.string.linux_mode_active)
            else -> getString(R.string.linux_mode_fallback, reason.ifBlank { "unknown" })
        }
        val active = running == app.dsh.mobile.engine.EngineModeSelector.Mode.LINUX
        modeStatus?.setTextColor(getColor(if (active) R.color.success else R.color.text_secondary))
    }

    private fun toggleEngineMode() {
        val cur = app.dsh.mobile.engine.EngineModeSelector.isLinuxEnabled(this)
        val next = !cur
        if (next) {
            // 开启前校验**完整**可用性，而不只是 rootfs 存在。
            //
            // ⚠️ 踩过的坑：最初只查 status()==READY 就放行，结果 rootfs 好了但
            // node 没装时用户也能打开开关 —— 引擎随即回退原生，UI 显示
            // 「已回退原生模式：unknown」，用户完全不知道缺的是什么。
            // 现在用 launcher.readiness() 做端到端判定，每种失败都给出可操作提示。
            when (val r = launcher.readiness()) {
                is LinuxEngineLauncher.Readiness.Ready -> Unit
                is LinuxEngineLauncher.Readiness.NeedsDeploy -> {
                    appendOutput("✗ ${getString(R.string.linux_err_env_not_ready)}")
                    return
                }
                is LinuxEngineLauncher.Readiness.NeedsPackages -> {
                    appendOutput("✗ ${r.reason}")
                    return
                }
                is LinuxEngineLauncher.Readiness.Unavailable -> {
                    appendOutput("✗ ${r.reason}")
                    return
                }
            }
        }
        app.dsh.mobile.engine.EngineModeSelector.setLinuxEnabled(this, next)
        refreshModeRow()
        appendOutput(
            getString(
                if (next) R.string.linux_mode_on_hint else R.string.linux_mode_off_hint
            )
        )
    }

    private fun actionRow(title: String, sub: String, onTap: () -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(10), dp(4), dp(10))
            isClickable = true
            isFocusable = true
            val tv = android.util.TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
            if (tv.resourceId != 0) setBackgroundResource(tv.resourceId)
            addView(LinearLayout(this@LinuxActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(this@LinuxActivity).apply {
                    text = title
                    textSize = 15f
                    setTextColor(getColor(R.color.text_primary))
                })
                addView(TextView(this@LinuxActivity).apply {
                    text = sub
                    textSize = 12f
                    setTextColor(getColor(R.color.text_secondary))
                    setPadding(0, dp(2), 0, 0)
                })
            })
            addView(ImageView(this@LinuxActivity).apply {
                setImageResource(R.drawable.ic_chevron)
                setColorFilter(getColor(R.color.text_secondary))
                layoutParams = LinearLayout.LayoutParams(dp(18), dp(18))
            })
            setOnClickListener { onTap() }
        }

    private fun consoleCard(): View = card().apply {
        outputView = TextView(this@LinuxActivity).apply {
            text = getString(R.string.linux_console_hint)
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(getColor(R.color.text_secondary))
            setPadding(0, 0, 0, dp(8))
            setTextIsSelectable(true)
        }
        addView(outputView)

        val inputRow = LinearLayout(this@LinuxActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        inputRow.addView(TextView(this@LinuxActivity).apply {
            text = "$ "
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextColor(Accent.current(this@LinuxActivity).argb)
        })
        cmdInput = EditText(this@LinuxActivity).apply {
            hint = getString(R.string.linux_console_placeholder)
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextColor(getColor(R.color.text_primary))
            setHintTextColor(getColor(R.color.text_secondary))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            background = null
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnEditorActionListener { _, _, _ ->
                submitCommand()
                true
            }
        }
        inputRow.addView(cmdInput)
        inputRow.addView(Button(this@LinuxActivity).apply {
            text = getString(R.string.linux_console_run)
            textSize = 12f
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(12), dp(4), dp(12), dp(4))
            setOnClickListener { submitCommand() }
        })
        addView(inputRow)
    }

    // ================= 状态刷新 =================

    private fun refreshStatus() {
        val st = env.status()
        statusCard.removeAllViews()
        statusCard.addView(card().apply {
            addView(statusLine(
                getString(R.string.linux_status_state),
                when (st.state) {
                    LinuxEnvironmentManager.State.READY -> getString(R.string.linux_state_ready)
                    LinuxEnvironmentManager.State.NOT_DEPLOYED -> getString(R.string.linux_state_not_deployed)
                    LinuxEnvironmentManager.State.DEPLOYING -> getString(R.string.linux_state_deploying)
                    LinuxEnvironmentManager.State.BROKEN -> getString(R.string.linux_state_broken)
                },
                st.state == LinuxEnvironmentManager.State.READY,
            ))
            if (st.rootfsVersion != null) {
                addView(statusLine(getString(R.string.linux_status_version), st.rootfsVersion, true))
            }
            // 体积/文件数是两次完整目录遍历（rootfs 628 文件，实测 300~500ms），
            // 不能放在 status() 里 —— 那会拖慢**每次冷启**（EngineModeSelector
            // 每次启动都调 status()）。这里先占位，后台算完再填。
            if (st.state == LinuxEnvironmentManager.State.READY) {
                val (sizeRow, sizeValue) = statusLineLive(getString(R.string.linux_status_size), true)
                addView(sizeRow)
                scope.launch {
                    val (bytes, files) = withContext(Dispatchers.IO) {
                        env.diskUsage() to env.fileCount()
                    }
                    if (bytes > 0) {
                        sizeValue.text = String.format(
                            "%.1f MB / %d %s",
                            bytes / (1024.0 * 1024.0), files,
                            getString(R.string.linux_files),
                        )
                    }
                }
            }
            // 运行器状态：固定基址是硬要求，单独展示
            val runnerFile = java.io.File(java.io.File(filesDir, "linux/runner"), "dsh_alpine")
            val runnerOk = runnerFile.isFile && EngineConfig.isFixedBaseLinuxRunner(runnerFile)
            addView(statusLine(
                getString(R.string.linux_status_runner),
                if (runnerOk) getString(R.string.linux_runner_ok) else getString(R.string.linux_runner_missing),
                runnerOk,
            ))
            if (st.detail.isNotBlank()) {
                addView(TextView(this@LinuxActivity).apply {
                    text = st.detail
                    textSize = 12f
                    setTextColor(getColor(R.color.warning))
                    setPadding(0, dp(6), 0, 0)
                })
            }
        })
        refreshDistroCard()
        refreshModeRow()
    }

    /**
     * 系统镜像选择卡：逐项列出可选发行版，标出「当前使用 / 已安装 / 未安装」，
     * 点一下即切换（切换只改指向，不动文件 —— 见 LinuxRootfsCatalog 的类注释）。
     *
     * 每项都显示**实测体积**，因为它要跟剩余磁盘比：下载 30MB 之前先让用户
     * 知道代价，比下到一半报 ENOSPC 好。
     */
    private fun refreshDistroCard() {
        distroCard.removeAllViews()
        val active = LinuxRootfsCatalog.active(this)
        distroCard.addView(card().apply {
            addView(TextView(this@LinuxActivity).apply {
                text = getString(R.string.linux_distro_sub)
                textSize = 12f
                setTextColor(getColor(R.color.text_secondary))
                setPadding(0, 0, 0, dp(6))
            })
            // ★用 catalog（带 flavor 展开）而不是 options：
            //   options 里 Alpine 只有一条，catalog 会展开成
            //   「Alpine 3.22 / Alpine 3.22 Dev / Alpine 3.22 GUI」三行 ——
            //   三者共享同一个 rootfs 目录，只是首启额外装的包不同
            //   （见 LinuxRootfsCatalog.Flavor 的注释）。
            LinuxRootfsCatalog.catalog.forEach { opt ->
                // 激活判定必须带上 flavor：同一 family 下只有**当前选中的形态**
                // 才算激活，否则三行会同时亮
                //
                // ★installed 的语义要分清两层（否则 UI 会误导）：
                //   · rootfs 部署态 —— 按 family 算，flavor 共享同一目录
                //   · 该形态的额外包是否已装 —— 按 flavor 的标记文件算
                // 只报前者会让「切到 GUI」显示成「已安装」，而 Xvfb 其实还没装，
                // 用户点开始画面就得到 not found。
                val deployed = env.isDeployed(opt)
                val extrasReady = env.flavorPackagesReady(opt)
                val isActive = opt.family == active.family && opt.flavor == active.flavor
                // ABI 不支持时标成不可用而不是隐藏：隐藏会让用户以为「选项丢了」
                val usable = LinuxRootfsCatalog.availableOnThisAbi(opt.flavor)
                addView(distroRow(opt, deployed, isActive, extrasReady, usable))
            }
            addView(TextView(this@LinuxActivity).apply {
                text = getString(R.string.linux_distro_engine_note)
                textSize = 11f
                setTextColor(getColor(R.color.text_secondary))
                setPadding(0, dp(8), 0, 0)
            })
        })
    }

    /** 单个发行版行：名称 + libc + 体积 + 状态徽标；点击切换 */
    private fun distroRow(
        opt: LinuxRootfsCatalog.Option,
        installed: Boolean,
        isActive: Boolean,
        extrasReady: Boolean = true,
        usable: Boolean = true,
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4), dp(10), dp(4), dp(10))
        isClickable = true
        isFocusable = true
        val tv = android.util.TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
        if (tv.resourceId != 0) setBackgroundResource(tv.resourceId)

        addView(LinearLayout(this@LinuxActivity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(this@LinuxActivity).apply {
                text = "${opt.displayName} ${opt.versionLabel}" +
                    if (opt.flavor == LinuxRootfsCatalog.Flavor.BASE) ""
                    else " · ${opt.flavor.label}"
                textSize = 15f
                setTextColor(getColor(R.color.text_primary))
            })
            addView(TextView(this@LinuxActivity).apply {
                // libc 是这个选项的技术分水岭，必须显示：musl 跑不了 glibc 程序
                text = "${opt.libc} · ${opt.installCommand}"
                textSize = 12f
                setTextColor(getColor(R.color.text_secondary))
                setPadding(0, dp(2), 0, 0)
            })
            addView(TextView(this@LinuxActivity).apply {
                text = sizeLine(opt, installed)
                textSize = 12f
                setTextColor(getColor(R.color.text_secondary))
                setPadding(0, dp(2), 0, 0)
            })
        })

        addView(TextView(this@LinuxActivity).apply {
            // 四种状态，别把「rootfs 在但该形态的包没装全」混成「已安装」
            text = when {
                !usable -> getString(R.string.linux_flavor_unsupported_short)
                isActive && installed && extrasReady -> getString(R.string.linux_distro_active)
                // rootfs 已部署、但这一形态的额外包还没装 —— 首次启动才会装
                installed && !extrasReady -> getString(R.string.linux_distro_extras_pending)
                installed -> getString(R.string.linux_distro_installed)
                else -> getString(R.string.linux_distro_not_installed)
            }
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(
                getColor(
                    when {
                        !usable -> R.color.text_secondary
                        isActive && extrasReady -> R.color.success
                        installed && !extrasReady -> R.color.warning
                        installed -> R.color.text_secondary
                        else -> R.color.warning
                    }
                )
            )
        })

        // 不可用的形态点了只给解释，不真的去切（切了也用不了）
        if (!usable) {
            isClickable = false
            isFocusable = false
            alpha = 0.45f
            setOnClickListener {
                appendOutput("✗ " + getString(R.string.linux_flavor_unsupported, opt.flavor.label))
            }
        } else {
            setOnClickListener { switchDistro(opt) }
        }
    }

    /**
     * 体积行文案：下载体积 / 解压后磁盘占用。
     *
     * 两个数字都要给：用户点「切换」时先看到代价（30MB 下载 + 96MB 磁盘），
     * 比下到一半遇到 ENOSPC 好。已安装时同样显示，方便判断要不要清理。
     */
    @Suppress("UNUSED_PARAMETER")
    private fun sizeLine(opt: LinuxRootfsCatalog.Option, installed: Boolean): String {
        val src = opt.source
        val compressed = when (src) {
            is LinuxRootfsCatalog.RootfsSource.Bundled -> src.compressedBytes
            is LinuxRootfsCatalog.RootfsSource.Remote -> src.compressedBytes
        }
        val extracted = when (src) {
            is LinuxRootfsCatalog.RootfsSource.Bundled -> src.extractedBytes
            is LinuxRootfsCatalog.RootfsSource.Remote -> src.extractedBytes
        }
        return getString(R.string.linux_distro_size, mb(compressed), mb(extracted))
    }

    private fun mb(bytes: Long): String = String.format("%.1f MB", bytes / (1024.0 * 1024.0))

    /**
     * 切换发行版。
     *
     * 只有两种结局，都必须让用户看懂：
     * - 已安装 → 只改指向，立即生效（引擎需重启）
     * - 未安装 → 先部署（联网下载），成功后再切换指向
     *
     * 切换前检查磁盘：Ubuntu 解压后约 96MB，空间不足时提前报错，
     * 而不是让用户在下载完 30MB 之后才遇到 ENOSPC。
     */
    private fun switchDistro(opt: LinuxRootfsCatalog.Option) {
        val needBytes = when (val src = opt.source) {
            is LinuxRootfsCatalog.RootfsSource.Bundled -> src.extractedBytes
            is LinuxRootfsCatalog.RootfsSource.Remote -> src.extractedBytes
        }
        val free = filesDir.usableSpace
        if (!env.isDeployed(opt) && free in 1 until needBytes) {
            appendOutput(
                "✗ " + getString(
                    R.string.linux_err_disk_space,
                    mb(needBytes),
                    mb(free),
                )
            )
            return
        }
        runAsync(
            if (env.isDeployed(opt)) null else getString(R.string.linux_msg_downloading_rootfs)
        ) {
            if (!env.isDeployed(opt)) {
                val st = env.ensureDeployed(opt, force = false)
                if (st.state != LinuxEnvironmentManager.State.READY) {
                    return@runAsync "✗ ${st.detail}"
                }
            }
            LinuxRootfsCatalog.setActive(this@LinuxActivity, opt.family)
            // ★必须同时记 flavor：只记 family 的话，用户点「Alpine · GUI」之后
            //   active() 读回来还是 BASE，GUI 的额外包永远不装 —— 表现是
            //   「点了开始画面报 Xvfb: not found」，而界面上明明已经选了 GUI。
            //   API 的 /dev/linux/switch 一直有调，UI 这条路径漏了（探针也没抓到）。
            LinuxRootfsCatalog.setActiveFlavor(this@LinuxActivity, opt.flavor)
            "✓ " + getString(
                R.string.linux_msg_switched,
                "${opt.displayName} ${opt.versionLabel}" +
                    if (opt.flavor == LinuxRootfsCatalog.Flavor.BASE) ""
                    else " · ${opt.flavor.label}"
            )
        }
    }

    /**
     * 带值视图引用的状态行 —— 供「先占位、后台算完再更新」的场景使用
     * （体积/文件数要遍历整个 rootfs，不能阻塞首屏）。
     */
    private fun statusLineLive(label: String, ok: Boolean): Pair<View, TextView> {
        val value = TextView(this).apply {
            text = "..."
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(getColor(if (ok) R.color.success else R.color.warning))
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(3), 0, dp(3))
            addView(TextView(this@LinuxActivity).apply {
                text = label
                textSize = 13f
                setTextColor(getColor(R.color.text_secondary))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(value)
        }
        return row to value
    }

    private fun statusLine(label: String, value: String, ok: Boolean): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(3), 0, dp(3))
            addView(TextView(this@LinuxActivity).apply {
                text = label
                textSize = 13f
                setTextColor(getColor(R.color.text_secondary))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(this@LinuxActivity).apply {
                text = value
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(getColor(if (ok) R.color.success else R.color.warning))
            })
        }

    // ================= 操作 =================

    private fun deploy(force: Boolean) {
        runAsync(
            if (force) getString(R.string.linux_msg_redeploying) else getString(R.string.linux_msg_deploying)
        ) {
            val st = env.ensureDeployed(force = force)
            if (st.state == LinuxEnvironmentManager.State.READY) {
                // 运行器部署**独立于** rootfs 状态判断：两者是不同的产物
                // （rootfs 是资产解包，运行器是 APK 原生库提取）。把它们绑在
                // 同一个 if 里，一旦 rootfs 判据写错，运行器就永远不部署 ——
                // 这正是实测踩到的「状态：需要修复 / 运行器：缺失」双错的成因。
                val runnerFile = EngineConfig.deployLinuxRunner(this@LinuxActivity)
                if (runnerFile == null) {
                    "✗ ${getString(R.string.linux_err_no_runner)}"
                } else if (!EngineConfig.isFixedBaseLinuxRunner(runnerFile)) {
                    "✗ ${getString(R.string.linux_err_bad_runner)}"
                } else {
                    "✓ ${getString(R.string.linux_msg_deploy_ok)}\n" +
                        "rootfs: ${st.rootfsVersion}\n" +
                        "files: ${st.fileCount}, ${st.rootfsBytes / 1048576} MB"
                }
            } else {
                "✗ ${st.detail}"
            }
        }
    }

    private fun installPackages(packages: List<String>) {
        runAsync(getString(R.string.linux_msg_installing, packages.joinToString(" "))) {
            if (!runner.ensureReady()) {
                return@runAsync "✗ ${getString(R.string.linux_err_env_not_ready)}"
            }
            val idx = pkg.refreshIndex()
            if (!idx.ok) {
                return@runAsync "✗ apk update failed:\n${idx.rawOutput}"
            }
            val r = pkg.install(packages)
            if (r.ok) {
                "✓ apk add ${packages.joinToString(" ")}\n${r.rawOutput.takeLast(1500)}"
            } else {
                "✗ apk add failed:\n${r.rawOutput.takeLast(2000)}"
            }
        }
    }

    private fun submitCommand() {
        val cmd = cmdInput.text?.toString()?.trim().orEmpty()
        if (cmd.isEmpty()) return
        cmdInput.setText("")
        runAsync(null) {
            if (!runner.ensureReady()) {
                return@runAsync "$ ${cmd}\n✗ ${getString(R.string.linux_err_env_not_ready)}"
            }
            val r = runner.shell(cmd, timeoutMs = 120_000)
            buildString {
                append("$ ").append(cmd).append('\n')
                val out = r.combined()
                if (out.isNotEmpty()) append(out).append('\n')
                append("[exit ${r.exitCode}")
                if (r.timedOut) append(", TIMEOUT")
                append(", ${r.durationMs}ms]")
            }
        }
    }

    /**
     * 统一的异步执行包装：显示忙碌条 → 后台跑 → 写输出区 → 刷新状态。
     *
     * `banner` 为 null 时不往输出区追加提示（终端命令自己会打 `$ cmd`）。
     */
    private fun runAsync(banner: String?, block: suspend () -> String) {
        busyBar.visibility = View.VISIBLE
        if (banner != null) appendOutput(banner)
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching { block() }.getOrElse { e ->
                    // 纪律：原始报错必须露出，不吞异常
                    "✗ ${e::class.java.simpleName}: ${e.message}"
                }
            }
            busyBar.visibility = View.GONE
            appendOutput(text)
            refreshStatus()
        }
    }

    private fun appendOutput(text: String) {
        val cur = outputView.text?.toString().orEmpty()
        val merged = if (cur == getString(R.string.linux_console_hint) || cur.isEmpty()) text else "$cur\n$text"
        outputView.text = merged
        outputView.setTextColor(getColor(R.color.text_primary))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        @Volatile
        private var inst: LinuxEngineLauncher? = null
    }
}
