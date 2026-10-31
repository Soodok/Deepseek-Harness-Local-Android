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
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.dsh.mobile.engine.BootTrace
import app.dsh.mobile.engine.RepoConfig
import app.dsh.mobile.engine.RuntimeInstaller
import app.dsh.mobile.engine.WebViewDownloader
import app.dsh.mobile.engine.EngineSupervisor
import app.dsh.mobile.engine.Privilege
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

    /** 桌面模式：桌面 UA + 固定 1280px 视口 + 手势缩放（手机浏览器「电脑模式」等价物） */
    private var desktopMode = false
    private var defaultUa: String = ""

    /** 屏幕朝向偏好（v1.2.120）：portrait 锁竖 / landscape 锁横+桌面布局 / auto 跟随旋转 */
    private var orientationMode: OrientationMode = OrientationMode.PORTRAIT

    /** 页面缩放百分比（竖屏时应用；等价浏览器 Ctrl+/Ctrl-）。横屏桌面模式交给 1280px meta，不叠加 */
    private var pageScale = DEFAULT_PAGE_SCALE

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

    // ---------- 启动动画（v1.3.1） ----------
    /** 全屏启动遮罩；null = 已淡出/未初始化 */
    private var splash: android.view.View? = null
    private var splashLogo: android.view.View? = null
    private var splashStatus: android.widget.TextView? = null
    private var splashProgress: android.widget.ProgressBar? = null
    /** 已用秒数 + 当前阶段（v1.4.8） */
    private var splashElapsed: android.widget.TextView? = null
    /** 文件加载明细 M/N（v1.4.8） */
    private var splashDetail: android.widget.TextView? = null
    /** 展开/收起阶段列表的按钮（v1.4.8） */
    private var splashToggle: android.widget.TextView? = null
    private var splashSteps: android.widget.TextView? = null
    private var splashStepsScroll: android.view.View? = null
    /** 步骤面板（分隔线 + 滚动区）；显隐只切这一个，避免分隔线残留 */
    private var splashStepsPanel: android.view.View? = null
    /** 上次渲染的步骤文本：内容没变就不重设，防止 130ms 一次的全量重排让卡片抖动 */
    private var lastStepsText: String? = null
    /** 上次渲染的步骤条数：只有条数增加才自动滚到底，避免用户翻看历史时被拽回 */
    private var lastStepsCount = -1
    /** 阶段列表是否展开 */
    private var splashStepsExpanded = false
    /** 当前 Activity 已应用的主题；从设置页返回时检测变化并 recreate。 */
    private var appliedTheme: ThemeMode? = null
    /** 秒表：逐秒刷新已用时间（v1.4.8） */
    private var splashTicker: Runnable? = null
    /** 遮罩出现时间：用于保证最短展示时长（避免一闪而过） */
    private var splashShownAt = 0L
    /** 是否已经淡出（幂等） */
    private var splashDismissed = false
    private var splashMinMs = 700L
    /** 兜底看门狗：任何加载路径异常都不能让遮罩永久吞掉触摸（issue #17） */
    private var splashWatchdog: Runnable? = null

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
        // 读回用户保存的页面缩放与屏幕朝向偏好
        readUiPrefs()
        applyOrientation(orientationMode)
        // v1.2.120：冷启动也要把桌面渲染落到偏好 —— 旧实现只在 onResume 的「偏好变化」
        // 分支里 setDesktop，导致 landscape=true 冷启动时 UA/视口/缩放手势全是竖屏那套，
        // 直到用户在设置里动过一次或旋转设备才恢复。
        setDesktop(shouldUseDesktop())

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
        // v1.4.62：岛是圆角胶囊，选项的系统方角涟漪会从圆角外戳出来（按压残影）。
        // 整行按轮廓裁切兜底，配合子项的 22dp 圆角涟漪（bg_island_btn）。
        // v1.4.63：上一轮并发改动把它删了，残影会复发 —— 补回。
        findViewById<View>(R.id.statusBarRow).clipToOutline = true
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
        val sizeMb = runCatching {
            RuntimeInstaller(this).readManifest().runtime.size / (1L shl 20)
        }.getOrDefault(0L)
        val sizeText = if (sizeMb > 0) "$sizeMb MB" else "~130 MB"
        AlertDialog.Builder(this)
            .setTitle(R.string.runtime_setup_title)
            .setMessage(getString(R.string.runtime_setup_message, sizeText))
            .setPositiveButton(R.string.runtime_setup_download) { _, _ ->
                EngineService.start(this)
            }
            .setNeutralButton(R.string.runtime_setup_full) { _, _ ->
                runCatching {
                    startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(RepoConfig.RELEASES_PAGE))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
            .setNegativeButton(R.string.runtime_setup_later, null)
            .show()
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
     * （物理横屏 → 桌面布局，竖屏 → 移动布局），旋转时由
     * onConfigurationChanged 重新求值。
     */
    private fun shouldUseDesktop(): Boolean = when (orientationMode) {
        OrientationMode.LANDSCAPE -> true
        OrientationMode.PORTRAIT -> false
        OrientationMode.AUTO ->
            resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    }

    private fun setupWebView() {
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

    /** 统一的回环页加载入口：缩放统一由 onPageFinished 的 viewport meta 接管，这里只导航。 */
    private fun loadLocalUrl(url: String) {
        webView.loadUrl(url)
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
    private fun toggleToolbar() {
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
                    handle.visibility = View.VISIBLE
                    // GONE → VISIBLE 期间 insets 回调可能已经跑过（那时设的
                    // layoutParams 对 GONE 视图不生效），这里补一次对齐
                    handle.requestApplyInsets()
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
                    row.post {
                        row.pivotX = row.width / 2f
                        row.pivotY = 0f
                        row.animate().scaleX(1f).scaleY(1f).alpha(1f)
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
    private fun setupHandleBar() {
        val handle = findViewById<View>(R.id.handleBar)
        val slop = 12f
        var rawX0 = 0f; var rawY0 = 0f
        var initTx = 0f; var initTy = 0f
        handle.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    rawX0 = ev.rawX; rawY0 = ev.rawY
                    initTx = v.translationX; initTy = v.translationY
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val parent = v.parent as View
                    v.translationX = (initTx + (ev.rawX - rawX0))
                        .coerceIn(-v.width * 0.6f, parent.width - v.width * 0.4f)
                    v.translationY = (initTy + (ev.rawY - rawY0))
                        .coerceIn(0f, (parent.height - v.height).coerceAtLeast(0).toFloat())
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    val dx = ev.rawX - rawX0; val dy = ev.rawY - rawY0
                    if (dx * dx + dy * dy < slop * slop) toggleToolbar()
                    true
                }
                else -> false
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 旋转后屏宽变化 → 需要重算适配视口（reload 后 onPageFinished 会按当前模式
        // 与 pageScale 重写 viewport meta）。竖屏旋转屏宽同样变，故统一 reload。
        // 同时同步手势缩放开关（竖屏锁死、桌面保留），防止切屏后对手势失效。
        // v1.2.120：auto 模式下桌面渲染跟随**实际**朝向——旋转后先按新朝向求值，
        // 否则横屏后仍是竖屏那套 UA/视口。setDesktop 自身不 reload，reload 紧随其后。
        setDesktop(shouldUseDesktop())
        applyZoomControls(desktopMode)
        applyStatusBarImmersive()
        webView.reload()
    }

    /**
     * 竖屏视口缩放 JS：把 viewport meta 写成
     * `width=device-width, initial-scale=R, minimum-scale=R, maximum-scale=R, user-scalable=no`。
     * - width=device-width → 布局宽=屏宽，不产生横向滚动；
     * - initial-scale=R   → 浏览器 Ctrl- 式显示缩放（R<1 缩小看更多，R>1 放大）；
     * - min=max=R         → 锁死缩放（既不会被 clamp 回 100%，也不许用户手动捏合）；
     * ratio 由用户 pageScale 推导，范围 0.5–1.5。
     */
    private fun portraitViewportJs(ratio: Float): String {
        val r = ratio.coerceIn(0.5f, 1.5f)
        return "(function(){" +
            "var m=document.querySelector('meta[name=\"viewport\"]');" +
            "if(!m){m=document.createElement('meta');m.setAttribute('name','viewport');" +
            "(document.head||document.documentElement).appendChild(m);}" +
            "m.setAttribute('content','width=device-width, initial-scale=$r, minimum-scale=$r, maximum-scale=$r, user-scalable=no');" +
            "})()"
    }

    /**
     * 应用当前渲染模式的视口缩放（v1.4.6）。
     *
     * - 桌面模式：固定 `width=1280`、`initial-scale=屏宽/1280`，让响应式 CSS 命中桌面断点；
     * - 竖屏模式：`width=device-width` + `initial-scale=R`（见 [portraitViewportJs]）。
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
     * @param view 已触发 `onPageFinished` 的 WebView。
     */
    private fun applyViewport(view: WebView) {
        if (desktopMode) {
            view.evaluateJavascript(DESKTOP_VIEWPORT_JS, null)
            scheduleReraster(view, "desktop")
            return
        }
        val scale = (pageScale / 100f).coerceIn(0.5f, 1.5f)
        view.evaluateJavascript(portraitViewportJs(scale), null)
        // scale=1 没有缩放突变，不需要额外重栅格化（也避免无谓的软件渲染帧）。
        if (scale != 1f) scheduleReraster(view, "portrait scale=$scale")
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
        // 启动遮罩里的进度条同步真实解压百分比（就绪后转为不确定动画）
        splashProgress?.let { p ->
            if (frac == null) {
                p.isIndeterminate = true
            } else {
                p.isIndeterminate = false
                p.progress = (frac * 10000).toInt()
            }
        }
    }

    // ==================== 启动遮罩（v1.3.1） ====================

    /**
     * 初始化启动遮罩：logo 淡入 + 缩放入场，随后进入缓慢呼吸。
     *
     * 设计取舍：动画只用 ViewPropertyAnimator（零依赖、不引第三方库）；
     * 遮罩不是纯装饰——它同时承载**真实解压进度**与引擎阶段文案，
     * 让冷启动那段时间「有东西看、也知道在干什么」。
     */
    private fun setupSplash() {
        val v = findViewById<android.view.View>(R.id.splash) ?: return
        splash = v
        splashLogo = findViewById(R.id.splashLogo)
        splashStatus = findViewById(R.id.splashStatus)
        splashProgress = findViewById(R.id.splashProgress)
        splashElapsed = findViewById(R.id.splashElapsed)
        splashDetail = findViewById(R.id.splashDetail)
        splashToggle = findViewById(R.id.splashToggle)
        splashSteps = findViewById(R.id.splashSteps)
        splashStepsScroll = findViewById(R.id.splashStepsScroll)
        splashStepsPanel = findViewById(R.id.splashStepsPanel)
        splashShownAt = System.currentTimeMillis()
        splashDismissed = false
        // 仅 debug 包：允许用 `am start ... --el splash_min_ms 20000` 拉长遮罩停留时间，
        // 方便在真机/模拟器上验证启动遮罩（尤其是展开加载步骤后的卡片布局）。
        // release 包 FLAG_DEBUGGABLE 为 0，这段不生效。
        if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            val override = intent?.getLongExtra("splash_min_ms", 0L) ?: 0L
            if (override > 0L) splashMinMs = override
        }
        // v1.4.8：默认收起阶段列表；每次冷启动都从收起状态开始，避免把 logo 挤走
        splashStepsExpanded = false
        lastStepsText = null
        lastStepsCount = -1
        splashStepsPanel?.visibility = android.view.View.GONE
        splashToggle?.setText(R.string.splash_show_steps)
        splashToggle?.setOnClickListener { toggleSplashSteps() }
        splashDetail?.text = ""
        // 兜底：WebView 异常时强制收遮罩（issue #17）。但 noruntime 首启下载/解压
        // 可能超过 45s —— 安装/启动期间不能收，否则用户会看到一块黑 WebView。
        // 每 15s 续期，最多续 20 次（约 5 分钟）后仍强制收起。
        splashWatchdog?.let { v.removeCallbacks(it) }
        var watchdogExtensions = 0
        val watchdog = object : Runnable {
            override fun run() {
                val state = (application as DshApp).supervisor.state.value
                val busy = state is EngineSupervisor.State.Installing ||
                    state is EngineSupervisor.State.Starting
                if (busy && watchdogExtensions < 20) {
                    watchdogExtensions++
                    v.postDelayed(this, 15_000L)
                } else {
                    dismissSplash()
                }
            }
        }
        splashWatchdog = watchdog
        v.postDelayed(watchdog, 45_000L)
        // 秒表：每秒刷新"已用 N.Ns · 当前阶段"
        startSplashTicker()
        // WebView 底色与主题一致：遮罩淡出时不会闪出白底
        runCatching { webView.setBackgroundColor(getColor(R.color.bg)) }
        val logo = splashLogo ?: return
        logo.alpha = 0f
        logo.scaleX = 0.82f
        logo.scaleY = 0.82f
        logo.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(420L)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .withEndAction {
                // 引擎还没好时，用极缓的呼吸动画表示"在动"
                logo.animate()
                    .scaleX(1.05f).scaleY(1.05f)
                    .setDuration(1_400L)
                    .setInterpolator(android.view.animation.AccelerateDecelerateInterpolator())
                    .withEndAction {
                        logo.animate()
                            .scaleX(1f).scaleY(1f)
                            .setDuration(1_400L)
                            .setInterpolator(android.view.animation.AccelerateDecelerateInterpolator())
                            .withEndAction { if (splash != null) setupSplashPulseAgain() }
                            .start()
                    }
                    .start()
            }
            .start()
    }

    /** 呼吸动画循环（单独方法，便于在遮罩淡出时停止）。 */
    private fun setupSplashPulseAgain() {
        val logo = splashLogo ?: return
        if (splash == null || splashDismissed) return
        logo.animate()
            .scaleX(1.05f).scaleY(1.05f)
            .setDuration(1_400L)
            .setInterpolator(android.view.animation.AccelerateDecelerateInterpolator())
            .withEndAction {
                if (splash == null || splashDismissed) return@withEndAction
                logo.animate()
                    .scaleX(1f).scaleY(1f)
                    .setDuration(1_400L)
                    .setInterpolator(android.view.animation.AccelerateDecelerateInterpolator())
                    .withEndAction { setupSplashPulseAgain() }
                    .start()
            }
            .start()
    }

    /** 更新遮罩上的阶段文案。 */
    private fun setSplashStatus(resId: Int) {
        splashStatus?.setText(resId)
    }

    // ==================== 启动过程可视化（v1.4.8） ====================

    /**
     * 启动秒表：每秒刷新一次「已用 N.Ns · 阶段」。
     *
     * 为什么用 `postDelayed` 循环而不是协程：遮罩的生命周期很短（几秒），
     * 一个 Runnable 就够，且随遮罩一起移除，不需要额外的 scope 管理。
     */
    private fun startSplashTicker() {
        stopSplashTicker()
        val v = splash ?: return
        val r = object : Runnable {
            override fun run() {
                if (splash == null || splashDismissed) return
                renderSplashTrace(BootTrace.snapshot.value)
                v.postDelayed(this, 200L)
            }
        }
        splashTicker = r
        v.post(r)
    }

    private fun stopSplashTicker() {
        splashTicker?.let { splash?.removeCallbacks(it) }
        splashTicker = null
    }

    private fun toggleSplashSteps() {
        splashStepsExpanded = !splashStepsExpanded
        splashStepsPanel?.visibility =
            if (splashStepsExpanded) android.view.View.VISIBLE else android.view.View.GONE
        splashToggle?.setText(
            if (splashStepsExpanded) R.string.splash_hide_steps else R.string.splash_show_steps,
        )
        if (splashStepsExpanded) {
            renderSplashTrace(BootTrace.snapshot.value)
            applyStepsViewport()
        }
    }

    /**
     * 把步骤滚动区的高度收敛到「内容高度」与上限之间（v1.4.30）。
     *
     * ScrollView 用 wrap_content 时会按内容无限展开、不滚动，卡片会被撑到超出屏幕；
     * 固定高度又会让步骤少时留一大块空白。这里按实际测量结果取 min，两者兼顾。
     */
    private fun applyStepsViewport() {
        val scroll = splashStepsScroll as? android.widget.ScrollView ?: return
        val tv = splashSteps ?: return
        scroll.post {
            val inner = scroll.width - scroll.paddingLeft - scroll.paddingRight
            if (inner <= 0) return@post
            tv.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(
                    inner,
                    android.view.View.MeasureSpec.EXACTLY,
                ),
                android.view.View.MeasureSpec.makeMeasureSpec(
                    0,
                    android.view.View.MeasureSpec.UNSPECIFIED,
                ),
            )
            val maxPx = (220 * resources.displayMetrics.density).toInt()
            val contentPx = tv.measuredHeight + tv.paddingTop + tv.paddingBottom
            val target = minOf(contentPx, maxPx).coerceAtLeast(1)
            if (scroll.layoutParams.height != target) {
                scroll.layoutParams = scroll.layoutParams.apply { height = target }
                scroll.requestLayout()
            }
        }
    }

    /** 阶段 key → 本地化文案；未知 key 原样显示（新阶段不会因缺翻译而消失） */
    private fun stageLabel(key: String): String = when (key) {
        "guardian-restore" -> getString(R.string.boot_step_guardian)
        "agent-seed" -> getString(R.string.boot_step_seed)
        "orphan-reap" -> getString(R.string.boot_step_orphans)
        "bridge" -> getString(R.string.boot_step_bridge)
        "log-sweep" -> getString(R.string.boot_step_log_sweep)
        "home-owner-check" -> getString(R.string.boot_step_owner)
        "hardssh-install" -> getString(R.string.boot_step_hardssh)
        "runtime-install" -> getString(R.string.boot_step_runtime)
        "profile-plugins" -> getString(R.string.boot_step_profile_plugins)
        "user-data" -> getString(R.string.boot_step_userdata)
        "spawn" -> getString(R.string.boot_step_spawn)
        "health-poll" -> getString(R.string.boot_step_health)
        else -> key
    }

    /** 当前阶段主文案（遮罩大字） */
    private fun stageStatusRes(stage: BootTrace.Stage): Int = when (stage) {
        BootTrace.Stage.IDLE, BootTrace.Stage.PREPARING -> R.string.splash_preparing
        BootTrace.Stage.DOWNLOADING -> R.string.splash_downloading
        BootTrace.Stage.EXTRACTING -> R.string.splash_extracting
        BootTrace.Stage.STARTING -> R.string.splash_starting
        BootTrace.Stage.LOADING -> R.string.splash_loading
        BootTrace.Stage.READY -> R.string.splash_loading
        BootTrace.Stage.FAILED -> R.string.splash_failed
    }

    /**
     * 把 [BootTrace] 快照渲染到遮罩上。
     *
     * 三个层次：
     *  1. 主文案 —— 粗粒度阶段（准备/解压/启动/加载）；
     *  2. 秒数行 —— `3.2s · 解压运行时`，让用户知道"还在动"以及动了多久；
     *  3. 明细行 —— 解压时给 `M/N 个文件`，之后给最近一个阶段的耗时。
     */
    private fun renderSplashTrace(snap: BootTrace.Snapshot) {
        if (splashDismissed) return
        splashStatus?.setText(stageStatusRes(snap.stage))

        val elapsed = snap.elapsedMs()
        // 优先用「进行中」的阶段（mark 出来的），否则回落到最后一个已完成阶段。
        // 旧实现只取 steps.last()，耗时长的阶段会被显示成上一个阶段名（慢一拍），
        // 真实案例：profile-plugins 首装慢，屏幕却写「链接用户数据」。
        val lastKey = snap.currentStep ?: snap.steps.lastOrNull()?.key
        val label = if (snap.stage == BootTrace.Stage.DOWNLOADING) {
            getString(R.string.boot_step_download)
        } else if (snap.stage == BootTrace.Stage.EXTRACTING) {
            getString(R.string.boot_step_runtime)
        } else if (lastKey != null) {
            stageLabel(lastKey)
        } else {
            getString(R.string.splash_preparing)
        }
        splashElapsed?.text = getString(R.string.splash_elapsed, fmtSeconds(elapsed), label)

        // 明细行
        val detail = StringBuilder()
        val ins = snap.install
        if (snap.stage == BootTrace.Stage.EXTRACTING || snap.stage == BootTrace.Stage.DOWNLOADING) {
            when {
                // 解压文件：文件数最直观（字节数在大量小文件上几乎不动）
                snap.stage == BootTrace.Stage.EXTRACTING && ins.totalFiles > 0 -> {
                    detail.append(getString(R.string.splash_files, ins.doneFiles, ins.totalFiles))
                    if (ins.doneBytes > 0) detail.append(" · ").append(fmtSize(ins.doneBytes))
                }
                // 下载/拷贝 zip：没有文件概念，只有字节
                ins.totalBytes > 0 -> {
                    detail.append(fmtSize(ins.doneBytes))
                        .append(" / ")
                        .append(fmtSize(ins.totalBytes))
                }
            }
        } else if (snap.runtimeReused && snap.steps.any { it.key == "runtime-install" }) {
            detail.append(getString(R.string.splash_runtime_reused))
            val last = snap.steps.lastOrNull { it.key == "runtime-install" }
            if (last != null) {
                detail.append(" · ").append(fmtSeconds(last.durationMs))
            }
        } else if (snap.steps.isNotEmpty() || snap.currentStep != null) {
            // 有进行中的阶段就显示它（没有耗时可显示），否则显示最后一个已完成的。
            val cur = snap.currentStep
            if (cur != null) {
                detail.append(stageLabel(cur))
            } else {
                val last = snap.steps.last()
                detail.append(stageLabel(last.key)).append(" · ").append(fmtSeconds(last.durationMs))
            }
        }
        if (snap.failure != null) {
            detail.setLength(0)
            detail.append(snap.failure.take(120))
        }
        splashDetail?.text = detail.toString()

        // 进度条：解压阶段用确定性进度，其余转不确定
        splashProgress?.let { p ->
            val frac = if (snap.stage == BootTrace.Stage.EXTRACTING || snap.stage == BootTrace.Stage.DOWNLOADING) {
                ins.fraction
            } else {
                null
            }
            if (frac == null) {
                if (!p.isIndeterminate) {
                    p.isIndeterminate = true
                }
            } else {
                p.isIndeterminate = false
                p.progress = (frac * 10000).toInt()
            }
        }

        if (splashStepsExpanded) renderSplashSteps(snap)
    }

    /** 阶段列表：每行 `+123ms  累计 456ms  阶段名` */
    private fun renderSplashSteps(snap: BootTrace.Snapshot) {
        val tv = splashSteps ?: return
        val text = if (snap.steps.isEmpty()) {
            getString(R.string.splash_steps_empty)
        } else {
            snap.steps.joinToString("\n") { s ->
                getString(
                    R.string.splash_step_line,
                    fmtMs(s.durationMs),
                    fmtSeconds(s.atMs),
                    stageLabel(s.key),
                )
            }
        }
        // 内容没变就不动 UI：BootTrace 每 130ms 推一次快照，全量 setText 会让列表持续重排，
        // 卡片跟着抖，滚动位置也会被反复重置（v1.4.30）。
        if (text == lastStepsText) return
        lastStepsText = text
        tv.text = text
        applyStepsViewport()
        // 只有新增步骤时才滚到底（用户最关心"现在到哪一步"）；
        // 条数不变时保留用户自己翻到的位置，别把人拽回去。
        val grew = snap.steps.size != lastStepsCount
        lastStepsCount = snap.steps.size
        if (grew) {
            splashStepsScroll?.post {
                (splashStepsScroll as? android.widget.ScrollView)?.fullScroll(
                    android.view.View.FOCUS_DOWN,
                )
            }
        }
    }

    private fun fmtSeconds(ms: Long): String {
        val s = ms / 1000.0
        return if (s < 60) String.format(java.util.Locale.US, "%.1fs", s)
        else String.format(
            java.util.Locale.US, "%d:%02d",
            (ms / 60_000).toInt(), ((ms % 60_000) / 1000).toInt(),
        )
    }

    private fun fmtMs(ms: Long): String = if (ms < 1000) "${ms}ms" else fmtSeconds(ms)

    private fun fmtSize(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024) String.format(java.util.Locale.US, "%.1fGB", mb / 1024)
        else String.format(java.util.Locale.US, "%.0fMB", mb)
    }

    /**
     * 淡出启动遮罩（幂等）。
     *
     * 触发点：WebView 首屏 onPageFinished（页面真的可用了），或引擎进入失败/停止态
     * （不能让用户卡在遮罩里看不到工具栏与错误）。保证最短展示 700ms，避免一闪而过。
     */
    private fun dismissSplash() {
        splashWatchdog?.let { wd -> splash?.removeCallbacks(wd) }
        splashWatchdog = null
        stopSplashTicker()
        val v = splash ?: return
        if (splashDismissed) return
        splashDismissed = true
        val wait = (splashMinMs - (System.currentTimeMillis() - splashShownAt)).coerceAtLeast(0L)
        v.postDelayed({
            // ⚠️ v1.3.0 补丁（用户反馈「直接硬切有点生硬」）：onPageFinished 时页面往往
            // 还没画出第一帧，立刻淡出会先露出一块空 WebView = 看起来像硬切。
            // 这里再等 ~180ms 让首帧落地，然后做「淡出 + 轻微放大」的过渡，
            // 视觉上是从启动图"化开"进入页面，而不是被替换。
            v.postDelayed({
                splashLogo?.animate()?.cancel()
                v.animate()
                    .alpha(0f)
                    .scaleX(1.04f)
                    .scaleY(1.04f)
                    .setDuration(460L)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .withEndAction {
                        v.visibility = android.view.View.GONE
                        v.scaleX = 1f
                        v.scaleY = 1f
                        splash = null
                        splashLogo = null
                        splashStatus = null
                        splashProgress = null
                        splashElapsed = null
                        splashDetail = null
                        splashToggle = null
                        splashSteps = null
                        splashStepsScroll = null
                        splashStepsPanel = null
                        // 复位展开态与去抖缓存，避免下次进引导流程时
                        // "按钮文案=收起 / 列表却不可见"的状态错配
                        splashStepsExpanded = false
                        lastStepsText = null
                        lastStepsCount = -1
                    }
                    .start()
            }, 180L)
        }, wait)
    }

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

    private fun applyIslandInsets() {
        val root = findViewById<android.view.View>(android.R.id.content) ?: return
        val row = findViewById<android.view.View>(R.id.statusBarRow) ?: return
        val handle = findViewById<android.view.View>(R.id.handleBar) ?: return
        val d = resources.displayMetrics.density
        val baseTop = (10 * d).toInt()
        val rowH = (44 * d).toInt()
        val handleH = (26 * d).toInt()

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
            val wantDot = if (immersive) {
                maxOf(handleH, minOf(holeSize, maxDotNoOverflow))
            } else handleH
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
            row.layoutParams?.let { lp ->
                val m = lp as? android.view.ViewGroup.MarginLayoutParams
                if (m != null && (m.topMargin != wantRowTop || lp.height != wantRowH)) {
                    m.topMargin = wantRowTop
                    lp.height = wantRowH
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
            val imeBottom = if (android.os.Build.VERSION.SDK_INT >= 30) {
                insets.getInsets(android.view.WindowInsets.Type.ime()).bottom
            } else {
                @Suppress("DEPRECATION")
                if (insets.systemWindowInsetBottom > statusTop) insets.systemWindowInsetBottom else 0
            }
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
            //   · 键盘弹起（imeBottom > 0）→ padding = imeBottom（IME 高度已含
            //     导航区，直接顶到键盘上方）；
            //   · 键盘收起 → padding = navBottom **仅在全屏时**需要（窗口铺到
            //     屏幕最底，手势条会压住内容）；非全屏时系统已让开，留 0。
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
            // 底部 padding 保留：导航条/键盘必须让位，否则输入框会被键盘盖住
            // （v1.4.46 修过的老问题）。
            // 顺手把可能残留的 topMargin 清零（老版本设过留白，升级后必须还原，
            // 否则网页仍被压缩 —— 这类"改回默认却没生效"的坑踩过一次）。
            val wlp = webView.layoutParams
            if (wlp is android.view.ViewGroup.MarginLayoutParams && wlp.topMargin != 0) {
                wlp.topMargin = 0
                webView.layoutParams = wlp
            }
            if (webView.paddingBottom != bottomPad) {
                webView.setPadding(0, 0, 0, bottomPad)
            }
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

    private fun render(state: EngineSupervisor.State) {
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
                if (BootTrace.snapshot.value.startedAt <= 0) {
                    setSplashStatus(R.string.splash_loading)
                }
            }
            is EngineSupervisor.State.Failed, is EngineSupervisor.State.Backoff,
            is EngineSupervisor.State.Stopped -> dismissSplash()
            else -> Unit
        }
        statusBar.text = when (state) {
            is EngineSupervisor.State.Idle -> getString(R.string.status_idle)
            is EngineSupervisor.State.Installing -> getString(R.string.status_installing)
            is EngineSupervisor.State.Starting -> getString(R.string.status_starting).also { startStartTicker() }
            is EngineSupervisor.State.Healthy -> {
                if (!urlLoaded) {
                    urlLoaded = true
                    // v1.4.46（安全审查 H2）：拿不到带 token 的地址时**不加载**，
                    // 否则可能把 WebView 指向占了 3080 的第三方页面。
                    val u = (application as DshApp).supervisor.webUrl()
                    if (u.isNotEmpty()) loadLocalUrl(u) else urlLoaded = false
                }
                state.recoveredPlugin?.let { getString(R.string.status_plugin_recovered, it) }
                    ?: getString(R.string.status_healthy)
            }
            is EngineSupervisor.State.SafeMode -> {
                if (!urlLoaded) {
                    urlLoaded = true
                    val u = (application as DshApp).supervisor.webUrl()
                    if (u.isNotEmpty()) loadLocalUrl(u) else urlLoaded = false
                }
                state.recoveredPlugin?.let { getString(R.string.status_plugin_recovered, it) }
                    ?: getString(R.string.status_safe_mode)
            }
            is EngineSupervisor.State.Backoff ->
                getString(R.string.status_backoff, state.delayMs / 1000, state.attempt)
            is EngineSupervisor.State.Failed -> getString(R.string.status_failed, state.reason)
            is EngineSupervisor.State.Stopped -> {
                urlLoaded = false
                getString(R.string.status_idle)
            }
        }
    }

    override fun onBackPressed() {
        // WebView 有历史则先回退，保持类原生浏览体验
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
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

    private fun checkWebViewGate(): Boolean {
        val ver = currentWebViewVersion()
        if (ver >= AppGate.MIN_WEBVIEW_CHROME) {
            AppGate.markWebViewOk()
            return true
        }
        AppGate.markWebViewBlocked()
        val appCtx = applicationContext
        // 升级指引：优先指向 Play 商店的 WebView 页（绝大多数设备），失败则给系统设置
        val updateIntent = runCatching {
            Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.webview"))
        }.getOrElse {
            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:com.google.android.webview"))
        }
        val msg = getString(
            R.string.webview_gate_body,
            if (ver > 0) ver.toString() else "??",
            AppGate.MIN_WEBVIEW_CHROME,
        )
        if (gateDialog?.isShowing == true) {
            logWebView("webview gate: dialog already showing, skip")
            return false
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.webview_gate_title))
            .setMessage(msg)
            .setPositiveButton(R.string.webview_gate_update) { _, _ ->
                runCatching { startActivity(updateIntent) }
                // 升级完回来 onResume 会重新检测（AppGate 仍是 BLOCKED，会再判一次）
            }
            .setNegativeButton(R.string.webview_gate_continue) { _, _ ->
                AppGate.overrideWebViewGate()
                onResume()   // 重新走一遍（这次会启动引擎）
            }
            .setCancelable(false)
            .create()
            .also { gateDialog = it }
            .show()
        logWebView("webview gate: chrome=$ver < ${AppGate.MIN_WEBVIEW_CHROME}, prompting user")
        return false
    }

    /**
     * 当前 WebView 内核的 Chrome 版本号（0 = 读不到）。
     *
     * ## 三级取法（都失败才返回 0）
     *  ① `WebView.getCurrentWebViewPackage()` —— 官方 API，但实测有坑：
     *     **onResume 时序上可能返回 null**（provider 尚未敲定），部分 ROM 还不实现
     *  ② 扫已知 provider 包名取 versionName —— 覆盖 Google WebView / AOSP /
     *     国产 ROM 内置 provider；Android 对 WebView provider 查询有 visibility 豁免
     *  ③ 当前 webView 实例的 UA —— 只在实例已建时可用
     * UA 里的 `Chrome/NN` 永远反映真正干活的内核版本，与 provider 包名无关。
     */
    private fun currentWebViewVersion(): Int {
        // ① 官方 API
        runCatching {
            android.webkit.WebView.getCurrentWebViewPackage()?.versionName?.let { v ->
                Regex("Chrome/(\\d+)").find(v)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
                // provider 版本号首段即 Chrome 大版本（如 133.0.6943.137）
                v.substringBefore('.')?.toIntOrNull()?.let { if (it > 0) return it }
            }
        }
        // ② 扫已知 provider
        runCatching {
            for (pkg in WEBVIEW_PROVIDERS) {
                val info = runCatching { packageManager.getPackageInfo(pkg, 0) }.getOrNull() ?: continue
                val vn = info.versionName ?: continue
                Regex("Chrome/(\\d+)").find(vn)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
                vn.substringBefore('.')?.toIntOrNull()?.let { if (it > 0) return it }
            }
        }
        // ③ 现有实例的 UA
        if (::webView.isInitialized) {
            runCatching {
                Regex("Chrome/(\\d+)").find(webView.settings.userAgentString ?: "")
                    ?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
            }
        }
        return 0
    }

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
        splashWatchdog?.let { wd -> splash?.removeCallbacks(wd) }
        splashWatchdog = null
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
            setupWebView()
            // 引擎仍在跑 → 直接回到它给的地址（没有则等 Healthy 回调重载）
            val target = oldUrl ?: runCatching {
                (application as DshApp).supervisor.webUrl()
            }.getOrNull()
            if (!target.isNullOrBlank()) {
                urlLoaded = false   // 交给 Healthy/加载流程统一处理
                webView.loadUrl(target)
            }
            startHangWatchdog()   // 新页面继续受监控
            logWebView("webview recovered (reloaded ${target ?: "engine url pending"})")
        }.onFailure {
            logWebView("webview recovery failed: ${it.message} — recreating activity")
            runCatching { recreate() }
        }
    }

    /** WebView 诊断日志（写 logcat + engine.log，用户可导出） */
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


        /** 已知 WebView provider 包名（v1.2.100，三级取版本的 ② 号路径） */
        private val WEBVIEW_PROVIDERS = listOf(
            "com.google.android.webview",
            "com.android.webview",
            "com.google.android.trichromelibrary",
            "com.miui.webview",
            "com.huawei.webview",
            "com.hihonor.webview",
            "com.oplus.webview",
            "com.heytap.webview",
        )

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

        /** Android 13+ 通知运行时权限请求（Service 启动路径回调到 Activity） */
        fun maybeRequestNotificationPermission(activity: Context) {
            if (Build.VERSION.SDK_INT < 33) return
            val granted = activity.checkSelfPermission(
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted && activity is Activity) {
                activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
            }
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
