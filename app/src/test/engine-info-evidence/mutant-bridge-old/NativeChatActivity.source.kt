package app.dsh.mobile

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.android.FlutterActivityLaunchConfigs
import io.flutter.embedding.android.RenderMode
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

/**
 * 独立 Flutter 聊天入口。只桥接引擎状态和 WebView 回退，不复制 WebUI 会话文件解析。
 */
class NativeChatActivity : FlutterActivity() {
    private var channel: MethodChannel? = null
    private val draftIo = java.util.concurrent.Executors.newSingleThreadExecutor()

    /**
     * 运行模式切换的串行执行器。
     *
     * 切换要跑 [app.dsh.mobile.engine.EngineModeSelector.resolve]，而它会走
     * LinuxEngineLauncher.readiness() —— 里面做 SELinux 哨兵、写 distro 配置、
     * 装兼容 shim，实测十几到几十毫秒不等。放主线程会卡掉一帧，所以整段
     * （判定 → 落偏好 → 权威回读 → 请求重启）都在这里串行跑。
     */
    private val runtimeIo = java.util.concurrent.Executors.newSingleThreadExecutor()

    /**
     * 是否有一次切换正在执行。
     *
     * 面板上两个模式项都在同一帧可点，双击会并发两次「落偏好 + 重启引擎」，
     * 后者把前者的重启掐掉。这里用「不排队、直接回 busy」的语义：并发的第二次
     * 调用拿到的是一份如实说「正在切换」的回执，而不是排一次队再重启一遍。
     */
    @Volatile private var runtimeSwitching = false

    /**
     * 「偏好已切、引擎还没重启」的待生效目标（wire）。
     *
     * 面板说「成功」指的是**权威判定**已经切过去了；引擎进程仍在用旧模式跑，
     * 直到用户点重启才开始用新模式 spawn。这个标记只作说明，不做动作 ——
     * 由 engineInfo() 在检测到 webUrl 换了一代（重启必然换 token）时清掉。
     */
    @Volatile private var pendingRuntimeTarget: String? = null
    @Volatile private var pendingRuntimeUrl: String = ""

    /**
     * 当前这个原生会话对应的 DSH 会话标识。
     *
     * 由 Flutter 侧在真正打开某个会话时经 [CHANNEL] 的 `setSession` 上报，
     * 不在这里猜：会话身份只有引擎与工作区知道。切回 Web 时把它带回去，
     * 让 Web 打开同一个会话，而不是"最近的一个"。
     */
    private var sessionId: String? = null

    override fun getBackgroundMode(): FlutterActivityLaunchConfigs.BackgroundMode =
        FlutterActivityLaunchConfigs.BackgroundMode.transparent

    override fun getRenderMode(): RenderMode = RenderMode.texture

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingFirstFrame = false
    private var firstFrameDrawn = false
    private var windowFocused = false
    private var announced = false
    private var backdropView: android.widget.ImageView? = null
    private var backdropPath: String? = null
    private var backdropManager: android.view.WindowManager? = null
    private var backdropReleased = false
    private var launchFailed = false
    private val readyTimeout = Runnable { failLaunch("content-ready-timeout") }

    override fun onFlutterUiDisplayed() {
        super.onFlutterUiDisplayed()
        firstFrameDrawn = true
        announceVisibleOnce()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        windowFocused = hasFocus
        if (hasFocus) {
            configureEdgeToEdge()
            announceVisibleOnce()
        }
    }

