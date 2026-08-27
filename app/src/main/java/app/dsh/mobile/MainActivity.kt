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
import android.text.InputType
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.TextView
import app.dsh.mobile.engine.EngineSupervisor
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

    private val uiScope = CoroutineScope(Dispatchers.Main)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusBar = findViewById(R.id.statusBar)
        // 先赋值字段再配置：setupWebView 内部读取的是 this.webView，
        // 若写在 apply{} 里会在赋值完成前执行而触发 UninitializedPropertyAccessException。
        // 注意此处必须无接收者调用（this.setupWebView()），
        // 写成 webView.setupWebView() 会把 WebView 当接收者而无法解析。
        webView = findViewById<WebView>(R.id.webView)
        setupWebView()

        // 热重启：用户显式动作，完整 stop→start 链路；urlLoaded 复位让 Healthy 后重载 3080
        findViewById<TextView>(R.id.btnRestart).setOnClickListener {
            urlLoaded = false
            (application as DshApp).supervisor.restart()
        }
        // 预览：AI 在引擎内自启的 web 服务（任意回环端口）直接在 WebView 里浏览
        findViewById<TextView>(R.id.btnPreview).setOnClickListener { showPreviewDialog() }

        val app = application as DshApp
        uiScope.launch {
            app.supervisor.state.collectLatest { render(it) }
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
                // 桌面模式核心：把页面视口改写为固定 1280px 并按屏宽取初始缩放，
                // 使响应式布局走桌面分支（侧栏完整展开），随后用户可双指缩放/平移。
                if (desktopMode && view != null) {
                    view.evaluateJavascript(DESKTOP_VIEWPORT_JS, null)
                }
            }
        }
    }

    /** ⋯ 菜单：两个带勾选态的模式开关 */
    private fun showUiMenu(anchor: android.view.View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, R.string.menu_desktop).apply { isCheckable = true; isChecked = desktopMode }
        popup.menu.add(0, 2, 0, R.string.menu_landscape).apply { isCheckable = true; isChecked = landscapeMode }
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> applyDesktopMode(!desktopMode)
                2 -> toggleLandscape()
            }
            true
        }
        popup.show()
    }

    private fun applyDesktopMode(enable: Boolean) {
        if (desktopMode == enable) return
        desktopMode = enable
        webView.settings.apply {
            userAgentString = if (enable) DESKTOP_UA else defaultUa
            setSupportZoom(enable)
            builtInZoomControls = enable
            displayZoomControls = false   // 只保留双指捏合，不显示 +/- 按钮浮层
            useWideViewPort = enable
            loadWithOverviewMode = enable
        }
        webView.reload()                  // 重载触发 onPageFinished 视口改写
    }

    private fun toggleLandscape() {
        landscapeMode = !landscapeMode
        requestedOrientation =
            if (landscapeMode) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 旋转后屏宽变化 → 桌面模式重算适配缩放（reload 后 onPageFinished 重写视口）
        if (desktopMode) webView.reload()
    }

    /**
     * 端口预览对话框：AI 像在电脑上一样自启 web 服务（如五子棋静态页 :3000）后，
     * 用户输入端口即可在 WebView 直接浏览。回环任意端口均被 shouldOverrideUrlLoading 放行。
     * urlLoaded 置 true 阻止状态流转把页面拉回 3080；按返回键沿 WebView 历史回主界面。
     */
    private fun showPreviewDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.preview_hint)
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.preview_title))
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val port = input.text.toString().trim().toIntOrNull() ?: return@setPositiveButton
                if (port in 1..65535) {
                    urlLoaded = true
                    webView.loadUrl("http://127.0.0.1:$port/")
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun render(state: EngineSupervisor.State) {
        statusBar.text = when (state) {
            is EngineSupervisor.State.Idle -> getString(R.string.status_idle)
            is EngineSupervisor.State.Installing -> getString(R.string.status_installing)
            is EngineSupervisor.State.Starting -> getString(R.string.status_starting)
            is EngineSupervisor.State.Healthy -> {
                if (!urlLoaded) {
                    urlLoaded = true
                    webView.loadUrl("http://127.0.0.1:${state.port}/")
                }
                getString(R.string.status_healthy)
            }
            is EngineSupervisor.State.SafeMode -> {
                if (!urlLoaded) {
                    urlLoaded = true
                    webView.loadUrl("http://127.0.0.1:${state.port}/")
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
