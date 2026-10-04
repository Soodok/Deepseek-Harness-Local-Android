package app.dsh.mobile

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import app.dsh.mobile.engine.EngineSupervisor
import app.dsh.mobile.engine.Privilege
import app.dsh.mobile.service.EngineService
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
class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var statusBar: TextView
    private var urlLoaded = false

    /** 桌面模式：桌面 UA + 固定 1280px 视口 + 手势缩放（手机浏览器"电脑模式"等价物） */
    private var desktopMode = false
    private var defaultUa: String = ""

    /** 横屏模式：锁横屏模拟电脑屏幕比例；关闭交还系统 */
    private var landscapeMode = false

    /** 页面缩放百分比（竖屏时应用；等价浏览器 Ctrl+/Ctrl-）。横屏桌面模式交给 1280px meta，不叠加 */
    private var pageScale = DEFAULT_PAGE_SCALE

    private val uiScope = CoroutineScope(Dispatchers.Main)

    /** 待回传的文件选择结果（onShowFileChooser → onActivityResult 之间持有；null 表示无进行中请求） */
    private var pendingFileCallback: android.webkit.ValueCallback<Array<Uri>>? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 首启保护：若用户尚未完成引导（如直接拉起 MainActivity），先跳 Onboarding
        if (!Privilege.isOnboarded(this)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_main)

        statusBar = findViewById(R.id.statusBar)
        // 先赋值字段再配置：setupWebView 内部读取的是 this.webView，
        // 若写在 apply{} 里会在赋值完成前执行而触发 UninitializedPropertyAccessException。
        webView = findViewById<WebView>(R.id.webView)
        setupWebView()
        // 读回用户保存的页面缩放与横竖屏偏好
        readUiPrefs()
        applyOrientation(landscapeMode)

        // 热重启：用户显式动作，完整 stop→start 链路；urlLoaded 复位让 Healthy 后重载 3080。
        // restart() 自身立即返回（内部串行 + 先置"启动中"），无需再套线程；重复点击幂等。
        // Toast 给即时反馈：引擎优雅退出最长等 10s，期间状态栏可能来不及刷新（实测观感
        // 是"点了没反应"于是连点三下 → 触发并发 stop/start 踩踏）。
        findViewById<TextView>(R.id.btnRestart).setOnClickListener {
            urlLoaded = false
            (application as DshApp).supervisor.restart()
            Toast.makeText(this, getString(R.string.engine_restarting), Toast.LENGTH_SHORT).show()
        }
        // 隐藏工具栏：一键收起让网页全屏（点顶部小把手唤回）
        findViewById<TextView>(R.id.btnHide).setOnClickListener { toggleToolbar() }
        // 齿轮按钮：跳转独立设置页
        findViewById<View>(R.id.btnMore).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        // 预览模式返回：一键从 AI 起的服务页回引擎主界面
        findViewById<TextView>(R.id.btnBack).setOnClickListener {
            loadLocalUrl((application as DshApp).supervisor.webUrl())
        }
        // 工具栏收起/唤回：点横栏文字空白区收起（网页全屏）
        statusBar.setOnClickListener { toggleToolbar() }
        // 把手：可拖到屏幕边缘任意位置（避让遮挡）；移动距离小于阈值视为点击唤回工具栏
        setupHandleBar()

        val app = application as DshApp
        uiScope.launch {
            app.supervisor.state.collectLatest { render(it) }
        }
        uiScope.launch {
            app.supervisor.installProgress.collectLatest { renderProgress(it) }
        }
    }

    override fun onResume() {
        super.onResume()
        // 前台进入即拉起前台服务；服务存在则幂等
        EngineService.start(this)
        // 从设置页返回：重新读取横竖屏/缩放偏好，若被改则同步并重载
        val oldScale = pageScale
        val oldLandscape = landscapeMode
        readUiPrefs()
        if (oldLandscape != landscapeMode) {
            setDesktop(landscapeMode)
            applyOrientation(landscapeMode)
        }
        if (oldScale != pageScale || oldLandscape != landscapeMode) {
            webView.reload()
        }
    }

    /** 读取用户持久化的横竖屏与缩放偏好（设置页与首次引导共用同一组 prefs） */
    private fun readUiPrefs() {
        val p = getSharedPreferences(PREFS_UI, MODE_PRIVATE)
        pageScale = p.getInt(KEY_PAGE_SCALE, DEFAULT_PAGE_SCALE)
        landscapeMode = p.getBoolean(KEY_LANDSCAPE, false)
    }

    private fun setupWebView() {
        defaultUa = webView.settings.userAgentString
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false       // 关闭文件域，收窄 WebView 攻击面
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            // 竖屏页面缩小靠 viewport meta 改写（同桌面模式机制），useWideViewPort 必须开。
            useWideViewPort = true
            loadWithOverviewMode = true
            applyZoomControls(desktopMode)
        }
        // ── WebView 诊断（v1.2.49）：用户报"用不了"时能拿到确凿信息 ────────────
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
             * 网页 <input type="file"> / WebUI 附件按钮 → 系统文件选择器。
             *
             * ⚠️ 2026-10-05（Issue #5）：此前**未实现**此回调，WebView 收到文件选择请求时
             * 无人应答，表现为"上传什么都失败、也弹不出选择窗口"（WebUI 附件功能整体不可用）。
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
                val intent = runCatching { params?.createIntent() }.getOrNull()
                    ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
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

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (view == null) return
                // 环境自检：把 WebView 版本与关键 API 存在性写进日志，用户报障时一眼定位
                view.evaluateJavascript(WEBVIEW_SELFCHECK_JS, null)
                // 桌面模式：视口改写为固定 1280px（响应式走桌面分支，侧栏完整展开）。
                if (desktopMode) {
                    view.evaluateJavascript(DESKTOP_VIEWPORT_JS, null)
                    return
                }
                // 竖屏：全交给 viewport meta 的 initial-scale 控制显示缩放（浏览器 Ctrl- 做法）。
                // width=device-width → 布局宽=屏宽（无横向滚动）；initial-scale=R 直接缩放显示
                //（缩小→按钮变小看更多、整页塞进屏；放大→内容变大）；min=max=R+user-scalable=no
                // 彻底锁死（既不 clamp 到 100%，也不许用户手动改）。setInitialScale 易被 meta
                // 干扰（maximum-scale=1 会把它 clamp 回 100%），故弃用。
                view.evaluateJavascript(portraitViewportJs(pageScale / 100f), null)
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

    /** 工具栏收起/唤回：收起时网页全屏，仅留顶部小把手提示可恢复 */
    private fun toggleToolbar() {
        val row = findViewById<View>(R.id.statusBarRow)
        val handle = findViewById<View>(R.id.handleBar)
        if (row.visibility == View.VISIBLE) {
            row.visibility = View.GONE
            handle.visibility = View.VISIBLE
        } else {
            row.visibility = View.VISIBLE
            handle.visibility = View.GONE
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
        applyZoomControls(desktopMode)
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

    /** 解压进度：null=不在解压（不确定转圈），有值=真实百分比确定性进度 */
    private fun renderProgress(frac: Float?) {
        val bar = findViewById<ProgressBar>(R.id.installProgress)
        if (frac == null) {
            bar.isIndeterminate = true
        } else {
            bar.isIndeterminate = false
            bar.progress = (frac * 10000).toInt()
        }
    }

    /**
     * 屏幕朝向：把用户偏好真正落到 requestedOrientation。
     *
     * ⚠️ 竖屏必须显式用 SCREEN_ORIENTATION_PORTRAIT。SCREEN_ORIENTATION_UNSPECIFIED
     * 的语义是「交给系统/传感器决定」，**不是**「锁定竖屏」—— 旧实现给竖屏填的是
     * UNSPECIFIED，所以设置里选「竖屏」后物理旋转设备照样变横屏，看起来就是开关失灵。
     */
    private fun applyOrientation(landscape: Boolean) {
        requestedOrientation =
            if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
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
        // 显式退出（Stopped）后按钮文案变「启动」，提示用户点这里重新拉起引擎
        findViewById<TextView>(R.id.btnRestart).text =
            if (state is EngineSupervisor.State.Stopped) getString(R.string.btn_start_engine)
            else getString(R.string.btn_restart)
        val bar = findViewById<ProgressBar>(R.id.installProgress)
        bar.visibility =
            if (state is EngineSupervisor.State.Installing || state is EngineSupervisor.State.Starting)
                View.VISIBLE else View.GONE
        if (state !is EngineSupervisor.State.Starting) stopStartTicker()
        statusBar.text = when (state) {
            is EngineSupervisor.State.Idle -> getString(R.string.status_idle)
            is EngineSupervisor.State.Installing -> getString(R.string.status_installing)
            is EngineSupervisor.State.Starting -> getString(R.string.status_starting).also { startStartTicker() }
            is EngineSupervisor.State.Healthy -> {
                if (!urlLoaded) {
                    urlLoaded = true
                    loadLocalUrl((application as DshApp).supervisor.webUrl())
                }
                getString(R.string.status_healthy)
            }
            is EngineSupervisor.State.SafeMode -> {
                if (!urlLoaded) {
                    urlLoaded = true
                    loadLocalUrl((application as DshApp).supervisor.webUrl())
                }
                getString(R.string.status_safe_mode)
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
     * 请求**，不调用则页面永久等待（表现为"点了上传没反应"）。用户取消时传 null 即取消。
     */
    @Deprecated("startActivityForResult is the established contract for the WebView file chooser")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
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

    override fun onDestroy() {
        // 注意：引擎由前台服务持有，Activity 销毁不影响后台任务。
        // 首启跳 Onboarding 时本 Activity 立即销毁，webView 尚未初始化——
        // lateinit 直接访问会崩（Android 11 新用户首启闪退实测）。
        uiScope.cancel()
        // 释放未完成的文件选择请求，否则 WebView 侧回调悬空
        pendingFileCallback?.let { runCatching { it.onReceiveValue(null) } }
        pendingFileCallback = null
        if (::webView.isInitialized) webView.destroy()
        super.onDestroy()
    }

    /** WebView 诊断日志（写 logcat + engine.log，用户可导出） */
    private fun logWebView(message: String) {
        android.util.Log.w("DshWebView", message)
        runCatching {
            val f = app.dsh.mobile.engine.EngineConfig.engineRoot(this).let {
                java.io.File(it, "engine.log")
            }
            f.appendText("[webview] $message\n")
        }
    }

    companion object {
        /** WebView 文件选择器的请求码（onShowFileChooser → onActivityResult） */
        private const val REQ_FILE_CHOOSER = 1001

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

        /** 页面缩放/横竖屏持久化：SharedPreferences 名 + key（设置页与引导共用） */
        private const val PREFS_UI = "dsh_ui"
        private const val KEY_PAGE_SCALE = "page_scale"
        private const val KEY_LANDSCAPE = "landscape"

        /** 竖屏页面缩放范围/步长/默认值（等价浏览器 Ctrl- 缩小一点，用户可再调） */
        private const val DEFAULT_PAGE_SCALE = 90
        private const val MIN_PAGE_SCALE = 50
        private const val MAX_PAGE_SCALE = 150
        private const val SCALE_STEP = 5

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
