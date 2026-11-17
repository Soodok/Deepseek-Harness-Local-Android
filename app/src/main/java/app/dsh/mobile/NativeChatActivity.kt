package app.dsh.mobile

import android.content.Intent
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

/**
 * 独立 Flutter 聊天入口。只桥接引擎状态和 WebView 回退，不复制 WebUI 会话文件解析。
 */
class NativeChatActivity : FlutterActivity() {
    private var channel: MethodChannel? = null

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
                    "openWebFallback" -> {
                        openWebFallback()
                        result.success(true)
                    }
                    else -> result.notImplemented()
                }
            }
        }
    }

    override fun cleanUpFlutterEngine(flutterEngine: FlutterEngine) {
        channel?.setMethodCallHandler(null)
        channel = null
        super.cleanUpFlutterEngine(flutterEngine)
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

    private fun openWebFallback() {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
        )
    }

    companion object {
        const val CHANNEL = "app.dsh.mobile/native_chat"
    }
}
