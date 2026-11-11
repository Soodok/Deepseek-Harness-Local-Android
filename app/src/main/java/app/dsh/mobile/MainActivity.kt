package app.dsh.mobile

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.SystemClock
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.dsh.mobile.engine.BootTrace
import app.dsh.mobile.engine.OpenBridge
import app.dsh.mobile.engine.RepoConfig
import app.dsh.mobile.engine.RuntimeInstaller
import app.dsh.mobile.engine.WebViewDownloader
import app.dsh.mobile.engine.EngineSupervisor
import app.dsh.mobile.engine.Privilege
import app.dsh.mobile.engine.EngineModeSelector
import app.dsh.mobile.service.EngineService
import app.dsh.mobile.pet.PetOverlayService
import app.dsh.mobile.update.AppUpdateBus
import app.dsh.mobile.update.AppUpdateDownloadService
import app.dsh.mobile.update.AppUpdateScheduler
import app.dsh.mobile.update.UpdateDialogs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * WebView 容器。
 *
 * UI 策略：不重写官方 WebUI（上游 developer preview 迭代快，追协议是无底洞），
 * 只做原生外壳 —— 引擎 Healthy 后加载 127.0.0.1 回环页面，状态条显示引擎生命周期。
 *
 * 设置入口：右上角 ⋯ 跳转独立设置页（SettingsActivity，MIUI 风格分组卡片），
 * 不再使用悬浮弹窗菜单。
 */
class MainActivity : ThemedActivity() {

    private lateinit var webView: WebView
    private lateinit var statusBar: TextView
    private var urlLoaded = false

    /**
     * WebView 预热已发起（v1.6.0）。
     *
     * 冷启动时 `loadUrl` 只在引擎进入 Healthy 后才会被调用（见 render()），
     * 于是 Chromium 进程创建 + JS 引擎初始化 + 合成器建立（实测约 700ms）
     * 全部排在引擎之后串行执行。但这段初始化**不依赖引擎** —— 只有页面内容依赖。
     *
     * 所以 onCreate 里先加载一个本地空白页，把这 700ms 提前跑掉；等引擎
     * Healthy 后 `loadLocalUrl(realUrl)` 直接导航，Chromium 已是热态。
     *
     * 注意：预热页的 `onPageFinished` **不能**触发 dismissSplash()，
     * 否则会在引擎还没起来时就把遮罩收掉，露出还没内容的 WebView。
     */
    private var webViewPreheated = false

    /** 预热占位页的 URL 前缀；onPageFinished 靠它区分「预热页」与「真正的 WebUI」 */
    private val preheatUrl = "about:blank"

    /**
     * 页面上报的返回栈状态（手机形态布局插件经 `DshAndroidBack` 写入）。
     *
     * 只看两个字段：
     * - `mobile`：当前是不是手机竖屏形态（插件负责判定，桌面/原生恒 false）；
     * - `consumable`：WebView 历史栈顶是否真有**同文档**条目可退。
     *
     * 为什么不能只信 `canGoBack()`：WebView 历史里可能存在跨文档条目（启动
     * 重定向、runtime 跳转、外链回退），退过去就是整页重载 —— 用户看到的是
     * 「按返回它重新加载了一下」。所以只有插件明确说「有条目」时才 goBack。
     *
     * 字段由 JavaBridge 线程写入，用 @Volatile 保证 UI 线程可见。
     */
    @Volatile private var backMobile = false
    @Volatile private var backConsumable = false

    /** 上一次「再按一次退出」的时刻（elapsedRealtime，不受用户改系统时间影响）。 */
    private var lastBackAt = 0L

    /** 桌面模式：桌面 UA + 固定 1280px 视口 + 手势缩放（手机浏览器「电脑模式」等价物） */
    private var desktopMode = false
    private var defaultUa: String = ""

    /** 屏幕朝向偏好（v1.2.120）：portrait 锁竖 / landscape 锁横+桌面布局 / auto 跟随旋转 */
    private var orientationMode: OrientationMode = OrientationMode.PORTRAIT

    /** 页面缩放百分比（竖屏时应用；等价浏览器 Ctrl+/Ctrl-）。横屏桌面模式交给 1280px meta，不叠加 */
    private var pageScale = DEFAULT_PAGE_SCALE

    // ── 视口重算（v1.5.1：自由窗口/分屏下字号变小修复）─────────────────────────

    /** 上一次应用视口时的 WebView 宽度（物理 px）。用于判断"真的变宽了"才重算 */
    private var lastViewportWidth = -1

    /**
     * 窗口尺寸变化后的延迟重应用任务（不 reload，只重写 viewport meta）。
     * 用字段持有是为了能 removeCallbacks —— 自由窗口拖拽时尺寸变化是高频事件。
     */
    private var viewportReapply: Runnable? = null

    /**
     * 上一次 `onPause` 的单调时刻（`SystemClock.elapsedRealtime()`）。
     *
     * 0 表示「还没离开过前台」，`onResume` 据此判断是首次进入还是从后台回来。
     * 用 `elapsedRealtime` 而不是 `currentTimeMillis`：后者会被改时间/校时影响，
     * 而我们要的是纯粹的时长。
     */
    private var pausedAtElapsed: Long = 0L

    /** 本页当前生效的语言代码（onResume 比对，变了就重建） */
    private var appliedLocale: String = ""

    private val uiScope = CoroutineScope(Dispatchers.Main)

    /**
     * 显式退出回调（v1.3.0 审查：实例感知注销）。
     *
     * Android 允许 recreate() 时**旧实例的 onDestroy 晚于新实例的 onCreate**；
     * 若 onDestroy 无条件置 null，会把新实例刚注册的回调清掉（退出后界面不关）。
     * 注销时比对实例引用即可避免。
     */
    private val exitCallback: () -> Unit = {
        Log.i("MainActivity", "explicit exit → finishing task to release WebView")
        finishAndRemoveTask()
    }

    /** 待回传的文件选择结果（onShowFileChooser → onActivityResult 之间持有；null 表示无进行中请求） */
    private var pendingFileCallback: android.webkit.ValueCallback<Array<Uri>>? = null

    /**
     * 挂起的网页媒体权限请求（issue #72）。
     *
     * `PermissionRequest` 是一次性对象：要么 grant、要么 deny，且只能各调一次。
     * 系统权限框是异步的，所以授权前必须先把它存起来，回到
     * `onRequestPermissionsResult` 再决定 grant/deny。
     * 新请求到达时先 deny 掉旧的，避免上一个请求永远悬着（页面会一直 pending）。
     */
    private var pendingWebPermission: android.webkit.PermissionRequest? = null

    // ---------- 启动遮罩（v1.6.19：状态收进 SplashController） ----------
    /** 全屏启动遮罩；null = 已淡出/未初始化 */
    private var splash: android.view.View? = null
    /** 遮罩的渲染 / 退出 / 动画全部由它负责 */
    private var splashController: app.dsh.mobile.ui.SplashController? = null
    /** 是否已经淡出（幂等） */
    private var splashDismissed = false
    private var splashMinMs = 700L
    /** 当前 Activity 已应用的主题；从设置页返回时检测变化并 recreate。 */
    private var appliedTheme: ThemeMode? = null
    /** 运行时下载面板（v1.6.18）。持有引用判重，同 gateDialog 范式。 */
    private var runtimeSetupDialog: AlertDialog? = null
    /** 预热历史条目是否已清理（幂等标记；clearHistory 只该做一次） */
    private var preheatHistoryDropped = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appliedTheme = ThemePref.get(this)
        // 用户主动打开了 App → 清除「显式退出」标记，恢复正常保活行为。
        // 与 EngineService.exitCompletely() 的置位成对：退出后不复活，重开即恢复。
        runCatching { DshApp.setUserExitedExplicitly(this, false) }
        // 首启保护：若用户尚未完成引导（如直接拉起 MainActivity），先跳 Onboarding
        if (!Privilege.isOnboarded(this)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_main)

        // 记录本页生效的语言（onResume 里比对，设置页改过就重建本页）
        appliedLocale = LocaleHelper.get(this)

        setupSplash()
        statusBar = findViewById(R.id.statusBar)
        // 先赋值字段再配置：setupWebView 内部读取的是 this.webView，
        // 若写在 apply{} 里会在赋值完成前执行而触发 UninitializedPropertyAccessException。
        webView = findViewById<WebView>(R.id.webView)
        setupWebView()
        // v1.6.0：立刻预热 WebView（详见 webViewPreheated 注释）。
        // 必须放在 setupWebView 之后（settings/client 已就绪）、且在
        // 任何 await 引擎状态的 UI 逻辑之前 —— 这样 Chromium 的 700ms
        // 初始化与引擎启动并行，而不是串行叠加。
        preheatWebView()
        // 读回用户保存的页面缩放与屏幕朝向偏好
        readUiPrefs()
        applyOrientation(orientationMode)
        // v1.2.120：冷启动也要把桌面渲染落到偏好 —— 旧实现只在 onResume 的「偏好变化」
        // 分支里 setDesktop，导致 landscape=true 冷启动时 UA/视口/缩放手势全是竖屏那套，
        // 直到用户在设置里动过一次或旋转设备才恢复。
        setDesktop(shouldUseDesktop())

