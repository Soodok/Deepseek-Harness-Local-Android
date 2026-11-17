package app.dsh.mobile

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

/**
 * 独立 Flutter 聊天入口。只桥接引擎状态和 WebView 回退，不复制 WebUI 会话文件解析。
 */
class NativeChatActivity : FlutterActivity() {
    private var channel: MethodChannel? = null

    /**
     * 当前这个原生会话对应的 DSH 会话标识。
     *
     * 由 Flutter 侧在真正打开某个会话时经 [CHANNEL] 的 `setSession` 上报，
     * 不在这里猜：会话身份只有引擎与工作区知道。切回 Web 时把它带回去，
     * 让 Web 打开同一个会话，而不是"最近的一个"。
     */
    private var sessionId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 从 Web 切过来时主界面已经知道当前会话，直接承接，不经过任何选择页。
        sessionId = intent?.getStringExtra(EXTRA_SESSION_ID)?.takeIf { it.isNotBlank() }
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
                    "readAppearance" -> {
                        val prefs = getSharedPreferences("native_chat_ui", MODE_PRIVATE)
                        result.success(mapOf("mode" to prefs.getString("mode", "dark"), "mood" to prefs.getString("mood", "mist")))
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
                    "openSshManager" -> { openSshManager(call.argument<String>("platform")); result.success(true) }
                    "openResource" -> {
                        val uri = call.argument<String>("uri")?.let { android.net.Uri.parse(it) }
                        val allowed = uri != null && uri.scheme in setOf("http", "https") && !uri.host.isNullOrEmpty() && uri.userInfo.isNullOrEmpty()
                        result.success(allowed && runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }.isSuccess)
                    }
                    "openSettingsSection" -> result.success(openSettingsSection(call.argument<String>("section")))
                    "shareText" -> result.success(shareText(call.argument<String>("text"), call.argument<String>("subject")))
                    "saveText" -> result.success(saveText(call.argument<String>("text"), call.argument<String>("suggestedName")))
                    // 首屏会话：从 Web 切过来时带进来的那个；没有就为 null，
                    // Flutter 会按引擎自己的目录决定打开哪一个。
                    "initialSession" -> result.success(sessionId)
                    // Flutter 真正打开会话后上报，供"切回 Web"带回同一会话。
                    "setSession" -> {
                        sessionId = call.argument<String>("sessionId")?.takeIf { it.isNotBlank() }
                        result.success(true)
                    }
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

    private fun engineInfo(): Map<String, Any> {
        val supervisor = supervisorOrNull()
        val mode = app.dsh.mobile.engine.EngineModeSelector.lastMode
        val resolved = app.dsh.mobile.engine.EngineModeSelector.resolved
        val runtimeMode = if (!resolved) "unknown" else when (mode) {
            app.dsh.mobile.engine.EngineModeSelector.Mode.NATIVE -> "native"
            app.dsh.mobile.engine.EngineModeSelector.Mode.LINUX -> "linux"
            app.dsh.mobile.engine.EngineModeSelector.Mode.NATIVE_FALLBACK -> "native_fallback"
        }
        val distro = app.dsh.mobile.engine.EngineModeSelector.lastDistro
        return mapOf(
            "webUrl" to (supervisor?.webUrl().orEmpty()),
            "available" to (supervisor?.hasLoadableUrl() == true),
            "state" to engineState(supervisor),
            "runtimeMode" to runtimeMode,
            "runtimeResolved" to resolved,
            "runtimeReason" to app.dsh.mobile.engine.EngineModeSelector.lastReason,
            "distro" to distro,
        )
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
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                sessionId?.let { putExtra(EXTRA_SESSION_ID, it) }
            },
        )
    }

    companion object {
        const val CHANNEL = "app.dsh.mobile/native_chat"
        private const val REQUEST_SAVE_TEXT = 5071

        /** 两个方向共用的会话承接 extra：Web→原生带进来，原生→Web 带回去。 */
        const val EXTRA_SESSION_ID = "app.dsh.mobile.extra.SESSION_ID"
    }
}