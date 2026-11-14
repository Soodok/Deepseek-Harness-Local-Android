package app.dsh.mobile

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.dsh.mobile.engine.EngineConfig
import app.dsh.mobile.engine.LinuxContainerRunner
import app.dsh.mobile.engine.LinuxEngineLauncher
import app.dsh.mobile.engine.LinuxDnsProxy
import app.dsh.mobile.engine.LinuxEnvironmentManager
import app.dsh.mobile.engine.LinuxPackageManager
import app.dsh.mobile.engine.LinuxRootfsCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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

    /**
     * 整屏自绘的 Linux 工作台不跟随界面风格。
     *
     * 它的底色、分组卡、状态点全是代码里算出来的（80 处 addView、29 处 getColor），
     * 配色按「深色工作台」设计 —— 套 Material 3 暖灰会让自绘部分与
     * `?attr/dshXxx` 部分互相打架。等它的自绘层也改成令牌驱动后再纳入。
     */
    override val followsStyle: Boolean get() = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var env: LinuxEnvironmentManager
    private lateinit var runner: LinuxContainerRunner
    private lateinit var pkg: LinuxPackageManager
    private lateinit var launcher: LinuxEngineLauncher

    private lateinit var wb: LinuxWorkbenchView
    private lateinit var scrollView: ScrollView
    private var factsSeq = 0

    /** 最近一次读到的事实与归约结果：忙碌叠加和「开始」按钮的分流都用它，不必为此再读一次盘 */
    private var lastFacts: WorkbenchFacts? = null
    private var lastState: WorkbenchUiState? = null

    /**
     * 页面发起、还在跑的「会改动容器环境」的操作（部署 / 装包 / 删除）。
     *
     * 为什么放在 Activity 而不是从磁盘读：环境层的 status() 只会报未部署 / 损坏 / 就绪，
     * 没有「正在装」这个状态。不记下来的话，装包的几分钟里页面和空闲时一模一样。
     */
    private var busyKind: BusyKind? = null
    private var busyDetail: String? = null
    private var busyStart = 0L
    /** 包管理器吐出的最新一行（IO 线程写、主线程读） */
    @Volatile private var busyLine = ""
    /** 这次操作怎么量进度（阶段、各阶段的预计体量） */
    private var busyPlan: BusyPlan? = null
    private var busyMeter: ProgressMeter? = null
    /** 包管理器报的「第几个 / 共几个」（IO 线程写、取样线程读） */
    @Volatile private var busyStep: ApkStep? = null
    /** 正在更新软件源索引（装包之前的那几秒，还没有任何包开始装） */
    @Volatile private var busyIndexing = false
    /** 把按块到达的输出拼回成行；每次操作换一个新的，上一次剩的半行不会串过来 */
    @Volatile private var busyLines = LineAssembler()
    /** 最近一次查到的已装软件名：点「Node」「工具链」时用来判断是不是已经装过了 */
    private var lastInstalled: Set<String> = emptySet()
    /** 正在被操作的那张镜像卡：卡片重建后要把它的进度条重新点亮 */
    private var busyCardKey: String? = null
    /** 横幅第二行最近一次显示的阶段文字（操作成功后收尾的那一两秒继续用它） */
    private var busyLastText: String? = null
    /**
     * 操作已经成功结束、但页面还没读到新状态的那一小段。
     *
     * 结束后要重新读盘（还要起一次容器去数已装的软件），在模拟器上实测要一两秒。
     * 这段时间若把读数收掉，横幅会退回一个没有百分比、来回滚动的「正在部署…」，
     * 看上去像是又卡住了；所以读数停在 100%，等新状态画出来的那一刻再一起收。
     */
    private var busySettling = false
    private var busyTicker: Job? = null

    /** 运行模式选择浮层（未显示时为 null） */
    private var modeHost: FrameLayout? = null
    private lateinit var statusCard: LinearLayout
    private lateinit var distroCard: LinearLayout
    private lateinit var guideCard: LinearLayout
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

    /**
     * 每个发行版行的行内进度控件（v1.5.3）。
     *
     * 为什么需要：原来安装只在顶部显示一根 indeterminate 横条，用户看不出
     * 「是哪个在装」「装到哪了」。扩展中心每一行都有自己的水平进度条，这里对齐它。
     * key 用 `family:flavor`；`refreshDistroCard` 重建时会清空重建。
     */
    private val rowProgress = mutableMapOf<String, ProgressBar>()

    /** 分组展开态（纯内存，进页面默认收起 —— 首屏只留「基础环境」） */
    private var moreExpanded = false
    private var otherExpanded = false


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        env = LinuxEnvironmentManager.get(this)
        runner = LinuxContainerRunner.get(this)
        pkg = LinuxPackageManager.get(this)
        launcher = LinuxEngineLauncher.get(this)

        setContentView(buildRoot())
        refreshStatus()
        // 引擎重启完成后重读一次状态：模式行的「重启后生效」和主卡的运行态取决于引擎实际跑在哪边。
        // 不监听的话，在本页点完「重启引擎」，页面会一直停在重启前的样子。
        scope.launch {
            var first = true
            (application as DshApp).supervisor.state.collectLatest { st ->
                // 第一个值是订阅那一刻的现状，上面已经刷过一次
                if (first) {
                    first = false
                    return@collectLatest
                }
                if (st is app.dsh.mobile.engine.EngineSupervisor.State.Healthy) refreshStatus()
            }
        }
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

    /**
     * 页面装配（方案 E）。
     *
     * 顺序即用户真实的决策链：现在是什么状态 → 该做什么 → 有哪些镜像 →
     * 装了什么 → 能开什么 → 画面 / 命令。
     *
     * 每个区块的显隐由 [LinuxWorkbenchUiState] 单向决定，页面里不再各自判状态。
     */
    private fun buildRoot(): View {
        scrollView = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(getColor(R.color.wb_bg))
        }
        wb = LinuxWorkbenchView(this) { action -> onWorkbenchAction(action) }
        val root = wb.build() as LinearLayout
        scrollView.addView(root)

        // 忙碌进度条挂在操作区下方：安装 / 部署时它离手指最近
        busyBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            visibility = View.GONE
            isIndeterminate = true
            progressTintList = android.content.res.ColorStateList.valueOf(
                Accent.current(this@LinuxActivity).argb
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(4)
            ).apply { topMargin = dp(8) }
        }
        wb.actionsHost.addView(busyBar)

        // 画面与命令两个区块沿用既有实现（它们持有 SurfaceView 与输入框）
        wb.screenHost.addView(sectionLabel(getString(R.string.linux_screen)))
        wb.screenHost.addView(screenCard())
        wb.consoleHost.addView(sectionLabel(getString(R.string.linux_console)))
        wb.consoleHost.addView(consoleCard())

        return scrollView
    }

    /** 旧装配入口（保留一个版本给探针与并行改动兜底）：只装配顶层标题 */
    private fun legacyRoot(): View {
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

        // 引导卡放在最上面：用户打开这一页的第一困惑是「我现在该干什么」，
        // 原文案只有一句发行版介绍，7 个选项全标 Not installed，等于让人自己猜。
        guideCard = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(guideCard)

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

        // 抓 compositor 的合成画面（任何 Wayland 窗口都被合成进去），
        // 与上面 framesrv / x11frsrv 的「自己渲染 / 抓 X 合成」是第三条独立路径。
        addView(actionRow(
            getString(R.string.linux_capture_action),
            getString(R.string.linux_capture_action_sub),
        ) { captureCompositor() })
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
            // ★合并（2026-11-09）：[i] 形式防自杀 —— 本 shell 的 cmdline 里就含
            //   "inputd" 字面量（下面 nohup 那行），裸写 pkill -f inputd 会杀掉
            //   自己这条 sh，导致 sleep / nohup / echo STARTED 全不执行。
            "pkill -f [i]nputd 2>/dev/null; sleep 0.2; " +
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
         * ★两条画面路线并联，按**产物是否随包**自动选（合并 2026-11-09）。
         *
         * ① X11 路线（main 3d782ee6）：Xvfb + 真实程序 + x11frsrv 抓 X 合成结果。
         *    这是让「触摸容器画面」真正有意义的那条 —— 抓到的是用户在操作的
         *    真实程序输出，点得到里面的按钮。**但 x11frsrv 只有 x86_64 产物**，
         *    arm64 上 `X11FRSRV_ASSET == null`（见 LinuxEnvironmentManager），
         *    走这条路只会得到 `nohup: /x11frsrv: not found`。
         *
         * ② framesrv 路线（feat 69a2437b）：自己用 EGL 画内容 + AHB 直写，
         *    两个 ABI 都有产物。它不是「用户在操作的画面」，但至少**有画面**。
         *
         * 选路判据用 `env.x11FrameAvailable()`（等价于 X11FRSRV_ASSET != null），
         * 而不是在 Activity 里再判一次 Build.SUPPORTED_ABIS —— 判据只留一个来源，
         * 免得「资产没随包但这里以为有」这类只在真机暴露的错位。
         */
        val useX11 = env.x11FrameAvailable()
        val cmd = if (useX11) {
            // X11：启动顺序有依赖（实测踩过）—— Xvfb 必须先起来，否则 x11frsrv 报
            // `X11FRSRV_FAIL open display`；X 的 socket 目录得先清，否则上一次残留的
            // abstract socket 会让新实例报 `Cannot establish any listening sockets`
            // （换 display 号没用，得清目录）。
            //
            // ★所有 pkill 模式都写成 [x]yz 形式：pkill -f 匹配整条 cmdline，而这条
            //   shell 自己的 cmdline 里就含 "x11frsrv" / "framesrv" / "Xvfb :99"
            //   字面量，裸写会把自己杀掉 —— 后面起帧服务全不执行且无任何报错。
            buildString {
                append("pkill -f [x]11frsrv 2>/dev/null; pkill -f [f]ramesrv 2>/dev/null; ")
                append("pkill -f '[X]vfb :99' 2>/dev/null; sleep 0.3; ")
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
        } else {
            // framesrv：第 7 个参数是取像素方式，auto = 有 AHardwareBuffer 就走 AHB
            // 直写、否则自动退回 glReadPixels。产线实测（模拟器 x86_64 / 720p）
            // 210.4 fps -> 286.0 fps，1080p 80.8 -> 121.4 fps（1.50 倍），两条路径
            // 落盘像素逐字节一致。容器内 AHB 不可用时它会自己退回 rpix 并打印原因。
            //
            // 必须传 auto 而不是硬编码 ahb：真机 gralloc 与模拟器不同（pitfalls #41），
            // 硬编码一旦不支持就是整个画面功能失败，而 auto 只是慢一点。
            "pkill -f [f]ramesrv 2>/dev/null; " +
                "nohup /framesrv 1280 720 0 16 $shared clear auto >/dev/null 2>&1 & echo STARTED"
        }
        val res = if (useX11) {
            runner.shell(cmd, timeoutMs = 40_000L)
        } else {
            runner.shell(cmd, timeoutMs = 15_000L)
        }
        if (res.ok) {
            getString(R.string.linux_screen_started) + " → " + shared
        } else {
            getString(R.string.linux_screen_start_failed, res.combined())
        }
    }

    /**
     * 抓取容器里 **compositor 的合成画面** 送进 App。
     *
     * 与 [startFrameServer] 的区别：
     *   - framesrv 是「我们自己的 GPU 程序」渲染，只能显示它画的东西
     *   - 这里抓的是 **任何窗口**（sway 下的 Wayland 客户端）被合成后的结果，
     *     走 wlr-screencopy 协议
     *
     * 为什么用 sway 而不是 weston：weston 的 capture 有授权模型，只授予
     * screen-share 拉起的 RDP 客户端，grim 一律 unauthorized（pitfalls #36）。
     * wlroots 的 wlr-screencopy 无授权门槛。
     *
     * 前提：容器里装了 sway + wlshot（apk add sway，再 sh /build_wlshot.sh）。
     * 缺任何一样都只是这条抓帧路径不可用，不影响 framesrv。
     */
    private fun captureCompositor() = runAsync(null) {
        val file = app.dsh.mobile.engine.LinuxFrameSurface.defaultFrameFile(this@LinuxActivity)
        val shared = "/data/data/${packageName}/files/${file.name}"
        // 三种情况分别给出可读反馈，不要笼统报「失败」：
        //   ① 没有 sway        → 提示先装
        //   ② 有 sway 没在跑   → 顺手拉起来
        //   ③ 有 wlshot 才抓
        //
        // 用 --loop 持续抓，而不是单帧快照：App 侧 LinuxFrameSurface 的脏判据
        // 是共享文件头里的 seq，只要 wlshot 持续递增 seq，画面就是活镜像。
        // 起之前先 pkill 上一轮遗留进程，避免两个进程抢同一文件把 seq 搅乱；
        // 起之后轮询等第一帧真落盘（最多 6 秒），否则 UI 会说「已开始」但画面空白。
        //
        // ★pkill 的模式必须写成 [x]yz 形式（本函数里是 [w]lshot / [f]ramesrv）。
        //
        // pkill -f 匹配的是整条 cmdline，而这条 shell 自己的 cmdline 里就含
        // "wlshot .* --loop" 这串字面量 —— 于是 pkill 会把自己杀掉。实测：
        //
        //   $ sh -c 'echo BEFORE; pkill -f "wlshot .* --loop"; echo AFTER'
        //   BEFORE
        //   Terminated            <- AFTER 永远不打印
        //
        // 后果是后面 nohup 起帧服务、等首帧、echo WLSHOT_STARTED 全都不执行：
        // 用户点「抓合成画面」看起来什么也没发生，而且没有任何报错。
        //
        // 正则 [w]lshot 匹配字面 "wlshot"，而字面量本身是 "[w]lshot"，与自己
        // 不匹配，所以不会自杀。实测 AFTER 正常打印。
        val res = runner.shell(
            """
            if ! command -v sway >/dev/null 2>&1; then
              echo NO_SWAY; exit 0
            fi
            if ! pgrep -f 'sway --unsupported-gpu' >/dev/null 2>&1; then
              mkdir -p /tmp/wl; chmod 700 /tmp/wl
              XDG_RUNTIME_DIR=/tmp/wl WLR_BACKENDS=headless WLR_RENDERER=pixman                 WLR_LIBINPUT_NO_DEVICES=1 WLR_HEADLESS_OUTPUTS=1                 nohup sway --unsupported-gpu >/tmp/sway.log 2>&1 &
              sleep 5
            fi
            if [ ! -x /wlsrc/wlshot ]; then echo NO_WLSHOT; exit 0; fi
            # DO NOT just take the first entry of ls. Repeated container starts
            # leave wayland-1/2/3... behind, and some of those sockets outlive
            # their compositor -- picking a dead one means no frame ever lands.
            # Probe each candidate by actually connecting; first success wins.
            CAND=${'$'}(ls -t /tmp/wl/ 2>/dev/null | grep '^wayland-' | grep -v '\.lock$')
            if [ -z "${'$'}CAND" ]; then echo NO_SOCKET; exit 0; fi
            DISP=""
            for c in ${'$'}CAND; do
              if XDG_RUNTIME_DIR=/tmp/wl WAYLAND_DISPLAY=${'$'}c                 timeout 3 /wlsrc/wlshot /tmp/probe.bin 1500 >/dev/null 2>&1; then
                DISP=${'$'}c; break
              fi
            done
            if [ -z "${'$'}DISP" ]; then echo NO_SOCKET; exit 0; fi
            pkill -f '[w]lshot .* --loop' 2>/dev/null
            nohup env XDG_RUNTIME_DIR=/tmp/wl WAYLAND_DISPLAY=${'$'}DISP               /wlsrc/wlshot $shared 5000 --loop 8 >/tmp/wlshot.log 2>&1 &
            i=0
            while [ ${'$'}i -lt 60 ]; do
              if [ -s $shared ]; then echo WLSHOT_STARTED; exit 0; fi
              sleep 0.1; i=${'$'}((i+1))
            done
            echo WLSHOT_NOSTART; head -3 /tmp/wlshot.log 2>/dev/null
            """.trimIndent(),
            timeoutMs = 25_000L,
        )
        val out = res.combined()
        when {
            out.contains("NO_SWAY") -> getString(R.string.linux_capture_no_sway)
            out.contains("NO_WLSHOT") -> getString(R.string.linux_capture_no_wlshot)
            out.contains("NO_SOCKET") -> getString(R.string.linux_capture_no_socket)
            out.contains("WLSHOT_STARTED") -> getString(R.string.linux_capture_ok)
            else -> getString(R.string.linux_capture_failed, out.take(300))
        }
    }

    /** 停掉帧服务（容器内 pkill）。画面停在最后一帧，不黑屏。 */
    private fun stopFrameServer() = runAsync(null) {
        val res = runner.shell(
            // ★合并（2026-11-09）：三路进程都要收 —— main 的 X11 路线（x11frsrv）、
            //   feat 的 framesrv、以及 wlshot 循环。全部写成 [x] 形式防自杀（见
            //   captureCompositor 里 pkill 自匹配的说明）。
            "pkill -f [x]11frsrv; pkill -f [f]ramesrv; " +
                "pkill -f '[w]lshot .* --loop'; echo STOPPED",
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
     * 进之前先问一次 [LinuxEngineLauncher.readiness] —— 与 TerminalActivity 里
     * startLinux 用的是同一个判据，所以两边不会走偏。没就绪就不跳转：
     * 旧版照跳不误，用户落在一个只有空标签页的终端里，看不出是容器没装好。
     */
    private fun openLinuxTerminal() {
        scope.launch {
            // readiness() 有副作用（部署运行器、写配置），只能在 IO 线程调
            val r = withContext(Dispatchers.IO) { runCatching { launcher.readiness() }.getOrNull() }
            if (r !is LinuxEngineLauncher.Readiness.Ready) {
                reportFailure(getString(R.string.linux_workbench_not_ready, readinessReason(r)))
                return@launch
            }
            val i = Intent(this@LinuxActivity, TerminalActivity::class.java)
                .putExtra(TerminalActivity.EXTRA_IN_LINUX, true)
            runCatching { startActivity(i) }.onFailure {
                reportFailure(it.message ?: it.javaClass.simpleName)
            }
        }
    }

    /** 没就绪的原因，原样取引擎层给的说法 */
    private fun readinessReason(r: LinuxEngineLauncher.Readiness?): String = when (r) {
        is LinuxEngineLauncher.Readiness.NeedsPackages -> r.reason
        is LinuxEngineLauncher.Readiness.Unavailable -> r.reason
        is LinuxEngineLauncher.Readiness.NeedsDeploy -> getString(R.string.linux_err_env_not_ready)
        else -> getString(R.string.linux_workbench_value_unknown)
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

    /**
     * 顶部引导卡（v1.5.3）。
     *
     * ## 为什么必须做
     *
     * 重构前这一页只有一句「Alpine 体积小、Ubuntu 用 glibc」，下面并排 7 个选项
     * 全标 `Not installed`。用户的第一困惑是「我现在该点哪个、点完会发生什么」，
     * 界面上一个字都没回答 —— 只能靠试。
     *
     * ## 三态判据（按用户的下一步动作分类，不是按内部状态枚举）
     *
     * - 还没装 rootfs → `setup`：告诉他选一个装，并说明哪个最快
     * - rootfs 在但缺前置产物 → `packages`：直接给「补齐依赖」按钮
     * - 都齐了 → `ready`：告诉他能干什么、AI 怎么才能用
     *
     * ## 可收起
     *
     * 熟手不需要反复看引导。收起的开关存 prefs（`linux_guide_hidden`），
     * 收起后留一行「展开引导」的小入口，不会再也找不回来。
     */
    private fun refreshGuide() {
        guideCard.removeAllViews()
        if (!guideCard.isAttachedToWindow && guideCard.childCount == 0) {
            // 首次装配；下面照常渲染
        }
        val prefs = getSharedPreferences("dsh_ui", MODE_PRIVATE)
        val hidden = prefs.getBoolean(KEY_GUIDE_HIDDEN, false)
        val st = env.status()
        val active = LinuxRootfsCatalog.active(this)
        val deployed = env.isDeployed(active)
        val extrasReady = env.flavorPackagesReady(active)

        // 缺哪个前置产物：与 readiness() 同一套静态判据（不起容器进程）
        val missingDep = when (active.family) {
            LinuxRootfsCatalog.Family.ALPINE,
            LinuxRootfsCatalog.Family.ALPINE_EDGE ->
                listOf("bin/bash" to R.string.linux_err_need_bash).firstOrNull {
                    !File(env.rootfsDir(active), it.first).isFile
                }
            else -> null
        }

        if (hidden) {
            guideCard.addView(TextView(this).apply {
                text = "▸ " + getString(R.string.linux_guide_show)
                textSize = 12f
                setTextColor(Accent.current(this@LinuxActivity).argb)
                setPadding(dp(4), dp(6), 0, dp(6))
                isClickable = true
                setOnClickListener {
                    prefs.edit().putBoolean(KEY_GUIDE_HIDDEN, false).apply()
                    refreshGuide()
                }
            })
            return
        }

        val title: Int
        val body: CharSequence
        when {
            st.state == LinuxEnvironmentManager.State.READY && extrasReady && missingDep == null -> {
                title = R.string.linux_guide_title_ready
                body = getString(R.string.linux_guide_body_ready)
            }
            deployed && missingDep != null -> {
                title = R.string.linux_guide_title_packages
                body = getString(
                    R.string.linux_guide_body_packages,
                    getString(missingDep.second),
                )
            }
            else -> {
                title = R.string.linux_guide_title_setup
                body = getString(R.string.linux_guide_body_setup)
            }
        }

        guideCard.addView(card().apply {
            addView(LinearLayout(this@LinuxActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(this@LinuxActivity).apply {
                    text = getString(title)
                    textSize = 15f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(getColor(R.color.text_primary))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(TextView(this@LinuxActivity).apply {
                    text = getString(R.string.linux_guide_hide)
                    textSize = 12f
                    setTextColor(getColor(R.color.text_secondary))
                    setPadding(dp(8), dp(4), 0, dp(4))
                    isClickable = true
                    setOnClickListener {
                        prefs.edit().putBoolean(KEY_GUIDE_HIDDEN, true).apply()
                        refreshGuide()
                    }
                })
            })
            addView(TextView(this@LinuxActivity).apply {
                text = body
                textSize = 12.5f
                setTextColor(getColor(R.color.text_secondary))
                setPadding(0, dp(6), 0, 0)
                setLineSpacing(dp(3).toFloat(), 1f)
            })
            // 缺依赖时直接在卡里给动作，别让用户去别处找
            if (deployed && missingDep != null) {
                addView(actionRow(
                    getString(R.string.linux_next_install_pkgs),
                    getString(R.string.linux_next_install_pkgs_sub),
                ) { installBootstrapPackages() })
            }
            // 就绪态给「下一步能干什么」—— 装完就断在这里是原设计的最大断点：
            // 用户不知道还能开画面、还能让 AI 调用
            if (st.state == LinuxEnvironmentManager.State.READY && missingDep == null) {
                addView(TextView(this@LinuxActivity).apply {
                    text = getString(R.string.linux_next_steps)
                    textSize = 12f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(getColor(R.color.text_primary))
                    setPadding(0, dp(10), 0, dp(2))
                })
                addView(actionRow(
                    getString(R.string.linux_next_open_terminal),
                    getString(R.string.linux_next_open_terminal_sub),
                ) {
                    appendOutput(getString(R.string.linux_console_hint))
                    cmdInput.requestFocus()
                })
            }
        })
    }

    /**
     * 补齐 bootstrap 依赖（bash / curl / ca-certificates）。
     *
     * 复用 `LinuxEnvironmentManager.ensureBootstrapPackages` 的包清单；
     * `force` 语义不同：那里 force 会 purge 整个 rootfs，这里只补包，
     * 所以走 pkg.install 直接装。
     */
    private fun installBootstrapPackages() {
        runAsync(getString(R.string.linux_next_install_pkgs)) {
            val r = withContext(Dispatchers.IO) {
                pkg.install(LinuxEnvironmentManager.BOOTSTRAP_PACKAGES)
            }
            // OpResult 只有 ok / packages / rawOutput —— 失败时把原始输出带出去，
            // 不能只回一句「失败」（项目纪律：原始报错必须露出）
            if (r.ok) getString(R.string.linux_pkgs_ok) else "✗ ${r.rawOutput}"
        }
    }

    /**
     * 统一的状态刷新入口。
     *
     * 一次 IO 快照 → 纯函数归约 → 主线程渲染。代次号 [factsSeq] 保证切镜像后
     * 旧请求回包不会覆盖新状态（切镜像与刷新可以并发）。
     *
     * ⚠️ launcher.readiness() 有副作用（部署 runner、写配置、prune、SELinux 自愈），
     * 只能在 IO 线程按需调，绝不在 render 或展开操作里调。
     */
    private fun refreshStatus() {
        // 有操作在跑时不重读磁盘：loadWorkbenchFacts 会调 launcher.readiness()（有副作用）并全量遍历目录，
        // 与正在解包 / 删除的线程抢同一棵目录树，读到的也只是半成品。
        // 这时只把忙碌态叠到上一份事实上重画；操作结束后 runAsync 会做一次完整刷新。
        if (busyKind != null) {
            renderBusyOverlay()
            return
        }
        val option = LinuxRootfsCatalog.active(this)
        val seq = ++factsSeq
        scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching { loadWorkbenchFacts(option) }.getOrElse { e ->
                    WorkbenchFacts(
                        optionKey = optionKey(option),
                        error = e.message ?: e.javaClass.simpleName,
                    )
                }
            }
            if (seq != factsSeq) return@launch
            lastFacts = loaded
            // 上一个操作成功后停在 100% 的读数，在新状态画出来的这一刻收掉
            if (busyKind == null && busySettling) {
                busySettling = false
                wb.clearBusyProgress()
            }
            // 读盘期间用户可能已经点了某个操作：把忙碌态叠上去，否则这次渲染会把横幅上的进度抹掉
            val facts = loaded.copy(busyKind = busyKind, busyDetail = busyDetail)
            val state = reduceWorkbench(facts)
            lastState = state
            wb.render(state, facts)
            refreshImages(option)
            when {
                state.phase == Phase.SETUP -> {
                    wb.renderToolsEmpty()
                    updateActionHints(option, emptySet())
                }
                // 忙碌时不查已装工具：查询要起容器，会和正在进行的操作撞在一起
                busyKind != null -> Unit
                else -> {
                    val installed = withContext(Dispatchers.IO) {
                        runCatching { pkg.installed() }.getOrDefault(emptyMap())
                    }
                    wb.renderTools(installed.keys.sorted(), installed.size)
                    updateActionHints(option, installed.keys)
                }
            }
            refreshModeRow()
        }
    }

    /** 把当前的忙碌态叠到上一份事实上重画，不读盘 */
    private fun renderBusyOverlay() {
        val facts = (lastFacts ?: return).copy(busyKind = busyKind, busyDetail = busyDetail)
        val state = reduceWorkbench(facts)
        lastState = state
        wb.render(state, facts)
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
        rowProgress.clear()
        val active = LinuxRootfsCatalog.active(this)
        distroCard.addView(card().apply {
            addView(TextView(this@LinuxActivity).apply {
                text = getString(R.string.linux_distro_sub)
                textSize = 12f
                setTextColor(getColor(R.color.text_secondary))
                setPadding(0, 0, 0, dp(2))
            })

            // ★v1.5.3 分组：原来 7 个选项平铺，用户看不出该点哪个。
            //   现在按「基础 / 更多形态 / 其他发行版」三段，首屏只展开基础组。
            //
            // ★用 catalog（带 flavor 展开）而不是 options：
            //   options 里 Alpine 只有一条，catalog 会展开成
            //   「Alpine 3.22 / Alpine 3.22 Dev / Alpine 3.22 GUI」三行 ——
            //   三者共享同一个 rootfs 目录，只是首启额外装的包不同。
            val groups = LinuxRootfsCatalog.catalog.groupBy { g ->
                when {
                    g.flavor == LinuxRootfsCatalog.Flavor.BASE &&
                        g.pkgManager == LinuxRootfsCatalog.PackageManager.APK -> Group.BASE
                    g.pkgManager == LinuxRootfsCatalog.PackageManager.APK -> Group.MORE
                    else -> Group.OTHER
                }
            }

            fun addRow(opt: LinuxRootfsCatalog.Option) {
                val deployed = env.isDeployed(opt)
                val extrasReady = env.flavorPackagesReady(opt)
                val isActive = opt.family == active.family && opt.flavor == active.flavor
                val usable = LinuxRootfsCatalog.availableOnThisAbi(opt.flavor)
                addView(distroRow(opt, deployed, isActive, extrasReady, usable))
            }

            // 基础组也带标题：三组都有标题，用户才知道「下面还有别的形态」，
            // 否则首屏只有一个无标题列表，看不出折叠区存在。
            addView(groupHeader(
                getString(R.string.linux_group_base),
                getString(R.string.linux_group_base_sub),
                true,
                null,
            ))
            groups[Group.BASE]?.forEach { addRow(it) }

            // 更多形态（Dev / GUI）：折叠
            groups[Group.MORE]?.let { list ->
                addView(groupHeader(
                    getString(R.string.linux_group_more),
                    getString(R.string.linux_group_more_sub),
                    moreExpanded,
                ) { moreExpanded = !moreExpanded; refreshDistroCard() })
                if (moreExpanded) list.forEach { addRow(it) }
            }

            // 其他发行版（Ubuntu 等非 apk 系）：折叠
            groups[Group.OTHER]?.let { list ->
                addView(groupHeader(
                    getString(R.string.linux_group_other),
                    getString(R.string.linux_group_other_sub),
                    otherExpanded,
                ) { otherExpanded = !otherExpanded; refreshDistroCard() })
                if (otherExpanded) list.forEach { addRow(it) }
            }

            addView(TextView(this@LinuxActivity).apply {
                text = getString(R.string.linux_distro_engine_note)
                textSize = 11f
                setTextColor(getColor(R.color.text_secondary))
                setPadding(0, dp(10), 0, 0)
            })
        })
    }

    /** 分组归属（只在 UI 层用，不进 catalog 的数据模型） */
    private enum class Group { BASE, MORE, OTHER }

    /**
     * 可折叠的分组标题行。
     *
     * 为什么折叠：7 个选项平铺时，一屏放不下且全都标 `Not installed`，
     * 用户根本不知道差别在哪。折起来后首屏只留「基础环境」一条，
     * 想看别的点一下展开 —— 选择负担从「读 7 条」降到「读 1 条」。
     */
    private fun groupHeader(
        title: String,
        sub: String,
        expanded: Boolean,
        onToggle: (() -> Unit)?,
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(4), dp(12), dp(4), dp(6))
        // 基础组是常展开的，没有折叠动作 —— 此时不设点击，避免点了没反应
        if (onToggle != null) {
            isClickable = true
            isFocusable = true
            setOnClickListener { onToggle() }
        }
        addView(TextView(this@LinuxActivity).apply {
            text = if (onToggle == null) title else (if (expanded) "▾ " else "▸ ") + title
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Accent.current(this@LinuxActivity).argb)
        })
        addView(TextView(this@LinuxActivity).apply {
            text = sub
            textSize = 11.5f
            setTextColor(getColor(R.color.text_secondary))
            setPadding(0, dp(2), 0, 0)
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
        // ★v1.5.3 改成纵向：上面是原横向内容，下面挂用途说明 + 行内进度条。
        //   原来是纯横向行 —— 加不进第二条（进度条），只能整行重排。
        orientation = LinearLayout.VERTICAL
        setPadding(dp(4), dp(10), dp(4), dp(10))
        isClickable = true
        isFocusable = true
        val tv = android.util.TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
        if (tv.resourceId != 0) setBackgroundResource(tv.resourceId)

        val mainRow = LinearLayout(this@LinuxActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        addView(mainRow)

        mainRow.addView(LinearLayout(this@LinuxActivity).apply {
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
            // ★「装完能干什么」—— 三个形态共享同一份 rootfs，体积行几乎一样，
            //   不写这句用户分不出 Dev 和 GUI 的区别，只能靠猜。
            addView(TextView(this@LinuxActivity).apply {
                text = getString(opt.flavor.useCaseRes)
                textSize = 11.5f
                setTextColor(Accent.current(this@LinuxActivity).argb)
                setPadding(0, dp(3), 0, 0)
            })
        })

        mainRow.addView(TextView(this@LinuxActivity).apply {
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

        // 行内进度条：与扩展中心同一形态（`ExtensionStoreActivity` 的 refs.progress）。
        // 默认 GONE，`switchDistro` 开始时显示、结束隐藏 —— 用户能看出「是哪一行在装」。
        val bar = ProgressBar(this@LinuxActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
            progressTintList = android.content.res.ColorStateList.valueOf(
                Accent.current(this@LinuxActivity).argb
            )
            visibility = View.GONE
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(6)
            ).apply { topMargin = dp(6) }
        }
        addView(bar)
        rowProgress[opt.family.id + ":" + opt.flavor.id] = bar

        /*
         * 不可用的形态：**可点**，但点了只给出解释，不真的去切。
         *
         * ⚠️ 原写法是 `isClickable = false` 又紧跟 `setOnClickListener` —— 二者矛盾：
         * Android 的 `View.setOnClickListener` 内部会把 view 置为 clickable，
         * 所以实际是「可点」，代码却写着「不可点」。行为虽符合意图，但读代码的人
         * 会以为点不动，后续维护极易改错。
         *
         * 这里显式写清楚：要可点（才能告诉用户为什么不行），保留焦点的可达性
         * （无障碍用户也需要能聚焦到这一行才知道它不可用）。
         */
        if (!usable) {
            isClickable = true
            isFocusable = true
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
        // ★v1.5.3：把 flavor 的额外体积算进去。
        //   之前三个形态（base/Dev/GUI）体积行**完全一样**都是「3.5 MB / 8.1 MB」，
        //   而 Dev 实际还要多下 48MB、多占 250MB —— 用户在点之前看不到任何差异，
        //   点完才发现要下几十兆。这里把 rootfs 与额外包合起来报总数。
        val extraDl = opt.flavor.extraDownloadBytes
        val extraEx = opt.flavor.extraExtractBytes
        return getString(
            R.string.linux_cost_line,
            mb(compressed + extraDl),
            mb(extracted + extraEx),
        )
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
        // 有操作在跑时连「只改指向」也不放行：装包 / 部署都是对「当前镜像」做的，
        // 中途把指向换掉，后半程就落到另一个镜像上了。
        if (busyKind != null) {
            toast(getString(R.string.linux_workbench_busy_blocked))
            return
        }
        val needBytes = when (val src = opt.source) {
            is LinuxRootfsCatalog.RootfsSource.Bundled -> src.extractedBytes
            is LinuxRootfsCatalog.RootfsSource.Remote -> src.extractedBytes
        }
        // free<=0 表示探测不到，不是真的没空间 —— 不拦，让部署报真实原因
        val free = filesDir.usableSpace
        val deployed = env.isDeployed(opt)
        if (!deployed && free > 0 && free < needBytes) {
            reportFailure(getString(R.string.linux_err_disk_space, mb(needBytes), mb(free)))
            return
        }
        runAsync(
            if (deployed) null else getString(R.string.linux_msg_downloading_rootfs),
            // 已装的镜像只是改指向，瞬间完成，不占忙碌态；没装的要下载解包，按部署算
            kind = if (deployed) null else BusyKind.DEPLOY,
            detail = opt.displayName + " " + opt.versionLabel,
            cardKey = if (deployed) null else optionKey(opt),
            plan = if (deployed) null else deployPlan(opt),
        ) {
            // v1.5.3：让「是哪一行在装」可见。refreshDistroCard 重建会清 map，
            // 所以这里取不到时静默跳过（顶部的 busyBar 仍然在转，不会毫无反馈）。
            val bar = rowProgress[opt.family.id + ":" + opt.flavor.id]
            withContext(Dispatchers.Main) { bar?.visibility = View.VISIBLE }
            try {
                val fresh = !env.isDeployed(opt)
                if (fresh) {
                    // 先按「基础形态」部署，形态要用的额外软件等指向换过去之后再装（见 completeBootstrap）。
                    // 环境层在部署末尾会顺手装一次软件，但那次安装进的是「当前指向的镜像」，不是正在部署的这个：
                    // 直接按 Dev / GUI 形态部署，几百 MB 的额外软件会装进用户正在用的那个镜像里。
                    val st = env.ensureDeployed(baseOption(opt), force = false)
                    if (st.state != LinuxEnvironmentManager.State.READY) {
                        return@runAsync "✗ ${st.detail}"
                    }
                }
                // ★必须同时记 flavor，且必须**原子**写：
                //   只记 family 的话 active() 读回来还是 BASE，GUI 额外包永远不装；
                //   两次独立 apply 之间被杀则会留下 family/flavor 不一致。
                //   （API 的 /dev/linux/switch 走同一个 setActiveExact，两条路径一致）
                LinuxRootfsCatalog.setActiveExact(this@LinuxActivity, opt.family, opt.flavor)
                if (fresh) {
                    completeBootstrap(opt)?.let { return@runAsync "✗ $it" }
                }
                "✓ " + getString(
                    R.string.linux_msg_switched,
                    "${opt.displayName} ${opt.versionLabel}" +
                        if (opt.flavor == LinuxRootfsCatalog.Flavor.BASE) ""
                        else " · ${opt.flavor.label}"
                )
            } finally {
                // 成功失败都要收 —— 失败时留着一条转圈的进度条，会让人以为还在跑
                withContext(Dispatchers.Main) { bar?.visibility = View.GONE }
            }
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
        val opt = LinuxRootfsCatalog.active(this)
        runAsync(
            if (force) getString(R.string.linux_msg_redeploying) else getString(R.string.linux_msg_deploying),
            kind = BusyKind.DEPLOY,
            detail = opt.displayName + " " + opt.versionLabel,
            cardKey = optionKey(opt),
            plan = deployPlan(opt),
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
                    // ⚠️ 不能读 st.fileCount / st.rootfsBytes：status() 的 READY 分支
                    // 刻意留空（那是两次全量目录遍历，冷启时会白等 300~500ms，
                    // 见 LinuxEnvironmentManager.status 的注释）。数字在这里单独取，
                    // 只在用户主动点「部署」时付这个代价 —— 否则永远显示 0。
                    "✓ ${getString(R.string.linux_msg_deploy_ok)}\n" +
                        "rootfs: ${st.rootfsVersion}\n" +
                        "files: ${env.fileCount()}, ${env.diskUsage() / 1048576} MB"
                }
            } else {
                "✗ ${st.detail}"
            }
        }
    }

    /**
     * 下载中的临时文件与总字节数，用来在横幅上显示下载百分比。
     *
     * 路径与 LinuxEnvironmentManager 下载远程 rootfs 时的落点一致
     * （cache/linux-rootfs/<family>-rootfs.<ext>，下载中多一个 .part 后缀）。
     * 环境层没有把下载进度回调透出来，这里只能看文件长到多大了；
     * 路径万一对不上，后果只是不显示百分比，不影响部署本身。
     */
    private fun downloadProbe(opt: LinuxRootfsCatalog.Option): Pair<File, Long>? {
        val src = opt.source as? LinuxRootfsCatalog.RootfsSource.Remote ?: return null
        val ext = when (src.format) {
            LinuxRootfsCatalog.ArchiveFormat.TAR_GZ -> "tar.gz"
            LinuxRootfsCatalog.ArchiveFormat.TAR_XZ -> "tar.xz"
        }
        val part = File(File(cacheDir, "linux-rootfs"), opt.family.id + "-rootfs." + ext + ".part")
        return part to src.compressedBytes
    }

    private fun installPackages(packages: List<String>, expected: Footprint? = null) {
        val opt = LinuxRootfsCatalog.active(this)
        val names = packages.joinToString(" ")
        runAsync(
            getString(R.string.linux_msg_installing, names),
            kind = BusyKind.INSTALL,
            detail = names,
            cardKey = optionKey(opt),
            plan = installPlan(opt, expected),
        ) {
            if (!runner.ensureReady()) {
                return@runAsync "✗ ${getString(R.string.linux_err_env_not_ready)}"
            }
            // ★glibc 发行版的 node 不走包管理器（v1.7.13）。
            //
            // Ubuntu 的 apt 源里只有 node 18.x，而引擎 `engines.node` 要求 >= 24.9
            // （[LinuxRootfsCatalog.nodeTarball] 的注释记了 22.14.0 静默退出的实测）。
            // 所以「装 node」这个动作在 glibc 系上必须换成官方预编译包；
            // 走 apt 只会装上一个跑不起来、且 readiness() 依然判缺依赖的版本。
            if (packages.contains("nodejs") &&
                opt.nodeStrategy != LinuxRootfsCatalog.NodeStrategy.APK_PACKAGE
            ) {
                noteBusyLine(getString(R.string.linux_pkg_installing_node_official))
                val okNode = runCatching { env.installNodeTarball(opt) }.getOrDefault(false)
                return@runAsync if (okNode) {
                    "✓ ${getString(R.string.linux_pkg_installing_node_official)}"
                } else {
                    "✗ ${getString(R.string.linux_err_node_tarball_failed)}"
                }
            }
            val idx = refreshIndexTracked()
            if (!idx.ok) {
                return@runAsync "✗ index update failed:\n${idx.rawOutput}\n${installDiagnosis()}"
            }
            val r = pkg.install(packages, onLine = ::noteBusyLine)
            // 状态刷新由 runAsync 在回到主线程后统一做（见 runAsync 的
            // appendOutput 之后那句 refreshStatus）—— 这里在 IO 线程里动 View
            // 会崩，所以不要再调用一次。
            // 命令名跟着发行版走：Ubuntu 上写 apk add 会把人引去查一个根本不存在的工具
            if (r.ok) {
                "✓ ${opt.installCommand} $names\n${r.rawOutput.takeLast(1500)}"
            } else {
                "✗ ${opt.installCommand} failed:\n${r.rawOutput.takeLast(2000)}\n${installDiagnosis()}"
            }
        }
    }

    /**
     * 引导第 3 步「补齐依赖」：当前镜像缺什么补什么。
     *
     * 为什么不能复用 deploy(force = false)：环境已经解包好时 ensureDeployed 直接返回，
     * 不会再装任何包 —— 旧版的「开始」就是这么点了没反应的。
     *
     * 顺序：
     * 1. 基础包 + 当前形态的额外包，走 LinuxPackageManager（有逐行输出，横幅上能看到进度）；
     * 2. 让环境层自己落「已装好」标记（见 [dropAttemptedMarker]）；
     * 3. 还缺 node 的话补上。
     */
    private fun fixDependencies() {
        val opt = LinuxRootfsCatalog.active(this)
        val base = LinuxEnvironmentManager.BOOTSTRAP_PACKAGES + opt.flavor.extraPackages
        // 缺 node 的话并进同一次安装：分两次装，包管理器的「第几个 / 共几个」会从头再数一遍，
        // 进度条走到头又退回去。
        val needNode = opt.nodeStrategy == LinuxRootfsCatalog.NodeStrategy.APK_PACKAGE && !nodePresent(opt)
        val wanted = if (needNode) base + "nodejs" else base
        val names = wanted.joinToString(" ")
        val boot = footprint(opt, PackageGroup.BOOTSTRAP)
        val node = if (needNode) footprint(opt, PackageGroup.NODE) else null
        val expected = if (boot == null || (needNode && node == null)) {
            null
        } else {
            Footprint(
                // 形态的额外软件没有逐包量过：包数记 0，进度只按体积推算
                count = if (opt.flavor.extraPackages.isEmpty()) boot.count + (node?.count ?: 0) else 0,
                download = boot.download + (node?.download ?: 0L) + opt.flavor.extraDownloadBytes,
                installed = boot.installed + (node?.installed ?: 0L) + opt.flavor.extraExtractBytes,
            )
        }
        runAsync(
            getString(R.string.linux_msg_installing, names),
            kind = BusyKind.INSTALL,
            detail = names,
            cardKey = optionKey(opt),
            plan = installPlan(opt, expected),
        ) {
            if (!runner.ensureReady()) {
                return@runAsync "✗ ${getString(R.string.linux_err_env_not_ready)}"
            }
            val idx = refreshIndexTracked()
            if (!idx.ok) {
                return@runAsync "✗ index update failed:\n${idx.rawOutput}\n${installDiagnosis()}"
            }
            val r = pkg.install(wanted, onLine = ::noteBusyLine)
            if (!r.ok) {
                return@runAsync "✗ ${opt.installCommand} failed:\n" +
                    "${r.rawOutput.takeLast(2000)}\n${installDiagnosis()}"
            }
            dropAttemptedMarker(opt)
            runCatching { env.ensureBootstrapPackages(opt) }
            // 环境层只认包管理器的退出码；而 apk 在触发器失败时会以非 0 退出、包其实已经装上
            // （LinuxPackageManager.install 为此专门做了「逐个核对是否已装」的兜底）。
            // 上面那次安装已经核对通过，这里它若又落回失败标记，页面就永远停在「缺依赖」——
            // 所以补写成功标记，内容与环境层写的一致（逗号分隔的包名）。
            //
            // ★但补写必须以**探针文件真的在**为前提（v1.7.14 真机）：曾经无条件补写，
            //   于是「dpkg 报成功、curl 没解出来」这种假绿也会留下永久成功标记，
            //   页面显示已就绪而容器里没有 curl，且永不重试。判据要与界面层同源。
            if (!env.flavorPackagesReady(opt) &&
                bootstrapFilesPresent(opt) &&
                (opt.nodeStrategy == LinuxRootfsCatalog.NodeStrategy.APK_PACKAGE || nodePresent(opt))
            ) {
                runCatching { bootstrapMarker(opt).writeText(base.joinToString(",")) }
                    .onFailure { android.util.Log.w("LinuxActivity", "marker write failed: " + it.message) }
            }

            var after = runCatching { launcher.readiness() }.getOrNull()
            if (after is LinuxEngineLauncher.Readiness.NeedsPackages &&
                opt.nodeStrategy != LinuxRootfsCatalog.NodeStrategy.APK_PACKAGE
            ) {
                // 这个发行版的 node 不在包管理器源里（apt 只有 18.x，引擎要求 >= 24.9），
                // 必须走官方预编译 tarball（v1.7.13 补齐 —— 此前这里是直接报错返回，
                // 于是「补齐依赖」在 Ubuntu 上永远失败，用户只能反复删了重装）。
                noteBusyLine(getString(R.string.linux_pkg_installing_node_official))
                val okNode = runCatching { env.installNodeTarball(opt) }.getOrDefault(false)
                if (!okNode) {
                    return@runAsync "✗ ${getString(R.string.linux_err_node_tarball_failed)}"
                }
                after = runCatching { launcher.readiness() }.getOrNull()
            }
            if (after is LinuxEngineLauncher.Readiness.NeedsPackages) {
                val n = pkg.install(listOf("nodejs"), onLine = ::noteBusyLine)
                if (!n.ok) {
                    return@runAsync "✗ ${opt.installCommand} nodejs failed:\n" +
                        "${n.rawOutput.takeLast(2000)}\n${installDiagnosis()}"
                }
            }
            "✓ ${opt.installCommand} $names\n${r.rawOutput.takeLast(1500)}"
        }
    }

    /**
     * 删掉「装过但失败」的标记，让环境层重新认定这个形态。
     *
     * 环境层装失败时会落一个 attempted: 开头的标记，之后只有在 bash / curl / 证书缺失时才重试。
     * 于是 Dev / GUI 形态的额外包一旦装失败过，而基础包又都在，它就永远不再重试 ——
     * 包明明已经补上了，页面却一直停在「缺依赖」。
     *
     * 这里只删失败标记。成功标记优先让 ensureBootstrapPackages 自己写，它没写成时才由 fixDependencies 补。
     */
    private fun dropAttemptedMarker(opt: LinuxRootfsCatalog.Option) {
        val mark = bootstrapMarker(opt)
        val attempted = runCatching { mark.readText().trim().startsWith("attempted:") }.getOrDefault(false)
        if (attempted) mark.delete()
    }

    /**
     * 某个形态的「依赖装好了没有」标记文件。
     *
     * 文件名（.dsh-bootstrap[.<flavor>]）与 LinuxEnvironmentManager.flavorPackagesReady 的读法一致；
     * 环境层没有把这个路径公开出来，所以这里只留这一处拼法。
     */
    private fun bootstrapMarker(opt: LinuxRootfsCatalog.Option): File =
        File(
            env.rootfsDir(opt),
            ".dsh-bootstrap" + if (opt.flavor == LinuxRootfsCatalog.Flavor.BASE) "" else "." + opt.flavor.id,
        )

    /**
     * 包管理器的输出回调（IO 线程）。
     *
     * 运行器给的是「读到多少算多少」的字节块，不是切好的行，先拼回成行再认。
     * 认出「(5/14) Installing nodejs」这种计数行就记下来 —— 它是装包进度最可靠的来源。
     */
    private fun noteBusyLine(chunk: String) {
        for (line in busyLines.feed(chunk)) {
            val t = line.trim()
            if (t.isEmpty()) continue
            busyLine = t
            parseApkStep(t)?.let {
                busyStep = it
                busyIndexing = false
            }
        }
    }

    /** 更新软件源索引，期间横幅写「更新软件源索引」而不是「安装」：这几秒还没有任何包开始装 */
    private fun refreshIndexTracked(): LinuxPackageManager.OpResult {
        busyIndexing = true
        return try {
            pkg.refreshIndex(onLine = ::noteBusyLine)
        } finally {
            busyIndexing = false
        }
    }

    /**
     * 装包失败时的自诊断附注。
     *
     * ★为什么要有：`apk add` 在无 root 场景失败时，最常见原因是 DNS 不通
     * （musl 把查询发往 53，而 App uid 绑不上 53，必须靠 loader 改写 +
     * App 内的 `LinuxDnsProxy`），但 apk 只会说
     *     WARNING: ... temporary error (try again later)
     *     ERROR: unable to select packages: nodejs (no such package)
     * 两句话把人引向「镜像站挂了」或「Alpine 没这个包」，实测两个方向都是错的
     * （root 域直接 `apk add nodejs` → `OK: 76 MiB`，RC=0）。
     *
     * 这里把决定性事实一次性打出来：代理实际端口、resolv.conf 内容、代理自己
     * 的诊断日志尾。用户复现一次就能定位到是哪一层断的。
     *
     * ⚠️ 文案必须走资源：i18n 门禁禁止 Kotlin 字面量含 CJK（check-i18n.py 的 D 段）。
     */
    private fun installDiagnosis(): String = buildString {
        val unreadable = getString(R.string.linux_diag_unreadable)
        append("\n").append(getString(R.string.linux_diag_title))
        append("\n").append(getString(R.string.linux_diag_proxy_port))
        append(" ").append(LinuxDnsProxy.currentPort())
        append("\nresolv.conf: ")
        append(
            runCatching {
                File(env.rootfsDir(), "etc/resolv.conf").readText().trim()
                    .replace("\n", " | ")
            }.getOrDefault(unreadable)
        )
        append("\n").append(getString(R.string.linux_diag_dns_log_tail))
        append("\n")
        append(
            runCatching {
                val f = File(filesDir, "engine/dns.log")
                if (!f.isFile) getString(R.string.linux_diag_no_dns_log)
                else f.readLines().takeLast(8).joinToString("\n")
            }.getOrDefault(unreadable)
        )
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
     *
     * `kind` 非空表示这是一次「会改动容器环境」的操作（部署 / 装包 / 删除）：
     * - 同一时刻只放行一个 —— 两个解包同时跑、或一边装包一边删目录，结果都是一棵半成品的 rootfs；
     * - 期间横幅显示进度（忙碌态叠进事实再归约，见 [renderBusyOverlay]）；
     * - 失败时把报错放进横幅下面的失败卡。输出区在环境未就绪时整块隐藏，
     *   而部署 / 装包的失败恰恰都发生在未就绪时，只写输出区等于没报。
     */
    private fun runAsync(
        banner: String?,
        kind: BusyKind? = null,
        detail: String? = null,
        cardKey: String? = null,
        plan: BusyPlan? = null,
        block: suspend () -> String,
    ) {
        if (kind != null) {
            if (busyKind != null) {
                toast(getString(R.string.linux_workbench_busy_blocked))
                return
            }
            busyKind = kind
            busyDetail = detail
            busyStart = SystemClock.elapsedRealtime()
            busyLine = ""
            busyStep = null
            busyIndexing = false
            busyLines = LineAssembler()
            busyPlan = plan
            busyMeter = plan?.let { ProgressMeter(it.stages) }
            busyCardKey = cardKey
            busyLastText = null
            busySettling = false
            wb.clearFailure()
            cardKey?.let { rowProgress[it] }?.visibility = View.VISIBLE
            renderBusyOverlay()
            startBusyTicker()
            // 进度都在页面顶部的横幅上，而发起操作的按钮（引导卡的「开始」、操作格）多半在下半屏：
            // 不把页面带回去的话，整段安装期间只看得到按钮变暗，百分比和剩余时间都在屏幕外。
            scrollView.post { scrollView.smoothScrollTo(0, 0) }
        }
        busyBar.visibility = View.VISIBLE
        if (banner != null) appendOutput(banner)
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching { block() }.getOrElse { e ->
                    // 纪律：原始报错必须露出，不吞异常
                    "✗ ${e::class.java.simpleName}: ${e.message}"
                }
            }
            if (kind != null) {
                busyKind = null
                busyDetail = null
                busyPlan = null
                busyMeter = null
                busyCardKey = null
                busyTicker?.cancel()
                busyTicker = null
                if (text.startsWith("✗")) {
                    // 失败：读数立刻收掉，页面退回操作之前的样子，原因写在失败卡里
                    wb.clearBusyProgress()
                    cardKey?.let { rowProgress[it] }?.visibility = View.GONE
                    renderBusyOverlay()
                    wb.showFailure(text.removePrefix("✗").trim())
                    // 失败卡在页面顶部的横幅下面，而发起操作的按钮多半在页面靠下的位置
                    scrollView.post { scrollView.smoothScrollTo(0, 0) }
                } else {
                    // 成功：读数停在 100%，由下面那次完整刷新在画出新状态时收掉（见 busySettling）
                    busySettling = true
                    wb.setBusyProgress(busyLastText, 1f, null, cardKey)
                    toast(text.substringBefore('\n'))
                }
            }
            // 不带 kind 的小操作（终端命令、画面服务）结束时，如果还有大操作在跑，忙碌条不能跟着收
            if (busyKind == null) busyBar.visibility = View.GONE
            appendOutput(text)
            refreshStatus()
        }
    }

    /**
     * 忙碌期间每半秒取一次样、刷一次横幅：到了哪个阶段、完成了百分之多少、大约还要多久。
     *
     * 取样要读文件长度、遍历目录，放在 IO 线程；读数拿回主线程再显示。
     */
    private fun startBusyTicker() {
        busyTicker?.cancel()
        busyTicker = scope.launch {
            while (busyKind != null) {
                val plan = busyPlan
                val sample = if (plan == null) {
                    null
                } else {
                    withContext(Dispatchers.IO) { runCatching { plan.sample() }.getOrNull() }
                }
                // 取样期间操作可能已经结束，或者已经换成了下一个操作：这份读数作废
                if (busyKind == null || busyPlan !== plan) break
                renderBusy(sample)
                delay(BUSY_TICK_MS)
            }
        }
    }

    private fun renderBusy(sample: BusySample?) {
        val now = SystemClock.elapsedRealtime()
        val sec = (now - busyStart) / 1000
        val clock = String.format(java.util.Locale.US, "%d:%02d", sec / 60, sec % 60)
        if (sample == null) {
            // 这一次没取到样：至少让已用时在走，看得出没卡死
            wb.setBusyProgress(null, null, clock, busyCardKey)
            return
        }
        busyLastText = sample.text
        val reading = busyMeter?.update(now, sample.stage, sample.within) ?: BusyReading(null, null)
        // 估得出剩余时间就显示剩余时间，估不出（刚开始、进度停住）就显示已用时
        val tail = reading.etaSeconds?.let { etaText(it) } ?: clock
        wb.setBusyProgress(sample.text, reading.fraction, tail, busyCardKey)
    }

    /** 一分钟以内按 5 秒取整，否则每半秒跳一个数，读起来比不显示还累 */
    private fun etaText(seconds: Long): String =
        if (seconds < 60) {
            getString(R.string.linux_workbench_eta_seconds, (((seconds + 4) / 5) * 5).coerceAtLeast(5).toInt())
        } else {
            getString(R.string.linux_workbench_eta_minutes, ((seconds + 59) / 60).toInt())
        }

    /** 一次取样的结果：到了哪个阶段、阶段内完成了多少（量不出为 null）、横幅第二行写什么 */
    private class BusySample(val stage: BusyStage, val within: Float?, val text: String)

    /**
     * 一次操作的进度怎么量。
     *
     * 环境层和包管理器都不往外报进度，能看到的只有三样东西：
     * - 下载中的临时文件有多大（除以归档总大小）；
     * - 镜像目录的体积涨了多少（除以预计要写入的量）；
     * - 包管理器输出里的「第几个 / 共几个」。
     * 这里把它们换算成「当前阶段 + 阶段内完成度」，整体百分比与剩余时间交给 ProgressMeter。
     *
     * 取样只在一个协程里串行调用，内部的记录不需要加锁。
     */
    private inner class BusyPlan(
        private val kind: BusyKind,
        private val root: File?,
        private val download: Pair<File, Long>? = null,
        private val unpackBytes: Long = 0L,
        private val packageBytes: Long = 0L,
        packageWeight: Long = 0L,
        private val packageCount: Int = 0,
        private val apt: Boolean = false,
        private val removeBytes: Long = 0L,
    ) {
        private val startWall = System.currentTimeMillis()

        /** 归档已经在缓存里（上次下过）：这次不会再下载，计划里就不排下载阶段 */
        private val cached: Boolean = download?.let { (part, total) ->
            !part.isFile && archiveComplete(part, total)
        } ?: false

        /** 按顺序要经过的阶段与权重（单位：要从网络取回的字节数，解包按折算值给） */
        val stages: List<Pair<BusyStage, Float>> = when (kind) {
            BusyKind.DEPLOY -> {
                val list = mutableListOf<Pair<BusyStage, Float>>()
                val total = download?.second ?: 0L
                if (total > 0L && !cached) list.add(BusyStage.DOWNLOAD to total.toFloat())
                list.add(BusyStage.UNPACK to unpackBytes.toFloat() / UNPACK_SPEEDUP)
                list.add(BusyStage.PACKAGES to maxOf(packageWeight, 1L).toFloat())
                list
            }
            BusyKind.INSTALL -> listOf(BusyStage.PACKAGES to 1f)
            BusyKind.REMOVE -> listOf(BusyStage.REMOVE to 1f)
        }

        private var size = -1L
        private var sizeAt = 0L
        private var walkCost = 0L
        /** 这次操作里见过的最小目录体积：重装会先清空旧文件，解包的进度要从清空之后算起 */
        private var floor = Long.MAX_VALUE
        /** 进入装包阶段那一刻的目录体积：之后涨出来的才是软件 */
        private var packagesBase = -1L
        private var sawDownload = false

        /** 下载完成后临时文件会改名成正式文件名：正式文件在、大小也对，就是下完了 */
        private fun archiveComplete(part: File, total: Long): Boolean {
            val done = File(part.path.removeSuffix(".part"))
            return done.isFile && done.length() == total
        }

        /** 镜像目录现在多大。遍历一次要几十到几百毫秒，所以隔一阵才重新量一次 */
        private fun measure(now: Long): Long {
            val dir = root ?: return -1L
            if (size < 0L || now - sizeAt >= maxOf(SIZE_INTERVAL_MS, walkCost * 4)) {
                val t0 = SystemClock.elapsedRealtime()
                size = treeBytes(dir)
                sizeAt = SystemClock.elapsedRealtime()
                walkCost = sizeAt - t0
            }
            return size
        }

        fun sample(): BusySample {
            val now = SystemClock.elapsedRealtime()
            return when (kind) {
                BusyKind.REMOVE -> {
                    val cur = measure(now)
                    val within = if (removeBytes > 0L && cur >= 0L) {
                        (1f - cur.toFloat() / removeBytes).coerceIn(0f, 1f)
                    } else {
                        null
                    }
                    BusySample(BusyStage.REMOVE, within, getString(R.string.linux_workbench_stage_remove))
                }
                BusyKind.INSTALL -> samplePackages(measure(now), busyStep)
                BusyKind.DEPLOY -> sampleDeploy(now)
            }
        }

        private fun sampleDeploy(now: Long): BusySample {
            val dl = download
            if (dl != null && !cached) {
                val (part, total) = dl
                if (part.isFile) {
                    sawDownload = true
                    val got = part.length()
                    return BusySample(
                        BusyStage.DOWNLOAD,
                        if (total > 0L) (got.toFloat() / total).coerceIn(0f, 1f) else null,
                        getString(R.string.linux_workbench_stage_download, mb(got), mb(total)),
                    )
                }
                // 临时文件不在有两种情况：还没开始下（在清空旧文件、连服务器），或者已经下完改了名。
                // 小镜像几百毫秒就下完，可能一次都没取到「下载中」的样，所以还要看正式文件在不在。
                if (!sawDownload && !archiveComplete(part, total)) {
                    return BusySample(
                        BusyStage.DOWNLOAD, 0f,
                        getString(R.string.linux_workbench_stage_download, mb(0L), mb(total)),
                    )
                }
            }
            val cur = measure(now)
            // 「解包完成」标记是环境层在解包末尾写的。重装时旧标记会留到被清空为止，
            // 所以只认这次操作开始之后写下的那一个。文件名与 LinuxEnvironmentManager 里的一致。
            val marker = root?.let { File(it, DEPLOYED_MARKER) }
            val unpacked = marker != null && marker.isFile && marker.lastModified() >= startWall - 1000L
            if (!unpacked) {
                if (cur in 0L until floor) floor = cur
                val within = if (unpackBytes > 0L && cur >= 0L) {
                    ((cur - floor).toFloat() / unpackBytes).coerceIn(0f, 0.99f)
                } else {
                    null
                }
                return BusySample(BusyStage.UNPACK, within, getString(R.string.linux_workbench_stage_unpack))
            }
            // 部署末尾装基础软件由环境层自己做，它不透出包管理器的输出，只能看体积；
            // 换指向之后的补装（completeBootstrap）走包管理器，有计数行就用上。
            return samplePackages(cur, busyStep)
        }

        private fun samplePackages(cur: Long, step: ApkStep?): BusySample {
            // 更新索引时写进去的索引文件不算软件：索引更新完才开始记体积
            if (cur >= 0L && (packagesBase < 0L || busyIndexing)) packagesBase = cur
            val grown = if (cur >= 0L && packagesBase >= 0L) maxOf(0L, cur - packagesBase) else 0L
            val within = packageFraction(step, grown, packageBytes, packageCount)
            val text = when {
                busyIndexing -> getString(R.string.linux_workbench_stage_index)
                step != null -> getString(R.string.linux_workbench_stage_package, step.index, step.total, step.name)
                // apt 不报「第几个」，把它最新的一行原样给出来，至少看得到在动
                apt && busyLine.isNotBlank() -> busyLine
                else -> getString(R.string.linux_workbench_stage_packages)
            }
            return BusySample(BusyStage.PACKAGES, within, text)
        }
    }

    /**
     * 目录里普通文件的总字节数。
     *
     * 不跟随符号链接（理由同 deleteTree：rootfs 里指向目录的链接会把遍历带到镜像之外）。
     * 遍历期间文件在不断增删（正在解包、装包、删除），读不到的条目直接跳过。
     */
    private fun treeBytes(dir: File): Long {
        if (!dir.exists()) return 0L
        var total = 0L
        runCatching {
            java.nio.file.Files.walkFileTree(
                dir.toPath(),
                object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
                    override fun visitFile(
                        f: java.nio.file.Path,
                        attrs: java.nio.file.attribute.BasicFileAttributes,
                    ): java.nio.file.FileVisitResult {
                        if (attrs.isRegularFile) total += attrs.size()
                        return java.nio.file.FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(
                        f: java.nio.file.Path,
                        e: java.io.IOException,
                    ): java.nio.file.FileVisitResult = java.nio.file.FileVisitResult.CONTINUE

                    override fun postVisitDirectory(
                        d: java.nio.file.Path,
                        e: java.io.IOException?,
                    ): java.nio.file.FileVisitResult = java.nio.file.FileVisitResult.CONTINUE
                },
            )
        }
        return total
    }

    /** 部署（下载 → 解包 → 装基础软件）的进度计划 */
    private fun deployPlan(opt: LinuxRootfsCatalog.Option): BusyPlan {
        val unpack = when (val src = opt.source) {
            is LinuxRootfsCatalog.RootfsSource.Bundled -> src.extractedBytes
            is LinuxRootfsCatalog.RootfsSource.Remote -> src.extractedBytes
        }
        val boot = footprint(opt, PackageGroup.BOOTSTRAP)
        return BusyPlan(
            kind = BusyKind.DEPLOY,
            root = env.rootfsDir(opt),
            download = downloadProbe(opt),
            unpackBytes = unpack,
            // 没量过体量的发行版给 0：装包阶段就老实显示「量不出」，不拿别家的数字充数
            packageBytes = if (boot == null) 0L else boot.installed + opt.flavor.extraExtractBytes,
            packageWeight = (boot?.download ?: DEFAULT_BOOTSTRAP_DOWNLOAD) + opt.flavor.extraDownloadBytes,
            packageCount = if (boot != null && opt.flavor.extraPackages.isEmpty()) boot.count else 0,
            apt = opt.pkgManager == LinuxRootfsCatalog.PackageManager.APT,
        )
    }

    /** 单独装一组软件的进度计划；[expected] 为 null 表示这组软件没量过体量 */
    private fun installPlan(opt: LinuxRootfsCatalog.Option, expected: Footprint?): BusyPlan =
        BusyPlan(
            kind = BusyKind.INSTALL,
            root = env.rootfsDir(opt),
            packageBytes = expected?.installed ?: 0L,
            packageCount = expected?.count ?: 0,
            apt = opt.pkgManager == LinuxRootfsCatalog.PackageManager.APT,
        )

    // ================= 软件体量与操作说明 =================

    /** 一组软件（含依赖）的体量：包数（未知为 0）、下载字节、装好后占用的字节 */
    private class Footprint(val count: Int, val download: Long, val installed: Long)

    private enum class PackageGroup { BOOTSTRAP, NODE, DEVTOOLS }

    /**
     * 各组软件装到只有最小系统的镜像上的体量。
     *
     * 数字来源：2026-11-13 从 Alpine 官方软件源 main/aarch64 的索引按依赖关系逐包累加
     * （扣掉最小系统自带的包；下载量取到 0.01 MB）。只用来写「约多少 MB」和推算进度，
     * 不参与任何判断；软件源更新后会有几个百分点的出入，x86_64 上的包体积也略有不同。
     * 其它发行版没有量过，返回 null —— 界面上就不写具体数字。
     */
    private fun footprint(opt: LinuxRootfsCatalog.Option, group: PackageGroup): Footprint? =
        when (opt.family) {
            LinuxRootfsCatalog.Family.ALPINE -> when (group) {
                PackageGroup.BOOTSTRAP -> Footprint(14, 3_271_557L, 9_804_108L)
                PackageGroup.NODE -> Footprint(14, 24_777_851L, 67_462_961L)
                PackageGroup.DEVTOOLS -> Footprint(32, 87_493_181L, 237_974_396L)
            }
            LinuxRootfsCatalog.Family.ALPINE_EDGE -> when (group) {
                PackageGroup.BOOTSTRAP -> Footprint(14, 3_397_386L, 10_274_984L)
                PackageGroup.NODE -> Footprint(14, 24_180_163L, 65_212_377L)
                PackageGroup.DEVTOOLS -> Footprint(32, 91_624_571L, 247_012_237L)
            }
            else -> null
        }

    /**
     * 这组软件还差多少没装。
     *
     * 工具链那一组里包含 Node：Node 已经装过的话就不该再把它算进「要下载」里。
     * 两组的依赖有少量重叠（C++ 运行库），相减得到的是约数，只用于提示。
     */
    private fun pendingFootprint(
        opt: LinuxRootfsCatalog.Option,
        group: PackageGroup,
        installed: Set<String>,
    ): Footprint? {
        val full = footprint(opt, group) ?: return null
        if (group != PackageGroup.DEVTOOLS || "nodejs" !in installed) return full
        val node = footprint(opt, PackageGroup.NODE) ?: return full
        return Footprint(0, maxOf(0L, full.download - node.download), maxOf(0L, full.installed - node.installed))
    }

    private fun wholeMb(bytes: Long): String =
        String.format(java.util.Locale.US, "%.0f MB", bytes / (1024.0 * 1024.0))

    /** 「Node」「工具链」两格下面的说明：没装时写要下载多少，装好了写已安装 */
    private fun updateActionHints(opt: LinuxRootfsCatalog.Option, installed: Set<String>) {
        lastInstalled = installed
        wb.setActionHint(
            WorkbenchAction.InstallNode, installHint(opt, NODE_PACKAGES, PackageGroup.NODE, installed),
        )
        wb.setActionHint(
            WorkbenchAction.InstallDevtools, installHint(opt, DEVTOOLS_PACKAGES, PackageGroup.DEVTOOLS, installed),
        )
    }

    private fun installHint(
        opt: LinuxRootfsCatalog.Option,
        packages: List<String>,
        group: PackageGroup,
        installed: Set<String>,
    ): String {
        if (packages.all { it in installed }) return getString(R.string.linux_workbench_image_installed)
        val size = pendingFootprint(opt, group, installed)
            ?: return getString(R.string.linux_workbench_act_hint_needs)
        return getString(R.string.linux_workbench_act_hint_download, wholeMb(size.download))
    }

    /**
     * 「Node」「工具链」：先说清楚要下载多少、装好占多少，确认了再装。
     *
     * 这两格原来点一下就直接开始联网下载（工具链八十多 MB），而格子上只有一个名字，
     * 第一次来的人不知道点下去会发生什么。
     */
    private fun installPackagesWithConfirm(titleRes: Int, packages: List<String>, group: PackageGroup) {
        if (busyKind != null) {
            toast(getString(R.string.linux_workbench_busy_blocked))
            return
        }
        val title = getString(titleRes)
        if (packages.all { it in lastInstalled }) {
            toast(getString(R.string.linux_workbench_install_already, title))
            return
        }
        val size = pendingFootprint(LinuxRootfsCatalog.active(this), group, lastInstalled)
        val body = if (size == null) {
            getString(R.string.linux_workbench_act_hint_needs)
        } else {
            getString(R.string.linux_workbench_install_body, wholeMb(size.download), wholeMb(size.installed))
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.linux_workbench_install_title, title))
            .setMessage(body)
            .setPositiveButton(R.string.linux_workbench_install_go) { _, _ -> installPackages(packages, size) }
            .setNegativeButton(android.R.string.cancel, null)
            .showRounded()
    }

    /**
     * 「重装」会清空整个镜像再重新部署：先确认。
     *
     * 原来点一下就直接开始，没有任何确认 —— 这一格夹在「部署」和「Node」中间，
     * 点偏一次，镜像里装的软件和放的文件就全没了。
     */
    private fun confirmRepair() {
        if (busyKind != null) {
            toast(getString(R.string.linux_workbench_busy_blocked))
            return
        }
        val opt = LinuxRootfsCatalog.active(this)
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.linux_workbench_repair_title)
            .setMessage(getString(R.string.linux_workbench_repair_body, opt.displayName + " " + opt.versionLabel))
            .setPositiveButton(R.string.linux_workbench_repair_go) { _, _ -> deploy(force = true) }
            .setNegativeButton(android.R.string.cancel, null)
            .showRounded()
    }

    // ================= 镜像是否真的装好 =================

    /** 同一发行版的基础形态（三种形态共用一份系统文件，部署按基础形态做） */
    private fun baseOption(opt: LinuxRootfsCatalog.Option): LinuxRootfsCatalog.Option =
        LinuxRootfsCatalog.catalog.firstOrNull {
            it.family == opt.family && it.flavor == LinuxRootfsCatalog.Flavor.BASE
        } ?: opt

    private fun bootstrapFilesPresent(opt: LinuxRootfsCatalog.Option): Boolean {
        val root = env.rootfsDir(opt)
        return BOOTSTRAP_FILES.all { File(root, it).isFile }
    }

    private fun nodePresent(opt: LinuxRootfsCatalog.Option): Boolean {
        val root = env.rootfsDir(opt)
        // 位置与引擎层判就绪时查的一致：包管理器装的在 /usr/bin，官方预编译包解在 /opt/node
        return if (opt.nodeStrategy == LinuxRootfsCatalog.NodeStrategy.APK_PACKAGE) {
            File(root, "usr/bin/node").isFile || File(root, "bin/node").isFile
        } else {
            File(root, "opt/node/bin/node").isFile
        }
    }

    /**
     * 这个镜像的这个形态是不是真的能用了。
     *
     * 只看「系统文件解包过没有」不够：同一发行版的基础 / Dev / GUI 三种形态共用一份系统文件，
     * 装了基础版，另外两张卡也会跟着显示「已安装」，而它们要用的软件一个都没装。
     * 只看环境层的「软件装好了」标记也不够：那个标记在部署非当前镜像时会照着另一个镜像的
     * 安装结果写（见 completeBootstrap），所以再核一遍 bash 和 curl 是不是真的在。
     */
    private fun imageReady(opt: LinuxRootfsCatalog.Option): Boolean =
        runCatching { env.flavorPackagesReady(opt) && bootstrapFilesPresent(opt) }.getOrDefault(false)

    /**
     * 新部署的镜像：核对基础软件是不是真的装进了这个镜像，没有就装上。成功返回 null，失败返回原因。
     *
     * 为什么需要：环境层在部署末尾会装一次基础软件，但执行安装的容器运行器进的是
     * 「当前指向的镜像」（LinuxContainerRunner.run 取的是 activeOption），不是正在部署的这个。
     * 部署一个非当前镜像时，bash / curl 是往旧镜像里装的（那里本来就有，于是立刻「成功」），
     * 新镜像里什么都没装上，却落了一个「已装好」的标记。
     * 这里在指向换过去之后调用，此时运行器进的才是新镜像。
     *
     * 安装走 LinuxPackageManager：它会先把域名解析代理拉起来、把软件源索引取好，
     * 而且有逐行输出，横幅上能显示装到第几个包。
     */
    private fun completeBootstrap(opt: LinuxRootfsCatalog.Option): String? {
        if (imageReady(opt)) return null
        val base = baseOption(opt)
        // 基础软件不在，那个「已装好」的标记就是照着别的镜像写的：先删掉，
        // 下面万一装不成，页面才会如实停在「缺依赖」而不是装作就绪。
        if (!bootstrapFilesPresent(opt)) {
            runCatching { bootstrapMarker(base).delete() }
        }
        val wanted = LinuxEnvironmentManager.BOOTSTRAP_PACKAGES + opt.flavor.extraPackages
        busyIndexing = true
        val r = try {
            pkg.install(wanted, onLine = ::noteBusyLine)
        } finally {
            busyIndexing = false
        }
        if (!r.ok) {
            return "${opt.installCommand} failed:\n${r.rawOutput.takeLast(2000)}\n${installDiagnosis()}"
        }
        // 标记仍由环境层自己写（软件已经在了，这一步只是核对）；它没写成时补上，理由同 fixDependencies
        for (o in listOf(base, opt).distinct()) {
            dropAttemptedMarker(o)
            runCatching { env.ensureBootstrapPackages(o) }
            if (!env.flavorPackagesReady(o)) {
                val names = LinuxEnvironmentManager.BOOTSTRAP_PACKAGES + o.flavor.extraPackages
                runCatching { bootstrapMarker(o).writeText(names.joinToString(",")) }
            }
        }
        return null
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    /** 没进后台就能判定的失败（空间不够、容器没就绪…）：与后台操作的失败走同一张失败卡 */
    private fun reportFailure(text: String) {
        appendOutput("✗ $text")
        wb.showFailure(text)
        scrollView.post { scrollView.smoothScrollTo(0, 0) }
    }

    private fun appendOutput(text: String) {
        val cur = outputView.text?.toString().orEmpty()
        val merged = if (cur == getString(R.string.linux_console_hint) || cur.isEmpty()) text else "$cur\n$text"
        outputView.text = merged
        outputView.setTextColor(getColor(R.color.text_primary))
    }

    // ================= 工作台数据装配 =================

    /** family:flavor —— 镜像的唯一标识，与 catalog 的判等方式一致 */
    private fun optionKey(opt: LinuxRootfsCatalog.Option): String =
        opt.family.id + ":" + opt.flavor.id

    private fun findOption(key: String): LinuxRootfsCatalog.Option? =
        LinuxRootfsCatalog.catalog.firstOrNull { optionKey(it) == key }

    /**
     * 设备的首选架构。
     *
     * 原来是「支持列表里有 arm64 就算 arm64」，而 x86_64 的模拟器镜像带 arm64 转译层，
     * 列表里两个都有，于是在 x86_64 上显示成 arm64。镜像目录选包看的也是列表第一项。
     */
    private fun abiLabel(): String =
        when (val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull()) {
            null, "arm64-v8a" -> "arm64"
            else -> abi
        }

    /**
     * 基础软件都在、只差当前形态（Dev / GUI）的额外软件时，返回形态名。
     *
     * 不给的话横幅是「补齐 — 后即可使用」：引擎层判就绪只看 bash / curl / node，
     * 说不出还差什么，而页面明明停在「缺依赖」。
     */
    private fun pendingFlavorLabel(option: LinuxRootfsCatalog.Option): String? =
        if (option.flavor != LinuxRootfsCatalog.Flavor.BASE &&
            runCatching { env.isDeployed(option) && !env.flavorPackagesReady(option) }.getOrDefault(false)
        ) {
            option.flavor.label
        } else {
            null
        }

    /**
     * 缺的是什么，给横幅「补齐 %s 后即可使用」用的短名字。
     *
     * 原来这里返回的是一整句报错，套进横幅成了
     * 「补齐 Linux 环境里还没有 node，请先点上方「安装 node」。 后即可使用」。
     * 判据与引擎层判就绪用的一致（bash、curl，然后是 node），先缺的先报。
     */
    private fun missingBootstrap(option: LinuxRootfsCatalog.Option): String? =
        when {
            !bootstrapFilesPresent(option) -> "bash / curl"
            !nodePresent(option) -> "node"
            else -> null
        }

    /**
     * 文件都在但标记不在时，把标记补上（自愈）。
     *
     * ## 为什么必须有（v1.7.13 真机实测）
     *
     * 判「这个形态装好了没有」有两套判据，它们会不同步：
     * - **引擎层** `readiness()` 只看文件（`bin/bash`、`usr/bin/curl`、node）；
     * - **UI 层** `missingBootstrap()` 也只看文件 —— 但另一个条件
     *   `flavorPackagesReady()` **只看标记**。
     *
     * 于是标记一旦缺失（装包中途被杀、用户删过、上一版 bug 落的 attempted 没清掉），
     * 页面就永远停在「缺依赖」：横幅参数是 null（文件确实都在），文案模板
     * 「补齐 %1$s 后即可使用」渲染成「补齐一后即可使用」，用户完全不知道要补什么，
     * 点进去也装不出任何东西 —— 真机实测就是这个形态（Ubuntu，bash/curl/node 全在，
     * 只有 `.dsh-bootstrap` 不见）。
     *
     * 修法：**以文件为准**补写标记。标记本来就是「这些包我装过」的缓存，
     * 文件在就是事实，标记只是它的加速副本 —— 缓存丢了重建即可。
     */
    private fun healBootstrapMarker(option: LinuxRootfsCatalog.Option) {
        if (!bootstrapFilesPresent(option)) return
        if (option.nodeStrategy != LinuxRootfsCatalog.NodeStrategy.APK_PACKAGE && !nodePresent(option)) return
        if (runCatching { env.flavorPackagesReady(option) }.getOrDefault(false)) return
        if (!runCatching { env.isDeployed(option) }.getOrDefault(false)) return
        runCatching {
            // 内容与环境层写的一致（BOOTSTRAP_PACKAGES + 形态额外包），否则
            // 环境层下次读到这份标记会以为「形态的额外包也装过了」。
            val pkgs = LinuxEnvironmentManager.BOOTSTRAP_PACKAGES + option.flavor.extraPackages
            bootstrapMarker(option).writeText(pkgs.joinToString(","))
        }.onSuccess {
            android.util.Log.i("LinuxActivity", "bootstrap marker healed from on-disk files")
        }
    }

    /** IO 快照：只读事实，不起容器、不写状态 */
    private fun loadWorkbenchFacts(option: LinuxRootfsCatalog.Option): WorkbenchFacts {
        // ★先自愈标记，再读事实（v1.7.13）。
        // 顺序就是语义：`flavorReady` 只看标记，而标记可能丢 / 停在 attempted。
        // 放在这里而不是 UI 事件里，是因为「读事实」是唯一的必经路径 ——
        // 打开页面、切发行版、装包完成回调全都会经过它。
        healBootstrapMarker(option)
        val st = env.status(option)
        val read = runCatching { launcher.readiness() }.getOrNull()
        val kind = when (read) {
            is LinuxEngineLauncher.Readiness.Ready -> ReadinessKind.READY
            is LinuxEngineLauncher.Readiness.NeedsDeploy -> ReadinessKind.NEEDS_DEPLOY
            is LinuxEngineLauncher.Readiness.NeedsPackages -> ReadinessKind.NEEDS_PACKAGES
            is LinuxEngineLauncher.Readiness.Unavailable -> ReadinessKind.UNAVAILABLE
            null -> if (st.state == LinuxEnvironmentManager.State.READY) {
                ReadinessKind.READY
            } else {
                ReadinessKind.NEEDS_DEPLOY
            }
        }
        val reason = when (read) {
            is LinuxEngineLauncher.Readiness.NeedsPackages -> read.reason
            is LinuxEngineLauncher.Readiness.Unavailable -> read.reason
            else -> st.detail.takeIf { it.isNotBlank() }
        }
        val envState = when (st.state) {
            LinuxEnvironmentManager.State.NOT_DEPLOYED -> EnvState.NOT_DEPLOYED
            LinuxEnvironmentManager.State.DEPLOYING -> EnvState.DEPLOYING
            LinuxEnvironmentManager.State.READY -> EnvState.READY
            LinuxEnvironmentManager.State.BROKEN -> EnvState.BROKEN
        }
        val requested = app.dsh.mobile.engine.EngineModeSelector.isLinuxEnabled(this)
        val last = app.dsh.mobile.engine.EngineModeSelector.lastMode
        val linuxNow = last == app.dsh.mobile.engine.EngineModeSelector.Mode.LINUX
        // 只在环境就绪时才数已装的包：pkg.installed() 会经 runner.ensureReady() 起容器，
        // 而 ensureReady 在未部署时会顺手把当前镜像部署掉 —— 仅仅打开这个页面就触发一次解包，
        // 而且与用户随后点的「部署 / 删除」并发。
        val installed = if (st.state == LinuxEnvironmentManager.State.READY) {
            runCatching { pkg.installed() }.getOrDefault(emptyMap())
        } else {
            emptyMap()
        }
        val disk = runCatching { env.diskUsage(option) }.getOrDefault(0L)
        val files = runCatching { env.fileCount(option) }.getOrDefault(0)
        return WorkbenchFacts(
            optionKey = optionKey(option),
            optionName = option.displayName + " " + option.versionLabel,
            libc = option.libc,
            abi = abiLabel(),
            versionLabel = st.rootfsVersion,
            envState = envState,
            readiness = kind,
            readinessReason = reason,
            flavorReady = runCatching { env.flavorPackagesReady(option) }.getOrDefault(false),
            missingDependency = missingBootstrap(option) ?: pendingFlavorLabel(option),
            requestedLinux = requested,
            actualMode = if (linuxNow) EngineMode.LINUX else EngineMode.NATIVE,
            engineRunning = requested && linuxNow,
            fallbackReason = app.dsh.mobile.engine.EngineModeSelector.lastReason,
            toolCount = if (st.state == LinuxEnvironmentManager.State.READY) installed.size else null,
            diskBytes = if (disk > 0) disk else null,
            fileCount = if (files > 0) files else null,
            freeBytes = filesDir.usableSpace.takeIf { it > 0 },
        )
    }

    /** 镜像卡牌：展示 6 张，当前项若在第 7 位则提升进主网格 */
    private fun refreshImages(active: LinuxRootfsCatalog.Option) {
        val activeKey = optionKey(active)
        val all = LinuxRootfsCatalog.catalog.map { opt ->
            val key = optionKey(opt)
            val deployed = runCatching { env.isDeployed(opt) }.getOrDefault(false)
            // 「已安装」按形态算，不按发行版算：三种形态共用一份系统文件，
            // 只装了基础版时 Dev / GUI 两张卡不能跟着变绿（引擎模式的选择框里也是按形态判的）。
            val ready = deployed && imageReady(opt)
            // 系统文件在盘上、这个形态却没装齐：当前在用的和基础形态一定算；
            // Dev / GUI 只有装过但没装成才算，从没装过的形态就是「没装」，不标成半成品。
            val partial = deployed && !ready && (
                key == activeKey ||
                    opt.flavor == LinuxRootfsCatalog.Flavor.BASE ||
                    runCatching { bootstrapMarker(opt).isFile }.getOrDefault(false)
                )
            val usable = runCatching { LinuxRootfsCatalog.availableOnThisAbi(opt.flavor) }.getOrDefault(true)
            // ★卡宽约 195dp：名字里再塞 flavor 后缀就会被 ellipsize 成「Alpine 3.2...」，
            //   让人以为两个 Alpine 版本号不一样。后缀挪到副行开头，名字只留发行版+版本。
            // ★卡名只留发行版：系统字体放大后两列卡内文字区不足 110dp，
            //   "Ubuntu 24.04" 12 个字符就会被 ellipsize 成 "Ubuntu 24...."。
            //   版本、形态、体积一起进副行（副行字号小 2sp，能多放 3~4 个字符）。
            val name = opt.displayName
            WorkbenchImageItem(
                key = key,
                name = name,
                // 卡宽只有约 195dp，两列里放不下「musl · 下载 3.8 MB / 解压 8.1 MB」这种长串，
                // 只留 libc + 下载体积；解压体积在点击后的确认对话框里给。
                sub = opt.versionLabel.substringBefore(' ') + " \u00b7 " +
                    (if (opt.flavor == LinuxRootfsCatalog.Flavor.BASE) ""
                     else opt.flavor.label + " \u00b7 ") + mb(
                    (when (val s = opt.source) {
                        is LinuxRootfsCatalog.RootfsSource.Bundled -> s.compressedBytes
                        is LinuxRootfsCatalog.RootfsSource.Remote -> s.compressedBytes
                    }) + opt.flavor.extraDownloadBytes,
                ),
                badge = when {
                    key == activeKey && ready -> getString(R.string.linux_workbench_image_in_use)
                    partial -> getString(R.string.linux_workbench_image_partial)
                    key == activeKey -> getString(R.string.linux_workbench_image_recommended)
                    ready -> getString(R.string.linux_workbench_image_installed)
                    else -> null
                },
                inUse = key == activeKey,
                installed = ready,
                usable = usable,
                partial = partial,
            )
        }
        // ★7 个全部渲染。旧实现只放前 6 个、把当前项顶进末位，
        //   结果第 7 个（Alpine 3.23 GUI）在界面上完全没有入口 —— 用户能选的比
        //   标题写的「7 个可用」少一个，且没有任何「更多」入口。
        wb.renderImages(all, all.size)
        // 单卡进度：switchDistro 里按 key 取控件，只转被点的那张
        rowProgress.clear()
        rowProgress.putAll(wb.imageBars)
        // 卡片是整批重建的：正在装的那张要把进度条重新点亮，否则装到一半一刷新进度条就没了
        busyCardKey?.let { rowProgress[it] }?.visibility = View.VISIBLE
    }

    /** 动作分发：视图层只发意图，业务仍走原有实现 */
    private fun onWorkbenchAction(action: WorkbenchAction) {
        when (action) {
            is WorkbenchAction.SelectImage -> {
                val opt = findOption(action.optionKey) ?: return
                if (!LinuxRootfsCatalog.availableOnThisAbi(opt.flavor)) {
                    reportFailure(getString(R.string.linux_workbench_image_unavailable))
                    return
                }
                switchDistro(opt)
            }
            is WorkbenchAction.DeleteImage -> {
                val opt = findOption(action.optionKey) ?: return
                confirmDeleteImage(opt)
            }
            WorkbenchAction.Deploy -> deploy(force = false)
            // 「开始」按阶段分流：没部署就部署；已部署只缺包时再调 deploy 是空操作（环境层直接返回）
            WorkbenchAction.StartSetup ->
                if (lastState?.phase == Phase.PACKAGES) fixDependencies() else deploy(force = false)
            WorkbenchAction.FixDependencies -> fixDependencies()
            WorkbenchAction.Repair -> confirmRepair()
            WorkbenchAction.InstallNode ->
                installPackagesWithConfirm(R.string.linux_workbench_act_node, NODE_PACKAGES, PackageGroup.NODE)
            WorkbenchAction.InstallDevtools ->
                installPackagesWithConfirm(
                    R.string.linux_workbench_act_devtools, DEVTOOLS_PACKAGES, PackageGroup.DEVTOOLS,
                )
            WorkbenchAction.OpenTerminal -> openLinuxTerminal()
            WorkbenchAction.Diagnose -> showDiagnosis()
            WorkbenchAction.ChooseMode -> showModePicker()
            WorkbenchAction.RestartEngine -> confirmRestartEngine()
            WorkbenchAction.GotoConsole -> {
                scrollToView(wb.consoleHost, alsoShow = cmdInput)
                // 焦点等滚动结束再给。旧版先 requestFocus 再滚：拿焦点时 ScrollView 会先把输入框滚进视野，
                // 紧接着那句 smoothScrollTo(0, cmdInput.top) 用的是输入框在它那一行里的局部坐标（接近 0），
                // 于是页面又被拉回顶部 —— 这就是「滑了一下又回去」。
                scrollView.postDelayed({ if (cmdInput.isShown) cmdInput.requestFocus() }, 360L)
            }
            WorkbenchAction.GotoImages -> scrollToView(wb.imagesAnchor)
            WorkbenchAction.GotoSetup -> scrollToView(wb.setupAnchor)
        }
    }

    /**
     * 平滑滚到某个区块。
     *
     * 目标的 top 不能直接用：View.top 是相对它直接父容器的坐标，隔了几层容器就不是页面上的位置了。
     * offsetDescendantRectToMyCoords 会沿父链逐层累加，得到的才是滚动内容里的绝对位置。
     *
     * [alsoShow] 是「滚完必须露在屏幕里」的子控件（命令区的输入框在输出区下面，
     * 输出一长，只对齐区块顶部的话输入框还在屏幕外）。
     */
    private fun scrollToView(target: View, alsoShow: View? = null) {
        scrollView.post {
            // 目标所在区块被收起时不滚：滚向一个高度为 0 的控件，表现就是随机跳一下
            if (!target.isShown) return@post
            val gap = dp(16)
            val r = android.graphics.Rect(0, 0, target.width, target.height)
            scrollView.offsetDescendantRectToMyCoords(target, r)
            var y = r.top - gap
            if (alsoShow != null && alsoShow.isShown) {
                val b = android.graphics.Rect(0, 0, alsoShow.width, alsoShow.height)
                scrollView.offsetDescendantRectToMyCoords(alsoShow, b)
                y = maxOf(y, b.bottom + gap - scrollView.height)
            }
            scrollView.smoothScrollTo(0, y.coerceAtLeast(0))
        }
    }

    // ================= 运行模式 / 重启 =================

    /**
     * 运行模式选择：与灵动岛侧边栏用同一个 [RunModeOverlay]，两处的选项与文案不会各写一份。
     *
     * 旧版点这一行是直接翻转开关，校验没过时只往输出区写一行 —— 输出区在未就绪时是隐藏的，
     * 所以表现为「点了没反应」。
     */
    private fun showModePicker() {
        val container = findViewById<ViewGroup>(android.R.id.content) ?: return
        hideModePicker()
        val linuxOn = app.dsh.mobile.engine.EngineModeSelector.isLinuxEnabled(this)
        val anyReady = LinuxRootfsCatalog.usableCatalog.any {
            runCatching { env.flavorPackagesReady(it) }.getOrDefault(false)
        }
        if (!linuxOn && !anyReady) {
            // 已经是原生、又没有任何装好的容器：浮层里没有东西可选，
            // 它那个「去下载」入口还会再开一个本页面。直接带去镜像区。
            toast(getString(R.string.run_mode_container_empty))
            scrollToView(wb.imagesAnchor)
            return
        }
        val host = FrameLayout(this)
        host.addView(
            View(this).apply {
                // 居中弹出要压暗背景；侧边栏那边是贴着面板出现的，所以它用的是透明接收层
                setBackgroundColor(0x99000000.toInt())
                isClickable = true
                setOnClickListener { hideModePicker() }
            },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        val card = RunModeOverlay(this).build { pick ->
            hideModePicker()
            applyModePick(pick)
        }
        // 卡片自己吃掉点击：否则点在卡片的留白上会穿到背景层，把浮层关掉
        card.isClickable = true
        val width = minOf(dp(320), resources.displayMetrics.widthPixels - dp(48))
        host.addView(card, FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        container.addView(
            host,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        modeHost = host
        host.alpha = 0f
        host.animate().alpha(1f).setDuration(140L).start()
    }

    private fun hideModePicker() {
        val h = modeHost ?: return
        modeHost = null
        (h.parent as? ViewGroup)?.removeView(h)
    }

    private fun applyModePick(pick: RunModeOverlay.Pick) {
        when (pick) {
            is RunModeOverlay.Pick.Native -> {
                RunModeOverlay.apply(this, pick)
                refreshStatus()
            }
            is RunModeOverlay.Pick.Container -> {
                if (busyKind != null) {
                    toast(getString(R.string.linux_workbench_busy_blocked))
                    return
                }
                scope.launch {
                    val prev = LinuxRootfsCatalog.active(this@LinuxActivity)
                    // 先把指向切到选中的镜像，再问它能不能跑：readiness() 判的是「当前镜像」。
                    // 能跑才真的打开容器模式 —— 否则引擎一启动就回退原生，用户还以为自己在容器里。
                    val r = withContext(Dispatchers.IO) {
                        LinuxRootfsCatalog.setActiveExact(
                            this@LinuxActivity, pick.option.family, pick.option.flavor,
                        )
                        runCatching { launcher.readiness() }.getOrNull()
                    }
                    if (r is LinuxEngineLauncher.Readiness.Ready) {
                        RunModeOverlay.apply(this@LinuxActivity, pick)
                    } else {
                        // 没成：把指向还原。这一步只是来问一句「能不能跑」，不该顺手把用户正在用的镜像换掉
                        withContext(Dispatchers.IO) {
                            LinuxRootfsCatalog.setActiveExact(this@LinuxActivity, prev.family, prev.flavor)
                        }
                        reportFailure(getString(R.string.linux_workbench_not_ready, readinessReason(r)))
                    }
                    refreshStatus()
                }
            }
        }
    }

    /** 返回键先收浮层，再退出页面 */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (modeHost != null) {
            hideModePicker()
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    /** 重启引擎：有中断后果，先确认。确认框与「诊断与维护」页里那一个用同一组文案 */
    private fun confirmRestartEngine() {
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.restart_confirm_title))
            .setMessage(getString(R.string.restart_confirm_body))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                (application as DshApp).supervisor.restart()
                toast(getString(R.string.linux_workbench_restart_done))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showRounded()
    }

    /** 与设置页的对话框同一个圆角底板 */
    private fun android.app.AlertDialog.Builder.showRounded(): android.app.AlertDialog =
        create().apply {
            setOnShowListener {
                window?.setBackgroundDrawableResource(R.drawable.bg_dialog_rounded)
            }
            show()
        }

    // ================= 删除镜像 =================

    /**
     * 长按已安装的镜像卡 → 确认 → 删除。
     *
     * 三道闸：有操作在跑时不删；镜像里还有进程在跑时不删（删正在被执行的文件，
     * 进程会在下一次读盘时崩在随机位置）；删之前把占用大小和「三种形态一起删」讲清楚。
     */
    private fun confirmDeleteImage(opt: LinuxRootfsCatalog.Option) {
        if (busyKind != null) {
            toast(getString(R.string.linux_workbench_busy_blocked))
            return
        }
        val dir = env.rootfsDir(opt)
        val name = opt.displayName + " " + opt.versionLabel
        scope.launch {
            val probe = withContext(Dispatchers.IO) {
                if (!env.isDeployed(opt)) {
                    null
                } else {
                    imageInUse(opt) to runCatching { env.dirBytes(dir) }.getOrDefault(0L)
                }
            } ?: return@launch
            if (isFinishing || isDestroyed) return@launch
            if (probe.first) {
                reportFailure(getString(R.string.linux_workbench_delete_running))
                return@launch
            }
            android.app.AlertDialog.Builder(this@LinuxActivity)
                .setTitle(getString(R.string.linux_workbench_delete_title, name))
                .setMessage(getString(R.string.linux_workbench_delete_body, mb(probe.second)))
                .setPositiveButton(R.string.action_delete) { _, _ -> deleteImage(opt, name, probe.second) }
                .setNegativeButton(android.R.string.cancel, null)
                .showRounded()
        }
    }

    /**
     * 这个镜像里是否还有进程在跑。
     *
     * 两个来源：引擎上次是以容器模式启动、且用的就是这个发行版；
     * 或者本应用名下有进程的命令行里带着这个 rootfs 的路径（容器终端、画面服务、正在跑的命令）。
     * /proc 里只看得到本应用自己的进程，正好是要查的范围。
     * 以 root 身份起的容器进程（chroot 沙箱）在这里看不到，那种情况只有第一条能拦住。
     */
    private fun imageInUse(opt: LinuxRootfsCatalog.Option): Boolean {
        val engineInside =
            app.dsh.mobile.engine.EngineModeSelector.lastMode ==
                app.dsh.mobile.engine.EngineModeSelector.Mode.LINUX &&
                LinuxRootfsCatalog.active(this).family == opt.family
        if (engineInside) return true
        // 只比路径的尾段：同一个目录在命令行里可能写成 /data/data/… 也可能是 /data/user/0/…
        val needle = "/linux/distros/" + opt.family.id
        val self = android.os.Process.myPid().toString()
        return File("/proc").listFiles().orEmpty().any { p ->
            if (p.name == self || !p.name.all { it.isDigit() }) return@any false
            val cmd = runCatching { File(p, "cmdline").readText() }.getOrNull() ?: return@any false
            var i = cmd.indexOf(needle)
            var hit = false
            while (i >= 0 && !hit) {
                // 后面紧跟字母或数字的是另一个发行版（alpine 与 alpine323），不算
                val next = cmd.getOrNull(i + needle.length)
                hit = next == null || !next.isLetterOrDigit()
                i = cmd.indexOf(needle, i + 1)
            }
            hit
        }
    }

    private fun deleteImage(opt: LinuxRootfsCatalog.Option, name: String, bytes: Long) {
        runAsync(
            null,
            kind = BusyKind.REMOVE,
            detail = name,
            cardKey = optionKey(opt),
            plan = BusyPlan(kind = BusyKind.REMOVE, root = env.rootfsDir(opt), removeBytes = bytes),
        ) {
            val dir = env.rootfsDir(opt)
            // 只删 files/linux/distros 下的直接子目录。路径来自环境层，这里再核一遍：
            // 递归删除的目标一旦算错，没有第二次机会。
            val root = File(filesDir, "linux/distros").canonicalFile
            if (dir.canonicalFile.parentFile != root) {
                return@runAsync "✗ refusing to delete unexpected path: ${dir.absolutePath}"
            }
            // ── 第一趟：先按当前属主删一遍 ──
            //
            // 绝大多数情况这一趟就够了：SECCOMP 沙箱下容器就是 App 进程，一切
            // 文件本来就归 app uid，deleteTree 能把整棵树清空。
            var failed = deleteTree(dir)

            // ── 第二趟：把属主交还给自己，再删一遍 ──
            //
            // ★这是「删除镜像删不干净」的真正修法（2026-11-13 真机实证）。
            //
            // rootfs 里只要有**任何**一次以 root 身份写过盘（CHROOT 沙箱的
            // `apk add`、`su` 手工装包、root 引擎跑过一次），那些 inode 属主就是
            // root。App 域**删不掉 root 的目录**：unlink 只看父目录写权限，而那
            // 些目录自身就是 root 的 0755 —— 父目录归 App 也进不去写，chmod 更是
            // EPERM。于是删不干净永远留下几千项（真机实测 alpine：2620 项、210 个
            // 目录），用户重装也只会覆盖在脏目录上。
            //
            // 顺序必须是「先删再交还再删」，不能只交还不删：
            //   · 先删能带走绝大多数条目，交还阶段扫的树就小得多；
            //   · 交还之后 App 自己就是属主，最终这一趟用纯 File API 完成，
            //     不依赖特权通道是否可用。
            if (failed > 0 && dir.exists()) {
                val normalized = runCatching {
                    app.dsh.mobile.engine.Privilege.normalizeOwnership(this@LinuxActivity, dir)
                }.getOrDefault(false)
                if (normalized) {
                    val after = deleteTree(dir)
                    android.util.Log.i(
                        "LinuxActivity",
                        "deleteImage: after ownership normalize, " + after + " entries left",
                    )
                    failed = after
                }
            }

            // ── 第三趟：仍然删不掉时走特权通道整树 rm ──
            //
            // 交还属主也可能失败（没 root、没 Shizuku、树里有不可变的挂载点）。
            // 这时退到「让特权身份直接删」，判据是**目录是否真的消失**。
            // runPrivileged 返回的是命令输出、不代表退出码，所以不能看返回值 ——
            // `rm -rf` 对部分失败也常常静默返回空输出，只有目录真的不在了才算成功。
            if (failed > 0 && dir.exists()) {
                val pruned = runCatching {
                    app.dsh.mobile.engine.Privilege.runPrivileged(
                        this@LinuxActivity,
                        "rm -rf " + app.dsh.mobile.engine.Privilege.shellQuoteForContext(dir.absolutePath) + " 2>/dev/null; echo ok",
                    )
                }.getOrNull()
                if (!dir.exists()) {
                    android.util.Log.i(
                        "LinuxActivity",
                        "deleteImage: privileged rm recovered " + dir.absolutePath,
                    )
                    failed = 0
                } else {
                    val left = deleteTree(dir)
                    failed = left
                    android.util.Log.w(
                        "LinuxActivity",
                        "deleteImage: " + left + " entries still undeletable (privileged=" +
                            (pruned != null) + ")",
                    )
                }
            }

            // ── 缓存与状态：这些不在 rootfs 里，deleteTree 从不碰它们 ──
            //
            // 下载包留着的话，下次再装会直接用缓存，等于没释放这部分空间。
            // 命名规则见 downloadRootfs()：cache/linux-rootfs/<family.id>-rootfs.<ext>
            // （下载中多一个 .part）。
            val cachePrefix = opt.family.id + "-rootfs."
            File(cacheDir, "linux-rootfs").listFiles().orEmpty()
                .filter { it.name.startsWith(cachePrefix) }
                .forEach { it.delete() }
            // Node 官方预编译包（cache/linux-node/node-<ver>-linux-arm64.tar.xz，
            // 实测 30MB）。只给 OFFICIAL_TARBALL 的发行版下载，与发行版一一对应
            // （每个发行版的 compatNode 是确定的），随镜像一起清掉。
            if (opt.nodeStrategy == LinuxRootfsCatalog.NodeStrategy.OFFICIAL_TARBALL) {
                File(cacheDir, "linux-node").listFiles().orEmpty().forEach { it.delete() }
            }
            // 装包日志（files/linux/state/apt-last.log）记的是上一次装包的原始输出，
            // 属于「这个环境」的现场记录。镜像没了，它指向的环境也没了。
            File(env.stateDir(), "apt-last.log").delete()

            // ── 激活指向：删掉当前用的镜像后必须清掉 ──
            //
            // 不清的话 prefs 仍指着一个不存在的目录，active() 照样返回它 ——
            // 页面显示「使用中」但什么都跑不起来，用户得手工切一次才能恢复。
            runCatching {
                if (LinuxRootfsCatalog.active(this@LinuxActivity).family == opt.family) {
                    LinuxRootfsCatalog.clearActive(this@LinuxActivity)
                }
            }.onFailure {
                android.util.Log.w("LinuxActivity", "deleteImage: clearActive failed: " + it.message)
            }

            if (failed > 0) {
                "✗ " + getString(R.string.linux_workbench_delete_partial, failed)
            } else {
                "✓ " + getString(R.string.linux_workbench_delete_done, name, mb(bytes))
            }
        }
    }

    /**
     * 递归删除，返回没删掉的条目数。
     *
     * ★不能用 File.deleteRecursively / walkBottomUp：它们按 isDirectory 判断要不要往里走，
     * 而 isDirectory 会跟随符号链接。rootfs 里到处是符号链接，其中指向目录的那些
     * （例如指到 rootfs 之外的挂载点）会被当成普通目录钻进去删。
     * walkFileTree 不带 FOLLOW_LINKS 时把符号链接当文件处理：只删链接本身。
     */
    private fun deleteTree(dir: File, retried: MutableSet<String> = HashSet()): Int {
        if (!dir.exists()) return 0
        var failed = 0
        java.nio.file.Files.walkFileTree(
            dir.toPath(),
            object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
                override fun preVisitDirectory(
                    d: java.nio.file.Path,
                    attrs: java.nio.file.attribute.BasicFileAttributes,
                ): java.nio.file.FileVisitResult {
                    // rootfs 里有只读目录（解包时按归档里的权限还原）：不先放开，里面的东西删不掉
                    d.toFile().apply {
                        setReadable(true)
                        setWritable(true)
                        setExecutable(true)
                    }
                    return java.nio.file.FileVisitResult.CONTINUE
                }

                override fun visitFile(
                    f: java.nio.file.Path,
                    attrs: java.nio.file.attribute.BasicFileAttributes,
                ): java.nio.file.FileVisitResult {
                    if (runCatching { java.nio.file.Files.delete(f) }.isFailure) failed++
                    return java.nio.file.FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(
                    f: java.nio.file.Path,
                    e: java.io.IOException,
                ): java.nio.file.FileVisitResult {
                    // 打不开的目录会落到这里而不是 preVisitDirectory（目录是先打开、后回调的）：
                    // 放开权限后把它单独再走一遍。每个路径只重试一次，权限放不开时不会原地打转。
                    val d = f.toFile()
                    val isDir = java.nio.file.Files.isDirectory(f, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    if (isDir && retried.add(d.path) && d.setReadable(true) && d.setExecutable(true)) {
                        d.setWritable(true)
                        failed += deleteTree(d, retried)
                    } else {
                        failed++
                    }
                    return java.nio.file.FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(
                    d: java.nio.file.Path,
                    e: java.io.IOException?,
                ): java.nio.file.FileVisitResult {
                    if (runCatching { java.nio.file.Files.delete(d) }.isFailure) failed++
                    return java.nio.file.FileVisitResult.CONTINUE
                }
            },
        )
        return failed
    }

    // ================= 诊断 =================

    /**
     * 诊断结果：与「诊断与维护 → 上次启动」同一个 [ReportDialog]，一项一行、带状态色。
     *
     * 旧版是系统默认对话框里塞一段纯文本，只有 DNS 那几行，看不出到底哪一项不对。
     * 原来那段文本没有丢：它附在「复制」的内容末尾，排查装包失败时仍然用得上。
     */
    private fun showDiagnosis() {
        val option = LinuxRootfsCatalog.active(this)
        scope.launch {
            val (items, extra) = withContext(Dispatchers.IO) {
                val list = runCatching { diagnosisItems(option) }.getOrElse { e ->
                    listOf(
                        ReportDialog.Item(
                            ReportDialog.Status.FAIL,
                            getString(R.string.report_error),
                            e.message ?: e.javaClass.simpleName,
                        )
                    )
                }
                list to runCatching { installDiagnosis() }.getOrDefault("")
            }
            if (isFinishing || isDestroyed) return@launch
            val text = items.joinToString("\n") {
                "[" + it.status.name + "] " + it.title + if (it.detail.isBlank()) "" else ": " + it.detail
            } + "\n" + extra
            ReportDialog.show(
                this@LinuxActivity,
                getString(R.string.linux_workbench_diag_title),
                items,
                getString(R.string.report_copy),
                text,
            )
        }
    }

    /** 逐项体检（IO 线程）。顺序就是排查顺序：运行器 → 镜像 → 能不能跑 → 网络 → 空间 */
    private fun diagnosisItems(option: LinuxRootfsCatalog.Option): List<ReportDialog.Item> {
        val items = ArrayList<ReportDialog.Item>()
        val ok = ReportDialog.Status.OK
        val warn = ReportDialog.Status.WARN
        val bad = ReportDialog.Status.FAIL

        val runnerFile = EngineConfig.deployLinuxRunner(this)
        items += when {
            runnerFile == null ->
                ReportDialog.Item(bad, getString(R.string.linux_status_runner), getString(R.string.linux_err_no_runner))
            !EngineConfig.isFixedBaseLinuxRunner(runnerFile) ->
                ReportDialog.Item(bad, getString(R.string.linux_status_runner), getString(R.string.linux_err_bad_runner))
            else ->
                ReportDialog.Item(ok, getString(R.string.linux_status_runner), runnerFile.name)
        }

        val st = env.status(option)
        val image = option.displayName + " " + option.versionLabel
        items += when (st.state) {
            LinuxEnvironmentManager.State.READY ->
                ReportDialog.Item(ok, image, st.rootfsVersion ?: "")
            LinuxEnvironmentManager.State.BROKEN ->
                ReportDialog.Item(
                    bad, image, st.detail.ifBlank { getString(R.string.linux_workbench_fault_broken) },
                )
            else ->
                ReportDialog.Item(warn, image, getString(R.string.linux_err_env_not_ready))
        }

        val ready = runCatching { launcher.readiness() }.getOrNull()
        items += when (ready) {
            is LinuxEngineLauncher.Readiness.Ready -> ReportDialog.Item(
                ok, getString(R.string.linux_workbench_diag_readiness), getString(R.string.linux_workbench_diag_ready),
            )
            is LinuxEngineLauncher.Readiness.Unavailable -> ReportDialog.Item(
                bad, getString(R.string.linux_workbench_diag_readiness), ready.reason,
            )
            else -> ReportDialog.Item(
                warn, getString(R.string.linux_workbench_diag_readiness), readinessReason(ready),
            )
        }

        val port = LinuxDnsProxy.currentPort()
        items += if (port > 0) {
            ReportDialog.Item(
                ok, getString(R.string.linux_workbench_diag_dns), getString(R.string.linux_workbench_diag_dns_up, port),
            )
        } else {
            ReportDialog.Item(
                warn, getString(R.string.linux_workbench_diag_dns), getString(R.string.linux_workbench_diag_dns_down),
            )
        }

        if (st.state == LinuxEnvironmentManager.State.READY) {
            val resolv = runCatching {
                File(env.rootfsDir(option), "etc/resolv.conf").readText().trim().replace("\n", " | ")
            }.getOrNull()
            items += if (resolv.isNullOrBlank()) {
                ReportDialog.Item(warn, "resolv.conf", getString(R.string.linux_diag_unreadable))
            } else {
                ReportDialog.Item(ok, "resolv.conf", resolv)
            }
        }

        val free = filesDir.usableSpace
        if (free > 0) {
            // 低于 200 MB 提醒：最大的镜像解压后约 100 MB，再装一套开发工具就到这个量级
            items += ReportDialog.Item(
                if (free < 200L * 1024 * 1024) warn else ok,
                getString(R.string.linux_workbench_info_free),
                mb(free),
            )
        }
        return items
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        @Volatile
        private var inst: LinuxEngineLauncher? = null

        /**
         * 引导卡是否被用户收起（存 `dsh_ui` prefs）。
         *
         * 退出 App 重进要记得住 —— 熟手不想每次都被同一段说明挡一下。
         * 收起后卡片位置仍留一行「展开引导」入口，不会再也找不回来。
         */
        private const val KEY_GUIDE_HIDDEN = "linux_guide_hidden"

        private val NODE_PACKAGES = listOf("nodejs")
        private val DEVTOOLS_PACKAGES = listOf("nodejs", "git", "make", "gcc", "musl-dev")

        /** 基础软件装好的判据：与 LinuxEngineLauncher.readiness 查的那两个文件一致 */
        private val BOOTSTRAP_FILES = listOf("bin/bash", "usr/bin/curl")

        /** 环境层解包完成后写的标记文件（LinuxEnvironmentManager 里是私有常量，这里只读不写） */
        private const val DEPLOYED_MARKER = ".dsh-deployed"

        private const val BUSY_TICK_MS = 500L
        /** 镜像目录体积最少隔多久重量一次 */
        private const val SIZE_INTERVAL_MS = 1000L
        /** 解包比下载快多少倍（本地写盘对比网络），用来把解包的字节数折算成和下载同一个单位的权重 */
        private const val UNPACK_SPEEDUP = 8f
        /** 没量过体量的发行版，装基础软件这一段在整体进度里按这么多下载量算 */
        private const val DEFAULT_BOOTSTRAP_DOWNLOAD = 3_000_000L
    }
}