    /** First-frame notifications are telemetry, never permission to remove the cover. */
    private fun announceVisibleOnce() {
        if (announced || !firstFrameDrawn || !windowFocused) return
        announced = true
        val target = channel
        if (target == null) pendingFirstFrame = true
        else target.invokeMethod("firstFrameShown", null)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Flutter can ask for these during super.onCreate/configureFlutterEngine.
        sessionId = if (savedInstanceState?.containsKey(EXTRA_SESSION_ID) == true) {
            savedInstanceState.getString(EXTRA_SESSION_ID)
        } else {
            intent?.getStringExtra(EXTRA_SESSION_ID)?.takeIf { it.isNotBlank() }
        }
        backdropPath = intent?.getStringExtra(EXTRA_BACKDROP)?.takeIf { it.isNotBlank() }
        super.onCreate(savedInstanceState)
        publishSessionResult()
        configureEdgeToEdge()
        backdropPath?.let {
            handler.postDelayed(readyTimeout, BACKDROP_READY_TIMEOUT_MS)
            attachBackdrop(it)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(EXTRA_SESSION_ID, sessionId)
    }

    override fun onResume() {
        super.onResume()
        configureEdgeToEdge()
    }

    @Suppress("DEPRECATION")
    private fun configureEdgeToEdge() {
        // Keep the same window rectangle before and after the Flutter handoff.
        // Insets remain available to Flutter; Android does not also inset its root.
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
        }
        window.decorView.systemUiVisibility = window.decorView.systemUiVisibility or
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or
            android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            window.navigationBarDividerColor = android.graphics.Color.TRANSPARENT
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
    }

    /** The panel holds the captured Web frame until Flutter explicitly owns it. */
    private fun attachBackdrop(path: String) {
        val bitmap = runCatching { android.graphics.BitmapFactory.decodeFile(path) }.getOrNull()
        if (bitmap == null) {
            handler.post { failLaunch("backdrop-decode-failed") }
            return
        }
        val image = android.widget.ImageView(this).apply {
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            setImageBitmap(bitmap)
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }
        val lp = android.view.WindowManager.LayoutParams(
            android.view.WindowManager.LayoutParams.MATCH_PARENT,
            android.view.WindowManager.LayoutParams.MATCH_PARENT,
            android.view.WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
            android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            android.graphics.PixelFormat.TRANSLUCENT,
        )
        lp.gravity = android.view.Gravity.TOP or android.view.Gravity.START
        // PixelCopy captured the full window; fitting the panel to system bars crops it.
        if (android.os.Build.VERSION.SDK_INT >= 30) lp.setFitInsetsTypes(0)
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode =
                android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        // The token is not available until the decor has attached. A queued add
        // must never resurrect a cover after an earlier release or failure.
        window.decorView.post {
            if (isFinishing || isDestroyed || backdropReleased || launchFailed) {
                image.setImageDrawable(null)
                return@post
            }
            lp.token = window.decorView.windowToken
            val manager = getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager
            val added = runCatching { manager.addView(image, lp) }
                .onFailure { logFade("backdrop overlay add failed: " + it) }
                .isSuccess
            if (!added) {
                image.setImageDrawable(null)
                failLaunch("backdrop-attach-failed")
                return@post
            }
            backdropView = image
            backdropManager = manager
            logFade("backdrop overlay attached: " + bitmap.width + "x" + bitmap.height)
        }
    }

    /** Return true only once the panel has actually been removed. */
    private fun releaseBackdrop(): Boolean {
        if (isFinishing || isDestroyed || launchFailed) return false
        if (!removeBackdrop()) return false
        backdropReleased = true
        handler.removeCallbacks(readyTimeout)
        logFade("backdrop released by flutter")
        return true
    }

    private fun removeBackdrop(): Boolean {
        val image = backdropView ?: return true
        val manager = backdropManager ?: return false
        val removed = runCatching { manager.removeViewImmediate(image) }
            .onFailure { logFade("backdrop remove failed: " + it) }
            .isSuccess
        if (!removed) return false
        // Do not recycle: a queued draw can still reference the BitmapDrawable.
        image.setImageDrawable(null)
        backdropView = null
        backdropManager = null
        return true
    }

    /** A timeout or explicit error returns to Web; it never reveals a placeholder. */
    private fun failLaunch(reason: String): Boolean {
        if (launchFailed || isFinishing || isDestroyed) return false
        launchFailed = true
        handler.removeCallbacks(readyTimeout)
        logFade("launch handoff failed: " + reason)
        runCatching { openWebFallback() }
            .onFailure { logFade("Web fallback failed: " + it) }
        removeBackdrop()
        finish()
        return true
    }

