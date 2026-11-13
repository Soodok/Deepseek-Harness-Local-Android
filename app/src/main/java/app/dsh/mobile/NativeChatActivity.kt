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
                    "engineInfo" -> result.success(engineInfo())
                    "webUrl" -> result.success(supervisorOrNull()?.webUrl().orEmpty())
                    "engineState" -> result.success(engineState())
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
        return mapOf(
            "webUrl" to (supervisor?.webUrl().orEmpty()),
            "available" to (supervisor?.hasLoadableUrl() == true),
            "state" to engineState(supervisor),
        )
    }

    private fun engineState(supervisor: app.dsh.mobile.engine.EngineSupervisor? = supervisorOrNull()): String =
        supervisor?.state?.value?.let { it::class.simpleName ?: "unknown" } ?: "uninitialized"

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