        // ── v1.5.1 自由窗口/分屏视口跟随 ─────────────────────────────────────
        // 只改窗口尺寸、不触发 onConfigurationChanged 的路径（OEM 分屏自由缩放、insets 重排）
        // 走这里兜底。只关心**宽度**：软键盘/状态栏变化只改高度，会被
        // scheduleViewportReapply 内部的宽度比对直接跳过，不会误触发重排。
        webView.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft) {
                scheduleViewportReapply("layout ${right - left}px")
            }
        }

        // 热重启：用户显式动作，完整 stop→start 链路；urlLoaded 复位让 Healthy 后重载 3080。
        // restart() 自身立即返回（内部串行 + 先置「启动中」），无需再套线程；重复点击幂等。
        // Toast 给即时反馈：引擎优雅退出最长等 10s，期间状态栏可能来不及刷新（实测观感
        // 是「点了没反应」于是连点三下 → 触发并发 stop/start 踩踏）。
        findViewById<View>(R.id.btnRestart).setOnClickListener {
            urlLoaded = false
            (application as DshApp).supervisor.restart()
            Toast.makeText(this, getString(R.string.engine_restarting), Toast.LENGTH_SHORT).show()
        }
        // 隐藏工具栏：一键收起让网页全屏（点顶部小把手唤回）
        findViewById<View>(R.id.btnHide).setOnClickListener { toggleToolbar() }
        // 原生终端入口：跳转独立终端页（真 PTY，自带 bash）
        findViewById<View>(R.id.btnTerminal).setOnClickListener {
            startActivity(Intent(this, TerminalActivity::class.java))
        }
        // 齿轮按钮：跳转独立设置页
        findViewById<View>(R.id.btnMore).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        setupIslandDock()
        // 保持 false。岛的层次现在只由 alpha 分档表达（见 islandIdleAlphaFor）——
        // drawable 里已没有任何画在轮廓之外的东西：投影、上缘高光、1dp 外描边
        // 全部撤掉了（2026-11-09 用户明确不要边缘悬浮效果，只保留透明度）。
        //
        // 留 false 的理由是历史遗留的按压残影：v1.4.62 用 clipToOutline=true 压住
        // 「系统方角涟漪从圆角外戳出来」，而现在岛内的按钮各自带 22dp 圆角涟漪
        // （bg_island_btn），整行不裁也不会露出方角。改回 true 没有收益，反而要
        // 重新验证一遍涟漪，故不动。
        findViewById<View>(R.id.statusBarRow).clipToOutline = false
        // 预览模式返回：一键从 AI 起的服务页回引擎主界面
        findViewById<View>(R.id.btnBack).setOnClickListener {
runCatching { (application as DshApp).supervisor.webUrl() }
                .getOrNull()?.takeIf { it.isNotEmpty() }?.let { loadLocalUrl(it) }
        }
        // 工具栏收起/唤回：点横栏文字空白区收起（网页全屏）
        statusBar.setOnClickListener { toggleToolbar() }
        // 把手：可拖到屏幕边缘任意位置（避让遮挡）；移动距离小于阈值视为点击唤回工具栏
        setupHandleBar()

        // 语音输入（v1.2.65）：麦克风入口已移交无障碍服务（不再依赖本 Activity 存活）；
        // 这里只注册「把文字送进 WebUI」的能力，供服务侧识别完成后调用。
        VoiceBridge.registerSender { text -> sendVoiceText(text) }

        val app = application as DshApp
        // v1.2.187/188：用户从通知栏「退出」→ 结束界面并销毁 WebView，释放 ~380MB。
        // 用回调而不是"标记位"：Activity 不在时回调为 null，不会留下残留状态
        // （标记方案曾导致"退出后重开 App 秒退"，审查 P0）。
        app.supervisor.onExplicitExit = exitCallback
        uiScope.launch {
            app.supervisor.state.collectLatest {
                render(it)
                // 自动更新的检查时机（R9）：引擎就绪才算"应用打开了"，
                // 首启可能正在解压 580MB 运行时，此时抢网络/IO 只会拖慢启动。
                if (it is EngineSupervisor.State.Healthy || it is EngineSupervisor.State.SafeMode) {
                    AppUpdateScheduler.onEngineReady(this@MainActivity)
                }
            }
        }
        // 下载完成 / 需要二选一时弹窗（服务在后台把状态推到总线）
        uiScope.launch {
            AppUpdateBus.state.collect {
                UpdateDialogs.maybeShow(this@MainActivity)
            }
        }
        uiScope.launch {
            app.supervisor.installProgress.collectLatest { renderProgress(it) }
        }
        // v1.4.8：启动过程可视化。状态流驱动的是"阶段切换"，秒数与明细由秒表刷新
        // （200ms 一次，只更新文本，不触发重排），两者互补不重复。
        uiScope.launch {
            BootTrace.snapshot.collectLatest { snap ->
                if (!splashDismissed) renderSplashTrace(snap)
            }
        }
    }

    /**
     * noruntime 包首启确认框：显示下载体积、建议 Wi-Fi、提供完整包入口。
     * 只有用户点「下载并启动」才拉起引擎（进而触发运行时下载）。
     */
    private fun showRuntimeSetupDialog() {
        // 幂等（同 gateDialog 的范式，v1.6.18）：`onResume` 会被反复触发
        // （切回前台、息屏亮屏、点按钮后重入），旧实现每次都 new 一个弹窗 ——
        // 「提示连续出现两次」就是这么来的（点掉一个、onResume 又弹一个）。
        if (runtimeSetupDialog?.isShowing == true) return

        val sizeMb = runCatching {
            RuntimeInstaller(this).readManifest().runtime.size / (1L shl 20)
        }.getOrDefault(0L)
        val sizeText = if (sizeMb > 0) "$sizeMb MB" else "~130 MB"

        // v1.6.18（用户反馈）：「这个是必选项，而不能是弹窗，不可被取消」。
        //
        // 旧实现三个问题：
        // 1. **可取消** —— 有「稍后」按钮，点它什么都不做（null listener），
        //    但引擎不会被拉起来，启动遮罩永远等不到 READY，界面就一直卡着；
        //    返回键/点外部同样能关掉这个弹窗，结果一样是卡死。
        // 2. **重复弹出** —— 见上，onResume 每此都 new 一个。
        // 3. 弹窗与遮罩叠在一起，用户不知道哪个才是「正在进行」的东西。
        //
        // 现在改成：**没有任何关闭途径**的必选面板 —— 不可取消、没有负面按钮、
        // 只有一个「下载并启动」。退到后台再回来仍是同一个（runtimeSetupDialog?.isShowing
        // 挡住重复创建）。
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.runtime_setup_title)
            .setMessage(getString(R.string.runtime_setup_message, sizeText))
            .setPositiveButton(R.string.runtime_setup_download) { _, _ ->
                EngineService.start(this)
            }
            .setNeutralButton(R.string.runtime_setup_full) { d, _ ->
                // 「获取完整包」只是引导去下载带运行时的 APK，面板不关：
                // 关掉它就回到「卡在空界面」的原状。
                (d as? AlertDialog)?.let { keepRuntimeSetupVisible(it) }
                runCatching {
                    startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(RepoConfig.RELEASES_PAGE))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
            .setCancelable(false)
            .create()
        dialog.setCanceledOnTouchOutside(false)
        // 返回键也吞掉（setCancelable(false) 已覆盖按键，这里显式再挡一次，
        // 因为有 OEM 会绕过）
        dialog.setOnKeyListener { _, keyCode, _ -> keyCode == android.view.KeyEvent.KEYCODE_BACK }
        runtimeSetupDialog = dialog
        dialog.show()
    }

    /**
     * 让必选面板保持可见：点「获取完整包」跳浏览器后回来，面板还在。
     *
     * 只是把按钮恢复成可用 —— `setPositiveButton` 的回调默认会 dismiss，
     * neutral 按钮则不会，所以这里不需要额外动作。留这个方法是为了
     * 把「面板不能消失」的意图写在调用点上。
     */
    private fun keepRuntimeSetupVisible(dialog: AlertDialog) {
        if (!dialog.isShowing) runCatching { showRuntimeSetupDialog() }
    }

    /**
     * 🔴 v1.4.56 修：点「已下载」通知只回主窗口、不弹安装 —— 根因在这里缺
     * `onNewIntent`。MainActivity 是 `singleTask`（AndroidManifest），下载完成时
     * 进程几乎必然活着，系统对已存在实例走 `onNewIntent()`（默认空实现），
     * 通知 Intent 里的 `ACTION_OPEN_INSTALL` 被直接丢弃；`onResume` 读到的
     * `intent` 还是旧实例的（action=null），判断永远不成立 → 只回到主窗口。
     * 补上 `setIntent` 后，随后必到的 `onResume` 就能吃到新 action。
     * （进程被杀后点通知冷启动原本就走 onCreate→onResume，不受影响。）
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    /** 点「已下载完成」通知进来：直接提交安装（有权限/闸门检查，见 UpdateDialogs）。 */
    private fun handleOpenInstallIntent() {
        if (intent?.action == AppUpdateDownloadService.ACTION_OPEN_INSTALL) {
            intent.action = null
            window.decorView.post {
                UpdateDialogs.startInstall(this)
            }
        }
    }

    /** 同步贴纸的可见性：胶囊收起时贴纸也要跟着消失。 */
    private fun setStickersVisible(visible: Boolean) {
        val overlay = findViewById<ViewGroup>(R.id.statusBarOverlay) ?: return
        for (i in 0 until overlay.childCount) {
            val c = overlay.getChildAt(i)
            if (c.tag == stickerTag) c.visibility = if (visible) View.VISIBLE else View.GONE
        }
    }

    /** 贴纸 View 的标记：重建时据此清理旧贴纸（类内字段，避免为此新建 companion）。 */
    private val stickerTag = "dsh_sticker"

    override fun onResume() {
        super.onResume()
        // NFC 读卡与屏幕录制都需要 Activity：把自身登记给两个 relay，
        // 让「贴标签」和「录屏授权」能真正接通（桥本身拿不到 Activity）。
        app.dsh.mobile.engine.cap.NfcForegroundRelay.attach(this)
        app.dsh.mobile.engine.cap.ScreenRecordRelay.attach(this)
        // 自动更新：回到前台补查（>24h）+ 状态弹窗；点「已下载」通知进来则直接走安装
        AppUpdateScheduler.onForeground(this)
        UpdateDialogs.maybeShow(this)
        handleOpenInstallIntent()
        // 外观 → 收起态状态栏改完返回本页时，立即按新图片/形状/尺寸重绘，
        // 不必等用户手动收起再展开才看到效果
        runCatching { findViewById<View>(R.id.handleBar)?.let { applyIslandDotImage(it) } }
        // 展开态状态栏：自定义背景图 + 贴纸，同样在返回本页时立即套用
        runCatching { applyExpandedStatusBar() }
        val currentTheme = ThemePref.get(this)
        if (appliedTheme != null && appliedTheme != currentTheme) {
            appliedTheme = currentTheme
            recreate()
            return
        }
        // 🔴 v1.2.91：恢复 WebView 的 JS 定时器。
        // 主人实测「回到后台时通过语音发送消息，消息发出去了但模型不响应，
        // 切回前台才开始响应」—— 根因就是这里缺 webView.onResume()：
        // Android 在 Activity 进入后台时会**冻结 WebView 的 JS 定时器**
        // （前端靠定时器/轮询驱动流式渲染），所以后台期间 AI 的回复渲染不出来。
        if (::webView.isInitialized) {
            runCatching { webView.onResume() }
            runCatching { webView.resumeTimers() }
            // v1.6.20：进程从 Android 冻结里解冻后，那条 WebSocket 已经半开 ——
            // 两端都以为还连着，但服务端推的增量全丢、发消息在 WS 路径上静默
            // 失败（HTTP 兜底那条却能通，所以用户看到「没发出去其实已发出」）。
            // 引擎的重连只监听 online/offline，解冻不改变网络状态，因此它永远
            // 不会自己醒。这里主动叫一次，并把「在后台待了多久」传给它：
            // 待得久（≥10s）就跳过探活直接换连接 —— 探活只能证明服务端在跑，
            // 证明不了那条 socket 还活着（第一版就栽在这个判据上，实测从未重连）。
            runCatching {
                // 把「在后台待了多久」传给插件：≥10 秒它就不探活、直接换连接
                // （探活只能证明服务端在跑，证明不了那条 socket 还活着）。
                // 首次进入没有 pausedAtElapsed，传 -1 让它按「不知道」保守处理。
                val awayMs = if (pausedAtElapsed > 0L) {
                    SystemClock.elapsedRealtime() - pausedAtElapsed
                } else {
                    -1L
                }
                pausedAtElapsed = 0L
                // 无钩子时返回 no-hook（正常：插件还没加载完，页面刚起来）。
                webView.evaluateJavascript(
                    "(window.__dshResume ? (window.__dshResume($awayMs), 'ok') : 'no-hook')",
                    null,
                )
            }
        }
        // Web 内核版本门禁（v1.2.100，酷安用户反馈引出的需求）：
        // WebView < Chrome 85 时引擎前端必白屏（??= 等解析期语法 polyfill 补不了），
        // 与其让用户看「进程异常退出/白屏」无限循环，不如先检测、给出明确升级指引，
        // 由用户决定要不要继续。见 checkWebViewGate()。
        if (AppGate.webViewState != AppGate.State.OK &&
            AppGate.webViewState != AppGate.State.OVERRIDDEN && !checkWebViewGate()
        ) {
            return   // 门禁未过：不启动引擎（引擎起了前端也渲染不了，纯粹耗电）
        }
        // 前台进入即拉起前台服务；服务存在则幂等。
        // v1.4.20：noruntime 包首启先确认下载运行时（约 130MB），不在用户不知情时
        // 直接走蜂窝下载；确认后仍由监督器的 Installing 流程显示真实进度。
        if (RuntimeInstaller(this).needsRuntimeDownload()) {
            showRuntimeSetupDialog()
        } else {
            EngineService.start(this)
        }
        // 用户已开启桌宠时恢复；未授权/未开启时这里是零开销。
        runCatching { PetOverlayService.restoreIfEnabled(this) }
        // 页面卡死看门狗（v1.2.100）：前台时才检测（后台由系统节流，探针会误判）
        startHangWatchdog()
        // 从设置页返回：语言若被改过，原地重建本页（设置页只重建了它自己，
        // 主界面返回时也得跟上，否则会出现「设置页已是新语言、主界面还是旧的」）
        val langNow = LocaleHelper.get(this)
        if (langNow != appliedLocale) {
            appliedLocale = langNow
            recreate()
            return
        }
        // 从设置页返回：重新读取朝向/缩放偏好，若被改则同步并重载
        val oldScale = pageScale
        val oldMode = orientationMode
        readUiPrefs()
        applyStatusBarImmersive()
        applyIslandInsets()
        if (oldMode != orientationMode) {
            setDesktop(shouldUseDesktop())
            applyOrientation(orientationMode)
        }
        if (oldScale != pageScale || oldMode != orientationMode) {
            webView.reload()
        }
    }

    /**
     * v1.2.91：**不**暂停 WebView。
     *
     * 默认实现（或主动调 `webView.onPause()`）会冻结 JS 定时器 —— 那正是
     * 「后台时 AI 不响应、切回前台才响应」的根因。
     * 本项目的前端需要**在后台继续驱动**（AI 回复的流式渲染、会话状态更新），
     * 而进程本身已由前台服务 + 无障碍服务保活，所以这里保持 WebView 活跃。
     *
     * ⚠️ 代价：后台时 WebView 仍会消耗少量 CPU（前端本身的轮询）。
     * 这是「后台也要能收到 AI 回复」这个需求的必要成本。
     */
    override fun onPause() {
        // v1.6.20：记下离开前台的时刻。回来后用「在后台待了多久」决定要不要
        // 强制重连 —— 这是判断「进程是否可能被系统冻结过」唯一可靠的信号。
        // 用 elapsedRealtime 而不是 currentTimeMillis：后者会被用户改时间/校时
        // 影响，而我们要的是单调递增的时长。
        pausedAtElapsed = SystemClock.elapsedRealtime()
        // 后台停看门狗：系统会节流后台 WebView，探针超时会造成误判并反复重建页面
        stopHangWatchdog()
        // 刻意不调用 super.onPause() 里的 WebView 暂停逻辑；
        // 同时显式 resumeTimers，确保切后台瞬间定时器仍在跑。
        if (::webView.isInitialized) {
            runCatching { webView.resumeTimers() }
        }
        super.onPause()
    }

    /** 读取用户持久化的屏幕朝向与缩放偏好（设置页与首次引导共用同一组 prefs） */
    private fun readUiPrefs() {
        val p = getSharedPreferences(PREFS_UI, MODE_PRIVATE)
        pageScale = p.getInt(KEY_PAGE_SCALE, DEFAULT_PAGE_SCALE)
        orientationMode = OrientationPrefs.get(this)
    }

    /**
     * v1.2.120：当前是否应该走桌面渲染。
     * landscape = 恒桌面；portrait = 恒移动；auto = 看**实际**朝向
     * （物理横屏 → 桌面布局，竖屏 → 移动布局），朝向取自屏幕几何（见 [screenIsLandscape]，
     * 不用会滞后的 `Configuration.orientation`），窗口尺寸一变就重新求值。
     *
     * ⚠️ v1.5.1（小窗字号变小）：窗口被压缩且窄于桌面断点时，一律给移动渲染。
     * 原因：桌面视口固定 `width=1280`，468dp 宽的小窗里内容被整体缩到 0.366 倍，
     * 字号只剩四成。全屏形态完全不受影响（[isWindowed] 为假时走原逻辑）。
     */
    private fun shouldUseDesktop(): Boolean {
        if (isWindowed() && windowDpWidth() < DESKTOP_MIN_WIDTH_DP) return false
        return when (orientationMode) {
            OrientationMode.LANDSCAPE -> true
            OrientationMode.PORTRAIT -> false
            OrientationMode.AUTO -> screenIsLandscape()
        }
    }

    /**
     * AUTO 模式下"屏幕当前是否横屏"。
     *
     * ## 为什么不用 `resources.configuration.orientation`
     * v1.5.1 实测（MuMu 平板，`wm size` 横→竖→横）：窗口重新布局时 WebView 已经是
     * 3840px 宽，而 `resources.configuration` 还停在上一朝向（`screenWidthDp=592`），
     * 于是 AUTO 按**旧**朝向算，模式切换被跳过；等配置真正更新过来时已经没有事件
     * 再触发一次求值 —— 页面就永久停在错误的渲染模式（1137dp 横屏上显示移动单栏）。
     *
     * ## 用屏幕几何
     * 屏幕尺寸用 `Display.getRealMetrics`（**整块屏幕**，不是窗口），显示尺寸是窗口
     * resize 的**原因**，所以它一定先于布局回调更新，不存在上面那个时间差；
     * 读的是物理屏尺寸，也不受软键盘改窗口高度影响，
     * 不会出现"弹出键盘 → 窗口变扁 → 误判成横屏"的抖动。
     *
     * 小窗/分屏"窗口窄但屏幕横"的情况不靠这里兜 —— 由 [shouldUseDesktop] 里的
     * 窗口宽度断点（[isWindowed]）先行拦住。
     */
    private fun screenIsLandscape(): Boolean {
        val real = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        val ok = runCatching { windowManager.defaultDisplay.getRealMetrics(real) }.isSuccess
        if (ok && real.widthPixels > 0 && real.heightPixels > 0) {
            return real.widthPixels > real.heightPixels
        }
        // 取不到屏幕尺寸时才退回 Configuration（冷启动极早期才会走到）
        return resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
    }

    /** 当前窗口宽度（dp）。已布局时用 WebView 实测值，否则退回 Configuration（多窗口下即窗口宽） */
    private fun windowDpWidth(): Float {
        val px = if (::webView.isInitialized && webView.width > 0) {
            webView.width
        } else {
            (resources.configuration.screenWidthDp * resources.displayMetrics.density).toInt()
        }
        return px / resources.displayMetrics.density
    }

    /**
     * 当前是否"窗口被压缩"（小窗 / 分屏 / OEM 自由窗口），即窗口明显窄于整块屏幕。
     *
     * ## 为什么不用系统现成的判据
     * vivo 自由窗口实测：`isInMultiWindowMode()` 返回 **false**，`Configuration.orientation`
     * 报 **land**（屏幕朝向），而窗口实际是 467×826dp 的竖长条 —— 两个判据都会把
     * 小窗当成"全屏横屏"，于是继续走桌面渲染。唯一可靠的是**几何**：窗口比屏幕窄。
     *
     * 屏幕尺寸必须用 [Display.getRealMetrics]（整块屏幕，不受窗口影响）；
     * `resources.displayMetrics` 在多窗口下可能已经被裁成窗口尺寸，比不出差值。
     */
    private fun isWindowed(): Boolean {
        val real = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        runCatching { windowManager.defaultDisplay.getRealMetrics(real) }
            .onFailure { return false }
        if (real.widthPixels <= 0 || real.density <= 0f) return false
        val screenDp = real.widthPixels / real.density
        return windowDpWidth() < screenDp * 0.92f
    }

    private fun setupWebView() {
        // debug 包打开 WebView 远程调试（chrome://inspect / CDP）：排障时能直接看
        // 页面控制台、读 history 状态与返回栈条数；release 包保持关闭。
        // 用 FLAG_DEBUGGABLE 而不是 BuildConfig：AGP 8 默认不生成 BuildConfig。
        val debuggable = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (debuggable) WebView.setWebContentsDebuggingEnabled(true)
        defaultUa = webView.settings.userAgentString
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false       // 关闭文件域，收窄 WebView 攻击面
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            // issue #72：语音插件的 getUserMedia 在部分 WebView 版本上要等用户手势
            // 才会 resolve，而它们的录制按钮本身就是那次手势。不关掉这个限制，
            // 权限已经 grant 了仍可能卡在 NotAllowedError。只影响自动播放策略，
            // 不涉及媒体采集权限（那个由 onPermissionRequest 管）。
            mediaPlaybackRequiresUserGesture = false
            // 竖屏页面缩小靠 viewport meta 改写（同桌面模式机制），useWideViewPort 必须开。
            useWideViewPort = true
            loadWithOverviewMode = true
            applyZoomControls(desktopMode)
        }
        // v1.4.20（issue #43）：document focus 是 navigator.clipboard.writeText 的
        // 硬条件——WebView 失焦时页面复制被 NotAllowedError 拒绝。让 WebView 可聚焦
        // 并在布局后拿到焦点，配合注入的剪贴板降级链把复制失败率压到最低。
        webView.isFocusable = true
        webView.isFocusableInTouchMode = true
        webView.requestFocus()
        // ── WebView 诊断（v1.2.49）：用户报「用不了」时能拿到确凿信息 ────────────
        // 背景：华为 Mate40 报 `AbortSignal.any is not a function`、另有 Android 16 用户
        // 报失败，但 App 此前既不捕获 WebView 错误也不转发 console，排查只能靠猜。
        // 这里把 JS 控制台消息、资源加载错误、以及 polyfill/WebView 版本自检全部落进
        // engine.log（用户可在设置里导出），日志里带 [webview] 前缀便于定位。
        webView.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onConsoleMessage(msg: android.webkit.ConsoleMessage?): Boolean {
                msg ?: return false
                val text = msg.message() ?: return false
                // 只记有价值的信息：错误/警告，或我们自己的自检标记
                val important = msg.messageLevel() >= android.webkit.ConsoleMessage.MessageLevel.WARNING ||
                    text.contains("[dsh-android]")
                if (important) {
                    logWebView("console/${msg.messageLevel()} ${msg.sourceId()}:${msg.lineNumber()} $text")
                }
                return false
            }

            /**
             * 网页媒体权限（getUserMedia）—— issue #72。
             *
             * ## 为什么必须有
             * Android WebView 的**默认实现直接拒绝**所有网页媒体请求，于是
             * `navigator.mediaDevices.getUserMedia({audio:true})` 恒返回
             * `DOMException: NotAllowedError / "Permission denied"`。
             * 第三方语音插件（`@goodandready/dsh-voice`、引擎内置
             * `dsh-experimental-client-ui-voice-input`）全靠这个 API，全部失效。
             *
             * 我们自己的语音输入走原生 `AsrManager`（不经 WebView），所以一直正常 ——
             * 这也是为什么这个缺口长期没被发现。
             *
             * ## 流程
             * ① 只处理 `RESOURCE_AUDIO_CAPTURE`，其余资源（视频/摄像头）原样拒绝；
             * ② 系统已有 `RECORD_AUDIO` → 直接 grant；
             * ③ 没有 → 弹系统权限框，结果在 `onRequestPermissionsResult` 里回到
             *    `pendingWebPermission` 再 grant/deny。
             *
             * ⚠️ 必须判空并只 grant 一次：`PermissionRequest` 是一次性的，
             * 重复调用 grant/deny 会抛 IllegalStateException（WebView 133 上实测）。
             */
            override fun onPermissionRequest(request: android.webkit.PermissionRequest?) {
                request ?: return
                // ⚠️ v1.4.46（安全审查 M2/L9）：旧实现只看「App 有没有 RECORD_AUDIO」，
                // 不校验请求页面来源。配合预览功能可打开任意回环端口，被提示注入的
                // AI 只要让 WebView 导航到某个恶意回环页面，那个页面就能静默拿到
                // 麦克风。这里只对本机引擎/回环放行，其他来源直接拒绝。
                val host = runCatching { java.net.URI(request.origin.toString()).host }.getOrNull()
                if (host != "127.0.0.1" && host != "localhost") {
                    android.util.Log.w("WebPerm", "deny media for non-loopback origin: ${request.origin}")
                    request.deny()
                    return
                }
                val wantsAudio = request.resources?.contains(
                    android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE,
                ) == true
                if (!wantsAudio) {
                    // 摄像头/视频采集目前没有使用场景，保守拒绝（不扩大攻击面）
                    request.deny()
                    return
                }
                val granted = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                if (granted) {
                    grantAudio(request)
                    return
                }
                // 挂起这次请求，等系统权限框回来
                pendingWebPermission?.deny()
                pendingWebPermission = request
                requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), REQ_WEB_AUDIO)
            }

            /**
             * 网页 <input type="file"> / WebUI 附件按钮 → 系统文件选择器。
             *
             * ⚠️ 2026-10-05（Issue #5）：此前**未实现**此回调，WebView 收到文件选择请求时
             * 无人应答，表现为「上传什么都失败、也弹不出选择窗口」（WebUI 附件功能整体不可用）。
             * Android WebView 不会自行弹选择器，必须由宿主实现本方法并通过
             * filePathCallback.onReceiveValue() 回传结果 —— 不回传则页面永远等待。
             *
             * 权限：用 ACTION_GET_CONTENT（SAF）而非直接读路径，**无需**存储权限，
             * 也不受 allowFileAccess=false 影响（我们刻意关着它收窄攻击面）。
             */
            override fun onShowFileChooser(
                view: WebView?,
                filePathCallback: android.webkit.ValueCallback<Array<Uri>>?,
                params: android.webkit.WebChromeClient.FileChooserParams?,
            ): Boolean {
                filePathCallback ?: return false
                // 上一个请求未结束（用户连点/页面切换）→ 先回传空值释放，否则 WebView 卡死
                pendingFileCallback?.onReceiveValue(null)
                pendingFileCallback = filePathCallback
                // v1.4.27（issue #54）：页面只要图片时走系统**照片选择器**——
                // 缩略图网格、原生多选、无需存储权限，比文件管理器翻目录快得多。
                // 判据是 accept 类型（页面 <input type=file accept="image/…">）。
                val intent = if (wantsOnlyImages(params)) {
                    buildPhotoPickerIntent(params)
                } else {
                    runCatching { params?.createIntent() }.getOrNull()
                        ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "*/*"
                        }
                }
                return runCatching {
                    @Suppress("DEPRECATION")
                    startActivityForResult(intent, REQ_FILE_CHOOSER)
                    true
                }.getOrElse {
                    logWebView("file chooser failed to launch: ${it.message}")
                    pendingFileCallback = null
                    filePathCallback.onReceiveValue(null)
                    false
                }
            }
        }
        // ── 下载支持（v1.4.17，issue #37）──────────────────────────────────
        // 用户报告：文件分享插件的「下载」按钮点了没反应。根因是宿主从未实现
        // DownloadListener——Android WebView 收到下载请求时若无监听器**直接静默丢弃**。
        // 且 App 只声明了 MANAGE_EXTERNAL_STORAGE（API 30 权限），Android 10 的设置里
        // 既没有「所有文件访问」也没有普通存储项，用户无从授权自救。
        //
        // 修复分两条路径（详见 WebViewDownloader）：
        //   · http(s) 直链 → 系统 DownloadManager 代写，任何 API 级别都不需要存储权限；
        //     必须把 WebView 会话的 Cookie 带上，否则下载到的是登录页 HTML。
        //   · blob:（插件的「打包下载」）→ DownloadListener 根本收不到，由注入页面的
        //     JS 桥（onPageFinished 里注入）把 blob 转 base64 交给 DshAndroidDownloader。
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            if (url.startsWith("blob:")) {
                // 理论上桥会拦掉 blob 点击；走到这里说明页面绕过了桥（如 location 赋值）
                runOnUiThread {
                    Toast.makeText(
                        this,
                        getString(R.string.download_blob_unsupported),
                        Toast.LENGTH_LONG,
                    ).show()
                }
                logWebView("download: blob URL reached listener without bridge")
                return@setDownloadListener
            }
            val err = WebViewDownloader.enqueueHttpDownload(
                this, url, userAgent, contentDisposition, mimeType,
            )
            runOnUiThread {
                Toast.makeText(
                    this,
                    getString(
                        if (err == null) R.string.download_started else R.string.download_failed,
                        err ?: "",
                    ),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
        webView.addJavascriptInterface(
            object : Any() {
                /** JS 桥回调：blob 转 base64 后落盘到公共 Download/。 */
                @android.webkit.JavascriptInterface
                fun saveBase64(mimeType: String?, base64: String?, fileName: String?): String {
                    val data = base64 ?: return "no data"
                    // 落盘在后台线程做；JS 侧同步拿回执（少量阻塞可接受，blob 通常 < 数十 MB）
                    return runCatching {
                        WebViewDownloader.saveBase64(
                            applicationContext,
                            mimeType ?: "",
                            data,
                            fileName ?: "",
                        )
                    }.getOrElse { e -> e.message ?: "save failed" }
                }
            },
            WebViewDownloader.BRIDGE_NAME,
        )
        // v1.4.20（issue #43）：剪贴板兜底桥。navigator.clipboard.writeText 在
        // WebView 里常因焦点/激活限制抛 NotAllowedError（页面又吞掉异常），
        // 桥让页面侧降级链有第二条路。调用来自 JavaBridge 线程，前台限制不适用。
        webView.addJavascriptInterface(
            object : Any() {
                @android.webkit.JavascriptInterface
                fun copyText(text: String?): Boolean {
                    val t = text ?: return false
                    return runCatching {
                        val cm = getSystemService(android.content.ClipboardManager::class.java)
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("text", t))
                        true
                    }.getOrDefault(false)
                }
            },
            "DshAndroidClipboard",
        )
        // v1.5.1（平板适配）：系统打开/分享桥。dsh-solution-explorer 的
        // 「用系统程序打开」经此把工作区文件交给 ACTION_VIEW 默认程序或
        // ACTION_SEND 选择器；失败返回 false，插件侧自动落回下载兜底。
        webView.addJavascriptInterface(
            OpenBridge(applicationContext),
            "DshAndroidOpen",
        )
        // 返回栈桥（手机形态布局插件专用）：页面把「当前能不能安全回退」上报
        // 到这里。没有这个桥时 onBackPressed 只能猜（canGoBack），一旦 WebView
        // 历史里混入跨文档条目，返回就变成整页重载。
        //
        // 调用来自 JavaBridge 线程 → 字段声明成 @Volatile；payload 解析失败
        // 就保持上次状态（宁可多一次提示，也不能退到未知条目上）。
        webView.addJavascriptInterface(
            object : Any() {
                @android.webkit.JavascriptInterface
                fun setState(payload: String?) {
                    if (payload.isNullOrBlank()) return
                    runCatching { org.json.JSONObject(payload) }.getOrNull()?.let { json ->
                        backMobile = json.optBoolean("mobile", false)
                        backConsumable = json.optBoolean("consumable", false)
                    }
                }
            },
            "DshAndroidBack",
        )
        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedError(
                view: WebView?, request: WebResourceRequest?, error: android.webkit.WebResourceError?,
            ) {
                // 主文档加载失败才是致命的；子资源失败常见（favicon 等），降级记录
                val url = request?.url?.toString() ?: "?"
                val desc = error?.description?.toString() ?: "?"
                val fatal = request?.isForMainFrame == true
                logWebView("${if (fatal) "LOAD-FAIL" else "subresource"} $url — $desc")
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return false
                // 仅允许回环导航；外部链接交给系统浏览器
                if (uri.host == "127.0.0.1" || uri.host == "localhost") return false
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                return true
            }

            /**
             * 渲染进程崩溃自愈（v1.2.100，酷安 issue #8）。
             *
             * ## 为什么必须有
             * issue 反馈：「会话内容卡住，计时器还在计数，模型仍在运行，要重启应用」——
             * 典型症状是**前端页面停止更新但后端一切正常**。WebView 的渲染进程
             * （独立沙箱进程）可能因内存压力被系统回收或自身崩溃，此时：
             *  · 不实现本回调 → Android 默认**直接杀掉宿主 App**（整个应用消失），或
             *  · 页面残留但不再刷新（用户看到「卡住」）
             * 两种都不是我们想要的：引擎由前台服务持有，本该继续跑。
             *
             * ## 做法
             * 返回 true 表示「我自己处理」，阻止系统杀 App；随后**重建 WebView 并重载**，
             * 用户视角是页面自动恢复（引擎没重启，会话与进行中的任务都在）。
             */
            override fun onRenderProcessGone(
                view: WebView?,
                detail: android.webkit.RenderProcessGoneDetail?,
            ): Boolean {
                logWebView(
                    "render process gone: crashed=${detail?.didCrash()} " +
                        "priority=${detail?.rendererPriorityAtExit()} — recovering"
                )
                recoverWebView()
                return true   // 已自行处理，别杀 App
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // v1.4.51：页面加载完成是 OEM 系统重置系统栏的高发时机
                // （用户实测「有些系统不会自动隐藏状态栏」）。重新断言一次。
                scheduleImmersiveReassert()
                if (view == null) return
                // v1.6.0：预热占位页（about:blank）不算「页面可用」。
                // 它只是为了提前建起 Chromium 进程与渲染管线，此时引擎还没起，
                // 若继续走下去会：① dismissSplash() 提前收遮罩露出空 WebView；
                // ② 对 about:blank 注入插件桥（无意义且可能报错）；
                // ③ BootTrace 误记首屏时间。所以到这里直接返回。
                if (isPreheatNavigation(url)) {
                    logWebView("preheat page finished (engine still starting)")
                    return
                }
                // v1.6.16：清掉预热留下的 about:blank 历史条目。
                // 必须在这里（真实页面加载完成、且已排除预热页）调用 —— 见
                // dropPreheatHistory 的注释，早调清不掉。
                dropPreheatHistory()
                // 环境自检：把 WebView 版本与关键 API 存在性写进日志，用户报障时一眼定位
                view.evaluateJavascript(WEBVIEW_SELFCHECK_JS, null)
                // v1.4.17（issue #37）：注入 blob 下载桥。每次页面加载后都要重新注入
                //（导航/刷新会重置页面 JS 环境）。桥只拦 a[download] 且 href 为 blob:
                // 的点击——插件「打包下载」走的就是这条路。
                runCatching {
                    view.evaluateJavascript(WebViewDownloader.bridgeJs(), null)
                }.onFailure { logWebView("blob bridge inject failed: ${it.message}") }
                // v1.4.20（issue #43）：剪贴板降级链注入——writeText 失败（焦点/激活
                // 被拒）时回退原生桥 DshAndroidClipboard.copyText，再回退
                // execCommand('copy')。无 clipboard API 的老内核直接补一个可用的。
                runCatching {
                    view.evaluateJavascript(CLIPBOARD_FALLBACK_JS, null)
                }.onFailure { logWebView("clipboard bridge inject failed: ${it.message}") }
                // v1.4.24：社区 dsh-web-mobile 覆盖层已移除（插件本身撤掉了，改回
                // 自有窄屏插件）。但强调色仍要传给 WebUI：部分插件读 --dsh-accent。
                runCatching {
                    val hex = String.format("#%06X", Accent.current(this@MainActivity).argb and 0xFFFFFF)
                    view.evaluateJavascript(
                        "document.documentElement.style.setProperty('--dsh-accent', '$hex');",
                        null,
                    )
                }.onFailure { logWebView("accent var inject failed: ${it.message}") }
                // v1.4.27（issue #53）：移动端键盘行为修正——触屏窄屏下把
                // 「Enter（无修饰键）」改写成「Shift+Enter」，让软键盘回车换行、
                // 发送交给界面按钮。桌面/外接键盘不受影响。
                runCatching {
                    val js = assets.open("js/mobile-keys.js")
                        .use { it.readBytes().toString(Charsets.UTF_8) }
                    view.evaluateJavascript(js, null)
                }.onFailure { logWebView("mobile keys inject failed: ${it.message}") }
                // v1.6.17：把外部共享进来的文件交给 WebUI。
                //
                // 时机必须在 onPageFinished 之后（页面 JS 环境已就绪、插件已注入），
                // 否则 evaluateJavascript 打进去没人接。drain() 取走即清空队列 ——
                // 注入失败时文件仍在 files/inbox/，用户可以手动让 AI 读那个目录，
                // 所以这里失败只记日志，不重试（重试会导致同一文件被塞两次）。
                runCatching { deliverInboxFiles(view) }
                    .onFailure { logWebView("inbox delivery failed: ${it.message}") }
                // v1.6.15：整页浮层的返回键兜底。
                //
                // WebUI 是单页应用，切页面不往浏览器历史写记录（实测前端 bundle 里
                // pushState/popstate 出现 0 次）。而 onBackPressed 是「能后退就后退，
                // 不能就退出应用」—— 于是在插件页 / 自动化任务页 / SSH 工作区这类
                // 整页界面上按返回会一路退出到桌面。这段脚本给整页浮层补历史条目，
                // 让 canGoBack() 在这些界面上为 true，Kotlin 侧无需改动。
                runCatching {
                    val js = assets.open("js/back-guard.js")
                        .use { it.readBytes().toString(Charsets.UTF_8) }
                    view.evaluateJavascript(js, null)
                }.onFailure { logWebView("back guard inject failed: ${it.message}") }
                // v1.4.6：视口缩放必须在**启动遮罩收起之前**定稿，并在遮罩之下整页
                // 重栅格化一次。旧顺序是「先 dismissSplash()、再改 meta」——用户会先看到
                // 按 initial-scale=1 画好的首屏（含登录/开始使用弹窗），紧接着整页缩放
                // 突变；WebView 会把部分合成层留在旧栅格里，弹窗被拉宽/截断、文字与按钮
                // 错位（复现截图与判定证据见本 PR 描述）。
                // v1.3.1 的约束仍然满足：收遮罩在两个渲染分支之后统一执行，不会提前 return。
                applyViewport(view)
                // v1.4.8：首屏 Paint 是用户体感的一部分 —— 引擎 healthy 到页面可用之间
                // 还有加载 WebUI 与执行客户端插件的空档。补记进 boot.log 便于对比。
                BootTrace.noteWebViewLoaded(this@MainActivity)
                dismissSplash()
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                updatePreviewChrome(url)
            }
        }
    }

    /**
     * 预览 chrome：WebView 导航到非引擎端口的回环页面（用户点击 AI 在对话里给的
     * http://127.0.0.1:PORT 链接）时，状态栏切预览模式并亮出返回按钮；
     * 回到引擎主界面自动恢复。AI 无需任何特殊协议，输出普通链接即可。
     */
    private fun updatePreviewChrome(url: String?) {
        val uri = url?.let { Uri.parse(it) } ?: return
        val loopback = uri.host == "127.0.0.1" || uri.host == "localhost"
        val enginePort = (application as DshApp).supervisor.healthyPort
        val preview = loopback && uri.port != enginePort
        findViewById<View>(R.id.btnBack).visibility = if (preview) View.VISIBLE else View.GONE
        if (preview) statusBar.text = getString(R.string.status_preview, uri.port)
    }

    /**
     * 预热 WebView（v1.6.0）。
     *
     * ## 为什么
     * 冷启动的时序原本是「引擎全部就绪 → 才 loadUrl」，于是 Chromium 进程创建、
     * JS 引擎初始化、合成器建立（实测约 700ms）全被排在引擎之后串行叠加。
     * 但这三件事**不依赖引擎**，只有页面内容依赖。
     *
     * 实测证据（logcat，同一次冷启）：
     * ```
     * 06:37:06.491 engine healthy on :3080
     * 06:37:06.508 chromium android_setCpu already initialized   ← 晚 17ms
     * ```
     * 即 Chromium 确实是在引擎 healthy 之后才开始初始化。
     *
     * ## 做什么
     * 加载 `about:blank`：不联网、不读文件、不触发任何插件注入，
     * 只让 Chromium 把进程与渲染管线建起来。等引擎 Healthy 后
     * `loadLocalUrl(realUrl)` 时已是热态。
     *
     * ## 风险与规避
     * 预热页同样会触发 `onPageFinished`，那里会 `dismissSplash()`。
     * 若不加区分，遮罩会在引擎未就绪时就被收掉，用户直接看到空白 WebView。
     * 因此 `onPageFinished` 里用 [webViewPreheated] + URL 判定跳过预热页。
     */
    private fun preheatWebView() {
        if (webViewPreheated) return
        webViewPreheated = true
        runCatching {
            // about:blank 是唯一零依赖的目标：不需要 assets、不需要网络、
            // 不走 shouldInterceptRequest，且 onPageFinished 会正常回调。
            webView.loadUrl(preheatUrl)
        }.onFailure { logWebView("preheat failed: ${it.message}") }
    }

    /**
     * 当前这次 onPageFinished 是否属于预热页（应跳过遮罩关闭与所有页面注入）。
     *
     * 只认 `about:blank`：真正 WebUI 的 URL 一定是 `http://127.0.0.1:3080/...`。
     */
    private fun isPreheatNavigation(url: String?): Boolean {
        if (!webViewPreheated) return false
        if (url.isNullOrEmpty()) return false
        return url == preheatUrl || url == "about:blank"
    }

    /** 统一的回环页加载入口：缩放统一由 onPageFinished 的 viewport meta 接管，这里只导航。 */
    private fun loadLocalUrl(url: String) {
        webView.loadUrl(url)
    }

    /**
     * 把预热留下的 `about:blank` 历史条目清掉。
     *
     * 不清的后果有两个，都是用户可见的：
     * 1. **右滑返回变白屏** —— 返回目标是空白页，界面整块变白（本次报障）；
     * 2. 返回键要按两次才退出应用 —— 一次退到空白页，一次才真退出。
     *
     * 调用时机很关键：必须在真实页面**加载完成后**调用。`loadUrl` 刚返回时
     * 新条目还没提交进历史，此时 `clearHistory()` 清不掉任何东西（历史里只有
     * 预热页那一条，清完反而让页面变成无历史状态）。所以放在 `onPageFinished`
     * 里、确认不是预热页之后再调。
     *
     * `clearHistory()` 只是**不可见地**抹掉历史，不触发导航，不重载页面，
     * 用户不会感知到任何闪动。
     */
    private fun dropPreheatHistory() {
        if (!webViewPreheated) return
        if (preheatHistoryDropped) return
        val list = runCatching { webView.copyBackForwardList() }.getOrNull() ?: return
        if (list.size <= 1) {
            // 历史里只有当前页 —— 预热条目已被 WebView 自己合并掉，无需处理
            preheatHistoryDropped = true
            return
        }
        val first = list.getItemAtIndex(0)
        val firstUrl = first?.url
        if (firstUrl == preheatUrl || firstUrl == "about:blank") {
            runCatching { webView.clearHistory() }
                .onSuccess {
                    preheatHistoryDropped = true
                    logWebView("dropped preheat history entry")
                }
                .onFailure { logWebView("clearHistory failed: ${it.message}") }
        } else {
            // 首条不是预热页：说明这次导航没经过 about:blank，不必清
            preheatHistoryDropped = true
        }
    }

    /** 手势缩放开关：竖屏关闭（锁死固定全屏，禁止双指捏合/拖动移动），桌面模式开启（保留双指缩放）。
     *  displayZoomControls 恒 false，只保留捏合不显示 +/- 浮层按钮。 */
    private fun applyZoomControls(enable: Boolean) {
        webView.settings.apply {
            setSupportZoom(enable)
            builtInZoomControls = enable
            displayZoomControls = false
        }
    }

    /** 只改桌面渲染的 UA + 手势缩放开关，不 reload——reload 统一由旋转/onResume 回调做（避免双重整页重载卡顿）。
     *  viewport 改写统一在 onPageFinished 里做。 */
    private fun setDesktop(enable: Boolean) {
        desktopMode = enable
        webView.settings.userAgentString = if (enable) DESKTOP_UA else defaultUa
        applyZoomControls(enable)
    }

    /** 工具栏收起/唤回：收起时网页全屏，仅留顶部灵动岛提示可恢复 */
    // ================= 灵动岛靠边停靠（v1.5.6） =================
    //
    // 顶部停靠走原有 XML（statusBarRow + handleBar）；贴左/右长边时改用代码建的
    // 竖排面板 IslandSidePanel。两者互斥：切到侧边就把 XML 那套藏起来，切回顶部
    // 再按原样恢复，避免两套视图同时出现。
    private var islandDock: IslandDock = IslandDock.TOP
    private var sidePanel: IslandSidePanel? = null
    /** 缓存的侧面板是按哪个停靠边构造的；与当前 dock 不一致时必须重建。 */
    private var sidePanelDock: IslandDock? = null
    private var sideHost: android.widget.FrameLayout? = null

    private fun setupIslandDock() {
        islandDock = CustomVisualPref.islandDock(this)
        // 长按把手循环切换停靠边：顶部 → 左 → 右 → 顶部
        findViewById<View>(R.id.handleBar)?.setOnLongClickListener {
            val next = when (islandDock) {
                IslandDock.TOP -> IslandDock.LEFT
                IslandDock.LEFT -> IslandDock.RIGHT
                IslandDock.RIGHT -> IslandDock.TOP
            }
            CustomVisualPref.setIslandDock(this, next)
            islandDock = next
            applyDock()
            Toast.makeText(
                this,
                getString(R.string.island_dock_switched, next.name.lowercase()),
                Toast.LENGTH_SHORT,
            ).show()
            true
        }
        applyDock()
    }

    private fun applyDock() {
        val container = findViewById<View>(R.id.statusBarOverlay)?.parent as? android.view.ViewGroup
        val overlay = findViewById<View>(R.id.statusBarOverlay)
        val handle = findViewById<View>(R.id.handleBar)
        if (container == null || overlay == null || handle == null) return

        val dm = resources.displayMetrics
        val pw = container.width.takeIf { it > 0 } ?: dm.widthPixels
        val ph = container.height.takeIf { it > 0 } ?: dm.heightPixels
        val ratio = CustomVisualPref.islandAlongRatio(this)

        if (islandDock == IslandDock.TOP) {
            sideHost?.visibility = View.GONE
            // 从侧边拖回顶部 = 恢复原本的灵动岛：顶部横向胶囊出现、把手收起。
            overlay.visibility = View.VISIBLE
            findViewById<View>(R.id.statusBarRow)?.visibility = View.VISIBLE
            setStickersVisible(true)
            // 先把把手按顶部长方形摆好（它此刻不可见）：收起工具栏时直接显示成
            // 圆角长方形，不再回退成原来的圆圈。
            placeCollapsedHandle(handle, IslandDock.TOP, pw, ph, ratio)
            handle.visibility = View.GONE
            return
        }

        // 贴长边：顶部横条收起来，把手做成竖条贴边
        overlay.visibility = View.GONE
        // ⚠️ 侧面板是按构造时的 dock 算圆角的（贴边侧平直、外侧圆角）。用户从
        // 一条边拖到另一条边之后，缓存里的旧面板仍按旧 dock 绘制 —— 表现就是
        // 「换到另一边展开，圆角跟着反了」。停靠边一变就丢弃重建。
        if (sidePanelDock != islandDock) {
            sidePanel = null
            sidePanelDock = islandDock
        }
        placeCollapsedHandle(handle, islandDock, pw, ph, ratio)
        handle.visibility = View.VISIBLE
    }

    /**
     * 摆放收起态把手（v1.5.8）。
     *
     * 三种停靠共用一套规则，只有贴边那一侧和尺寸不同：
     *   贴顶   44×26dp，**上侧两角平直**（贴着状态栏），下侧 10dp 圆角；
     *   贴左   26×44dp，**左侧两角平直**，右侧 10dp 圆角；
     *   贴右   26×44dp，**右侧两角平直**，左侧 10dp 圆角。
     *
     * 位置用 margin 摆（不用 gravity 居中），这样沿边位置（ratio）才能生效：
     * 用户把把手拖到边上的任意位置，松手吸附后**停在该位置**，不再永远居中。
     */
    private fun placeCollapsedHandle(
        handle: View,
        dock: IslandDock,
        parentW: Int,
        parentH: Int,
        ratio: Float,
    ) {
        val d = resources.displayMetrics.density
        val wDp = if (dock == IslandDock.TOP) IslandChrome.COLLAPSED_H_DP else IslandChrome.COLLAPSED_W_DP
        val hDp = if (dock == IslandDock.TOP) IslandChrome.COLLAPSED_W_DP else IslandChrome.COLLAPSED_H_DP
        val w = (wDp * d).toInt()
        val h = (hDp * d).toInt()
        val safe = (IslandChrome.EDGE_SAFE_DP * d).toInt()
        // 贴顶时上沿贴到状态栏下方（与原有 10dp 一致），贴边时让出屏幕圆角安全区
        val topBase = (10 * d).toInt()
        val x: Int
        val y: Int
        when (dock) {
            IslandDock.TOP -> {
                x = (ratio * (parentW - w)).toInt().coerceIn(0, (parentW - w).coerceAtLeast(0))
                y = topBase
            }
            IslandDock.LEFT -> {
                x = 0
                val minY = safe
                y = (minY + ratio * ((parentH - h - safe) - minY).coerceAtLeast(0)).toInt()
            }
            IslandDock.RIGHT -> {
                x = (parentW - w).coerceAtLeast(0)
                val minY = safe
                y = (minY + ratio * ((parentH - h - safe) - minY).coerceAtLeast(0)).toInt()
            }
        }
        (handle.layoutParams as? android.widget.FrameLayout.LayoutParams)?.let { lp ->
            lp.gravity = android.view.Gravity.TOP or android.view.Gravity.START
            lp.leftMargin = x
            lp.topMargin = y
            lp.width = w
            lp.height = h
            handle.layoutParams = lp
        }
        handle.background = collapsedHandleBackground(dock)
    }

    /**
     * 收起态把手背景：贴哪条边，哪一侧就是直角，另一侧 10dp 圆角。
     * （用户：「贴屏幕的那一侧不用圆角，离边时四边都要圆」—— 离边那种由
     *  floatingHandleBackground() 负责。）
     */
    private fun collapsedHandleBackground(dock: IslandDock): android.graphics.drawable.GradientDrawable {
        val r = IslandChrome.outerRadiusPx(expanded = false, density = resources.displayMetrics.density).toFloat()
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            setColor(0xFF000000.toInt())
            cornerRadii = when (dock) {
                IslandDock.TOP -> floatArrayOf(0f, 0f, 0f, 0f, r, r, r, r)
                IslandDock.LEFT -> floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
                IslandDock.RIGHT -> floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
            }
        }
    }

    private fun dp26(): Int = (26 * resources.displayMetrics.density).toInt()

    /** 贴边把手：贴边侧圆角 0、外凸侧 10dp，与设计稿一致。 */
    private fun sideHandleBackground(): android.graphics.drawable.GradientDrawable {
        val d = resources.displayMetrics.density
        val r = IslandChrome.outerRadiusPx(expanded = false, density = d).toFloat()
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            setColor(0xFF000000.toInt())
            cornerRadii = if (islandDock == IslandDock.LEFT)
                floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
            else floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
            // 不描边：贴屏幕那一侧若画描边，会沿边露出一条亮线
        }
    }

    // ================= 运行模式悬浮窗（v1.5.8） =================
    //
    // 用户明确要「悬浮窗不是弹窗」：所以不走 AlertDialog，而是在根容器里加一层
    // 浮层——透明接收层负责「点空白收起」，卡片自己贴在侧面板旁边。
    private var runModeHost: android.widget.FrameLayout? = null

    private fun showRunModeOverlay() {
        val container = findViewById<View>(R.id.statusBarOverlay)?.parent as? ViewGroup ?: return
        hideRunModeOverlay()
        val host = android.widget.FrameLayout(this).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        val scrim = View(this).apply {
            setBackgroundColor(0x00000000)
            isClickable = true
            setOnClickListener { hideRunModeOverlay() }
        }
        host.addView(scrim, android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        val card = RunModeOverlay(this).build { pick ->
            hideRunModeOverlay()
            RunModeOverlay.apply(this, pick)
        }
        val cardLp = android.widget.FrameLayout.LayoutParams(
            (250 * resources.displayMetrics.density).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = if (islandDock == IslandDock.RIGHT)
                android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
            else android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
            // 贴边侧留一点缝，别把侧面板整个盖住
            if (islandDock == IslandDock.RIGHT) rightMargin = dpV(8) else leftMargin = dpV(8)
        }
        host.addView(card, cardLp)
        container.addView(host)
        runModeHost = host
        host.alpha = 0f
        host.animate().alpha(1f).setDuration(140L).start()
    }

    private fun hideRunModeOverlay() {
        val h = runModeHost ?: return
        runModeHost = null
        (h.parent as? ViewGroup)?.removeView(h)
    }

    /** 收起侧面板但不触发"展开"分支（拖动/开浮层时需要强制关）。 */
    private fun closeSidePanelNow() {
        val h = sideHost ?: return
        if (h.visibility != View.VISIBLE) return
        h.animate().alpha(0f).setDuration(120L).withEndAction {
            h.visibility = View.GONE
            findViewById<View>(R.id.handleBar)?.visibility = View.VISIBLE
        }.start()
    }

    private fun dpV(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toggleSidePanel() {
        val existing = sideHost
        if (existing != null && existing.visibility == View.VISIBLE) {
            existing.animate().alpha(0f).setDuration(120L).withEndAction {
                existing.visibility = View.GONE
                findViewById<View>(R.id.handleBar)?.visibility = View.VISIBLE
            }.start()
            return
        }
        val panel = sidePanel ?: IslandSidePanel(this, islandDock).also { sp ->
            sp.onCollapse = { runOnUiThread { toggleSidePanel() } }
            // 先收起侧面板再开运行模式浮层：否则面板会从卡片左边露出一条，
            // 看起来像"两个浮层叠在一起"（真机截图一眼可见）。
            sp.onRunMode = { runOnUiThread { closeSidePanelNow(); showRunModeOverlay() } }
            sp.onRestart = {
                urlLoaded = false
                (application as DshApp).supervisor.restart()
                Toast.makeText(this, getString(R.string.engine_restarting), Toast.LENGTH_SHORT).show()
            }
            sidePanel = sp
            sidePanelDock = islandDock
        }
        val container = findViewById<View>(R.id.statusBarOverlay)?.parent as? android.view.ViewGroup ?: return
        val host = existing ?: android.widget.FrameLayout(this).also { h ->
            h.layoutParams = android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            container.addView(h)
            sideHost = h
        }
        host.removeAllViews()
        // 全屏接收层：面板之外点一下也收起（与灵动岛「点空白收起」一致）。
        // 用透明 scrim 而不是全屏遮罩，避免挡住网页观感；它只负责吞点击。
        val scrim = View(this).apply {
            setBackgroundColor(0x00000000)
            isClickable = true
            setOnClickListener { runOnUiThread { toggleSidePanel() } }
        }
        host.addView(scrim, android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        val content = panel.build()
        host.addView(content, android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = if (islandDock == IslandDock.LEFT)
                android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
            else android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
        })
        val dm = resources.displayMetrics
        // ⚠️ 面板高度必须按**内容**量出来再夹到 70% 屏高。
        // 之前传的是屏幕高度，被 clamp 成 70% 后 ScrollView 填满，
        // 结果面板下半截一大片空白（真机截图一眼可见）。
        val wPx = (IslandChrome.PANEL_W_DP * dm.density).toInt()
        content.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(wPx, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED),
        )
        val m = IslandChrome.expanded(
            dock = islandDock,
            screenW = dm.widthPixels,
            screenH = dm.heightPixels,
            density = dm.density,
            contentH = content.measuredHeight,
            yCenter = dm.heightPixels / 2,
        )
        val lp = host.layoutParams as android.widget.FrameLayout.LayoutParams
        lp.width = ViewGroup.LayoutParams.MATCH_PARENT
        lp.height = ViewGroup.LayoutParams.MATCH_PARENT
        lp.gravity = android.view.Gravity.TOP or android.view.Gravity.START
        lp.leftMargin = 0; lp.rightMargin = 0
        host.layoutParams = lp
        // 面板内容自己量出来的宽高（m）apply 到 content 上
        (content.layoutParams as? android.widget.FrameLayout.LayoutParams)?.let { clp ->
            clp.width = m.w; clp.height = m.h
            content.layoutParams = clp
        }
        host.visibility = View.VISIBLE
        host.alpha = 0f
        host.animate().alpha(1f).setDuration(120L).start()
        val headText = findViewById<android.widget.TextView>(R.id.statusBar)?.text?.toString()
        panel.update(
            state = headText.orEmpty().ifBlank { getString(R.string.overlay_idle) },
            // 副标题留空：停靠方位是实现细节，不是用户关心的状态。
            detail = "",
            dotColor = 0xFF7DD3FC.toInt(),
        )
        findViewById<View>(R.id.handleBar)?.visibility = View.GONE
    }

    private fun toggleToolbar() {
        // 贴长边时展开的是竖排面板（见 IslandSidePanel），与顶部横条是两套形态。
        if (islandDock != IslandDock.TOP) { hideRunModeOverlay(); toggleSidePanel(); return }
        val row = findViewById<View>(R.id.statusBarRow)
        val handle = findViewById<View>(R.id.handleBar)
        if (row.visibility == View.VISIBLE) {
            // 合拢：岛缩小淡出 → 把手淡入（灵动岛收起的观感）
            // width 此刻有效（row 可见），但布局未完成时仍可能是 0 —— 兜底居中。
            row.pivotX = if (row.width > 0) row.width / 2f else resources.displayMetrics.widthPixels / 2f
            row.pivotY = 0f
            row.animate().scaleX(0.6f).scaleY(0.2f).alpha(0f).setDuration(160L)
                .withEndAction {
                    row.visibility = View.GONE
                    row.scaleX = 1f; row.scaleY = 1f; row.alpha = 1f
                    handle.alpha = 0f
                    // ⚠️ v1.4.54：上一次拖拽留下的 translation 会把圆推离挖孔，
                    // 收起时归零（拖拽是临时手感，不该影响对齐）
                    handle.translationX = 0f
                    handle.translationY = 0f
                    // 收起后显示的是「圆角长方形」把手（贴边侧平直），不再是圆圈：
                    // 用户明确反馈顶部模式仍然是圆圈，所以这里按当前停靠边重画。
                    placeCollapsedHandle(
                        handle, islandDock,
                        (handle.parent as? View)?.width ?: resources.displayMetrics.widthPixels,
                        (handle.parent as? View)?.height ?: resources.displayMetrics.heightPixels,
                        CustomVisualPref.islandAlongRatio(this),
                    )
                    handle.visibility = View.VISIBLE
                    // GONE → VISIBLE 期间 insets 回调可能已经跑过（那时设的
                    // layoutParams 对 GONE 视图不生效），这里补一次对齐
                    handle.requestApplyInsets()
                    // 贴纸是胶囊上的装饰，胶囊收起后必须一起消失，
                    // 否则会孤零零浮在顶部
                    setStickersVisible(false)
                    handle.animate().alpha(1f).setDuration(140L).start()
                }.start()
        } else {
            // 展开：从把手位置撑开成岛（灵动岛展开的观感）
            handle.animate().scaleX(0.4f).scaleY(0.4f).alpha(0f).setDuration(120L)
                .withEndAction {
                    handle.visibility = View.GONE
                    handle.scaleX = 1f; handle.scaleY = 1f; handle.alpha = 1f
                    // 展开态不需要对齐挖孔（全宽胶囊本来就罩住顶部），
                    // 但下次收起要重新按当前挖孔算位置，故清掉临时位移
                    handle.translationX = 0f
                    handle.translationY = 0f
                    row.scaleX = 0.7f; row.scaleY = 0.4f; row.alpha = 0f
                    row.visibility = View.VISIBLE
                    // ⚠️ 必须等一次布局后再取 width：GONE → VISIBLE 的同一帧里
                    // row.width 还是 0，pivotX 会被算成 0.5px，岛就从左上角展开
                    // 而不是从中心撑开。post 到下一帧即可拿到真实宽度。
                    setStickersVisible(true)
                    row.post {
                        row.pivotX = row.width / 2f
                        row.pivotY = 0f
                        // ★不能硬用 alpha(1f)：那会把「活跃度透明度」覆盖掉
                        //   （引擎空闲时岛本应退到 0.55）。用当前状态的目标值收尾，
                        //   否则每次手动收起再展开，岛都会变回满不透明。
                        val targetAlpha = islandIdleAlphaFor(lastRenderedState)
                        row.animate().scaleX(1f).scaleY(1f).alpha(targetAlpha)
                            .setDuration(180L)
                            .setInterpolator(android.view.animation.OvershootInterpolator(0.9f))
                            .start()
                    }
                }.start()
        }
    }

    /**
     * 把手拖拽：按住可在父容器内自由移动（clamp 保住 60% 宽度在屏内，防止拖丢），
     * 松手时若位移小于 touch slop（约 12px）则视为点击 → 唤回工具栏。
     * 位置仅本次隐藏期间保持（重显工具栏后再收起回到上次位置，rotation/重建回默认）。
     */
    /**
     * 展开态状态栏的外观：自定义背景图 + 用户贴的贴纸（v1.5.x）。
     *
     * 贴纸放在 `statusBarOverlay` 这层 FrameLayout 里 —— 它与胶囊共用一个容器，
     * 位置尺寸完全一致，所以贴纸的相对坐标直接乘容器宽高即可，不必关心
     * 状态栏被 insets 下移了多少。
     *
     * 贴纸不消费触摸，不会挡住胶囊上的返回/重启/设置按钮。
     */
    private fun applyExpandedStatusBar() {
        val row = findViewById<View>(R.id.statusBarRow) ?: return
        val overlay = findViewById<ViewGroup>(R.id.statusBarOverlay) ?: return

        // —— 背景图：没有自定义图时回到默认胶囊 ——
        val bgFile = CustomVisualPref.expandedBgFile(this)
        val bmp = bgFile?.let {
            runCatching { android.graphics.BitmapFactory.decodeFile(it.absolutePath) }.getOrNull()
        }
        if (bmp != null) {
            row.background = android.graphics.drawable.BitmapDrawable(resources, bmp).apply {
                gravity = android.view.Gravity.FILL
            }
        } else {
            row.setBackgroundResource(R.drawable.bg_dynamic_island)
        }

        // —— 贴纸：清掉上一轮，再按当前列表重建 ——
        for (i in overlay.childCount - 1 downTo 0) {
            if (overlay.getChildAt(i).tag == stickerTag) overlay.removeViewAt(i)
        }
        val items = StickerPref.list(this)
        if (items.isEmpty()) return
        overlay.post {
            val w = overlay.width
            val h = overlay.height
            if (w <= 0 || h <= 0) return@post
            items.forEach { st ->
                val stickerBmp = StickerPref.fileOf(this, st)?.let {
                    runCatching {
                        android.graphics.BitmapFactory.decodeFile(it.absolutePath)
                    }.getOrNull()
                } ?: return@forEach
                val sizePx = (st.sizeDp * resources.displayMetrics.density).toInt()
                val iv = ImageView(this)
                iv.setImageBitmap(stickerBmp)
                iv.layoutParams = android.widget.FrameLayout.LayoutParams(sizePx, sizePx)
                // 上限必须夹到 >= 0：贴纸边长可以大于容器高（32dp 贴纸 vs 44dp 高，
                // 放大到 64dp 时 h-sizePx 为负），coerceIn(0f, 负数) 会直接抛
                // IllegalArgumentException 把主界面搞崩。
                iv.x = (st.xRatio * w - sizePx / 2f)
                    .coerceIn(0f, maxOf(0f, (w - sizePx).toFloat()))
                iv.y = (st.yRatio * h - sizePx / 2f)
                    .coerceIn(0f, maxOf(0f, (h - sizePx).toFloat()))
                iv.tag = stickerTag
                overlay.addView(iv)
            }
        }
    }

    /**
     * 收起态灵动岛的渲染：统一交给 [IslandDotRenderer]，与设置子页里的预览
     * 用同一套代码，保证"预览长什么样、收起就什么样"。
     */
    private fun applyIslandDotImage(handle: View) {
        // 贴长边时把手是 26×44dp 的圆角长方形，背景必须由 sideHandleBackground()
        // 画（贴边侧零圆角）；这里若再套圆点渲染器，会把它换成椭圆环 ——
        // 实测就是「四边全圆角」。所以侧边停靠时整段跳过。
        // 收起态统一是圆角长方形（贴边侧平直），不再用圆点渲染器 ——
        // 用户明确「灵动岛模式仍然还是圆圈」是错的，要的是长方形圆角。
        return
        // applySize = false：真机圆点的尺寸由 applyIslandInsets 统一管理
        // （那里还要考虑挖孔对齐与不溢出屏幕），两处都设会互相覆盖。
        IslandDotRenderer.apply(this, handle, applySize = false)
    }

    private fun setupHandleBar() {
        val handle = findViewById<View>(R.id.handleBar)
        applyIslandDotImage(handle)
        val slop = (6 * resources.displayMetrics.density).toFloat()
        var rawX0 = 0f; var rawY0 = 0f
        var initTx = 0f; var initTy = 0f
        var moved = false
        handle.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    rawX0 = ev.rawX; rawY0 = ev.rawY
                    initTx = v.translationX; initTy = v.translationY
                    moved = false
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dxm = ev.rawX - rawX0; val dym = ev.rawY - rawY0
                    if (!moved && dxm * dxm + dym * dym > slop * slop) {
                        moved = true
                        // 拖离边缘时四角全圆：贴边那侧的直角只在贴边时有意义，
                        // 悬空时留直角会像"被切掉一块"。
                        if (islandDock != IslandDock.TOP) {
                            v.background = floatingHandleBackground()
                        }
                    }
                    if (moved) {
                        val parent = v.parent as View
                        // ⚠️ 范围要按视图当前的 left/top 换算成「相对位移」：
                        // 原来 translationY 下限写死 0，侧边停靠时把手本来就靠垂直居中，
                        // 于是**永远拖不到顶部**（用户反馈「拖不到上方，恢复不了原来的灵动岛」）。
                        val minTx = -v.left.toFloat()
                        val maxTx = (parent.width - v.width - v.left).toFloat()
                        val minTy = -v.top.toFloat()
                        val maxTy = (parent.height - v.height - v.top).toFloat()
                        v.translationX = (initTx + dxm).coerceIn(minTx, maxTx.coerceAtLeast(minTx))
                        v.translationY = (initTy + dym).coerceIn(minTy, maxTy.coerceAtLeast(minTy))
                    }
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    val dx = ev.rawX - rawX0; val dy = ev.rawY - rawY0
                    if (!moved || dx * dx + dy * dy < slop * slop) {
                        toggleToolbar()
                    } else {
                        snapHandleToEdge(v)
                    }
                    true
                }
                android.view.MotionEvent.ACTION_CANCEL -> {
                    if (moved) snapHandleToEdge(v)
                    true
                }
                else -> false
            }
        }
    }

    /**
     * 松手后吸附最近的边，并据此切换停靠形态（v1.5.7）。
     *
     * 用户要求：「拖离边缘时自适应变圆角；拖到左边/右边自适应调整；拖到上方
     * 能恢复成原来的灵动岛样式」。所以停靠边不再只靠长按循环切换 ——
     * **拖动落点说了算**：
     *   距顶边最近 → TOP（恢复顶部横向灵动岛，藏起侧边面板）
     *   距左边最近 → LEFT（贴左竖条，贴边侧平直）
     *   距右边最近 → RIGHT（贴右竖条，镜像）
     * 没有"撒手不管"的分支：任何落点都会吸到最近的一条边。
     */
    private fun snapHandleToEdge(v: View) {
        val parent = v.parent as? View ?: return
        val cx = v.left + v.translationX + v.width / 2f
        val cy = v.top + v.translationY + v.height / 2f
        val pw = parent.width.toFloat()
        val dLeft = cx
        val dRight = pw - cx
        val dTop = cy
        val next = when {
            dTop <= dLeft && dTop <= dRight -> IslandDock.TOP
            dLeft <= dRight -> IslandDock.LEFT
            else -> IslandDock.RIGHT
        }
        // 沿边位置：贴顶取水平比例，贴左/右取垂直比例 —— 用户要求「边缘位置
        // 不是固定的，可以自己调，会自动吸边缘」，所以吸附只作用于贴边那一维，
        // 另一维保留落点。
        val d = resources.displayMetrics.density
        val safe = IslandChrome.EDGE_SAFE_DP * d
        val ratio = when (next) {
            IslandDock.TOP -> (cx / pw.toFloat()).coerceIn(0f, 1f)
            else -> {
                val usable = (parent.height - v.height - safe).coerceAtLeast(1f)
                ((cy - safe) / usable).coerceIn(0f, 1f)
            }
        }
        CustomVisualPref.setIslandAlongRatio(this, ratio)
        islandDock = next
        CustomVisualPref.setIslandDock(this, next)
        // 先把拖拽位移收回，再由 applyDock 按新停靠边重新摆位
        v.translationX = 0f
        v.translationY = 0f
        sideHost?.visibility = View.GONE
        applyDock()
        // TOP 形态下把手回到顶部居中并恢复原本的圆点外观
        if (next == IslandDock.TOP) {
            applyIslandDotImage(v)
        }
    }

    /** 拖离边缘时的把手背景：四角全圆的圆角长方形。 */
    private fun floatingHandleBackground(): android.graphics.drawable.GradientDrawable {
        val r = IslandChrome.outerRadiusPx(expanded = false, density = resources.displayMetrics.density).toFloat()
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            setColor(0xFF000000.toInt())
            cornerRadius = r
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // ⚠️ v1.5.1：自由窗口/分屏的**尺寸变化也走这里**（configChanges 含 screenSize），
        // 而且拖动时每变一像素来一次。旧实现无条件 reload() → 拖动过程中连续整页重载
        // （卡顿、闪白、反复重走启动遮罩）。
        //
        // 这里**不能**当场判断渲染模式：此刻 WebView 还没重新布局，webView.width 仍是
        // 旧窗口（乃至全屏）的尺寸，isWindowed() 会算成"窗口够宽"，于是小窗里继续走
        // 桌面渲染。统一交给 scheduleViewportReapply，在布局完成后判断模式、
        // 必要时才 reload，单纯尺寸变化只重写 viewport meta。
        applyZoomControls(desktopMode)
        applyStatusBarImmersive()
        scheduleViewportReapply("config ${newConfig.screenWidthDp}x${newConfig.screenHeightDp}dp")
    }
    /**
     * 进出小窗 / 分屏 / 画中画（v1.5.1）。
     *
     * Activity 不会因此重建，但 WebView 的尺寸变了 —— 而视口宽正是按 WebView 尺寸算的，
     * 不在这里补一次，页面就会保持进小窗前的宽度被整体缩小（字号变小的直接成因）。
     */
    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        scheduleViewportReapply("multiwindow=$isInMultiWindowMode")
    }

    /**
     * 当前窗口（= WebView 视口）的 CSS 宽度（v1.5.1）。
     *
     * ## 为什么必须显式算，而不是交给 `device-width`
     * `device-width` 在 Android WebView 里恒等于**整块屏幕**的 CSS 宽，且不随
     * WebView 尺寸变化。手机上手窗口==屏幕所以一直没暴露；自由窗口/分屏里窗口比
     * 屏窄，页面仍按屏宽排版，再被 `loadWithOverviewMode` 整体缩进窗口 ——
     * 字号按 `窗口宽/屏宽` 等比缩水（vivo 平板小窗实测 468/1238 ≈ 0.38，字只剩 40%）。
     *
     * ## 为什么用 view.width 而不是 WindowManager
     * - `resources.displayMetrics.widthPixels`：整块屏幕物理宽，自由窗口下与窗口无关；
     * - `WindowManager.getCurrentWindowMetrics()`：含窗口装饰，且 API 30+（本项目 minSdk=26），
     *   拿到的也比 WebView 内容宽略大；
     * - WebView 在 activity_main.xml 里是 `match_parent` 全幅铺满窗口 → `view.width`
     *   本身就是窗口内容宽（物理 px）。除以 density 即 CSS px（WebView 的
     *   devicePixelRatio 恒等于 resources.displayMetrics.density；即便系统做兼容缩放，
     *   两者同步变化，比值不变）。
     *
     * @return 窗口 CSS 宽度，下限 200（异常态兜底，避免 meta 写出非法值）
     */
    private fun windowCssWidth(view: View): Int {
        if (view.width > 0) {
            val css = view.width / resources.displayMetrics.density
            if (css >= 200f) return css.toInt()
        }
        // 布局尚未完成时兜底：多窗口下 configuration.screenWidthDp 就是**窗口**宽（不是屏宽）
        return resources.configuration.screenWidthDp.coerceAtLeast(200)
    }

    /**
     * 竖屏视口缩放 JS（v1.5.1 重写）：布局宽 = 窗口 CSS 宽 / R，显示比例 = R。
     *
     * - `width=(宽/R)` + `initial-scale=R` → `(宽/R) × R = 宽`，正好铺满视口；
     *   旧实现写 `width=device-width`，自由窗口下会把整屏宽当布局宽（见 [windowCssWidth]）。
     * - `min=max=R` → 锁死缩放（既不会被 clamp 回 100%，也不许用户手动捏合）。
     *   ⚠️ 这也正是必须把 R 同时放进 width 的原因：若布局宽已经等于视口宽、再只用
     *   `initial-scale=R`，R<1 时 WebView 会把"填满视口"优先于初始缩放，缩放直接失效。
     * - R 由用户 pageScale 推导，范围 0.5–1.5。
     */
    private fun portraitViewportJs(cssWidth: Int, ratio: Float): String {
        val r = ratio.coerceIn(0.5f, 1.5f)
        val w = (cssWidth / r).toInt().coerceIn(200, 4000)
        return "(function(){" +
            "var m=document.querySelector('meta[name=\"viewport\"]');" +
            "if(!m){m=document.createElement('meta');m.setAttribute('name','viewport');" +
            "(document.head||document.documentElement).appendChild(m);}" +
            "m.setAttribute('content','width=$w, initial-scale=$r, minimum-scale=$r, maximum-scale=$r, user-scalable=no');" +
            "})()"
    }

    /**
     * 应用当前渲染模式的视口缩放（v1.4.6，v1.5.1 修自由窗口字号）。
     *
     * - 桌面模式：固定 `width=1280`、`initial-scale=clientWidth/1280`，让响应式 CSS 命中桌面断点；
     * - 竖屏模式：`width=窗口CSS宽/R` + `initial-scale=R`（见 [portraitViewportJs]）。
     *
     * ⚠️ v1.5.1 之前的竖屏分支写 `width=device-width` —— 那是**整屏宽**，自由窗口/
     * 分屏下窗口比屏窄时页面被整体缩小塞进窗口，字号按 窗口宽/屏宽 等比缩水。
     * 现在两个分支都以「WebView 自身尺寸」为准，与窗口一致。
     *
     * 两个分支都在**启动遮罩尚未淡出**时执行（`onPageFinished` 里先本函数、后
     * `dismissSplash()`）。涉及缩放的模式随后安排一次整页重栅格化，保证用户看到的
     * 第一帧就是最终缩放，不会出现「旧栅格 + 新缩放」的混合画面。
     *
     * 竖屏「对话流被挤压」的修复不在这里，而是引擎侧客户端插件
     * （`assets/android-plugins/narrow-sidebar/`，v1.2.175）：外部注入 JS 会和其它
     * UI 插件抢 DOM，且引擎升级后哈希类名一变就失效；插件走 `dsh-client-modules`
     * 的官方发现机制，卸载时自清理，互不干扰。
     *
     * @param view 已触发 `onPageFinished`、或窗口尺寸刚变化过的 WebView。
     */
    private fun applyViewport(view: WebView) {
        if (desktopMode) {
            // 必须记下宽度：scheduleViewportReapply 靠它判断"只改高度就跳过"，
            // 漏写会让桌面模式下的去抖永远失效（键盘弹出也整页重栅格化 → 白闪）。
            lastViewportWidth = view.width
            view.evaluateJavascript(DESKTOP_VIEWPORT_JS, null)
            scheduleReraster(view, "desktop")
            return
        }
        val scale = (pageScale / 100f).coerceIn(0.5f, 1.5f)
        val cssW = windowCssWidth(view)
        lastViewportWidth = view.width
        view.evaluateJavascript(portraitViewportJs(cssW, scale), null)
        // 窗口尺寸取错是这类问题的唯一来源 —— 留一条日志，真机 `logcat -s DshWebView` 一眼可验：
        // 小窗下 cssW 必须≈窗口宽(dp)，若仍≈屏宽说明 windowCssWidth 取错了来源。
        logWebView(
            "viewport/portrait win=${view.width}px cssW=$cssW scale=$scale " +
                "density=${resources.displayMetrics.density} " +
                "screenDp=${resources.configuration.screenWidthDp} " +
                "multi=$isInMultiWindowMode",
        )
        // scale=1 没有缩放突变，不需要额外重栅格化（也避免无谓的软件渲染帧）。
        if (scale != 1f) scheduleReraster(view, "portrait scale=$scale")
    }

    /**
     * 窗口尺寸变化后延迟重写 viewport meta（v1.5.1）。
     *
     * ## 为什么不 reload
     * 自由窗口**每拖动一像素** `screenWidthDp` 就会变，旧实现是
     * `onConfigurationChanged` 里无条件 `webView.reload()` —— 等于拖动过程中连续整页
     * 重载：卡顿、闪白，而且每次重载都要重走一遍启动遮罩链路。重写 meta 没有这些代价。
     *
     * 去抖 [VIEWPORT_SETTLE_MS]，并在真正执行时比对宽度：软键盘弹出、状态栏 insets
     * 变化只改高度，宽度没变直接跳过，不会误触发重排。
     */
    private fun scheduleViewportReapply(reason: String) {
        if (!::webView.isInitialized) return
        viewportReapply?.let { webView.removeCallbacks(it) }
        val task = Runnable {
            if (!::webView.isInitialized) return@Runnable
            if (webView.url == null) return@Runnable
            val w = webView.width
            if (w <= 0) return@Runnable
            // 布局已完成 → 此时宽高就是新窗口的真实尺寸，可以安全判断渲染模式。
            // 模式变了必须整页重载（UA + 视口 + 缩放开关一整套要重走）。
            val wantDesktop = shouldUseDesktop()
            if (wantDesktop != desktopMode) {
                logWebView("viewport mode switch: desktop=$wantDesktop w=$w ($reason)")
                setDesktop(wantDesktop)
                lastViewportWidth = -1
                webView.reload()
                return@Runnable
            }
            // 宽度没变（键盘弹出、状态栏 insets 变化只改高度）→ 不必重排
            if (w == lastViewportWidth) return@Runnable
            logWebView("viewport reapply: $reason w=$w")
            applyViewport(webView)
        }
        viewportReapply = task
        webView.postDelayed(task, VIEWPORT_SETTLE_MS)
    }

    /**
     * 在启动遮罩之下丢弃整页栅格缓存并重新光栅化一次（v1.4.6）。
     *
     * ## 为什么需要
     * viewport meta 由渲染进程异步应用。页面首帧往往已经按 `initial-scale=1` 画完
     * （登录弹窗就在这一帧里），随后整页缩放突变；Android WebView 可能把部分合成层
     * （圆角裁剪卡片、`backdrop-filter` 遮罩、淡入动画层）留在旧缩放/旧尺寸的栅格里，
     * 表现为弹窗被拉宽、纵向截断、按钮只剩一个字（复现截图见本 PR 描述）。
     *
     * ## 做法
     * 切到 `LAYER_TYPE_SOFTWARE` 会强制丢弃全部已缓存栅格并整页软件重绘，再切回默认
     * （`LAYER_TYPE_NONE`）恢复硬件合成。两次切换都发生在 `dismissSplash()` 的等待
     * 窗口内（遮罩至少 `splashMinMs + 180ms` 后才开始淡出），用户看不到这次刷新。
     *
     * @param view 目标 WebView。
     * @param reason 写入 `engine.log` 的触发来源，便于真机排查。
     */
    private fun scheduleReraster(view: WebView, reason: String) {
        view.postDelayed({
            if (!::webView.isInitialized) return@postDelayed
            val wv = webView
            wv.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            wv.post {
                wv.setLayerType(View.LAYER_TYPE_NONE, null)
                logWebView("reraster: $reason")
            }
        }, VIEWPORT_SETTLE_MS)
    }

    /** 解压进度：null=不在解压（不确定转圈），有值=真实百分比确定性进度 */
    private fun renderProgress(frac: Float?) {
        val bar = findViewById<ProgressBar>(R.id.installProgress)
        if (frac == null) {
            bar.isIndeterminate = true
        } else {
            bar.isIndeterminate = false
            bar.progress = (frac * 10000).toInt()
        }
        // v1.6.19：**不再**从这里写启动遮罩的进度条。
        //
        // 原先有两条订阅同时写 `splashProgress`：本方法（安装总进度，口径是
        // 「下载 45% + 解压 55%」）与 `renderSplashTrace`（BootTrace 的**当前阶段**
        // 百分比，0..100%）。两个口径不同、回调时机也不同，同一控件被两边交替
        // 赋值 → 百分比来回跳。现在遮罩只由 BootTrace 驱动，顶部细条继续用
        // 安装总进度，各管一个控件。
    }

    // ==================== 启动遮罩（v1.3.1） ====================

    // ==================== 启动遮罩（v1.6.19 重做） ====================

    /**
     * 初始化启动遮罩。
     *
     * v1.6.19 起把状态、渲染、退出全部收进 [SplashController]，这里只做三件事：
     * 拿到根 View、把 debug 包的 `splash_min_ms` 覆盖传进去、把「忙不忙」与
     * 「看门狗触发」两个回调接上。旧实现在本类里堆了 15 个 splash 字段 +
     * 三套渲染路径，改一处要对照三处。
     */
    private fun setupSplash() {
        splashController?.dispose()

        val root = findViewById<android.view.View>(R.id.splash) ?: return
        splash = root
        splashDismissed = false

        // 仅 debug 包：允许用 `am start ... --el splash_min_ms 20000` 拉长遮罩
        // 停留时间，方便验证展开步骤后的布局。release 包这段不生效。
        val debuggable =
            (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val override = if (debuggable) intent?.getLongExtra("splash_min_ms", 0L) ?: 0L else 0L
        splashMinMs = override.takeIf { it > 0L } ?: 700L

        splashController = app.dsh.mobile.ui.SplashController(
            activity = this,
            root = root,
            minimumMs = splashMinMs,
            isBusy = {
                // 「等用户点下载」也算忙。
                //
                // 这是模拟器上复现出来的 bug：用户停在运行时下载面板上超过 45 秒，
                // 此时引擎状态还是 Idle（用户没点，引擎没起），看门狗判定
                // 「不忙 → 出问题了」→ 收掉遮罩。等用户再点下载，遮罩已经没了，
                // 只剩一片白 WebView + 顶部「Installing runtime…」。
                // 面板还在等用户决策，不该被当成卡死。
                val waitingUser = runtimeSetupDialog?.isShowing == true
                val state = (application as DshApp).supervisor.state.value
                waitingUser ||
                    state is EngineSupervisor.State.Installing ||
                    state is EngineSupervisor.State.Starting
            },
            onWatchdog = { dismissSplash() },
            onHidden = {
                splash = null
                splashController = null
            },
        )
    }

    /** 更新遮罩上的阶段文案（兜底路径；正常渲染走 BootTrace）。 */
    private fun setSplashStatus(resId: Int) {
        splashController?.setFallbackStatus(resId)
    }

    /** 把 BootTrace 快照交给控制器渲染；遮罩已收时是空操作。 */
    private fun renderSplashTrace(snap: BootTrace.Snapshot) {
        if (!splashDismissed) splashController?.render(snap)
    }

    /**
     * 收起启动遮罩。
     *
     * 必须幂等：它会被 `onPageFinished`、看门狗超时、失败路径、控制器自身
     * 的退出动画多处调用，重复调用不能重复排退出动画。
     *
     * 最短展示时长（[splashMinMs]）由控制器内部计算 —— 它会等
     * `minimumMs + 180ms`（那 180ms 是给整页重栅格化留的窗口）。
     */
    private fun dismissSplash() {
        if (splashDismissed) return
        splashDismissed = true
        splashController?.dismiss()
    }

    /**
     * 「引擎起来了，但能力残缺」的提示。
     *
     * 与 [FailureDialog] 的定位不同：那边是**起不来**，必须打断用户；
     * 这边引擎是可用的，所以不弹模态框、不拦操作，只在状态栏说明并给一次入口。
     * 每个降级组合只提示一次（[lastDegradedKey]），避免状态回流时反复打扰。
     */
    private fun showDegradedNotice(degraded: EngineSupervisor.State.Degraded) {
        val key = degraded.missingServices.joinToString(",")
        if (key == lastDegradedKey) return
        lastDegradedKey = key
        val detail = degraded.missingServices.joinToString("\n") { "\u00b7 $it" }
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.degraded_title))
            .setMessage(
                getString(R.string.degraded_body, degraded.missingServices.size, detail),
            )
            .setPositiveButton(R.string.degraded_open_maintenance) { _, _ ->
                startActivity(android.content.Intent(this, MaintenanceActivity::class.java))
            }
            .setNegativeButton(android.R.string.ok, null)
            .show()
    }

    /** 上次提示过的降级组合，避免同一个问题反复弹。 */
    private var lastDegradedKey: String? = null




    /**
     * 屏幕朝向：把用户偏好真正落到 requestedOrientation（v1.2.120 三态）。
     *
     * ⚠️ 竖屏必须显式用 SCREEN_ORIENTATION_PORTRAIT。SCREEN_ORIENTATION_UNSPECIFIED
     * 的语义是「交给系统/传感器决定」，**不是**「锁定竖屏」—— 旧实现给竖屏填的是
     * UNSPECIFIED，所以设置里选「竖屏」后物理旋转设备照样变横屏，看起来就是开关失灵。
     * auto 用 USER（跟随传感器且尊重系统旋转锁），见 OrientationPrefs.toActivityInfo。
     */
    private fun applyOrientation(mode: OrientationMode) {
        requestedOrientation = OrientationPrefs.toActivityInfo(mode)
        applyStatusBarImmersive()
    }

    /**
     * 沉浸式状态栏：开关开启时横竖屏都隐藏系统状态栏，网页全屏；关闭则恢复显示。
     *
     * 必须在 onCreate / onResume / onConfigurationChanged / onWindowFocusChanged
     * 四处重复应用：系统在转屏、重新获得焦点、弹软键盘时都会重置 insets 可见性。
     *
     * ⚠️ 不做「铺进挖孔区」（v1.4.30，宝宝实测反馈）：上一版在隐藏状态栏时切
     * SHORT_EDGES/ALWAYS 让窗口画进摄像头挖孔，结果是顶部灵动岛与摄像头重叠。
     * 现在保持系统默认的挖孔模式——窗口不进摄像头区；隐藏状态栏后挖孔位置留的
     * 一条窄边是可接受的代价（想要它消失的用户本来就不该开沉浸式）。
     *
     * ⚠️ 关闭态（hide=false）**只清 immersive flag，不动状态栏颜色/图标**：
     * 旧实现无条件把 statusBarColor 设成透明、并把 systemUiVisibility 整体赋 0，
     * 结果是把 ThemedActivity → ThemeManager.applyBars() 刚设好的
     * LIGHT_STATUS_BAR / LIGHT_NAVIGATION_BAR 一起抹掉 —— 浅色主题下状态栏变成
     * 「透明底 + 白图标」，压在白网页上完全看不见。颜色与图标明暗交回 applyBars 管。
     */
    private fun applyStatusBarImmersive() {
        val w = window ?: return
        runCatching {
            val hide = immersiveEnabled(this)
            // 状态栏可见性变了 → 岛的位置要跟着重新算
            applyIslandInsets()
            if (hide) {
                // ⚠️ 关键（v1.4.44，用户实测「岛不在最上面，还是往下了一点」）：
                // 只设 immersive flag 不够 —— 窗口本身仍从状态栏下方开始
                // （dumpsys 实测 topActivityAppBounds=Rect(0,128-1080,2337)），
                // 所以岛再贴顶也只是贴到 y=128。
                //
                // 必须让 decorView **不再为系统栏预留空间**：
                //   · API 30+：setDecorFitsSystemWindows(false)
                //   · 更早：SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN（配合 hide 真正铺满）
                // 两者都做，覆盖所有版本；关掉时再恢复。
                @Suppress("DEPRECATION")
                w.statusBarColor = android.graphics.Color.TRANSPARENT
                // v1.4.51（用户报「底部横条遮挡页面」）：导航条背景也要透明。
                // 否则全屏时底部是一条与页面无关的浅色带（实测 rgb(245,247,250)
                // 压在深色页面上非常扎眼），且系统会认为那是自己的区域，
                // 页面内容被顶出去。透明后由 WebView 自己的背景填满，
                // 内容区再靠 insets padding 让位（见 applyIslandInsets）。
                @Suppress("DEPRECATION")
                w.navigationBarColor = android.graphics.Color.TRANSPARENT
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    @Suppress("DEPRECATION")
                    w.isNavigationBarContrastEnforced = false
                }
                // ⚠️ v1.4.46（用户实测「隐藏状态栏时键盘完全盖住对话框输入框」）：
                // setDecorFitsSystemWindows(false) 之后窗口不再自动为 IME 让位，
                // WebView 的输入框会被键盘整个盖住。必须显式 adjustResize。
                @Suppress("DEPRECATION")
                w.setSoftInputMode(
                    android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
                )
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    w.setDecorFitsSystemWindows(false)
                    val c = WindowInsetsControllerCompat(w, w.decorView)
                    c.hide(WindowInsetsCompat.Type.statusBars())
                    c.systemBarsBehavior =
                        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
                @Suppress("DEPRECATION")
                w.decorView.systemUiVisibility =
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        .or(View.SYSTEM_UI_FLAG_FULLSCREEN)
                        .or(View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
                        .or(View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
                WindowInsetsControllerCompat(w, w.decorView)
                    .hide(WindowInsetsCompat.Type.statusBars())
                // ⚠️ v1.4.51（用户报「有些系统不会自动隐藏状态栏，导致状态栏遮挡页面」）：
                // 部分 OEM 系统（MIUI/EMUI/部分 Android 12 定制版）在 Activity 刚
                // 创建、或从后台切回、或 WebView 重新加载时会**重置** systemUiVisibility，
                // 状态栏又冒出来压在页面上。
                //
                // 兜底：短时间内多次重试 hide，并在每次窗口焦点变化/insets 变化时
                // 重新断言。scheduleImmersiveReassert() 内部有防抖与次数上限，
                // 不会变成常驻轮询。
                scheduleImmersiveReassert()
            } else {
                // 恢复窗口为系统栏预留空间
                @Suppress("DEPRECATION")
                w.setSoftInputMode(
                    android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
                )
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    w.setDecorFitsSystemWindows(true)
                }
                // 恢复导航条为**主题色**（applyBars 会设 navigationBarColor = bg）
                // 并重新打开系统对比度强制（全屏时关掉了它）。
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    @Suppress("DEPRECATION")
                    w.isNavigationBarContrastEnforced = true
                }
                // ⚠️ v1.4.57（用户报「全屏开启后再关闭，网页渲染不恢复，左上角
                // 打开对话列表的按钮被状态栏完全遮挡」）：
                // 必须**显式清掉**全屏时设的 layout flag。
                //
                // `ThemeManager.applyBars()` 只改颜色与图标明暗，**不动这些布局位**；
                // 而 `SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN` 一旦残留，窗口就继续按
                // 「内容画进状态栏区域」布局 —— 页面顶部的按钮被状态栏压在下面，
                // 点不到也看不见。这正是用户看到的现象。
                //
                // 清 flag 只保留 LAYOUT_STABLE（保证切换过程不跳动），
                // 不能用 `= 0`（会把 applyBars 刚设好的图标明暗一起抹掉）。
                @Suppress("DEPRECATION")
                run {
                    val clearMask = View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                        View.SYSTEM_UI_FLAG_IMMERSIVE
                    @Suppress("DEPRECATION")
                    val cur = w.decorView.systemUiVisibility
                    w.decorView.systemUiVisibility =
                        (cur and clearMask.inv()) or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                }
                // 交给 ThemeManager.applyBars 恢复「主题背景色 + 正确的图标明暗」。
                ThemeManager.applyBars(this)
                WindowInsetsControllerCompat(w, w.decorView)
                    .show(WindowInsetsCompat.Type.statusBars())
                // v1.4.57：立刻重算一次布局（岛的 topMargin、WebView 的底部 padding
                // 都按 immersive 分支切换）。只靠 insets 回调是不够的 —— 退出全屏时
                // insets 可能没变化（状态栏本来就在），回调不会触发，padding 就
                // 一直是全屏时的值，页面底部留一条空白。
                w.decorView.requestApplyInsets()
                webView.requestLayout()
            }
        }
    }

    /**
     * 全屏状态栏的**兜底重断言**（v1.4.51）。
     *
     * 为什么需要：部分 OEM 系统会在 Activity 创建完成、WebView 加载、从后台
     * 切回等时机把 systemUiVisibility 重置成默认值，隐藏掉的系统状态栏又冒
     * 出来压在页面顶部（用户实测）。单次 hide() 不够可靠，必须在关键时间点
     * 反复断言。
     *
     * 策略：一次调度内做 4 次重试（0 / 120 / 400 / 1000ms），覆盖系统迟到的
     * 重置；每次都会先检查「用户是否还开着全屏」和「Activity 是否在前台」，
     * 关掉全屏或退到后台立即停止。不常驻轮询。
     */
    private var immersiveReassertToken = 0

    private fun scheduleImmersiveReassert() {
        if (!immersiveEnabled(this)) return
        val token = ++immersiveReassertToken
        val delays = longArrayOf(0L, 120L, 400L, 1000L)
        delays.forEach { delayMs ->
            window?.decorView?.postDelayed({
                // 期间用户关了全屏 / 换了页面 / Activity 不在前台 → 放弃
                if (token != immersiveReassertToken) return@postDelayed
                if (!immersiveEnabled(this)) return@postDelayed
                if (isFinishing || isDestroyed) return@postDelayed
                val w2 = window ?: return@postDelayed
                runCatching {
                    if (android.os.Build.VERSION.SDK_INT >= 30) {
                        w2.setDecorFitsSystemWindows(false)
                        WindowInsetsControllerCompat(w2, w2.decorView)
                            .hide(WindowInsetsCompat.Type.statusBars())
                    }
                    @Suppress("DEPRECATION")
                    w2.decorView.systemUiVisibility =
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            .or(View.SYSTEM_UI_FLAG_FULLSCREEN)
                            .or(View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
                            .or(View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
                    WindowInsetsControllerCompat(w2, w2.decorView)
                        .hide(WindowInsetsCompat.Type.statusBars())
                    // 系统重置后窗口可能又开始为状态栏预留空间 → 立刻重算岛位置
                    w2.decorView.requestApplyInsets()
                }
            }, delayMs)
        }
    }

    /**
     * 顶部安全内边距：让灵动岛避开**状态栏与挖孔**（v1.4.43）。
     *
     * ## 为什么需要
     * 灵动岛（`statusBarRow` / `handleBar`）在布局里是 `layout_gravity="top"` +
     * `marginTop=10dp`，固定在窗口绝对顶部。开启全屏（隐藏状态栏）后：
     *   · 若不做处理，岛直接压在系统状态栏/挖孔上 —— 用户点不动它；
     *   · 若按「状态栏高度」下移，挖孔屏上会留一条无意义黑边。
     *
     * ## 做法
     * 监听 window insets，取 **statusBars ∪ displayCutout** 的 top 作为岛的下移量：
     *   · 显示状态栏 → top = 状态栏高度（含挖孔安全区），岛在下方；
     *   · 全屏 + 无挖孔 → top = 0，岛贴顶；
     *   · 全屏 + 挖孔屏 → top = 挖孔下沿高度，岛避开摄像头。
     *
     * `displayCutout` 的 insets 天然覆盖各种位置（中心孔 / 左孔 / 右孔 / 刘海）——
     * 系统给的是「该边被遮挡的最大深度」，与孔在哪一侧无关，所以我们不需要
     * 自己判断孔位，取 top 即可。
     *
     * 横屏时挖孔通常在左侧或右侧，此时 `displayCutout` 的 left/right 会大于 0、
     * top 可能为 0；岛是水平居中的，因此只需保证 top 安全，横向由居中布局自行避开。
     */
    /**
     * 把收起态正圆的**触控区**扩到 48dp 见方（v1.4.59）。
     *
     * ## 为什么需要
     * 摄像头中心离屏幕顶端只有 67px（真机），圆要完整可见就不能超过 134px 直径
     * —— 视觉上刚刚好，但换算成 dp 只有 38dp，**低于 Android 的 48dp 最小触控
     * 尺寸**。用户实测「我都点不到」。
     *
     * 单纯把 View 放大不行：直径超过「圆心到屏幕顶距离 × 2」就会溢出屏幕，
     * 而 clamp 到 top=0 又会把圆心推下去（用户报的「溢出 + 位置下移」）。
     *
     * ## 做法
     * View 尺寸保持视觉正确（贴住顶部、罩住摄像头），用 [TouchDelegate] 把可点
     * 区域向外扩到 48dp。父容器（FrameLayout）负责把落在扩展区的触摸事件转发
     * 给这个 View —— 这是 Android 官方支持的「小控件大热区」做法。
     *
     * 扩展区会超出父容器边界时（圆贴顶），TouchDelegate 的 rect 允许负数起点，
     * 父容器仍会按坐标命中；超出屏幕的部分自然无效，不影响可用性。
     */
    private fun applyHandleTouchTarget(handle: android.view.View, dotSize: Int) {
        val minTouch = (48 * resources.displayMetrics.density).toInt()
        if (dotSize >= minTouch) return   // 已经够大，不需要扩展
        val parent = handle.parent as? android.view.ViewGroup ?: return
        val grow = (minTouch - dotSize) / 2
        val rect = android.graphics.Rect(
            -grow,
            -grow,
            handle.width + grow,
            handle.height + grow,
        )
        parent.touchDelegate = android.view.TouchDelegate(rect, handle)
    }

    /**
     * 上一次应用的底部让位高度（物理 px）。
     *
     * 只用于把 `insets/ime …` 诊断行压成「变化时才打」—— insets 回调频率很高，
     * 不去重会把 engine.log 刷满。**不要**拿它当状态源判断当前是否让位。
     */
    private var lastBottomInsetPad = -1

    private fun applyIslandInsets() {
        val root = findViewById<android.view.View>(android.R.id.content) ?: return
        val row = findViewById<android.view.View>(R.id.statusBarRow) ?: return
        val handle = findViewById<android.view.View>(R.id.handleBar) ?: return
        val d = resources.displayMetrics.density
        val baseTop = (10 * d).toInt()
        val rowH = (44 * d).toInt()
        // 收起态圆点边长：默认 26dp，可由「外观 → 收起态状态栏」调整。
        // 这里作为基准尺寸，下面的 wantDot 再按屏幕安全边界收敛。
        val handleH = (CustomVisualPref.islandDotSizeDp(this) * d).toInt()

        root.setOnApplyWindowInsetsListener { _, insets ->
            val immersive = immersiveEnabled(this@MainActivity)
            // 竖屏取 top：系统给的 top 是「顶边被遮挡的最大深度」。
            val cutTop = if (android.os.Build.VERSION.SDK_INT >= 30) {
                insets.getInsets(android.view.WindowInsets.Type.displayCutout()).top
            } else 0

            // ⚠️ v1.4.54（用户报「灵动岛收起来有时根本对不准摄像头，不同手机摄像头
            // 位置不同」）：只按屏幕水平居中是不够的 —— 挖孔可能在左侧、右侧、
            // 偏左偏右，居中摆放必然对不准。必须读**挖孔的真实矩形**再对齐。
            //
            // `insets.displayCutout` 只给「顶边遮挡深度」，不告诉你在哪个 x；
            // `DisplayCutout.boundingRects` 才是真实位置（实测本机
            // Rect(492,0-610,128)，中心 x=551，而屏幕中心是 540 —— 差 11px）。
            // 取所有 boundingRect 的**并集中心**作为岛的水平中心：
            //   · 单孔（左/中/右）：并集就是那个孔，精确对齐；
            //   · 双孔（如部分机型的药丸+副孔）：并集包住两者，岛罩住整段；
            //   · 无孔：返回 null，保持屏幕居中。
            // v1.4.58（真机反馈「还是不能贴紧摄像头、高度更高一点」）：
            // 之前用 `cutTop * 0.85` 估直径，但 `cutTop` 是「顶边被遮挡深度」，
            // 与摄像头**可见孔**的尺寸不完全一致：
            //   · 模拟器：cutTop=128，孔 Rect(492,0-610,128) → 宽 118 高 128
            //   · 真机（PLZ110）：cutTop=135，孔 Rect(568,0-648,135) → 宽 80 高 135
            // 按 0.85 缩放得到 114px 的圆，顶在 y=0、圆心 y=57，而孔中心在
            // y=67.5 —— 圆比孔中心高 10px，视觉上就被「顶上去」了。
            //
            // 正确做法：直接用 **boundingRect 的真实尺寸**，圆直径取孔的外接
            // 正方形（max(宽,高)），并让圆心对齐孔中心。
            val cutRect = if (android.os.Build.VERSION.SDK_INT >= 30) {
                val cutout = insets.displayCutout
                val rects = cutout?.boundingRects.orEmpty().filter {
                    it.width() > 0 && it.height() > 0
                }
                if (rects.isEmpty()) null else {
                    val l = rects.minOf { it.left }
                    val r = rects.maxOf { it.right }
                    val t = rects.minOf { it.top }
                    val b = rects.maxOf { it.bottom }
                    android.graphics.Rect(l, t, r, b)
                }
            } else null
            val cutCenterX = cutRect?.let { (it.left + it.right) / 2 }
            val cutCenterY = cutRect?.let { (it.top + it.bottom) / 2 }
            val cutHoleH = cutRect?.height() ?: cutTop
            val cutHoleW = cutRect?.width() ?: cutTop

            // ⚠️ v1.4.46（用户实测「关掉全屏后灵动岛被状态栏盖住、连设置都进不去」）：
            // 非全屏时岛绝不能只按固定 10dp 定位 —— 窗口从 y=0 开始，状态栏压在最
            // 上层，10dp 处的岛会被整个盖住、点不到（这是「无法进入设置」的根因）。
            // 必须用状态栏 insets 把岛推到状态栏下方。
            val statusTop = if (android.os.Build.VERSION.SDK_INT >= 30) {
                insets.getInsets(android.view.WindowInsets.Type.statusBars()).top
            } else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsetTop
            }

            // 全屏：岛贴屏幕最顶端（top=0），高度至少盖过挖孔 —— 黑色胶囊正好
            // 把摄像头罩住，这才是灵动岛该有的样子。
            // 非全屏：让开状态栏（statusTop）+ 常规 10dp 呼吸位。
            // ⚠️ v1.4.58（真机反馈「看着不跟摄像头」）：
            // `boundingRect` 是「被遮挡区域的外接矩形」，**不等于摄像头的形状**。
            // 真机实测 Rect(568,0-648,135)：宽 80、高 135 —— 摄像头本身是
            // 80 直径的圆，135 是系统为安全留的纵向深度。
            //
            // 早期版本按「高」做直径（135+余量=149），圆心被推到 74.5，
            // 而摄像头中心在 67.5 甚至更靠上 → 视觉上圆「比摄像头低/大一圈」，
            // 所以怎么看都不贴合。
            //
            // 正确做法（v1.4.58）：
            //   · 直径取 **min(宽,高)** = 摄像头真实尺寸（真机 80 → 兜底 91），
            //     不再用高度，避免做成覆盖半个状态栏的大黑饼；
            //   · 圆心 **对齐孔矩形的中心**（cutCenterY），而不是永远贴 y=0。
            //     摄像头靠边缘时，圆跟着上移；孔偏下时圆也跟着下移。
            //
            // 直径下限用 handleH（26dp）保证可点（88dp 触控目标的近似）。
            //
            // ⚠️ v1.4.59（真机反馈「收起来之后的大小怎么跟我摄像头基本一样大？
            // 我都点不到」「全屏模式下灵动岛会直接溢出屏幕，位置下移一点」）：
            // v1.4.58 把直径钉成 `min(宽,高)` = 摄像头本身的 80px，视觉上贴合了，
            // 但 80px ≈ 23dp，远低于 Android 48dp 触控下限 —— 手指根本点不中。
            // 而单纯放大又不行：摄像头中心离屏幕顶端只有 67px，直径一旦超过
            // 134px，圆的上沿就会跑到屏幕外（clamp 到 top=0 后又导致圆心下移，
            // 这就是用户说的「溢出屏幕 + 位置下移」）。
            //
            // 现在的做法是**分离视觉尺寸与触控尺寸**：
            //   · 视觉直径 = min(孔尺寸 + 余量, 圆心到屏幕顶距离 × 2)
            //     —— 保证圆完整可见、不溢出，同时比摄像头大一圈（真机 134px）；
            //   · 触控区由 TouchDelegate 额外扩到 48dp 见方（见 applyHandleTouchTarget）。
            // 这样「看起来比摄像头大一圈、贴住顶部、手指点得中」三者同时成立。
            val coverPad = (4 * d).toInt()
            val holeSize = maxOf(cutHoleW, cutHoleH) + coverPad
            // 圆心到屏幕顶的距离 = 孔中心 Y。直径不能超过它的两倍，否则溢出。
            val maxDotNoOverflow = if (cutCenterY != null && cutCenterY > 0) {
                cutCenterY * 2
            } else {
                Int.MAX_VALUE
            }
            // 尺寸以用户偏好为准（handleH 即偏好值），仅收敛到"不溢出屏幕顶部"的上限。
            // 历史实现会在沉浸态强制放大到能罩住挖孔，那样用户调小就不生效 ——
            // 尺寸既然开放给用户，就以用户意愿优先。
            val wantDot = handleH.coerceAtMost(maxDotNoOverflow)
            // 圆心对齐孔中心：top = 中心Y - 半径。clamp ≥0（孔贴顶时圆也贴顶）。
            val wantTop = when {
                !immersive -> statusTop + baseTop
                cutCenterY == null -> 0
                else -> (cutCenterY - wantDot / 2).coerceAtLeast(0)
            }
            // 展开态是长条工具栏（v1.4.60 真机反馈「展开时还是没有向下移，
            // 会出现屏幕溢出，太贴边缘」）。
            //
            // 历史：早期把工具栏 top 设成 0（贴屏幕最顶），高度取孔高 —— 结果
            // 工具栏顶边正好卡在屏幕边缘，圆角被裁、图标贴着挖孔，视觉上「溢出」。
            //
            // 现在的规则：
            //   · top  = 孔的下沿（cutTop）+ 6dp 呼吸位 → 完全落在摄像头下方，
            //            不再与孔重叠，也不会被屏幕边缘裁掉圆角；
            //   · 高度 = 标准 rowH（44dp），不再为了「避开孔」而拉高 ——
            //            既然已经下移，就不需要额外高度。
            //   · 无挖孔时 top 用 statusTop + baseTop（与非全屏一致）。
            // v1.4.62（宝宝反馈「灵动岛往上移，起码上以灵动岛当前高度的 3/4」）：
            // 之前 top = 挖孔下沿 + 6dp（真机 156），整条工具栏都落在摄像头下方，
            // 视觉上「离屏幕顶太远」，不像灵动岛而像一条独立的工具条。
            //
            // 现在从那个位置**上移自身高度的 3/4**（真机 154×0.75 ≈ 115），
            // 顶边落到 y≈41 —— 工具栏上半部分与摄像头区域重叠，下半部分
            // 仍在孔下方，观感更接近真灵动岛（贴着顶部、包住镜头）。
            //
            // 为什么是 3/4 而不是全移：全移到 y=0 会贴屏幕边、圆角被裁
            // （v1.4.60 修过的「溢出」问题）；3/4 保留了顶部一点余量，
            // 同时把大部分内容提到摄像头高度上。
            val rowGap = (6 * d).toInt()
            val wantRowH = rowH
            val raise = (wantRowH * 3) / 4
            val wantRowTop = when {
                !immersive -> statusTop + baseTop
                cutTop > 0 -> (cutTop + rowGap - raise).coerceAtLeast(0)
                else -> statusTop + baseTop
            }

            // 横向对齐（v1.4.54）：把「屏幕居中的重力」换成「按挖孔中心偏移」。
            //
            // 布局里是 layout_gravity="top|center_horizontal" —— 居中由 FrameLayout
            // 的 gravity 决定，光改 marginStart 没用（重力会把它再居中一次）。
            // 正确做法：把 gravity 改成纯 top（不居中），再用 marginStart 精确
            // 定位到挖孔中心。无挖孔时恢复居中。
            //
            // 偏移量 = 挖孔中心 - 屏幕中心。只对**收起态的正圆**应用：展开态的
            // 工具栏是全宽胶囊，横向本来就该铺满（且它的左 padding 已经留了
            // 文字空间），偏移反而会让左右留白不对称。
            val screenW = resources.displayMetrics.widthPixels
            val handleW = wantDot
            val handleStart = when {
                !immersive -> null                       // 非全屏：保持居中
                cutCenterX == null -> null               // 无挖孔：保持居中
                // 夹在屏幕内：孔极靠边时（如左上角），圆的左沿不越界
                else -> (cutCenterX - handleW / 2).coerceIn(0, (screenW - handleW).coerceAtLeast(0))
            }
            handle.layoutParams?.let { lp ->
                val m = lp as? android.view.ViewGroup.MarginLayoutParams
                if (m == null) return@let
                val flp = lp as? android.widget.FrameLayout.LayoutParams
                val wantGravity = if (handleStart == null) {
                    (android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL)
                } else {
                    android.view.Gravity.TOP or android.view.Gravity.START
                }
                var dirty = false
                if (m.topMargin != wantTop || lp.height != wantDot || lp.width != wantDot) {
                    m.topMargin = wantTop
                    lp.height = wantDot
                    lp.width = wantDot
                    dirty = true
                }
                if (flp != null) {
                    if (flp.gravity != wantGravity) { flp.gravity = wantGravity; dirty = true }
                    val wantStart = handleStart ?: 0
                    if (m.marginStart != wantStart) { m.marginStart = wantStart; dirty = true }
                }
                if (dirty) {
                    handle.layoutParams = lp
                    // 布局变了 → 触控区要跟着重算（等一次布局完成后再装）
                    handle.post { applyHandleTouchTarget(handle, wantDot) }
                }
            }
            // 工具栏用 wantRowTop（贴顶），圆用 wantTop（对齐孔中心）—— 两者不再混用。
            //
            // ⚠️ 位置与高度设在**外层 statusBarOverlay** 上，不是里面的 statusBarRow：
            // v1.5.x 给胶囊套了一层 FrameLayout 作为贴纸图层，overlay 自己带
            // layout_marginTop="10dp"、高度固定 44dp。若再把 topMargin 加到内层 row，
            // 两段边距叠加 + row 超出 44dp 容器 → 胶囊下半部分被裁掉（用户实测）。
            val overlay = findViewById<View>(R.id.statusBarOverlay)
            overlay?.layoutParams?.let { lp ->
                val m = lp as? android.view.ViewGroup.MarginLayoutParams
                if (m != null && (m.topMargin != wantRowTop || lp.height != wantRowH)) {
                    m.topMargin = wantRowTop
                    lp.height = wantRowH
                    overlay.layoutParams = lp
                }
            }
            // 内层 row 撑满 overlay，不再自己管位置（否则又会和上面打架）
            row.layoutParams?.let { lp ->
                if (lp.height != android.view.ViewGroup.LayoutParams.MATCH_PARENT) {
                    lp.height = android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    val m = lp as? android.view.ViewGroup.MarginLayoutParams
                    if (m != null) {
                        m.topMargin = 0
                        m.marginStart = 0
                    }
                    row.layoutParams = lp
                }
            }

            // WebView 的底部内边距 = max(IME, 系统导航区)。
            //
            // · IME（v1.4.46）：decorFitsSystemWindows=false 后系统不再替我们缩小
            //   窗口，键盘会直接盖住输入框 → 用 IME 高度把内容顶上去。
            // · 系统导航区（v1.4.51，用户报「部分手机屏幕下面横条会遮挡部分页面」）：
            //   全屏时窗口铺到屏幕最底，手势条 / 三键导航条会压住页面底部。
            //   之前只算 IME，键盘不在时 padding=0，底部内容就被横条挡住。
            //   两者取大：键盘弹出时用键盘高度（已包含导航条），否则用导航条高度。
            //
            // 只改 padding 不改 layoutParams：后者会触发 WebView 重新测量并闪白。
            // 背景色仍会填满 padding 区（WebView 自身背景透出），所以视觉上仍是全屏。
            // （v1.5.0-beta.6 起改为 bottomMargin：让位是否生效不能依赖 padding 是否
            //  被 Chromium 算进内容尺寸，见下方说明。）
            // ⚠️ v1.5.0-beta.6（vivo X300 Pro 真机实测）：`WindowInsets.Type.ime()`
            // **可能是恒 0 的** —— WM 侧 `InsetsSource type=ime visible=true
            // frame=[0,1683][1260,2800]`、`mInputShown=true`、本窗口就是
            // `imeLayeringTarget`，但监听器里 `insets.getInsets(ime()).bottom` 读出来
            // 是 0（诊断日志原文：`insets/ime pad=63 insets=0 frame=0 rootH=2800
            // visibleBottom=2737`）。于是 v1.4.65 的让位链路整条空转：WebView 一直是
            // 整屏，页面 `documentElement.clientHeight` 不变、`visualViewport` 也不变，
            // 引擎侧手机 UI 插件收不到任何键盘信号，输入框卡片停在 2800px 页面的底部
            // 被键盘整个盖住，正文一路顶到键盘工具栏。K70 不复现只是因为它那套
            // WebView/ROM 组合把 ime insets 正常下发了。
            //
            // 兜底：用 `getWindowVisibleDisplayFrame()` 反推被键盘占掉的高度 —— 它取的是
            // 窗口**可见区域**，与 fit-insets、与 ime insets 是否下发都无关，IME 遮住
            // 多少就少多少（实测 visibleBottom=1683 → 1117）。键盘高度不可能小于
            // 96dp，低于此值一律当 0，避免把导航条误判成键盘。
            val imeFromInsets = if (android.os.Build.VERSION.SDK_INT >= 30) {
                insets.getInsets(android.view.WindowInsets.Type.ime()).bottom
            } else {
                @Suppress("DEPRECATION")
                if (insets.systemWindowInsetBottom > statusTop) insets.systemWindowInsetBottom else 0
            }
            val visibleRect = android.graphics.Rect()
            root.getWindowVisibleDisplayFrame(visibleRect)
            val imeFromFrame = (root.height - visibleRect.bottom).let {
                if (it >= (96 * d).toInt()) it else 0
            }
            val imeBottom = maxOf(imeFromInsets, imeFromFrame)
            val navBottom = if (android.os.Build.VERSION.SDK_INT >= 30) {
                insets.getInsets(
                    android.view.WindowInsets.Type.navigationBars()
                        or android.view.WindowInsets.Type.systemGestures(),
                ).bottom
            } else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsetBottom
            }
            // 底部让位（v1.4.65，宝宝反馈「全屏模式下打开输入法，输入框没有上移
            // 导致根本看不了输入的文字」）。
            //
            // 旧实现 `if (immersive) maxOf(imeBottom, navBottom) else 0` 有两个错：
            //   ① 非全屏时直接 0 —— 但 manifest 里的 adjustResize 在
            //      `setDecorFitsSystemWindows(false)` 之后**不生效**（窗口不再为
            //      任何系统栏让位），键盘照样盖住输入框；
            //   ② 全屏时用 max(IME, 导航区) —— 键盘收起时 navBottom 会留一条空白。
            //
            // 正确语义：
            //   · 键盘弹起（imeBottom > 0）→ 让位 imeBottom（IME 高度已含导航区，
            //     直接顶到键盘上方）；
            //   · 键盘收起 → 让位 navBottom **仅在全屏时**需要（窗口铺到屏幕最底，
            //     手势条会压住内容）；非全屏时系统已让开，留 0。
            val bottomPad = if (imeBottom > 0) {
                imeBottom
            } else if (immersive) {
                navBottom
            } else {
                0
            }

            // v1.4.63（宝宝反馈「网页感觉有点被压缩太狠了，还是改回全屏吧，
            // 到时候被遮挡就收起灵动岛即可」）：
            // 取消 WebView 的顶部留白，网页恢复真正全屏。
            //
            // 权衡（记录一下，避免以后又改回去）：
            //   · 留白（v1.4.61）：网页内容永不被岛遮，但视口矮了一整条工具栏
            //     （真机 154px ≈ 44dp），手机上本就紧张，压缩感明显；
            //   · 全屏（现在）：网页拿满整屏，岛浮在顶部。首屏顶部会被遮住一条，
            //     但**岛可以一键收起**（点岛上的文字区/把手），收起后就是纯净全屏。
            // 用户选择了后者 —— 交互上多一步，但默认视觉更开阔。
            //
            // 底部让位保留：导航条/键盘必须让位，否则输入框会被键盘盖住
            // （v1.4.46 修过的老问题）。
            // 顺手把可能残留的 topMargin 清零（老版本设过留白，升级后必须还原，
            // 否则网页仍被压缩 —— 这类"改回默认却没生效"的坑踩过一次）。
            // ⚠️ v1.5.0-beta.6：让位方式从 `setPadding` 改成 `bottomMargin`。
            // padding 只在 WebView **内部**留白，视图尺寸不变 → 不会触发
            // `onSizeChanged`，Chromium 是否重排视口全看它内部有没有把 padding 算进
            // 内容尺寸（各版本行为不一致，X300 实测就完全没重排）。bottomMargin 让视图
            // **真的变矮**，`onSizeChanged` 必然发生，Chromium 必须重排 —— 跨版本稳。
            // padding 同时归零，避免 padding 与 margin 双重让位。
            // 真机验收（X300，pad=1117）：页面 clientHeight 869 → 534 CSS px，
            // 输入框卡片底边正好落在 534（= 视口底），键盘不再遮挡。
            if (bottomPad != lastBottomInsetPad) {
                lastBottomInsetPad = bottomPad
                logWebView(
                    "insets/ime pad=$bottomPad insets=$imeFromInsets frame=$imeFromFrame " +
                        "rootH=${root.height} visibleBottom=${visibleRect.bottom}",
                )
            }
            val wlp = webView.layoutParams
            if (wlp is android.view.ViewGroup.MarginLayoutParams) {
                val dirty = wlp.topMargin != 0 || wlp.bottomMargin != bottomPad
                if (dirty) {
                    wlp.topMargin = 0
                    wlp.bottomMargin = bottomPad
                    webView.layoutParams = wlp
                }
            }
            if (webView.paddingBottom != 0) webView.setPadding(0, 0, 0, 0)
            insets
        }
        root.requestApplyInsets()
        // v1.4.54：handle 与 row 的 insets 都挂在 content 上，但 GONE 的
        // 视图不会因 requestApplyInsets 收到新的布局参数；可见后补一次。
        findViewById<android.view.View>(R.id.handleBar)?.let { h ->
            if (h.visibility == android.view.View.VISIBLE) h.requestApplyInsets()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            applyStatusBarImmersive()
            // 从后台/多任务切回时，部分 OEM 系统会把状态栏恢复出来，
            // 这里再断言一次（见 scheduleImmersiveReassert 说明）。
            scheduleImmersiveReassert()
        }
    }

    /** 启动阶段秒表：引擎启动本身耗时（真机可达十几秒），显示已用秒数避免被误判为卡死 */
    private var startTicker: Runnable? = null

    /**
     * 最近一次 render() 收到的引擎状态。
     *
     * 为什么需要存：toggleToolbar 的展开动画要用「活跃度目标透明度」收尾，
     * 而那时拿不到 render 的入参。存一份最简单的状态引用即可（EngineSupervisor
     * 的状态对象是不可变 sealed class，持有它没有生命周期问题）。
     */
    private var lastRenderedState: EngineSupervisor.State? = null

    private fun stopStartTicker() {
        startTicker?.let { statusBar.removeCallbacks(it) }
        startTicker = null
    }

    private fun startStartTicker() {
        if (startTicker != null) return
        val t0 = System.currentTimeMillis()
        val r = object : Runnable {
            override fun run() {
                val sec = (System.currentTimeMillis() - t0) / 1000
                statusBar.text = getString(R.string.status_starting) + " " + sec + "s"
                statusBar.postDelayed(this, 1000)
            }
        }
        startTicker = r
        statusBar.postDelayed(r, 1000)
    }

    /**
     * 状态栏用的 cause 标题（与 [FailureDialog] 同一套文案）。
     *
     * 单独一个函数而不是复用 FailureDialog 里的实现：那个是 object 内的 private，
     * 而这里只需要字符串资源 id，硬要共享就得把它提升成公共 API，反而更乱。
     */
    private fun causeTitleResForStatus(cause: app.dsh.mobile.engine.EngineTriage.Cause): Int =
        when (cause) {
            app.dsh.mobile.engine.EngineTriage.Cause.PORT_FOREIGN -> R.string.cause_port_foreign
            app.dsh.mobile.engine.EngineTriage.Cause.PORT_ORPHAN -> R.string.cause_port_orphan
            app.dsh.mobile.engine.EngineTriage.Cause.PORT_UNKNOWN_OWNER -> R.string.cause_port_unknown_owner
            app.dsh.mobile.engine.EngineTriage.Cause.PLUGIN_INACTIVE -> R.string.cause_plugin_inactive
            app.dsh.mobile.engine.EngineTriage.Cause.PROFILE_CORRUPT -> R.string.cause_profile_corrupt
            app.dsh.mobile.engine.EngineTriage.Cause.RUNTIME_MISSING -> R.string.cause_runtime_missing
            app.dsh.mobile.engine.EngineTriage.Cause.RUNTIME_ARCH -> R.string.cause_runtime_arch
            app.dsh.mobile.engine.EngineTriage.Cause.STORAGE_DENIED -> R.string.cause_storage_denied
            app.dsh.mobile.engine.EngineTriage.Cause.SYSTEM_OOM -> R.string.cause_system_oom
            app.dsh.mobile.engine.EngineTriage.Cause.SYSTEM_KILLED -> R.string.cause_system_killed
            app.dsh.mobile.engine.EngineTriage.Cause.AUTH_EXPIRED -> R.string.cause_auth_expired
            app.dsh.mobile.engine.EngineTriage.Cause.UNKNOWN -> R.string.cause_unknown
        }

    private fun render(state: EngineSupervisor.State) {
        lastRenderedState = state
        // ⚠️ v1.2.187（用户反馈「状态栏退出不彻底，后台仍以 380MB 运行、耗电」）：
        // 用户在通知栏点「退出」后，引擎停了但 Activity 还活着 —— WebView 与它
        // 的渲染进程继续占着 ~380MB。收到**显式退出**信号就结束任务栈：
        // finishAndRemoveTask → onDestroy → webView.destroy()，内存随之释放。
        // v1.4.30：重启按钮改成图标（文字按钮把顶部胶囊挤满，状态文字被截成
        // "Engine r..."）。图标没有文字位，改用 contentDescription 表达「启动/重启」
        // 两态，保住原来的可发现性（读屏与长按提示都能读到）。
        findViewById<View>(R.id.btnRestart).contentDescription =
            getString(
                if (state is EngineSupervisor.State.Stopped) R.string.btn_start_engine
                else R.string.btn_restart,
            )
        val bar = findViewById<ProgressBar>(R.id.installProgress)
        bar.visibility =
            if (state is EngineSupervisor.State.Installing || state is EngineSupervisor.State.Starting)
                View.VISIBLE else View.GONE
        if (state !is EngineSupervisor.State.Starting) stopStartTicker()
        // 启动遮罩：失败/停止时兜底（不能让用户卡在遮罩里看不到错误）。
        // v1.4.8：阶段文案改由 BootTrace 驱动（renderSplashTrace，订阅 + 秒表），
        // 这里只在「没有启动记录」时给一句静态兜底，避免两套文案互相覆盖。
        when (state) {
            is EngineSupervisor.State.Installing, is EngineSupervisor.State.Starting -> {
                if (BootTrace.snapshot.value.startedAt <= 0) {
                    setSplashStatus(
                        if (state is EngineSupervisor.State.Installing) R.string.splash_preparing
                        else R.string.splash_starting,
                    )
                }
            }
            is EngineSupervisor.State.Healthy, is EngineSupervisor.State.SafeMode -> {
                // ⚠️ 这里**只能有一个** Healthy/SafeMode 分支。
                // 2026-11-07 实测踩过：加降级提示时又写了一个同样的 when 条件，
                // Kotlin 只走第一个匹配分支，第二段成了**永不执行的死代码** ——
                // 状态栏显示「引擎降级 · 3 项不可用」，说明框却一次都没弹过。
                // 症状极隐蔽：没有编译错误、没有告警，只有盯着真机才发现少了个框。
                if (BootTrace.snapshot.value.startedAt <= 0) {
                    setSplashStatus(R.string.splash_loading)
                }
                // 降级：UI 已经能正常用了，所以**不弹大字报**，只在状态栏说清楚
                // 缺了几项能力，并提供点开看详情。这类问题（插件加载失败）用户
                // 从来不知道，也就从来没反馈过。
                val degraded = when (state) {
                    is EngineSupervisor.State.Healthy -> state.degraded
                    is EngineSupervisor.State.SafeMode -> state.degraded
                    else -> null
                }
                if (degraded != null) {
                    showDegradedNotice(degraded)
                }
            }
            is EngineSupervisor.State.Failed -> {
                dismissSplash()
                // ★v1.5.3：终态失败自动弹结构化诊断（同 cause 只弹一次，
                //   否则退避重试会让它反复弹）。用户关掉后仍可点状态栏再看。
                FailureDialog.showOnce(this, state)
            }
            is EngineSupervisor.State.Backoff,
            is EngineSupervisor.State.Stopped -> dismissSplash()
            else -> Unit
        }
        statusBar.text = when (state) {
            is EngineSupervisor.State.Idle -> getString(R.string.status_idle)
            is EngineSupervisor.State.Installing -> getString(R.string.status_installing)
            is EngineSupervisor.State.Starting -> getString(R.string.status_starting).also { startStartTicker() }
            is EngineSupervisor.State.Healthy -> {
                if (pendingBrowserFallback) {
                    // v1.6.8：用户在门禁里选了「用浏览器打开」，现在有带 token 的地址了。
                    // 走 external browser 而不是 WebView —— 前者内核对，能真正渲染。
                    pendingBrowserFallback = false
                    val u = (application as DshApp).supervisor.webUrl()
                    if (u.isNotEmpty()) openInExternalBrowser(u)
                }
                if (!urlLoaded) {
                    urlLoaded = true
                    // v1.4.46（安全审查 H2）：拿不到带 token 的地址时**不加载**，
                    // 否则可能把 WebView 指向占了 3080 的第三方页面。
                    val u = (application as DshApp).supervisor.webUrl()
                    if (u.isNotEmpty()) loadLocalUrl(u) else urlLoaded = false
                }
                state.recoveredPlugin?.let { getString(R.string.status_plugin_recovered, it) }
                    ?: state.degraded?.let {
                        getString(R.string.status_degraded, it.missingServices.size)
                    }
                    ?: getString(R.string.status_healthy)
            }
            is EngineSupervisor.State.SafeMode -> {
                if (pendingBrowserFallback) {
                    pendingBrowserFallback = false
                    val u = (application as DshApp).supervisor.webUrl()
                    if (u.isNotEmpty()) openInExternalBrowser(u)
                }
                if (!urlLoaded) {
                    urlLoaded = true
                    val u = (application as DshApp).supervisor.webUrl()
                    if (u.isNotEmpty()) loadLocalUrl(u) else urlLoaded = false
                }
                state.recoveredPlugin?.let { getString(R.string.status_plugin_recovered, it) }
                    ?: state.degraded?.let {
                        getString(R.string.status_degraded, it.missingServices.size)
                    }
                    ?: getString(R.string.status_safe_mode)
            }
            is EngineSupervisor.State.Backoff ->
                getString(R.string.status_backoff, state.delayMs / 1000, state.attempt)
            is EngineSupervisor.State.Failed -> {
                // v1.5.3：有结构化诊断时，状态栏显示**本地化的原因标题**而不是
                // 那句英文 reason —— 后者是给日志的（含路径/端口细节），压在
                // 一行状态栏里既挤又看不懂。详情在点开的诊断弹窗里。
                val cause = state.diagnosis?.cause
                if (cause != null) getString(causeTitleResForStatus(cause))
                else getString(R.string.status_failed, state.reason)
            }
            is EngineSupervisor.State.Stopped -> {
                urlLoaded = false
                getString(R.string.status_idle)
            }
        }
        applyIslandActivity(state)
    }

    /**
     * 灵动岛「活跃度」透明度（v1.5.5）。
     *
     * ## 为什么需要
     * 灵动岛一直以满不透明度压在网页上方。引擎就绪后它其实**没有新信息**，
     * 却和「正在安装 / 启动失败」一样显眼 —— 用户的注意力被一块静止的条持续
     * 分走。这正是 iOS 灵动岛空闲时会「退回一点」的原因：把视觉权重让给内容。
     *
     * ## 分档而不是二值
     * - **Idle / Stopped**：0.55 —— 明显退后，但仍能读到状态（不至于以为它消失了）
     * - **Healthy / SafeMode / Degraded**：0.72 —— 正常运行，轻微退让
     * - **Installing / Starting / Backoff / Failed**：1.0 —— 有事发生，必须显眼
     * 用 0.55 而不是 0.42：0.42 时 11sp 的状态文字在浅色网页上基本读不出来
     * （实测对比度掉到 2.7:1），"降透明"不该变成"看不见"。
     *
     * ## 为什么动 alpha 而不是 visibility
     * visibility=GONE 会让整行参与重新布局，收起动画与 insets 会抖；
     * alpha 只影响合成，零布局成本 —— StatusOverlay 的截图隐藏也是同一手法
     * （见 StatusOverlay 里 alpha=0f 的注释）。
     */
    private fun applyIslandActivity(state: EngineSupervisor.State) {
        val row = findViewById<View>(R.id.statusBarRow) ?: return
        // 用户手动收起（GONE）时不要把它拽回来：那种情况由 toggleToolbar 管
        if (row.visibility != View.VISIBLE) return
        val target = islandIdleAlphaFor(state)
        // 已经在动画途中就跳过，避免每次 render 都打断上一段动画
        if (kotlin.math.abs(row.alpha - target) < 0.01f) return
        row.animate().alpha(target).setDuration(220L).start()
    }

    /**
     * 灵动岛在该状态下的目标不透明度（纯函数，供 render 与 toggleToolbar 共用）。
     *
     * 分三档而不是二值，理由见 [applyIslandActivity]。null 视为「未知」→ 0.72
     * （偏向"正常运行"，避免刚启动时岛就隐得看不见）。
     */
    private fun islandIdleAlphaFor(state: EngineSupervisor.State?): Float = when (state) {
        is EngineSupervisor.State.Installing,
        is EngineSupervisor.State.Starting,
        is EngineSupervisor.State.Backoff,
        is EngineSupervisor.State.Failed -> 1.0f
        is EngineSupervisor.State.Healthy,
        is EngineSupervisor.State.SafeMode -> 0.72f
        EngineSupervisor.State.Idle, EngineSupervisor.State.Stopped -> 0.55f
        null -> 0.72f
        else -> 0.72f
    }

    /**
     * 系统返回：手机形态走三段逻辑，其它形态保持原样。
     *
     * 1. `mobile && consumable && canGoBack()` → `goBack()`。`consumable` 是插件
     *    按「自己压了几条同文档条目」上报的，因此这一步不会退到跨文档条目，
     *    也就不会出现整页重载。
     * 2. `mobile` 但没条目可退 → 双击退出：首次轻提示，2 秒内再按才 `finish()`。
     * 3. 非手机形态（桌面 / 原生预设、纯浏览器场景）→ 完全保持 v1.5.x 行为。
     *
     * 注意：`consumable=true` 但 `canGoBack()=false` 时也要走第 2 段 —— 状态与
     * WebView 历史短暂不同步时，宁可提示一次，也不能退到未知条目上。
     */
    override fun onBackPressed() {
        // v1.6.15：先问页面「有没有东西可关」，再考虑历史，最后才退出。
        //
        // 顺序为什么是这样：WebUI 是单页应用，切页面不写浏览器历史（实测
        // `pushState`/`popstate` 出现 0 次），所以 `canGoBack()` 在插件页、
        // 自动化任务页、SSH 工作区这些整页界面上恒为 false —— 旧逻辑会直接
        // `super.onBackPressed()` 退出到桌面，用户感受是「返回键没用，直接退出了」。
        //
        // 但也不能把「问页面」放到历史之后：历史里可能留着上一轮导航的条目
        // （比如从某个深链接进来），先 goBack() 会把用户带到一个不相干的位置，
        // 而当前浮层还开着。
        askBackGuard {
            // ── 手机形态（PR #95）：插件上报「当前有没有同文档条目可退」 ──
            //
            // 不能只信 canGoBack()：WebView 历史里可能留着跨文档条目（启动重定向、
            // runtime 跳转、外链），退过去就是整页重载。只有插件明确说「有条目」
            // 时才 goBack。
            if (backMobile) {
                if (backConsumable && webView.canGoBack() && backTargetIsRealPage()) {
                    webView.goBack()
                    return@askBackGuard
                }
                // 没有可退条目 → 双击退出：首次轻提示，2 秒内再按才 finish()。
                // 注意 consumable=true 但 canGoBack()=false 也要走这里 —— 状态与
                // WebView 历史短暂不同步时，宁可提示一次，也不能退到未知条目上。
                val now = android.os.SystemClock.elapsedRealtime()
                if (lastBackAt > 0L && now - lastBackAt < BACK_EXIT_WINDOW_MS) {
                    lastBackAt = 0L
                    finish()
                    return@askBackGuard
                }
                lastBackAt = now
                Toast.makeText(this, R.string.back_exit_hint, Toast.LENGTH_SHORT).show()
                return@askBackGuard
            }
            // 关键：`canGoBack()` 为 true 不等于「有上一个界面」。
            //
            // 预热时我们往 WebView 里 loadUrl("about:blank")，它**占掉一条历史条目**。
            // 于是真实首页加载完成后 canGoBack() 恒为 true，而 goBack() 的目标正是
            // 那个空白占位页 —— 用户看到的是「右滑一下整个界面变白，像退出网页」。
            // 必须先确认上一条历史是真实页面，否则一律按「没有上一页」处理。
            if (webView.canGoBack() && backTargetIsRealPage()) {
                webView.goBack()
            } else {
                super.onBackPressed()
            }
        }
    }

    /**
     * 历史栈里的「上一条」是不是真实页面（而非预热占位页）。
     *
     * [WebView.copyBackForwardList] 在当前索引为 0 时返回空列表，所以还要
     * 单独判一次 `currentIndex > 0`：列表为空或索引不大于 0 都说明没有上一页。
     *
     * 为什么不直接用 URL 判断：回环页可能是 `http://127.0.0.1:3080/#/xxx` 等各种
     * 形态，而**预热页只有 `about:blank` 一种**。所以只判「是不是空白页」，
     * 更窄也更稳 —— 任何非 about:blank 的历史条目都当真实页面。
     */
    private fun backTargetIsRealPage(): Boolean {
        val list = runCatching { webView.copyBackForwardList() }.getOrNull() ?: return false
        val idx = list.currentIndex
        if (idx <= 0) return false
        val prev = list.getItemAtIndex(idx - 1) ?: return false
        val u = prev.url ?: return false
        return u != preheatUrl && u != "about:blank"
    }

    /**
     * 调页面里的 `__dshBackGuard.back()`，问它「当前有没有可关闭的界面」。
     *
     * 注意这是**异步**的（`evaluateJavascript` 没有同步返回值），而
     * `onBackPressed()` 必须同步决定是否 `super.onBackPressed()`。所以这里
     * 先同步把「要不要退出」压住，等回调回来再决定 —— 否则会出现「已经退出、
     * 回调才说该关浮层」的竞态。
     *
     * 页面未注入（引擎还没起来、或注入失败）时按「没处理」走原逻辑，
     * 不能因为兜底脚本挂了就让返回键彻底失效。
     */
    private fun askBackGuard(onNotHandled: () -> Unit) {
        if (webView.url.isNullOrEmpty()) {
            onNotHandled()
            return
        }
        var decided = false
        val timeout = Runnable {
            if (decided) return@Runnable
            decided = true
            runOnUiThread { onNotHandled() }
        }
        webView.postDelayed(timeout, 260L)
        runCatching {
            webView.evaluateJavascript(
                "(function(){try{return !!(window.__dshBackGuard&&window.__dshBackGuard.back())}" +
                    "catch(e){return false}})()",
            ) { raw ->
                if (decided) return@evaluateJavascript
                decided = true
                webView.removeCallbacks(timeout)
                val handled = raw.trim().trim('"') == "true"
                if (!handled) runOnUiThread { onNotHandled() }
            }
        }.onFailure {
            if (!decided) {
                decided = true
                webView.removeCallbacks(timeout)
                onNotHandled()
            }
        }
    }

    /**
     * 文件选择器结果回传。
     *
     * ⚠️ 必须调用 onReceiveValue（哪怕是 null）：WebView 在回调前会**挂起页面的文件选择
     * 请求**，不调用则页面永久等待（表现为「点了上传没反应」）。用户取消时传 null 即取消。
     */
    @Deprecated("startActivityForResult is the established contract for the WebView file chooser")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == app.dsh.mobile.engine.cap.ScreenRecordRelay.REQ_CAPTURE) {
            // 屏幕录制授权：系统弹窗的「开始录制」结果只能在这里收到，转交给 RecordCap。
            // 打日志是因为授权链路一旦断了，从外部只看到「permission_pending 永远 false」，
            // 无法区分「没回调」与「回调了但 resultCode 不是 OK」—— 这两种要修的地方完全不同。
            android.util.Log.i(
                "DshRecord",
                "record auth result: code=$resultCode data=${data != null} " +
                    "extras=${data?.extras?.keySet()?.joinToString(",") ?: ""}",
            )
            app.dsh.mobile.engine.cap.ScreenRecordRelay.onResult(resultCode, data)
            return
        }
        if (requestCode == REQ_FILE_CHOOSER) {
            val cb = pendingFileCallback
            pendingFileCallback = null
            if (cb == null) {
                super.onActivityResult(requestCode, resultCode, data)
                return
            }
            val uris: Array<Uri>? = when {
                resultCode != RESULT_OK -> null
                else -> {
                    val fromData = android.webkit.WebChromeClient.FileChooserParams
                        .parseResult(resultCode, data)
                    // 部分 ROM 的选择器把结果放在 clipData（多选/分享式选择器），parseResult 拿不到
                    if (fromData != null && fromData.isNotEmpty()) fromData
                    else data?.clipData?.let { cd -> Array(cd.itemCount) { cd.getItemAt(it).uri } }
                        ?: data?.data?.let { arrayOf(it) }
                }
            }
            runCatching { cb.onReceiveValue(uris) }
                .onFailure { logWebView("file chooser result delivery failed: ${it.message}") }
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    // ==================== 语音输入（v1.2.58 起，v1.2.65 移交服务托管） ====================
    //
    // 麦克风入口、识别编排、输入面板都在 DshAccessibilityService 侧
    // （见其 startVoiceInput / VoicePanel）—— 因为它们需要 TYPE_ACCESSIBILITY_OVERLAY
    // 窗口，且不应依赖本 Activity 存活。
    // 本 Activity 只负责两件事：
    //   ① 注册「把文字送进 WebUI 输入框」的能力（VoiceBridge.registerSender）
    //   ② 用户从主界面发起语音时申请麦克风权限（onRequestPermissionsResult 回调里
    //      转交服务；requestPermissions 是 Activity 才能做的事）

    /**
     * 发送语音文本：写进 WebUI 输入框并触发发送。
     * 走 DOM 注入（而非引擎 API），因为要复用前端既有的发送流程。
     */
    /**
     * 发送语音文本：写进 WebUI 输入框并触发发送。
     *
     * ⚠️ v1.2.65：按**实际结果**反馈，不再无条件弹「已发送」——
     * 旧版注入失败（如停在首页无会话）也报成功，用户实测「实际无作用」却看到「已发送」。
     *  · ok:*            → 已发送
     *  · entering-session→ 已在进入会话，稍后自动重发一次
     *  · no-input-found  → 明确提示「当前没有可发送的会话」
     *  · err:*           → 报具体错误
     */
    /**
     * 发送去重守卫（v1.2.90）。
     *
     * 🔴 主人实测「语音输入还是一次性复制两段发出去」。
     * 除了面板按钮的 consumed 守卫，这里再按**文本内容 + 时间窗**兜一层：
     * 相同文本在 [DEDUP_WINDOW_MS] 内只允许发送一次。
     *
     * 为什么需要两层：重复可能来自不同入口（面板按钮重复触发、服务回调重入、
     * 重试路径），单堵一处不够。这一层是**最终防线**，且对"用户真的想连说两遍
     * 同样的话"影响很小（1.5 秒内几乎不可能刻意重复）。
     */
    @Volatile private var lastSentText: String = ""
    @Volatile private var lastSentAt: Long = 0L

    private fun sendVoiceText(text: String) {
        val now = System.currentTimeMillis()
        if (text == lastSentText && now - lastSentAt < DEDUP_WINDOW_MS) {
            logWebView("voice send deduped: '$text' within ${now - lastSentAt}ms")
            return
        }
        lastSentText = text
        lastSentAt = now
        sendVoiceTextOnce(text, allowRetry = true)
    }

    private fun sendVoiceTextOnce(text: String, allowRetry: Boolean) {
        val js = buildVoiceInputJs(text)
        webView.evaluateJavascript(js) { raw ->
            val result = jsResult(raw)
            logWebView("voice send: $result")
            when {
                result.startsWith("ok:") -> {
                    // v1.2.95：写入（paste）后先**校验是否真的落了地**再提交。
                    // Lexical 等框架编辑器对合成 paste 的处理是异步的，且对
                    // execCommand 会翻倍 —— 所以不能在 JS 里同步回退，改由
                    // Kotlin 在 FIRE_DELAY_MS 后读框内容分派：
                    //   · 框里 == 发送文本 → paste 成功 → 提交
                    //   · 框里 != 文本     → paste 无效 → repair（execCommand）
                    webView.postDelayed({ verifyWriteThenFire(text) }, FIRE_DELAY_MS)
                }
                result == "entering-session" && allowRetry -> {
                    // 刚从首页点进会话：等 UI 渲染出输入框后重试一次。
                    //
                    // ⚠️ v1.2.94：重写前必须确认输入框里**没有我们那段文字**。
                    // 旧版只判断"框里有没有字"，而"有字"既可能是「上次没写进去」，
                    // 也可能是「上次写进去了、前端正在提交但还没清空」——
                    // 后者会让我们把同一句话再写一遍 → 主人实测的「两段」。
                    //
                    // 现在按**文本内容**比对：只有框里既非空、又不是我们刚发的那段
                    // （= 用户自己打的字），才放弃重写；否则按情况重写或补提交。
                    Toast.makeText(this, getString(R.string.voice_opening_session), Toast.LENGTH_SHORT).show()
                    webView.postDelayed({
                        readSendText { inBox ->
                            when {
                                inBox == text -> {
                                    // 正是我们那段、还躺在框里 → 补一次提交（令牌在写入时已发放）
                                    logWebView("retry: our text still in box, submitting it")
                                    fireSendKey()
                                    webView.postDelayed({ pollSendVal(text, attempt = 0) }, POLL_MS)
                                }
                                inBox.isNotEmpty() -> {
                                    // 是别的文字（用户自己打的）→ 绝不覆盖、绝不提交
                                    logWebView("retry skipped: box has unrelated text, leaving it alone")
                                    reportVoiceTarget("")
                                }
                                else -> {
                                    // 框是空的 → 上次确实没写进去，安全重写
                                    sendVoiceTextOnce(text, allowRetry = false)
                                }
                            }
                        }
                    }, 1_500L)
                }
                result == "entering-session" -> {
                    Toast.makeText(this, getString(R.string.voice_session_timeout), Toast.LENGTH_LONG).show()
                }
                result == "no-input-found" -> {
                    Toast.makeText(this, getString(R.string.voice_no_session), Toast.LENGTH_LONG).show()
                }
                else -> {
                    Toast.makeText(
                        this, getString(R.string.voice_send_failed, result), Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    /**
     * Web 内核版本门禁（v1.2.100）。
     *
     * ## 为什么需要
     * 引擎前端用了 `??=` / 类字段等**解析期**语法（Chrome 85+），polyfill 只能补
     * **API 层**（13 个现代 API 注入），补不了语法 —— WebView 过旧时页面必白屏。
     * 之前的处理是「照常启动引擎 + 日志里记录 SyntaxError」：用户看到的是
     * 白屏/无限重启，不知道原因（酷安用户反馈的正是这种体验）。
     *
     * ## 做法
     * 直接查 WebView Provider 的包版本（不走 JS，无需加载页面）：
     *   · ≥ [MIN_WEBVIEW_CHROME]：放行（本会话内不再重复检查）
     *   · < 门槛：弹对话框，给出当前版本、升级路径（应用商店/Play），并提供
     *     「仍然继续」—— 用户明确坚持才启动引擎（尊重知情选择）；对话框
     *     不会因 AlertDialog 取消而反复弹出，选择「继续」后本会话记入记忆。
     *
     * @return true = 放行（版本达标或用户坚持）
     */
    /**
     * 当前显示的版本门禁对话框（v1.2.100）。
     * ⚠️ 必须持有引用并判重：`onResume` 会被反复触发（切回前台、息屏亮屏、点按钮后
     * 重入），旧实现每次都 `show()` 一个新对话框 → **无限叠堆**（且 setCancelable(false)
     * 无法取消）。独立审查指出后补上这道守卫。
     */
    private var gateDialog: android.app.AlertDialog? = null

    /**
     * 用户在内核门禁里点了「用浏览器打开」（v1.6.8）。
     *
     * ## 为什么需要延迟
     * 门禁在 `onResume` 里跑，早于引擎 `Healthy`，此刻还没有带 token 的 WebUI 地址，
     * 直接 `startActivity` 只能打开一个 401 页面。所以这里只置标记，等
     * `render()` 拿到可用地址时立刻拉起浏览器 —— 用户点一次就够，不必自己再点。
     *
     * 只在内存里：一次性的动作语义，进程重启后本就该重新协商。
     */
    private var pendingBrowserFallback = false

    private fun checkWebViewGate(): Boolean {
        // 把实例 UA 传进去做第三级兜底（前两级都读不到时才会用到）。
        // 实例此刻已由 onCreate 的 setupWebView 建好，UA 是内核真实版本。
        val ua = runCatching { if (::webView.isInitialized) webView.settings.userAgentString else null }
            .getOrNull()
        val report = WebViewDiagnostics.inspect(this, ua)
        AppGate.applyReport(report)
        if (report.allowed) {
            // 达标，或「读不到版本」（读不到不等于太旧，见 WebViewDiagnostics.Cause）
            logWebView("webview gate pass: ${WebViewDiagnostics.summary(report)}")
            return true
        }

        if (gateDialog?.isShowing == true) {
            logWebView("webview gate: dialog already showing, skip")
            return false
        }
        val msg = getString(
            R.string.webview_gate_body,
            if (report.chrome > 0) report.chrome.toString() else "?",
            AppGate.MIN_WEBVIEW_CHROME,
        )
        val builder = android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.webview_gate_title))
            .setMessage(msg)
            .setPositiveButton(R.string.webview_gate_update) { _, _ ->
                openWebViewUpdate(report)
            }
            .setNegativeButton(R.string.webview_gate_continue) { _, _ ->
                AppGate.overrideWebViewGate()
                onResume()   // 重新走一遍（这次会启动引擎）
            }
            .setCancelable(false)

        // 第三条出路（v1.6.8）：把同一个 WebUI 地址交给系统浏览器渲染。
        // 这是「内核换不了」前提下**唯一**能让老设备真正看到界面的办法 ——
        // 回环地址在任何浏览器里都能访问，功能全在引擎侧，WebView 只是显示器。
        // 没有可用浏览器时（极罕见的无浏览器 ROM）不显示该按钮。
        val hasBrowser = WebViewDiagnostics.browserFallbackIntent(
            this, PROBE_URL_FOR_BROWSER,
        ) != null
        if (hasBrowser) {
            builder.setNeutralButton(R.string.webview_gate_open_browser) { _, _ ->
                // 此刻引擎可能还没起来（门禁在 onResume，早于引擎 Healthy），
                // 拿不到带 token 的地址。置一个待办标记，等首个 Healthy + URL
                // 就绪时立刻拉起浏览器，用户不必自己去点第二次。
                pendingBrowserFallback = true
                AppGate.overrideWebViewGate()
                onResume()
            }
        }

        builder.create().also { gateDialog = it }.show()
        logWebView("webview gate: ${WebViewDiagnostics.summary(report)} → prompting user")
        return false
    }

    /**
     * 把当前 WebUI 地址交给系统浏览器（v1.6.8）。
     *
     * ## 这条路径为什么成立
     * 引擎只监听 `127.0.0.1`，而回环地址在**设备自身的任何浏览器**里都能访问 ——
     * 外部浏览器与 App 共享同一网络命名空间。并且 `http://127.0.0.1` 属于
     * "potentially trustworthy origin"，是 secure context，
     * `crypto.randomUUID` 这类仅安全上下文可用的 API 正常可用
     * （对照：局域网 IP 不是 secure context，所以 LAN WebUI 才需要那个注入
     * randomUUID 的反向代理，见 docs/LAN_WEBUI.md）。
     *
     * 界面与功能全在引擎侧，WebView 只是个显示器，换成浏览器不损失能力。
     * 这是「内核换不了」前提下老设备唯一能真正出界面的出路。
     */
    private fun openInExternalBrowser(url: String) {
        val intent = WebViewDiagnostics.browserFallbackIntent(this, url)
        if (intent == null) {
            logWebView("browser fallback: no activity can handle http")
            Toast.makeText(this, getString(R.string.webview_gate_no_browser), Toast.LENGTH_LONG).show()
            return
        }
        val ok = runCatching { startActivity(intent) }
            .onFailure { logWebView("browser fallback failed: ${it.message}") }
            .isSuccess
        if (ok) logWebView("browser fallback: opened WebUI in external browser")
    }

    /**
     * 按 [WebViewDiagnostics.updateIntents] 给出的顺序尝试打开升级入口，
     * 全部失败时用 Toast 告知（不能静默 —— 用户点了「去更新」却什么都没发生
     * 是最差的体验）。
     */
    private fun openWebViewUpdate(report: WebViewDiagnostics.Report) {
        val intents = WebViewDiagnostics.updateIntents(this, report)
        for (intent in intents) {
            val ok = runCatching { startActivity(intent) }.isSuccess
            if (ok) {
                logWebView("webview gate: opened update target ${intent.data}")
                return
            }
        }
        logWebView("webview gate: every update target failed (report=${WebViewDiagnostics.summary(report)})")
        Toast.makeText(
            this,
            getString(R.string.webview_gate_no_store, report.provider ?: "com.google.android.webview"),
            Toast.LENGTH_LONG,
        ).show()
    }

    /*
     * ⚠️ v1.6.8：原来的 `currentWebViewVersion()` / `WEBVIEW_PROVIDERS` 已移除。
     * 版本探测搬到 [WebViewDiagnostics]（单一真源）：原先它既是 private、
     * 又缺「读不到 ≠ 太旧」的区分，别处想显示同一份信息还得复制一遍。
     * 现在门禁与日志共用同一份 [WebViewDiagnostics.Report]。
     */

    /** evaluateJavascript 的返回值带 JSON 引号与转义，统一清一遍 */
    private fun jsResult(raw: String?): String =
        raw?.trim('"')?.replace("\\u003d", "=")?.replace("\\", "") ?: ""

    /**
     * 解析 `evaluateJavascript` 返回的**字符串值**（v1.2.94）。
     *
     * ⚠️ 不能复用 [jsResult]：它会把所有反斜杠删掉（那是为状态标记这种
     * 纯 ASCII 短串设计的）。用来比对用户文本时，反斜杠/引号会被破坏，
     * 导致"文字相同却判为不同" → 重写一遍 → 又是「两段」。
     * 这里用 JSON 解析，完整保留原字符。
     */
    private fun jsString(raw: String?): String = runCatching {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty() || s == "null") ""
        else org.json.JSONTokener(s).nextValue() as? String ?: s
    }.getOrDefault(raw?.trim('"').orEmpty())

    /**
     * 页面是否只接受图片（v1.4.27，issue #54）。
     *
     * 只看 `accept` 属性：全部条目都是图片类型才算「只要图片」。混合类型
     * （如 `image/…` 混 `application/pdf`）不算——那种场景用户可能要选文档，
     * 走通用文件选择器才不会挡住他。
     */
    private fun wantsOnlyImages(params: android.webkit.WebChromeClient.FileChooserParams?): Boolean {
        val accepts = runCatching { params?.acceptTypes?.toList() }.getOrNull().orEmpty()
            .filter { it.isNotBlank() }
        if (accepts.isEmpty()) return false
        return accepts.all { a ->
            val t = a.trim().lowercase()
            t == "image/*" || t.startsWith("image/")
        }
    }

    /**
     * 构造照片选择器 Intent（v1.4.27，issue #54）。
     *
     * - API 33+：`ACTION_PICK_IMAGES`（系统照片选择器，缩略图网格、免存储权限）；
     * - 更低版本 / 选择器不可用：回退 `ACTION_GET_CONTENT` + 图片类型。
     *
     * 多选只在页面声明 multiple 时开启，避免单选场景「选了多张只回传一张」的困惑。
     */
    private fun buildPhotoPickerIntent(
        params: android.webkit.WebChromeClient.FileChooserParams?,
    ): Intent {
        val multiple = runCatching {
            params?.mode == android.webkit.WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE
        }.getOrDefault(false)
        if (Build.VERSION.SDK_INT >= 33) {
            val pick = Intent(android.provider.MediaStore.ACTION_PICK_IMAGES).apply {
                type = "image/*"
                if (multiple) putExtra(android.provider.MediaStore.EXTRA_PICK_IMAGES_MAX, 20)
            }
            if (pick.resolveActivity(packageManager) != null) return pick
        }
        return Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            if (multiple) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
    }

    /**
     * 提交输入框内容：调用 JS 里的**同步钩子** `__dshFire()`。
     *
     * 钩子本身不排任何定时器 —— 谁调用它、什么时候调用，由 Android 侧决定。
     * JS 端带 1.5 秒节流窗与空框守卫，重复调用不会造成重复发送。
     */
    private fun fireSendKey() {
        webView.evaluateJavascript("(window.__dshFire && window.__dshFire()) || 'no-hook'") { raw ->
            logWebView("voice fire: ${jsResult(raw)}")
        }
    }

    /** 读输入框状态：empty/gone = 已提交；text = 仍在框里；none = 找不到元素 */
    private fun readSendVal(cb: (String) -> Unit) {
        webView.evaluateJavascript("window.__dshReadVal ? window.__dshReadVal() : 'none'") { raw ->
            cb(jsResult(raw))
        }
    }

    /**
     * 校验 paste 写入是否落地，落地才提交；没落地走 repair（v1.2.95）。
     *
     * 为什么必须有这一步：Lexical 对合成 paste 的处理是**异步**的——JS 注入返回时
     * 内容还没进编辑器。若不看结果直接提交，消息可能是旧的；若在 JS 里同步回退
     * （execCommand），Lexical 会把文本翻倍（主人实测「你好你好」）。
     * 所以「写入 → 校验 → 提交/补写」全部由 Android 侧时序驱动。
     */
    private fun verifyWriteThenFire(text: String) {
        readSendText { inBox ->
            when {
                inBox == text -> {
                    // paste 已落地 → 发放提交令牌并点击
                    webView.evaluateJavascript("window.__dshTicket = true") { _ ->
                        fireSendKey()
                        webView.postDelayed({ pollSendVal(text, attempt = 0) }, POLL_MS)
                    }
                }
                inBox.isEmpty() || inBox == "none" -> {
                    // paste 无效（编辑器不认合成 paste）→ execCommand 补写
                    logWebView("voice write: paste had no effect, repairing (execCommand)")
                    webView.evaluateJavascript(buildVoiceRepairJs(text)) { raw ->
                        logWebView("voice repair: ${jsResult(raw)}")
                        readSendText { after ->
                            if (after == text) {
                                webView.evaluateJavascript("window.__dshTicket = true") { _ ->
                                    fireSendKey()
                                    webView.postDelayed({ pollSendVal(text, attempt = 0) }, POLL_MS)
                                }
                            } else {
                                logWebView("voice write failed after repair: '$after'")
                                Toast.makeText(this, getString(R.string.voice_not_sent), Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
                else -> {
                    // 框里是别的内容（用户自己打的字）→ 绝不覆盖，放弃本次发送
                    logWebView("voice write: box holds unrelated text '$inBox', aborting")
                    Toast.makeText(this, getString(R.string.voice_not_sent), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * 读输入框里的**实际文字**（v1.2.94）。
     *
     * 与 [readSendVal] 的区别：那个只回答"空不空"，这个给出内容 —— 重试路径
     * 必须靠它区分「我们刚写进去的那段」和「用户自己打的字」，
     * 否则会把用户的输入覆盖掉、或把我们那段提交两遍（主人实测的「两段」）。
     */
    private fun readSendText(cb: (String) -> Unit) {
        webView.evaluateJavascript("window.__dshText ? window.__dshText() : ''") { raw ->
            cb(jsString(raw))
        }
    }

    /**
     * 轮询发送结果（v1.2.94 再修）。
     *
     * 判定源是**同步读值**（`__dshReadVal`），由 Android 侧定时轮询，前台后台一个样。
     * 判定依据：前端提交后会把输入框清空。
     *
     * 🔴 v1.2.93 之前的版本在 4 秒超时后会**再点一次发送**，主人实测
     * 「消息还是会被复制成两段」。那就是重复的来源：
     *  · JS 的 1.5s 节流窗拦不住它（4s > 1.5s）
     *  · Kotlin 的 1.5s 同文本去重也拦不住（重发在 sendVoiceTextOnce 内部，绕过它）
     *  · 前端若因网络慢还没清空输入框，这一下就真的提交了第二遍
     *
     * 现在**绝不重发**：提交动作每段文字只做一次（JS 侧一次性令牌保证），
     * 4 秒后仍没清空就如实报「未发出」，把判断权交回用户 —— 宁可少发，
     * 不可重复发（重复消息会污染对话，用户很难撤销）。
     *
     * @param attempt 已轮询次数（上限 [MAX_POLL] ≈ 4 秒）
     */
    private fun pollSendVal(text: String, attempt: Int) {
        readSendVal { st ->
            when {
                st == "empty" || st == "gone" -> reportVoiceTarget("")
                st == "none" -> {
                    // 输入框不在了（页面被导航走等）：按已提交处理，与旧版语义一致
                    logWebView("voice send: input gone (assume committed)")
                    reportVoiceTarget("")
                }
                attempt < MAX_POLL -> {
                    webView.postDelayed({ pollSendVal(text, attempt + 1) }, POLL_MS)
                }
                else -> {
                    // 超时仍未清空：**不再补发**（那正是「两段」的来源），如实报未发出
                    logWebView("voice send: still in box after ${MAX_POLL * POLL_MS}ms, not retrying")
                    Toast.makeText(this, getString(R.string.voice_not_sent), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * 报告语音文本**真正落到了哪个对话**（v1.2.72）。
     *
     * 主人反复问「我都不知道他发哪个对话、究竟能不能发」—— 光靠 DOM 猜标题不可靠，
     * 改用引擎自己的账本：发送成功后该会话的 `lastPromptAt` 会被刷新为刚刚，
     * 于是「最近 20 秒内活跃过、且非空白」的那个会话就是真正的落点，报它的标题。
     *
     * @param domTitle DOM 里捞到的标题（兜底，通常为空或应用名）
     */
    private fun reportVoiceTarget(domTitle: String) {
        Thread({
            val now = System.currentTimeMillis()
            val hit = runCatching {
                app.dsh.mobile.engine.SessionReader.list(this)
                    .firstOrNull { !it.blank && now - it.lastActiveAt < 20_000L }
            }.getOrNull()
            val name = hit?.title?.takeIf { it.isNotBlank() } ?: domTitle
            val msg = if (name.isBlank()) getString(R.string.voice_sent)
            else getString(R.string.voice_sent_to, name)
            webView.post { runCatching { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() } }
        }, "voice-target").apply { isDaemon = true; start() }
    }

    /** 授权网页媒体请求（一次性对象，只能调用一次）。 */
    private fun grantAudio(request: android.webkit.PermissionRequest) {
        runCatching {
            request.grant(arrayOf(android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE))
        }.onFailure { logWebView("permission: grant audio failed: ${it.message}") }
        if (pendingWebPermission === request) pendingWebPermission = null
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        // issue #72：网页 getUserMedia 的系统权限回执
        if (requestCode == REQ_WEB_AUDIO) {
            val req = pendingWebPermission
            pendingWebPermission = null
            val ok = grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (req == null) return
            if (ok) {
                grantAudio(req)
            } else {
                runCatching { req.deny() }
                Toast.makeText(this, getString(R.string.voice_need_permission), Toast.LENGTH_SHORT).show()
            }
            return
        }
        if (requestCode == REQ_RECORD_AUDIO) {
            val ok = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (ok) {
                // 授权成功 → 交给无障碍服务弹面板并开始识别（面板归服务托管）
                DshAccessibilityService.instance?.startVoiceInput()
            } else {
                Toast.makeText(this, getString(R.string.voice_need_permission), Toast.LENGTH_SHORT).show()
            }
            return
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    override fun onDestroy() {
        // 注意：引擎由前台服务持有，Activity 销毁不影响后台任务。
        // 首启跳 Onboarding 时本 Activity 立即销毁，webView 尚未初始化——
        // lateinit 直接访问会崩（Android 11 新用户首启闪退实测）。
        // relay 里的 Activity 引用必须先摘掉，否则重建后会留下失效实例。
        runCatching {
            app.dsh.mobile.engine.cap.NfcForegroundRelay.detach(this)
            app.dsh.mobile.engine.cap.ScreenRecordRelay.detach(this)
        }
        uiScope.cancel()
        // 注销显式退出回调（避免持有已销毁 Activity）。
        // ⚠️ 实例感知：只在**当前注册的就是自己**时才清，否则 recreate 的时序
        //（旧 onDestroy 晚于新 onCreate）会把新实例的回调误清掉。
        runCatching {
            val sup = (application as DshApp).supervisor
            if (sup.onExplicitExit === exitCallback) sup.onExplicitExit = null
        }
        // 注销语音发送能力：本 Activity 已销毁，服务侧再发会拿到明确的「需打开 App」提示
        // （而不是把文字注入到一个已销毁的 WebView）
        stopHangWatchdog()
        splashController?.dispose()
        splashController = null
        splash = null
        splashDismissed = true
        VoiceBridge.registerSender(null)
        // 释放未完成的文件选择请求，否则 WebView 侧回调悬空
        pendingFileCallback?.let { runCatching { it.onReceiveValue(null) } }
        pendingFileCallback = null
        if (::webView.isInitialized) webView.destroy()
        super.onDestroy()
    }

    // ==================== WebView 卡死自愈（v1.2.100，issue #8） ====================
    //
    // ## 问题
    // 用户反馈：「运行到一半会话内容卡住，计时器还在正常计数，模型仍在运行，
    // 需要关闭进程重启应用，偶发」。症状 = **后端正常、前端停止更新**。
    // 两种成因：
    //  ① WebView 渲染进程崩溃/被系统回收 → 页面残留但不再刷新
    //     （未实现 onRenderProcessGone 时 Android 甚至会直接杀掉整个 App）
    //  ② 前端 JS 事件循环被长任务卡住（渲染进程还活着，但不再处理消息）
    //
    // ## 做法：两级看门狗
    //  ① 渲染进程崩溃 → onRenderProcessGone 里 [recoverWebView]（重建 WebView + 重载）
    //  ② 页面活着但不响应 → 定时注入轻量探针，连续失败则同样走 [recoverWebView]
    //
    // ⚠️ 引擎**不重启**（它由前台服务持有，会话与进行中的任务都在），
    // 只重建渲染层，用户视角是「页面自己恢复了」。

    private val hangWatchdog = android.os.Handler(android.os.Looper.getMainLooper())

    /** 连续探针失败次数（成功即清零；达阈值判定卡死） */
    private var probeFails = 0

    /** 页面探针：更新一个时间戳；若 eval 回调迟迟不回来，说明主线程/JS 卡住 */
    private val HANG_PROBE_JS =
        "(function(){window.__dshProbe=Date.now();return String(window.__dshProbe);})()"

    private fun startHangWatchdog() {
        stopHangWatchdog()
        hangWatchdog.postDelayed(hangTick, HANG_CHECK_INTERVAL_MS)
    }

    /** 看门狗的一次心跳：探针 → 判定 → 排下一次 */
    private val hangTick = object : Runnable {
        override fun run() {
            if (!::webView.isInitialized) return
            probePage { alive ->
                if (alive) {
                    probeFails = 0
                } else {
                    probeFails++
                    logWebView("hang probe failed ($probeFails/$HANG_FAIL_LIMIT)")
                    if (probeFails >= HANG_FAIL_LIMIT) {
                        logWebView("page unresponsive — recovering webview (engine untouched)")
                        recoverWebView()
                        probeFails = 0
                        return@probePage   // recoverWebView 内部会重启看门狗
                    }
                }
            }
            hangWatchdog.postDelayed(this, HANG_CHECK_INTERVAL_MS)
        }
    }

    private fun stopHangWatchdog() {
        hangWatchdog.removeCallbacks(hangTick)
        probeFails = 0
    }

    /**
     * 探针：注入一段极短的 JS 并等待回调。
     * @param cb true = 页面有响应；false = 超时或异常
     */
    private fun probePage(cb: (Boolean) -> Unit) {
        var done = false
        val timeout = Runnable {
            if (!done) {
                done = true
                cb(false)
            }
        }
        hangWatchdog.postDelayed(timeout, HANG_PROBE_TIMEOUT_MS)
        runCatching {
            webView.evaluateJavascript(HANG_PROBE_JS) { raw ->
                if (!done) {
                    done = true
                    hangWatchdog.removeCallbacks(timeout)
                    cb(raw != null && raw.contains("1"))   // 时间戳必然含数字
                }
            }
        }.onFailure {
            if (!done) {
                done = true
                hangWatchdog.removeCallbacks(timeout)
                cb(false)
            }
        }
    }

    /**
     * 重建渲染层：销毁旧 WebView、新建并重新加载当前 URL。
     * 引擎与会话完全不受影响（它们不在渲染进程里）。
     */
    private fun recoverWebView() {
        runCatching {
            stopHangWatchdog()
            val parent = webView.parent as? android.view.ViewGroup
            val oldUrl = runCatching { webView.url }.getOrNull()
            parent?.removeView(webView)
            runCatching { webView.destroy() }
            val fresh = WebView(this)
            fresh.id = R.id.webView
            parent?.addView(fresh, android.view.ViewGroup.LayoutParams(-1, -1))
            webView = fresh
            // 新渲染层还没有页面上报：先回到「不认识」状态，让 onBackPressed
            // 走原逻辑，避免拿旧 WebView 的条目判断去退新页面。
            backMobile = false
            backConsumable = false
            lastBackAt = 0L
            setupWebView()
            // v1.6.0：新 WebView 是全新的，预热标记要跟着重置，
            // 否则 isPreheatNavigation() 会把真实页面误判成预热页而跳过全部注入。
            webViewPreheated = false
            // 引擎仍在跑 → 直接回到它给的地址（没有则等 Healthy 回调重载）
            val target = oldUrl ?: runCatching {
                (application as DshApp).supervisor.webUrl()
            }.getOrNull()
            if (!target.isNullOrBlank()) {
                urlLoaded = false   // 交给 Healthy/加载流程统一处理
                webView.loadUrl(target)
            } else {
                // 引擎还没就绪：同样先预热，别让 Chromium 再冷启一次。
                preheatWebView()
            }
            startHangWatchdog()   // 新页面继续受监控
            logWebView("webview recovered (reloaded ${target ?: "engine url pending"})")
        }.onFailure {
            logWebView("webview recovery failed: ${it.message} — recreating activity")
            runCatching { recreate() }
        }
    }

    /** WebView 诊断日志（写 logcat + engine.log，用户可导出） */
    /**
     * 把外部共享进来的文件投递给 WebUI（v1.6.17）。
     *
     * ## 投递方式
     * 直接把路径以文本形式写进对话输入框，而不是伪造一个 `File` 对象塞给
     * `<input type=file>` —— 后者在 WebView 里无法用 JS 设置（安全限制，
     * `input.files` 只读），硬做要重新走一遍 `onShowFileChooser`，等于让
     * 用户再选一次文件，白折腾。
     *
     * 写路径进输入框的实际效果：用户看到一行 `[已接收] /data/.../inbox/x.pdf`，
     * 直接回车即可让 AI 去读。AI 有 `cat`/`ls`/`read` 能力，路径就是它能用
     * 的最小充分信息。
     *
     * ## 为什么用 setRangeText 而不是改 value
     * 上游 WebUI 是受控组件（React 式），直接改 `value` 不会触发框架的状态
     * 更新，用户一打字就被覆盖回去。`setRangeText` 触发的是原生 input 事件，
     * 框架能感知。先 focus 再插入，光标位置也正确。
     */
    private fun deliverInboxFiles(view: WebView) {
        val files = Inbox.drain(this)
        if (files.isEmpty()) return

        // 门禁 check-i18n.py 禁止 Kotlin 字符串里出现 CJK（文案必须走资源文件）。
        // 这是给 AI 看的技术标记，不是用户可见文案，用 ASCII 前缀最省事。
        val text = files.joinToString("\n") { "[received] $it" }
        // raw string 里写 ${'$'}{JSON.stringify(...)} 会被 Kotlin 当插值求值 → 未定义。
        // 在 Kotlin 侧先算好带引号的 JS 字面量再插进来。
        val jsText = org.json.JSONObject.quote(text)
        val js = """
            (function(){
              const t = $jsText;
              const el = document.querySelector('textarea, [contenteditable="true"], input[type="text"]');
              if (!el) return 'no-input';
              el.focus();
              if (el.tagName === 'TEXTAREA' || el.tagName === 'INPUT') {
                const start = el.selectionStart ?? el.value.length;
                const end = el.selectionEnd ?? el.value.length;
                const ins = (el.value && start > 0 ? "\n" : "") + t;
                el.setRangeText(ins, start, end, 'end');
                el.dispatchEvent(new Event('input', { bubbles: true }));
              } else {
                // contenteditable path: insertText keeps the undo stack
                document.execCommand('insertText', false, t);
              }
              return 'ok';
            })();
        """.trimIndent()
        view.evaluateJavascript(js) { result ->
            logWebView("inbox delivery -> $result (${files.size} file(s))")
        }
    }

    private fun logWebView(message: String) {
        android.util.Log.w("DshWebView", message)
        runCatching {
            val f = app.dsh.mobile.engine.EngineConfig.engineRoot(this).let {
                java.io.File(it, "engine.log")
            }
            // ⚠️ 本方法由 WebView 的 onConsoleMessage 调用，**跑在 UI 线程**：
            // 直接 appendText 是同步磁盘 I/O，会让渲染掉帧；而引擎日志泵线程同时在写
            // 同一文件、两者无锁，落盘内容可能交错。统一交给 EngineLog（串行 + 后台线程）。
            app.dsh.mobile.engine.EngineLog.appendAsync(f, "[webview] $message\n")
        }
    }

    companion object {
        /** WebView 文件选择器的请求码（onShowFileChooser → onActivityResult） */
        private const val REQ_FILE_CHOOSER = 1001

        /**
         * 「再按一次退出」的窗口（毫秒）。
         *
         * 与插件侧 `BACK_EXIT_WINDOW_MS` 保持一致：提示 Toast 的时长、
         * 用户的反应节奏、窗口三者对齐，才不会出现「提示已消失但仍算第二次」。
         */
        private const val BACK_EXIT_WINDOW_MS = 2_000L

        /** 麦克风权限请求码（语音输入，v1.2.58） */
        private const val REQ_RECORD_AUDIO = 1002

        /** 网页 getUserMedia 的麦克风权限请求（issue #72，与原生语音区分开） */
        private const val REQ_WEB_AUDIO = 1003

        /** 发送去重窗口（v1.2.90）：同文本在此时间内只发一次，防重复 */
        private const val DEDUP_WINDOW_MS = 1_500L

        /**
         * 页面卡死看门狗的时序（v1.2.100，issue #8）。
         * 探针注入极短 JS 并要求回执；连续 [HANG_FAIL_LIMIT] 次无响应即判定卡死。
         * 间隔取 15s：既不会漏掉「跑了很久才发现」的情况，也不会频繁打扰正常页面。
         */
        private const val HANG_CHECK_INTERVAL_MS = 15_000L
        /** 单次探针等待上限（超过即算无响应） */
        private const val HANG_PROBE_TIMEOUT_MS = 3_000L
        /** 连续失败几次判定卡死（3 次 ≈ 45s 无响应，足够排除偶发抖动） */
        private const val HANG_FAIL_LIMIT = 3

        /**
         * 探测「设备上有没有浏览器」用的占位地址（v1.6.8）。
         *
         * ⚠️ 不能拿真实 WebUI 地址来探测：门禁执行时引擎还没起来，`webUrl()` 是空串，
         * `resolveActivity` 对空 URL 恒为 false，会导致「明明有浏览器却不显示按钮」。
         * 这里只关心有没有 Activity 能处理 http 浏览，所以给一个永远可解析的回环地址。
         */
        private const val PROBE_URL_FOR_BROWSER = "http://127.0.0.1/"


        /*
         * ⚠️ v1.6.8：`WEBVIEW_PROVIDERS` 已迁到 [WebViewDiagnostics.KNOWN_PROVIDERS]。
         * 保留在 MainActivity 的 companion 里会让「哪些包算 provider」出现两份，
         * 一处更新另一处忘记就会静默分叉。
         */

        /**
         * 语音发送的 Android 侧时序（v1.2.92，v1.2.94 调整）。
         *
         * 全部走 Handler，不依赖 JS 定时器或渲染帧 —— 后台 WebView 既不产帧、
         * JS 定时器也可能被节流，只有进程级 Handler 在前后台行为一致。
         */
        /** 写入输入框后、点发送键前的等待：给 React 受控组件落地 state 的时间 */
        private const val FIRE_DELAY_MS = 250L
        /** 结果轮询间隔 */
        private const val POLL_MS = 100L
        /** 轮询上限（× POLL_MS ≈ 4 秒）；超时只报未发出，**绝不重发**（防重复） */
        private const val MAX_POLL = 40
        /** 「输入框是否确定为空」的采样间隔（见 probeInputEmpty） */
        private const val PROBE_INTERVAL_MS = 60L

        /**
         * v1.4.20（issue #43）：剪贴板降级链。页面普遍用
         * `navigator.clipboard.writeText()`（要求文档焦点 + 用户手势），WebView 里
         * 常被焦点/激活限制拒绝，而页面实现多把异常吞掉——用户感知就是「复制按钮
         * 坏了」。这里把 writeText 包装成降级链：
         *   ① 原生 API（有焦点时行为不变）→ ② 原生桥 DshAndroidClipboard（绕开
         *   焦点限制）→ ③ execCommand('copy')（textarea 必须 position:fixed+
         *   opacity:0，display:none 会复制失败）→ ④ 全失败才把原异常抛回。
         * 老内核没有 clipboard API 时直接装一个可用的 writeText。
         */
        private const val CLIPBOARD_FALLBACK_JS = """
(function () {
  if (window.__dshClipboardPatched) return;
  window.__dshClipboardPatched = true;
  function nativeCopy(t) {
    try { return !!(window.DshAndroidClipboard && window.DshAndroidClipboard.copyText(t)); }
    catch (e) { return false; }
  }
  function legacyCopy(t) {
    try {
      var ta = document.createElement('textarea');
      ta.value = t;
      ta.style.cssText = 'position:fixed;top:-999px;left:-999px;opacity:0;';
      document.body.appendChild(ta);
      ta.focus(); ta.select();
      var ok = document.execCommand('copy');
      document.body.removeChild(ta);
      return ok;
    } catch (e) { return false; }
  }
  function viaFallback(t) {
    if (nativeCopy(t)) return;
    if (legacyCopy(t)) return;
    throw new Error('all clipboard paths failed');
  }
  if (navigator.clipboard && typeof navigator.clipboard.writeText === 'function') {
    var orig = navigator.clipboard.writeText.bind(navigator.clipboard);
    navigator.clipboard.writeText = function (text) {
      return Promise.resolve().then(function () { return orig(text); }).catch(function (err) {
        viaFallback(text);
      });
    };
  } else {
    navigator.clipboard = navigator.clipboard || {};
    navigator.clipboard.writeText = function (text) {
      try { viaFallback(text); return Promise.resolve(); }
      catch (e) { return Promise.reject(e); }
    };
  }
})();
"""

        /**
         * 环境自检脚本：报告 WebView 版本与引擎所需关键 API 是否存在。
         * 引擎实际需要 Chrome 126（pdf.js 用 URL.parse），polyfill 可补 API 但补不了语法
         * （`??=` 需 Chrome 85，语法错误会导致白屏 —— 那种情况日志里会看到 SyntaxError）。
         */
        private const val WEBVIEW_SELFCHECK_JS = """
(function () {
  try {
    var ua = navigator.userAgent || '';
    var m = ua.match(/Chrome\/(\d+)/);
    var chrome = m ? parseInt(m[1], 10) : 0;
    var need = {
      'Object.hasOwn': 93, 'replaceChildren': 86, 'replaceAll': 85,
      'findLast': 97, 'structuredClone': 98, 'toSorted': 110,
      'AbortSignal.any': 116, 'Promise.withResolvers': 119, 'URL.parse': 126
    };
    var missing = [];
    var probes = {
      'Object.hasOwn': function(){ return typeof Object.hasOwn === 'function'; },
      'replaceChildren': function(){ return typeof Element !== 'undefined' && !!Element.prototype.replaceChildren; },
      'replaceAll': function(){ return !!String.prototype.replaceAll; },
      'findLast': function(){ return !!Array.prototype.findLast; },
      'structuredClone': function(){ return typeof structuredClone === 'function'; },
      'toSorted': function(){ return !!Array.prototype.toSorted; },
      'AbortSignal.any': function(){ return typeof AbortSignal !== 'undefined' && !!AbortSignal.any; },
      'Promise.withResolvers': function(){ return !!Promise.withResolvers; },
      'URL.parse': function(){ return typeof URL !== 'undefined' && !!URL.parse; }
    };
    for (var k in probes) { try { if (!probes[k]()) missing.push(k + '(need ' + need[k] + ')'); } catch (e) { missing.push(k + '(probe err)'); } }
    return '[dsh-android] webview ok | chrome=' + chrome +
      ' | polyfill=' + (!missing.length ? 'complete' : 'MISSING: ' + missing.join(',')) +
      ' | ua=' + ua.slice(0, 120);
  } catch (e) { return '[dsh-android] selfcheck failed: ' + e.message; }
})();
"""

        /**
         * Android 13+ 通知运行时权限请求（Service 启动路径回调到 Activity）。
         *
         * ⚠️ v1.5.0-beta.9：**只问一次**。此前每次前台服务启动都会调这里，
         * 用户首次点了「不允许」之后仍会被反复弹框 —— 那正是「一上来就弹一堆窗」
         * 最招人烦的形态。现在用 [KEY_ASKED_NOTIF] 记住问过没问过，问过就不问了。
         *
         * 想再授权时用户自己去「权限中心 → 通知」点，那里是显式入口，不打扰。
         */
        fun maybeRequestNotificationPermission(activity: Context) {
            if (Build.VERSION.SDK_INT < 33) return
            val granted = activity.checkSelfPermission(
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) return
            if (activity !is Activity) return
            val sp = activity.getSharedPreferences(PREFS_UI, Context.MODE_PRIVATE)
            if (sp.getBoolean(KEY_ASKED_NOTIF, false)) return
            sp.edit().putBoolean(KEY_ASKED_NOTIF, true).apply()
            activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }

        /**
         * 把识别出的文本写入 WebUI 的输入框并提交（v1.2.58）。
         *
         * 为什么用 JS 注入而不是直接调引擎 API：WebUI 的输入框是前端状态的一部分，
         * 只有走它的 DOM 才能触发正确的发送流程（含附件、@引用等既有逻辑）。
         *
         * 选择器策略（按可靠性排序，逐个尝试）：
         *  ① textarea（当前前端用的是 textarea）
         *  ② [contenteditable]（富文本输入框）
         *  ③ input[type=text]
         * 写入用原生 setter + 派发 input 事件，让 React 的受控组件感知变化
         * （直接改 value 而不派发事件，React 会忽略）。
         */
        fun buildVoiceInputJs(text: String): String {
            val escaped = org.json.JSONObject.quote(text)
            return """
(function(){
  try {
    var t = $escaped;
    // v1.2.65: ensure there is an input box to write into. The user may be sitting
    // on the home screen (no conversation open), where no textarea exists; the old
    // build returned 'no-input-found' and the send silently did nothing.
    // Here we first look for a conversation entry in the DOM, click into it, and
    // let the caller retry once the UI has rendered.
    function findInput() {
      return document.querySelector('textarea')
          || document.querySelector('[contenteditable="true"]')
          || document.querySelector('input[type=text]');
    }
    // Send button: exact selectors first, then a fuzzy sweep over buttons whose
    // aria-label/class mentions send. The old build tried three exact selectors and
    // silently fell back to Enter; if Enter inserts a newline in this frontend,
    // the message never leaves the box (user-reported "it does not send").
    function findSend(el) {
      var sels = ['button[aria-label*="send" i]', 'button[aria-label*="\u53d1\u9001"]',
                  '[data-testid*="send" i]'];
      for (var i = 0; i < sels.length; i++) {
        var b = document.querySelector(sels[i]);
        if (b && b.disabled !== true) return b;
      }
      var cands = document.querySelectorAll('button, [role="button"]');
      for (var k = cands.length - 1; k >= 0; k--) {
        var c = cands[k];
        var label = (c.getAttribute('aria-label') || '') + ' ' + (c.className || '');
        if (/send|\u53d1\u9001/i.test(label) && c.disabled !== true) return c;
      }
      // Fallback: the last enabled button inside the input's own container
      var scope = el.closest('form') || (el.parentElement && el.parentElement.parentElement);
      if (scope) {
        var btns = scope.querySelectorAll('button');
        for (var j = btns.length - 1; j >= 0; j--) {
          if (btns[j].disabled !== true) return btns[j];
        }
      }
      return null;
    }
    var el = findInput();
    if (!el) {
      var cand = document.querySelector('[data-session-id]')
              || document.querySelector('a[href*="session"]')
              || document.querySelector('[role="listitem"]')
              || document.querySelector('[class*="session" i]');
      if (cand instanceof HTMLElement) {
        cand.click();
        return 'entering-session';
      }
      return 'no-input-found';
    }
    el.focus();
    if (el.tagName === 'TEXTAREA' || el.tagName === 'INPUT') {
      var proto = el.tagName === 'TEXTAREA'
        ? window.HTMLTextAreaElement.prototype
        : window.HTMLInputElement.prototype;
      var setter = Object.getOwnPropertyDescriptor(proto, 'value').set;
      setter.call(el, t);
      el.dispatchEvent(new Event('input', { bubbles: true }));
      el.dispatchEvent(new Event('change', { bubbles: true }));
    } else {
      // contenteditable (rich-text editors: Lexical / ProseMirror / Quill ...).
      //
      // v1.2.95 - REWRITE after live reproduce on the real WebUI (emulator + CDP).
      // Facts established by experiment:
      //  1) execCommand('insertText') on this Lexical editor DOUBLED the text: the
      //     framework imports the DOM mutation AND applies the beforeinput that
      //     execCommand fires. One write became two.
      //  2) Dispatching cancelable 'beforeinput' ALSO doubled.
      //  3) A synthetic ClipboardEvent('paste') with a DataTransfer is processed
      //     by Lexical's native paste pipeline EXACTLY ONCE - but ASYNCHRONOUSLY:
      //     the content lands a beat later. A synchronous check right after the
      //     dispatch still sees the old content, which made the old in-JS fallback
      //     kick in and double the text anyway.
      // So this snippet does ONE thing only: fire the paste. Whether the write
      // landed is checked by Kotlin (readSendText after FIRE_DELAY_MS), which
      // falls back to buildVoiceRepairJs (execCommand, safe for framework-less
      // editors) when the paste had no effect. No in-JS fallback here - any
      // synchronous second write races the paste and doubles the text.
      el.focus();
      // Ctrl+A keydown lets the editor select its own content first.
      el.dispatchEvent(new KeyboardEvent('keydown', {
        key: 'a', code: 'KeyA', keyCode: 65, ctrlKey: true, bubbles: true,
      }));
      var dt = new DataTransfer();
      dt.setData('text/plain', t);
      el.dispatchEvent(new ClipboardEvent('paste', {
        bubbles: true, cancelable: true, clipboardData: dt,
      }));
    }
    // Do NOT click send synchronously: a React controlled input updates its state
    // asynchronously, so right after dispatching the input event the send button may
    // still be disabled and the click is a no-op.
    //
    // v1.2.92 - regression fix. v1.2.88 deferred that click with
    // requestAnimationFrame to shave latency, but rAF callbacks ride on frame
    // production and a backgrounded WebView produces no frames: tapping "send"
    // while the app was in the background left the click queued, so the message
    // only went out the instant the user returned to the foreground (user: "it
    // only sends the moment I come back"). The pre-v1.2.88 setTimeout version
    // did work in the background.
    //
    // Now the app side owns both the commit and the verdict: we expose two
    // synchronous hooks and Kotlin calls them on its own Handler schedule, so no
    // JS timer and no frame is involved and background behaves like foreground.
    window.__dshEl = el;
    // One-shot ticket, handed out per write (see __dshFire). Not a time window:
    // v1.2.92 throttled by elapsed time, and Kotlin's "still in the box after 4s,
    // fire again" retry sailed straight through it (4s > 1.5s) - when the
    // frontend was slow to clear the box that second click sent the same text
    // again, which is exactly the "two copies" the user kept seeing.
    window.__dshTicket = false;
    window.__dshFire = function () {
      if (!window.__dshTicket) return 'consumed';   // already committed this text
      var target = (window.__dshEl && document.body.contains(window.__dshEl))
        ? window.__dshEl : findInput();
      if (!target) return 'no-input';
      var v = (target.tagName === 'TEXTAREA' || target.tagName === 'INPUT')
        ? (target.value || '') : (target.textContent || '');
      if (!v.trim()) return 'empty';   // nothing to send - never click on empty
      var btn = findSend(target);
      // Spend the ticket BEFORE clicking: whatever happens next, this text gets
      // exactly one commit attempt. Retrying a submit that already reached the
      // frontend is what produced duplicate messages.
      window.__dshTicket = false;
      if (btn) { btn.click(); return 'clicked'; }
      target.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', keyCode: 13, bubbles: true }));
      target.dispatchEvent(new KeyboardEvent('keyup',   { key: 'Enter', code: 'Enter', keyCode: 13, bubbles: true }));
      return 'enter';
    };
    // Verdict source: the frontend clears the input once the message is committed.
    window.__dshReadVal = function () {
      var e = window.__dshEl;
      if (!e) return 'none';
      if (!document.body.contains(e)) return 'gone';
      var v = (e.tagName === 'TEXTAREA' || e.tagName === 'INPUT') ? (e.value || '') : (e.textContent || '');
      return v.trim() ? 'text' : 'empty';
    };
    // Actual text in the box. Kotlin compares it against what it tried to send:
    // only a match means "this is our text, safe to (re)submit"; anything else is
    // the user's own typing and must be left alone.
    window.__dshText = function () {
      var e = window.__dshEl;
      if (!e) {
        var f = findInput();
        if (!f) return '';
        e = f;
      }
      if (!document.body.contains(e)) return '';
      var v = (e.tagName === 'TEXTAREA' || e.tagName === 'INPUT') ? (e.value || '') : (e.textContent || '');
      return v.trim();
    };
    // Hand out exactly one commit ticket for the text just written. Kotlin calls
    // __dshFire() later (250ms, on its own Handler) to spend it.
    // ⚠️ Grant MUST come after the hook block above: that block re-initialises
    // __dshTicket = false, and in v1.2.94 the grant sat before it - the reset
    // clobbered the grant, every fire returned 'consumed' and nothing was ever
    // submitted (the text just sat in the box). This order is load-bearing.
    window.__dshTicket = true;
    return 'ok:written';
  } catch (e) { return 'err:' + e.message; }
})();
""".trimIndent()
        }

        /**
         * 补写（v1.2.95）：paste 通道无效时的回退写入。
         *
         * 专用场景：编辑器**不响应合成 paste 事件**（无框架的普通 contenteditable）。
         * 此时 execCommand 是标准路径且没有框架去双处理 DOM 变更，安全。
         *
         * ⚠️ 绝不能对 Lexical 这类框架编辑器使用（实测翻倍）——调用方必须先
         * 用 readSendText 确认 paste 没生效才允许走这里（见 sendVoiceTextOnce）。
         */
        fun buildVoiceRepairJs(text: String): String {
            val escaped = org.json.JSONObject.quote(text)
            return """
(function(){
  try {
    var t = $escaped;
    var el = window.__dshEl && document.body.contains(window.__dshEl)
      ? window.__dshEl
      : (document.querySelector('textarea')
         || document.querySelector('[contenteditable="true"]')
         || document.querySelector('input[type=text]'));
    if (!el) return 'no-input';
    el.focus();
    function contentOf(node) { return (node.textContent || '').trim(); }
    function selectAllIn(node) {
      try {
        var sel = window.getSelection();
        var range = document.createRange();
        range.selectNodeContents(node);
        sel.removeAllRanges();
        sel.addRange(range);
      } catch (e) {}
    }
    selectAllIn(el);
    try { document.execCommand('insertText', false, t); } catch (e) {}
    if (contentOf(el) !== t.trim() && (el.tagName === 'TEXTAREA' || el.tagName === 'INPUT')) {
      var proto = el.tagName === 'TEXTAREA'
        ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;
      Object.getOwnPropertyDescriptor(proto, 'value').set.call(el, t);
      el.dispatchEvent(new Event('input', { bubbles: true }));
    }
    return 'repaired:' + contentOf(el).slice(0, 40);
  } catch (e) { return 'err:' + e.message; }
})();
""".trimIndent()
        }



        /** 页面缩放持久化：SharedPreferences 名 + key（设置页与引导共用）。
         *  屏幕朝向偏好（v1.2.120 起三态 portrait/landscape/auto）集中在
         *  OrientationPrefs，此处不再持有布尔键。 */
        private const val PREFS_UI = "dsh_ui"

        /** 通知权限是否已经问过用户（问过就不再问，避免反复弹框）。 */
        private const val KEY_ASKED_NOTIF = "asked_notif_permission"
        private const val KEY_PAGE_SCALE = "page_scale"

        /**
         * 手机 UI 方案（v1.4.67，宝宝要求）：内置两套竖屏布局插件，用哪个由用户选。
         *
         * · false（默认）= 我们自制的 narrow-sidebar / narrow-settings / narrow-rightbar
         *   三件套。更轻、行为可预期，是长期使用的默认。
         * · true = 社区插件 @xiaopb/dsh-mobile-layout（「官方推荐手机 UI」）。
         *   抽屉 / 底部操作条 / 全屏设置页，视觉更接近官方 App。
         *
         * 两者互斥：overlay 里按这个开关把另一个标成 disabled，引擎不会同时加载。
         */
        private const val KEY_OFFICIAL_MOBILE_UI = "official_mobile_ui"

        @JvmStatic
        fun officialMobileUiEnabled(ctx: Context): Boolean =
            ctx.getSharedPreferences(PREFS_UI, Context.MODE_PRIVATE)
                .getBoolean(KEY_OFFICIAL_MOBILE_UI, false)

        @JvmStatic
        fun setOfficialMobileUiEnabled(ctx: Context, on: Boolean) {
            ctx.getSharedPreferences(PREFS_UI, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_OFFICIAL_MOBILE_UI, on).apply()
        }

        /**
         * 沉浸式状态栏开关（v1.4.64：默认回到「带状态栏」）。
         *
         * 历史上反复过几次，结论记在这里避免再绕：
         *   · v1.4.30 默认关 —— 隐藏状态栏后岛会压到挖孔上；
         *   · v1.4.61 默认开 —— 岛已能精确对齐挖孔，想让岛代替状态栏；
         *   · **v1.4.64 默认关** —— 宝宝实测「怎么改都有点难受」，全屏 + 岛
         *     浮在网页上的组合，无论留白还是叠压都不够自然。带状态栏时窗口
         *     天然让开顶部，岛在状态栏下方，最省心。
         *
         * 想全屏的用户仍可在设置 → 外观与显示里打开（岛会贴住摄像头）。
         */
        private const val KEY_IMMERSIVE = "immersive_statusbar"
        private const val DEFAULT_IMMERSIVE = false

        @JvmStatic
        fun immersiveEnabled(ctx: Context): Boolean =
            ctx.getSharedPreferences(PREFS_UI, Context.MODE_PRIVATE)
                .getBoolean(KEY_IMMERSIVE, DEFAULT_IMMERSIVE)

        @JvmStatic
        fun setImmersiveEnabled(ctx: Context, on: Boolean) {
            ctx.getSharedPreferences(PREFS_UI, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_IMMERSIVE, on).apply()
        }

        /** 竖屏页面缩放范围/步长/默认值（等价浏览器 Ctrl- 缩小一点，用户可再调） */
        private const val DEFAULT_PAGE_SCALE = 90
        private const val MIN_PAGE_SCALE = 50
        private const val MAX_PAGE_SCALE = 150
        private const val SCALE_STEP = 5

        /**
         * 视口 meta 应用后，等待渲染进程完成重排/缩放再整页重栅格化的时间（v1.4.6）。
         * 必须小于 `dismissSplash()` 的最短等待（`splashMinMs` + 180ms），
         * 这样重栅格化始终发生在启动遮罩之下，用户看不到软件渲染帧。
         */
        private const val VIEWPORT_SETTLE_MS = 120L

        /**
         * 桌面渲染的最小窗口宽度（dp，v1.5.1）。
         *
         * 桌面视口固定 `width=1280`，窗口比它窄多少，内容就等比缩多少。小窗常见宽度
         * 468dp → 0.366 倍，字号只剩四成。768dp 是桌面三栏的实用下限：低于它即使是
         * 横屏长条，桌面布局也只能靠缩，不如给移动渲染。
         *
         * ⚠️ 只在多窗口下用（见 [isWindowed]），全屏形态不受影响。
         */
        private const val DESKTOP_MIN_WIDTH_DP = 768

        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        /**
         * 桌面视口改写：width=1280 让响应式 CSS 命中桌面断点；
         * initial-scale = 屏宽/1280 整页缩进屏内；maximum-scale=5 + user-scalable
         * 允许双指缩放细看。执行时机 onPageFinished（此时 clientWidth=设备宽）。
         */
        private const val DESKTOP_VIEWPORT_JS =
            "(function(){" +
                "var W=1280;" +
                "var m=document.querySelector('meta[name=\"viewport\"]');" +
                "if(!m){m=document.createElement('meta');m.setAttribute('name','viewport');" +
                "(document.head||document.documentElement).appendChild(m);}" +
                "var cw=document.documentElement.clientWidth||412;" +
                "m.setAttribute('content','width='+W+', initial-scale='+(cw/W).toFixed(4)+', maximum-scale=5, user-scalable=yes');" +
                "})()"
    }
}