    /** Read the already captured file; the panel may still be queued for attachment. */
    private fun backdropPng(): ByteArray? {
        val path = backdropPath ?: return null
        val bytes = runCatching { java.io.File(path).readBytes() }.getOrNull()
        if (bytes == null || bytes.isEmpty()) {
            handler.post { failLaunch("backdrop-read-failed") }
            return null
        }
        return bytes
    }

    private fun logFade(message: String) {
        android.util.Log.i("NativeChatFade", message)
    }

    /**
     * 待保存的分享文本。用户先在系统文件选择器里定好位置，回调里才写入，
     * 不用应用自己的目录冒充"下载"。
     */
    private var pendingSaveText: String? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        channel = MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            CHANNEL,
        ).also { methodChannel ->
            methodChannel.setMethodCallHandler { call, result ->
                when (call.method) {
                    "readSessionDraft", "writeSessionDraft" -> {
                        val id = call.argument<String>("id")
                        val text = call.argument<String>("text")
                        val writing = call.method == "writeSessionDraft"
                        if (id.isNullOrBlank() || id.length > 256 || (writing && (text == null || text.length > 1048576))) {
                            result.error("draft/invalid", "Invalid session draft", null)
                        } else {
                            val prefs = getSharedPreferences("native_chat_drafts", MODE_PRIVATE)
                            draftIo.execute {
                                val value: Any? = runCatching {
                                    if (!writing) prefs.getString(id, null)
                                    else prefs.edit().apply {
                                        if (text.isNullOrEmpty()) remove(id) else putString(id, text)
                                    }.commit()
                                }.getOrElse { if (writing) false else null }
                                handler.post { result.success(value) }
                            }
                        }
                    }
                    "readDefaultWorkspace", "writeDefaultWorkspace", "clearDefaultWorkspace" -> {
                        val domain = call.argument<String>("domain")
                        val generation = call.argument<String>("generation")
                        val scope = supervisorOrNull()?.nativeWorkspaceScope()
                        if (scope == null || !scope.matches(domain, generation)) {
                            result.error("workspace/scope-unknown", "Running local Host data domain is unknown or changed", null)
                        } else {
                            val writing = call.method == "writeDefaultWorkspace"
                            val clearing = call.method == "clearDefaultWorkspace"
                            val target = call.argument<Map<String, Any?>>("target")
                            val validTarget = target != null && (
                                (target.size == 1 && target["home"] == true) ||
                                (target.size == 2 && (target["workspaceId"] as? String)?.isNotBlank() == true &&
                                    (target["path"] as? String)?.let { path ->
                                        path.startsWith("/") && path.none { it.code < 32 || it.code == 127 }
                                    } == true)
                                )
                            if (writing && !validTarget) {
                                result.error("workspace/invalid", "Invalid native default workspace target", null)
                            } else {
                                val queued = runCatching {
                                    runtimeIo.execute {
                                        val value = runCatching {
                                            check(supervisorOrNull()?.nativeWorkspaceScope() == scope)
                                            val prefs = getSharedPreferences("native_chat_ui", MODE_PRIVATE)
                                            val key = "defaultWorkspace.v1." + scope.domain
                                            if (writing) {
                                                val encoded = org.json.JSONObject(target!!).toString()
                                                val committed = prefs.edit().putString(key, encoded).commit()
                                                committed && prefs.getString(key, null) == encoded &&
                                                    supervisorOrNull()?.nativeWorkspaceScope() == scope
                                            } else if (clearing) {
                                                val committed = prefs.edit().remove(key).commit()
                                                committed && !prefs.contains(key) &&
                                                    supervisorOrNull()?.nativeWorkspaceScope() == scope
                                            } else {
                                                val stored = prefs.getString(key, null)
                                                val decoded = stored?.let { org.json.JSONObject(it) }
                                                val wire = decoded?.let { json ->
                                                    json.keys().asSequence().associateWith { json.get(it) }
                                                }
                                                check(supervisorOrNull()?.nativeWorkspaceScope() == scope)
                                                mapOf("domain" to scope.domain, "generation" to scope.generation, "target" to wire)
                                            }
                                        }
                                        handler.post {
                                            value.fold(
                                                onSuccess = { result.success(it) },
                                                onFailure = { result.error("workspace/preference-failed", "Native workspace preference unavailable", null) },
                                            )
                                        }
                                    }
                                }.isSuccess
                                if (!queued) result.error("workspace/preference-failed", "Native workspace preference executor unavailable", null)
                            }
                        }
                    }
                    "readAppearance" -> {
                        val prefs = getSharedPreferences("native_chat_ui", MODE_PRIVATE)
                        result.success(mapOf("mode" to prefs.getString("mode", "dark"), "mood" to prefs.getString("mood", "mist")))
                    }
                    "readLocale" -> result.success(LocaleHelper.get(this@NativeChatActivity))
                    "consumeFirstFrame" -> {
                        // Dart 侧订阅前先问一句：首帧是不是已经显示过了？
                        val already = pendingFirstFrame || announced
                        pendingFirstFrame = false
                        result.success(already)
                    }
                    "writeAppearance" -> {
                        val mode = call.argument<String>("mode")
                        val mood = call.argument<String>("mood")
                        if (mode !in setOf("system", "light", "dark") || mood !in setOf("mist", "sand", "sage", "plum")) {
                            result.error("appearance/invalid", "Unknown appearance value", null)
                        } else {
                            result.success(getSharedPreferences("native_chat_ui", MODE_PRIVATE).edit().putString("mode", mode).putString("mood", mood).commit())
                        }
                    }
                    "readDisplaySize" -> {
                        val prefs = getSharedPreferences("native_chat_ui", MODE_PRIVATE)
                        result.success(prefs.getString("displaySize", "standard"))
                    }
                    "writeDisplaySize" -> {
                        val size = call.argument<String>("size")
                        if (size !in setOf("compact", "standard", "relaxed")) {
                            result.error("display/invalid", "Unknown display size", null)
                        } else {
                            result.success(
                                getSharedPreferences("native_chat_ui", MODE_PRIVATE)
                                    .edit().putString("displaySize", size).commit(),
                            )
                        }
                    }
                    "engineInfo" -> result.success(engineInfo())
                    "webUrl" -> result.success(supervisorOrNull()?.webUrl().orEmpty())
                    "engineState" -> result.success(engineState())
                    "openRuntimeSettings" -> {
                        openRuntimeSettings(call.argument<String>("target"))
                        result.success(true)
                    }
                    // 面板内切换运行模式：先落偏好，再由 EngineModeSelector.resolve()
                    // 做权威判定，readback 与目标不一致就回滚 —— 绝不把「写进去了」
                    // 当成「切成功了」。见 RuntimeModeAuthority 的注释。
                    "selectRuntimeMode" -> {
                        val target = call.argument<String>("mode")
                        switchRuntimeMode(target, result)
                    }
                    "openSshManager" -> { openSshManager(call.argument<String>("platform")); result.success(true) }
                    "openResource" -> {
                        val uri = call.argument<String>("uri")?.let { android.net.Uri.parse(it) }
                        val allowed = uri != null && uri.scheme in setOf("http", "https") && !uri.host.isNullOrEmpty() && uri.userInfo.isNullOrEmpty()
                        result.success(allowed && runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }.isSuccess)
                    }
                    "openSettingsSection" -> result.success(openSettingsSection(call.argument<String>("section")))
                    "shareText" -> result.success(shareText(call.argument<String>("text"), call.argument<String>("subject")))
                    "openFile" -> result.success(
                        app.dsh.mobile.engine.OpenBridge(applicationContext).open(
                            call.argument<String>("path"),
                            call.argument<String>("mime"),
                        ),
                    )
                    "shareFile" -> result.success(
                        app.dsh.mobile.engine.OpenBridge(applicationContext).share(
                            call.argument<String>("path"),
                            call.argument<String>("mime"),
                        ),
                    )
                    "openFileBytes", "shareFileBytes" -> {
                        val bytes = call.argument<ByteArray>("bytes")
                        val name = call.argument<String>("name")
                        val mime = call.argument<String>("mime")
                        if (bytes == null || name == null) {
                            result.success(false)
                        } else {
                            val share = call.method == "shareFileBytes"
                            Thread({
                                val file = runCatching {
                                    app.dsh.mobile.engine.DeliveredFileCache.stage(
                                        java.io.File(cacheDir, "shared/deliveries"), name, bytes,
                                    )
                                }.getOrNull()
                                runOnUiThread {
                                    if (isFinishing || isDestroyed || file == null) {
                                        result.success(false)
                                    } else {
                                        val bridge = app.dsh.mobile.engine.OpenBridge(applicationContext)
                                        result.success(if (share) bridge.share(file.absolutePath, mime)
                                            else bridge.open(file.absolutePath, mime))
                                    }
                                }
                            }, "dsh-file-share").start()
                        }
                    }
                    "saveText" -> result.success(saveText(call.argument<String>("text"), call.argument<String>("suggestedName")))
                    // 首屏会话：从 Web 切过来时带进来的那个；没有就为 null，
                    // Flutter 会按引擎自己的目录决定打开哪一个。
                    "initialSession" -> result.success(sessionId)
                    // Flutter 真正打开会话后上报，供"切回 Web"带回同一会话。
                    "setSession" -> {
                        sessionId = call.argument<String>("sessionId")?.takeIf { it.isNotBlank() }
                        publishSessionResult()
                        result.success(true)
                    }
                    "backdropImage" -> {
                        val bytes = backdropPng()
                        logFade("backdrop png requested, bytes=" + (bytes?.size ?: -1))
                        result.success(bytes)
                    }
                    "releaseBackdrop" -> result.success(releaseBackdrop())
                    "launchFadeFailed" -> result.success(
                        failLaunch(call.argument<String>("reason") ?: "content-failed"),
                    )
                    "openWebFallback" -> {
                        openWebFallback()
                        result.success(true)
                    }
                    else -> result.notImplemented()
                }
            }
        }
    }

    /** 文件选择器的结果：拿到 Uri 才写，取消就丢弃待存文本。 */
    @Deprecated("FlutterActivity 继承 android.app.Activity，结果走这个回调")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_SAVE_TEXT) {
            val text = pendingSaveText
            pendingSaveText = null
            val uri = data?.data
            if (resultCode != Activity.RESULT_OK || uri == null || text == null) return
            val ok = runCatching {
                contentResolver.openOutputStream(uri, "w")?.use { out ->
                    out.write(text.toByteArray(Charsets.UTF_8))
                    out.flush()
                } ?: error("output stream unavailable")
            }.isSuccess
            android.widget.Toast.makeText(
                this,
                if (ok) "已保存" else "保存失败，请换一个位置重试",
                android.widget.Toast.LENGTH_LONG,
            ).show()
            return
        }
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onDestroy() {
        draftIo.shutdown()
        runtimeIo.shutdown()
        handler.removeCallbacksAndMessages(null)
        backdropReleased = true
        removeBackdrop()
        super.onDestroy()
    }

    override fun cleanUpFlutterEngine(flutterEngine: FlutterEngine) {
        channel?.setMethodCallHandler(null)
        channel = null
        super.cleanUpFlutterEngine(flutterEngine)
    }

    /** 纯文本分享面板；返回是否真的弹出了选择器。 */
    private fun shareText(text: String?, subject: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            subject?.takeIf { it.isNotBlank() }?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
        }
        return runCatching {
            startActivity(Intent.createChooser(intent, "分享对话内容"))
            true
        }.getOrDefault(false)
    }

    /** 让用户用系统文件选择器保存纯文本；回调里才写文件。 */
    private fun saveText(text: String?, suggestedName: String?): Boolean {
        if (text.isNullOrBlank()) return false
        pendingSaveText = text
        val name = suggestedName?.takeIf { it.isNotBlank() } ?: "dsh-conversation.txt"
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, name)
        }
        return runCatching {
            startActivityForResult(intent, REQUEST_SAVE_TEXT)
            true
        }.getOrElse {
            pendingSaveText = null
            false
        }
    }

    private fun supervisorOrNull(): app.dsh.mobile.engine.EngineSupervisor? =
        (application as? DshApp)?.let { app ->
            runCatching { app.supervisor }.getOrNull()
        }

    /**
     * 判定结果转 wire 值。与 Flutter 侧 [HostInfo.runtimeMode] 的判据一一对应 ——
     * 两边都改才叫改，只改一边会让面板显示「未知环境」。
     */
    private fun runtimeWire(mode: app.dsh.mobile.engine.EngineModeSelector.Mode): String =
        when (mode) {
            app.dsh.mobile.engine.EngineModeSelector.Mode.NATIVE -> "native"
            app.dsh.mobile.engine.EngineModeSelector.Mode.LINUX -> "linux"
            app.dsh.mobile.engine.EngineModeSelector.Mode.NATIVE_FALLBACK -> "native_fallback"
        }

    /**
     * 当前**判定**值；未判定时是 "unknown"（面板显示「检测中」）。
     *
     * ⚠️ 不能只读 lastMode：EngineModeSelector.resolve() 在「Linux 已关闭」这条
     * 快路径上会在 resolveEarly 里提前返回，**不刷新 lastMode** —— 于是同进程内
     * 从 Linux 切回原生之后，lastMode 仍停在 LINUX（上一次判定留下的），而引擎
     * 下次 spawn 实际会走原生。直接透出 lastMode 就会让面板说「没切过去」。
     *
     * 这里按偏好归一：偏好关 ⇒ 判定恒为原生（这是 resolve() 自己的语义）；
     * 偏好开 ⇒ lastMode 才是真判定（LINUX / NATIVE_FALLBACK）。
     */
    private fun currentRuntimeWire(): String {
        if (!app.dsh.mobile.engine.EngineModeSelector.resolved) return "unknown"
        if (!app.dsh.mobile.engine.EngineModeSelector.isLinuxEnabled(this)) return "native"
        return runtimeWire(app.dsh.mobile.engine.EngineModeSelector.lastMode)
    }

    private fun engineInfo(): Map<String, Any> {
        val supervisor = supervisorOrNull()
        val webUrl = supervisor?.webUrl().orEmpty()
        val distro = app.dsh.mobile.engine.EngineModeSelector.lastDistro
        val workspaceScope = supervisor?.nativeWorkspaceScope()
        // 「切了但引擎还没重启」的判定：webUrl 里带每次启动才有的 token，所以
        // 换了一代（含引擎停掉后变空）就说明已经重新拉起过、新偏好已被采用，
        // 待生效标记可以收掉；没变则继续挂着。
        if (pendingRuntimeTarget != null && webUrl != pendingRuntimeUrl) {
            pendingRuntimeTarget = null
            pendingRuntimeUrl = ""
        }
        return mapOf(
            "webUrl" to webUrl,
            "available" to (supervisor?.hasLoadableUrl() == true),
            "state" to engineState(supervisor),
            "runtimeMode" to currentRuntimeWire(),
            "runtimeResolved" to app.dsh.mobile.engine.EngineModeSelector.resolved,
            "runtimeReason" to app.dsh.mobile.engine.EngineModeSelector.lastReason,
            "distro" to distro,
            "runtimeRestartPending" to (pendingRuntimeTarget != null),
            "workspaceDomain" to workspaceScope?.domain.orEmpty(),
            "workspaceGeneration" to workspaceScope?.generation.orEmpty(),
        )
    }

    /**
     * 面板内切换运行模式的宿主实现。
     *
     * 只走 [RuntimeModeAuthority] 那条「先验 → 落偏好 → 权威回读 → 不一致回滚」的路径；
     * 不重启引擎、不起停止进程、不碰 su —— 重启是用户显式动作。
     * readiness() 会做几十毫秒的静态探测与幂等自愈，所以整段放到 [runtimeIo]。
     */
    private fun switchRuntimeMode(rawTarget: String?, result: MethodChannel.Result) {
        val requested = app.dsh.mobile.engine.RuntimeModeAuthority.Target.fromWire(rawTarget)
        if (requested == null) {
            result.error("runtime/invalid", "Unknown runtime mode", null)
            return
        }
        val previous = currentRuntimeWire()
        if (runtimeSwitching) {
            result.success(
                app.dsh.mobile.engine.RuntimeModeAuthority.Receipt(
                    ok = false,
                    target = requested.wire,
                    applied = previous,
                    previous = previous,
                    restartRequired = false,
                    reason = "busy",
                ).toWire(),
            )
            return
        }
        runtimeSwitching = true
        val bootUrlAtSwitch = supervisorOrNull()?.webUrl().orEmpty()
        // 执行器可能已随 Activity 关闭（RejectedExecutionException）：那样必须
        // 立刻解开锁并如实回失败，不能留一个「永远在切换中」的面板。
        val queued = runCatching {
            runtimeIo.execute {
                val receipt = try {
                    app.dsh.mobile.engine.RuntimeModeAuthority.select(
                        requested,
                        runtimePort(),
                        previous,
                    )
                } catch (t: Throwable) {
                    app.dsh.mobile.engine.RuntimeModeAuthority.Receipt(
                        ok = false,
                        target = requested.wire,
                        applied = currentRuntimeWire(),
                        previous = previous,
                        restartRequired = false,
                        reason = t.message ?: (t::class.simpleName ?: "unknown error"),
                    )
                }
                handler.post {
                    // 锁在**回执送达**的同一刻解开，而不是算完就解：否则两次点击
                    // 之间存在「算完了但界面还没收到」的窗口，第二次点击会在旧回执
                    // 尚未生效时又改一次偏好 —— 面板显示的成功就属于两次里较晚的那次。
                    runtimeSwitching = false
                    // 「重启后才生效」只在引擎**正在跑**时才成立；没在跑时下次启动
                    // 自然按新偏好 spawn，挂个待生效标记只会让面板一直挂着假提醒。
                    if (receipt.ok && bootUrlAtSwitch.isNotEmpty()) {
                        pendingRuntimeTarget = receipt.applied
                        pendingRuntimeUrl = bootUrlAtSwitch
                    }
                    result.success(receipt.toWire())
                }
            }
        }.isSuccess
        if (!queued) {
            // 执行器已随 Activity 关闭：如实回失败，并把锁解开，
            // 绝不留一个「永远在切换中」的面板。
            runtimeSwitching = false
            result.success(
                app.dsh.mobile.engine.RuntimeModeAuthority.Receipt(
                    ok = false,
                    target = requested.wire,
                    applied = previous,
                    previous = previous,
                    restartRequired = false,
                    reason = "unavailable",
                ).toWire(),
            )
        }
    }

    /** [app.dsh.mobile.engine.RuntimeModeAuthority.Port] 的真实实现。 */
    private fun runtimePort(): app.dsh.mobile.engine.RuntimeModeAuthority.Port =
        object : app.dsh.mobile.engine.RuntimeModeAuthority.Port {
            override fun isLinuxEnabled(): Boolean =
                app.dsh.mobile.engine.EngineModeSelector.isLinuxEnabled(this@NativeChatActivity)

            override fun setLinuxEnabled(on: Boolean) =
                app.dsh.mobile.engine.EngineModeSelector.setLinuxEnabled(
                    this@NativeChatActivity,
                    on,
                )

            override fun resolve(): String = runtimeWire(
                app.dsh.mobile.engine.EngineModeSelector.resolve(this@NativeChatActivity),
            )

            override fun lastReason(): String =
                app.dsh.mobile.engine.EngineModeSelector.lastReason

            /**
             * 与 LinuxActivity.toggleEngineMode 同一套判据（end-to-end readiness，
             * 不是「rootfs 目录存在」那种半判据），失败原因原样上交，由面板显示。
             */
            override fun linuxUnavailableReason(): String? =
                when (
                    val r = app.dsh.mobile.engine.LinuxEngineLauncher
                        .get(this@NativeChatActivity)
                        .readiness()
                ) {
                    is app.dsh.mobile.engine.LinuxEngineLauncher.Readiness.Ready -> null
                    is app.dsh.mobile.engine.LinuxEngineLauncher.Readiness.NeedsDeploy ->
                        "needs-deploy"
                    is app.dsh.mobile.engine.LinuxEngineLauncher.Readiness.NeedsPackages -> r.reason
                    is app.dsh.mobile.engine.LinuxEngineLauncher.Readiness.Unavailable -> r.reason
                }
        }

    private fun engineState(supervisor: app.dsh.mobile.engine.EngineSupervisor? = supervisorOrNull()): String =
        supervisor?.state?.value?.let { it::class.simpleName ?: "unknown" } ?: "uninitialized"

    private fun openRuntimeSettings(target: String? = null) {
        val destination = target ?: when (app.dsh.mobile.engine.EngineModeSelector.lastMode) {
            app.dsh.mobile.engine.EngineModeSelector.Mode.LINUX -> "linux"
            else -> "native"
        }
        when (destination) {
            "linux" ->
                startActivity(Intent(this, LinuxActivity::class.java))
            else -> startActivity(Intent(this, PermissionCenterActivity::class.java).apply {
                putExtra(PermissionCenterActivity.EXTRA_GROUP, PermissionCenterActivity.GROUP_ENGINE)
            })
        }
    }

    private fun openSettingsSection(section: String?): Boolean {
        val destination = when (section) {
            "plugins" -> Intent(this, PluginStoreActivity::class.java)
            "extensions" -> Intent(this, ExtensionStoreActivity::class.java)
            "containers" -> Intent(this, LinuxActivity::class.java)
            "language" -> Intent(this, SettingsActivity::class.java).putExtra("native_chat_open_language", true)
            "permissions" -> Intent(this, PermissionCenterActivity::class.java)
            "diagnostics" -> Intent(this, LogViewerActivity::class.java)
            "backup" -> Intent(this, BackupActivity::class.java)
            "about" -> Intent(this, AboutActivity::class.java)
            else -> return false
        }
        return runCatching { startActivity(destination) }.isSuccess
    }

    /** SSH workspace routing is owned by the Web shell; no native deep link is verified. */
    private fun openSshManager(@Suppress("UNUSED_PARAMETER") platform: String?) {
        openWebFallback()
    }

    /**
     * 切回 Web 主界面，并把当前会话一并交回去。
     *
     * 主界面收到后会让 Web 的同一份会话真实打开（不是刷新到最近会话）；
     * 没有会话标识时不带 extra，另一个方向也不会因此猜一个。
     */
    private fun openWebFallback() {
        if (returnsToWeb()) {
            publishSessionResult()
            finish()
        } else {
            startActivity(Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            })
        }
    }

    // Let Flutter handle nested Back routes; Android delivers this result only on Activity exit.
    private fun returnsToWeb(): Boolean = callingActivity?.className == MainActivity::class.java.name

    private fun publishSessionResult() {
        if (!returnsToWeb()) return
        val selected = sessionId
        if (selected == null || launchFailed) {
            setResult(RESULT_CANCELED)
        } else {
            setResult(RESULT_OK, Intent().putExtra(EXTRA_SESSION_ID, selected))
        }
    }

    companion object {
        const val CHANNEL = "app.dsh.mobile/native_chat"

        /** Last-resort failure deadline; never interpreted as content-ready. */
        private const val BACKDROP_READY_TIMEOUT_MS = 45000L

        /**
         * 主界面拍下的入场底图（PNG 绝对路径）。
         *
         * 传路径不传 Bitmap：整屏 1080×2340 的 Bitmap 过 Intent 会撞
         * TransactionTooLargeException，而 PNG 只有一两百 KB。
         */
        const val EXTRA_BACKDROP = "app.dsh.mobile.extra.BACKDROP"
        private const val REQUEST_SAVE_TEXT = 5071

        /** 两个方向共用的会话承接 extra：Web→原生带进来，原生→Web 带回去。 */
        const val EXTRA_SESSION_ID = "app.dsh.mobile.extra.SESSION_ID"
    }
}