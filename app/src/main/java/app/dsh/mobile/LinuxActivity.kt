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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var env: LinuxEnvironmentManager
    private lateinit var runner: LinuxContainerRunner
    private lateinit var pkg: LinuxPackageManager
    private lateinit var launcher: LinuxEngineLauncher

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
        refreshGuide()
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
        val needBytes = when (val src = opt.source) {
            is LinuxRootfsCatalog.RootfsSource.Bundled -> src.extractedBytes
            is LinuxRootfsCatalog.RootfsSource.Remote -> src.extractedBytes
        }
        // free<=0 表示探测不到，不是真的没空间 —— 不拦，让部署报真实原因
        val free = filesDir.usableSpace
        if (!env.isDeployed(opt) && free > 0 && free < needBytes) {
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
            // v1.5.3：让「是哪一行在装」可见。refreshDistroCard 重建会清 map，
            // 所以这里取不到时静默跳过（顶部的 busyBar 仍然在转，不会毫无反馈）。
            val bar = rowProgress[opt.family.id + ":" + opt.flavor.id]
            withContext(Dispatchers.Main) { bar?.visibility = View.VISIBLE }
            try {
                if (!env.isDeployed(opt)) {
                    val st = env.ensureDeployed(opt, force = false)
                    if (st.state != LinuxEnvironmentManager.State.READY) {
                        return@runAsync "✗ ${st.detail}"
                    }
                }
                // ★必须同时记 flavor，且必须**原子**写：
                //   只记 family 的话 active() 读回来还是 BASE，GUI 额外包永远不装；
                //   两次独立 apply 之间被杀则会留下 family/flavor 不一致。
                //   （API 的 /dev/linux/switch 走同一个 setActiveExact，两条路径一致）
                LinuxRootfsCatalog.setActiveExact(this@LinuxActivity, opt.family, opt.flavor)
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

        /**
         * 引导卡是否被用户收起（存 `dsh_ui` prefs）。
         *
         * 退出 App 重进要记得住 —— 熟手不想每次都被同一段说明挡一下。
         * 收起后卡片位置仍留一行「展开引导」入口，不会再也找不回来。
         */
        private const val KEY_GUIDE_HIDDEN = "linux_guide_hidden"
    }
}
