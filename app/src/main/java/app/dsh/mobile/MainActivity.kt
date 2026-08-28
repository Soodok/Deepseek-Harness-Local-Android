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
import android.view.Gravity
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import app.dsh.mobile.engine.EngineSupervisor
import app.dsh.mobile.engine.PrivMode
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

    @SuppressLint("SetJavaScriptEnabled")
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
        // 注意此处必须无接收者调用（this.setupWebView()），
        // 写成 webView.setupWebView() 会把 WebView 当接收者而无法解析。
        webView = findViewById<WebView>(R.id.webView)
        setupWebView()
        // 读回用户保存的页面缩放
        pageScale = getSharedPreferences(PREFS_UI, MODE_PRIVATE)
            .getInt(KEY_PAGE_SCALE, DEFAULT_PAGE_SCALE)
        // 读回用户在引导里选的默认横竖屏，并应用朝向
        landscapeMode = getSharedPreferences(PREFS_UI, MODE_PRIVATE)
            .getBoolean(KEY_LANDSCAPE, false)
        if (landscapeMode) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }

        // 热重启：用户显式动作，完整 stop→start 链路；urlLoaded 复位让 Healthy 后重载 3080
        findViewById<TextView>(R.id.btnRestart).setOnClickListener {
            urlLoaded = false
            (application as DshApp).supervisor.restart()
        }
        // 隐藏工具栏：一键收起让网页全屏（点顶部小把手唤回）
        findViewById<TextView>(R.id.btnHide).setOnClickListener { toggleToolbar() }
        // ⋯ 菜单：界面选项（横屏/隐藏工具栏）
        findViewById<TextView>(R.id.btnMore).setOnClickListener { showUiMenu() }
        // 预览模式返回：一键从 AI 起的服务页回引擎主界面
        findViewById<TextView>(R.id.btnBack).setOnClickListener {
            val port = (application as DshApp).supervisor.healthyPort
            loadLocalUrl("http://127.0.0.1:$port/")
        }
        // 工具栏收起/唤回：点横栏文字空白区收起（网页全屏），点顶部把手唤回
        statusBar.setOnClickListener { toggleToolbar() }
        findViewById<View>(R.id.handleBar).setOnClickListener { toggleToolbar() }

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
            // 手势缩放按模式切换：竖屏锁死固定全屏（applyZoomControls(false)），桌面模式保留双指缩放。
            useWideViewPort = true
            loadWithOverviewMode = true
            applyZoomControls(desktopMode)
        }
        webView.webViewClient = object : WebViewClient() {
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

    /** ⋯ 菜单：AlertDialog 实现（PopupMenu 在部分 ROM/主题下不可靠，m1.8 真机教训）。
     *  桌面渲染与横屏绑定（不再独立开关，用户要求）：横屏=电脑比例=桌面渲染。 */
    private fun showUiMenu() {
        val mode = Privilege.getMode(this)
        val modeLabel = when (mode) {
            PrivMode.ROOT -> getString(R.string.priv_root)
            PrivMode.SHIZUKU -> getString(R.string.priv_shizuku)
            PrivMode.NORMAL -> getString(R.string.priv_normal)
        }
        val items = arrayOf(
            (if (landscapeMode) "✓ " else "") + getString(R.string.menu_landscape),
            getString(R.string.menu_hide_toolbar),
            getString(R.string.menu_scale),
            getString(R.string.menu_priv) + "【" + modeLabel + "】",
        )
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.menu_title))
            .setItems(items) { _, which ->
                when (which) {
                    0 -> toggleLandscape()
                    1 -> toggleToolbar()
                    2 -> showScaleDialog()
                    3 -> showPrivDialog()
                }
            }
            .showStyled()
    }

    /** 运行权限模式选择（应用内切换入口）：普通/Shizuku/Root，Root 需双警告，切换后建议重启引擎 */
    private fun showPrivDialog() {
        val current = Privilege.getMode(this)
        val opts = arrayOf(
            getString(R.string.priv_normal),
            getString(R.string.priv_shizuku),
            getString(R.string.priv_root),
        )
        val checked = when (current) {
            PrivMode.ROOT -> 2
            PrivMode.SHIZUKU -> 1
            PrivMode.NORMAL -> 0
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.menu_priv_title))
            .setSingleChoiceItems(opts, checked) { diag, which ->
                val target = when (which) {
                    2 -> PrivMode.ROOT
                    1 -> PrivMode.SHIZUKU
                    else -> PrivMode.NORMAL
                }
                if (target == PrivMode.ROOT) {
                    warnRootSwitch {
                        Privilege.setMode(this, PrivMode.ROOT)
                        diag.dismiss()
                        toastChanged()
                    }
                } else {
                    Privilege.setMode(this, target)
                    diag.dismiss()
                    toastChanged()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showStyled()
    }

    private fun warnRootSwitch(next: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.ob_priv_root_warn_title))
            .setMessage(getString(R.string.ob_priv_root_warn))
            .setCancelable(false)
            .setPositiveButton(getString(R.string.ob_dialog_continue)) { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.ob_priv_root_warn2_title))
                    .setMessage(getString(R.string.ob_priv_root_warn2))
                    .setCancelable(false)
                    .setPositiveButton(getString(R.string.ob_dialog_continue)) { _, _ -> next() }
                    .setNegativeButton(getString(R.string.ob_dialog_cancel)) { _, _ -> }
                    .showStyled()
            }
            .setNegativeButton(getString(R.string.ob_dialog_cancel)) { _, _ -> }
            .showStyled()
    }

    private fun toastChanged() {
        android.widget.Toast.makeText(this, getString(R.string.priv_changed_restart), android.widget.Toast.LENGTH_LONG).show()
    }

    /**
     * 页面缩放对话框：−/＋ 步进调节（步长 5），竖屏立即生效并 reload。
     * 等价浏览器 Ctrl-/Ctrl+，让用户按个人偏好缩放整体布局。
     */
    private fun showScaleDialog() {
        var current = pageScale
        val value = TextView(this).apply {
            textSize = 22f
            gravity = Gravity.CENTER
            text = "$current%"
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(Button(this@MainActivity).apply {
                text = "−"
                textSize = 22f
                setOnClickListener {
                    current = (current - SCALE_STEP).coerceIn(MIN_PAGE_SCALE, MAX_PAGE_SCALE)
                    value.text = "$current%"
                }
            })
            val centerParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            value.layoutParams = centerParams
            addView(value)
            addView(Button(this@MainActivity).apply {
                text = "＋"
                textSize = 22f
                setOnClickListener {
                    current = (current + SCALE_STEP).coerceIn(MIN_PAGE_SCALE, MAX_PAGE_SCALE)
                    value.text = "$current%"
                }
            })
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.scale_title))
            .setView(row)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                pageScale = current
                getSharedPreferences(PREFS_UI, MODE_PRIVATE)
                    .edit().putInt(KEY_PAGE_SCALE, pageScale).apply()
                // 立即应用 + 重载当前页：reload 后 onPageFinished 会按新 pageScale 重写视口
                webView.reload()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showStyled()
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

    /** 只改桌面渲染的 UA + 手势缩放开关，不 reload——reload 统一由旋转回调做（避免双重整页重载卡顿）。
     *  viewport 改写统一在 onPageFinished 里做。 */
    private fun setDesktop(enable: Boolean) {
        desktopMode = enable
        webView.settings.userAgentString = if (enable) DESKTOP_UA else defaultUa
        applyZoomControls(enable)
    }

    /** 横屏 = 电脑比例 = 桌面渲染（绑定）；回竖屏 = 自动还原手机渲染 */
    private fun toggleLandscape() {
        landscapeMode = !landscapeMode
        setDesktop(landscapeMode)
        requestedOrientation =
            if (landscapeMode) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
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
     * 统一弹窗样式：ColorOS 类 ROM 的 Material 对话框是直角方框，show 时覆盖
     * 自绘圆角背景（22dp），贴近原生安卓对话框的圆润观感。
     */
    private fun AlertDialog.Builder.showStyled(): AlertDialog =
        create().apply {
            setOnShowListener {
                window?.setBackgroundDrawableResource(R.drawable.bg_dialog_rounded)
            }
            show()
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

    private fun render(state: EngineSupervisor.State) {
        val bar = findViewById<ProgressBar>(R.id.installProgress)
        bar.visibility =
            if (state is EngineSupervisor.State.Installing || state is EngineSupervisor.State.Starting)
                View.VISIBLE else View.GONE
        statusBar.text = when (state) {
            is EngineSupervisor.State.Idle -> getString(R.string.status_idle)
            is EngineSupervisor.State.Installing -> getString(R.string.status_installing)
            is EngineSupervisor.State.Starting -> getString(R.string.status_starting)
            is EngineSupervisor.State.Healthy -> {
                if (!urlLoaded) {
                    urlLoaded = true
                    loadLocalUrl("http://127.0.0.1:${state.port}/")
                }
                getString(R.string.status_healthy)
            }
            is EngineSupervisor.State.SafeMode -> {
                if (!urlLoaded) {
                    urlLoaded = true
                    loadLocalUrl("http://127.0.0.1:${state.port}/")
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

    override fun onDestroy() {
        // 注意：引擎由前台服务持有，Activity 销毁不影响后台任务
        uiScope.cancel()
        webView.destroy()
        super.onDestroy()
    }

    companion object {
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

        /** 页面缩放持久化：SharedPreferences 名 + key（m1.18 新增，防重新构建后丢失用户偏好） */
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
