package app.dsh.mobile.engine.cap

import android.content.Context
import app.dsh.mobile.engine.CapabilityGate
import app.dsh.mobile.engine.EngineConfig
import app.dsh.mobile.engine.Privilege
import org.json.JSONObject

/**
 * 虚拟屏（「给 AI 的第二块屏」）。
 *
 * ## 它解决的是什么问题
 *
 * 屏幕是单数资源。AI 一旦在上面动手，用户就没法用手机了 —— 这是所有
 * 「AI 代理手机」方案共同的死结：要么 AI 抢屏，要么用户让位。
 *
 * 虚拟屏把这块冲突拆开：在 `DisplayManager` 里另开一块**只存在于系统内部**的
 * 逻辑屏（`createVirtualDisplay`），把目标 App 启到那个 display 上。AI 在那块屏
 * 上截图、点击、输入，用户的物理屏完全不受影响，两边可以同时操作。
 *
 * ## 为什么必须走门脚本而不是这里的 Kotlin
 *
 * 建屏与跨屏启动都有硬门槛，App 进程（uid 10488 / `u:r:untrusted_app`）跨不过：
 *
 * 1. `VIRTUAL_DISPLAY_FLAG_TRUSTED`（API 31+）是签名级权限 `ADD_TRUSTED_DISPLAY`，
 *    不持有就请求会抛 `SecurityException`。持有它才有输入焦点。
 * 2. `am start --display` 启**非导出** Activity 要求 uid 0；shell（Shizuku）会被
 *    `not exported from uid` 拒掉。
 * 3. 取帧用的 `ImageReader` + `VirtualDisplay` 必须与建屏同一进程常驻。
 *
 * 所以真正的核心是一段**独立进程**：`app_process` 以 uid 0 加载本 APK 的
 * `VScreenCore` 类（实测 logcat `RuntimeInit uid 0`），自己监听一个本地端口，
 * 由门脚本 `vscreen` 转发。本类只是 App 进程侧的一层薄封装，把 HTTP 端点的
 * 请求翻译成「执行一次门脚本」。
 *
 * ## 已知边界（不要假装）
 *
 * - **只有 ROOT 模式可用**。Shizuku 能建屏但拿不到 TRUSTED，且起不了非导出组件。
 * - `uiautomator dump` 不支持 `--display`，只抓物理屏；要看虚拟屏的树必须用
 *   无障碍服务（见 `DshAccessibilityService.windowListJson(displayId)`）。
 * - `screencap -d` 认的是 SurfaceFlinger 的数字 id，不是逻辑 display id；
 *   取帧走核心自己的 `ImageReader`，不用 screencap。
 * - 核心进程是长驻的，App 被杀不会自动带走（ppid 会变 1）。回收见 [stop]。
 */
object VScreenCap {

    /** 门脚本单次调用的超时。建屏要分配 GraphicBuffer，比普通命令慢。 */
    private const val TIMEOUT_MS = 30_000L

    private const val GATE = "vscreen"

    /**
     * 支持的 op 白名单。
     *
     * 白名单而不是透传：门脚本的 `exec` 类子命令（如果有）一旦被透传，
     * 就等于给了 AI 一个可以拼任意参数的 root 通道。这里逐个列名，
     * 参数只取我们认识的字段。
     */
    private val OPS = setOf("start", "status", "stop", "shot", "launch", "tap", "swipe", "key", "type", "journal")

    fun status(ctx: Context): Pair<Int, String> {
        if (!CapabilityGate.can(ctx, CapabilityGate.VSCREEN)) return denied()
        return 200 to run(ctx, "status")
    }

    /**
     * 统一入口。`op` 决定转发到门脚本的哪个子命令。
     *
     * 返回 `Pair<状态码, JSON 字符串>`，与其它 Cap 一致。
     */
    fun control(ctx: Context, body: String): Pair<Int, String> {
        if (!CapabilityGate.can(ctx, CapabilityGate.VSCREEN)) return denied()
        val obj = runCatching { JSONObject(body) }.getOrNull()
            ?: return 400 to error("invalid json")
        val op = obj.optString("op").lowercase()
        if (op !in OPS) return 400 to error("op must be " + OPS.joinToString("|"))
        val args = mutableListOf(op)
        when (op) {
            "launch" -> {
                val pkg = obj.optString("pkg").ifBlank { return 400 to error("pkg required") }
                // 只允许包名或 包名/类名 两种形态，拒绝把 shell 元字符带进去。
                if (!pkg.matches(Regex("^[A-Za-z0-9_.]+(/[A-Za-z0-9_.\\$]+)?$"))) {
                    return 400 to error("pkg must be a package or package/class")
                }
                args += pkg
            }
            "tap" -> {
                args += obj.optInt("x", -1).toString()
                args += obj.optInt("y", -1).toString()
            }
            "swipe" -> {
                args += obj.optInt("x1", -1).toString()
                args += obj.optInt("y1", -1).toString()
                args += obj.optInt("x2", -1).toString()
                args += obj.optInt("y2", -1).toString()
                if (obj.has("ms")) args += obj.optInt("ms", 300).toString()
            }
            "key" -> {
                val code = obj.optInt("code", obj.optInt("key", -1))
                if (code < 0) return 400 to error("code required")
                args += code.toString()
            }
            "type" -> {
                val text = obj.optString("text")
                if (text.isEmpty()) return 400 to error("text required")
                args += text
            }
            else -> Unit
        }
        return 200 to run(ctx, args.joinToString(" ") { quote(it) })
    }

    // ==================== 执行 ====================

    /**
     * 跑一次门脚本。用 `Privilege.runPrivileged`（先 Shizuku 后 su）。
     *
     * 注意环境变量的两个相反要求（实测踩过，别合并）：
     * - 门脚本自己是 **engine bash**，必须有 `LD_LIBRARY_PATH` 指向引擎 lib，
     *   否则报 `cannot locate symbol "rl_completion_rewrite_hook"`；
     * - 而它内部拉起的 `app_process` 必须**没有** `LD_LIBRARY_PATH`，否则会去
     *   引擎 lib 里找 `libz.so` 并 abort。门脚本里用 `env -u` 处理了这一点。
     */
    private fun run(ctx: Context, argLine: String): String {
        val engine = EngineConfig.engineRoot(ctx)
        val gate = java.io.File(java.io.File(engine, "bin"), GATE)
        if (!gate.isFile) return error("gate script not deployed: run the engine once")
        val lib = java.io.File(engine, "lib")
        val home = EngineConfig.dshHome(ctx)
        val cmd = "export LD_LIBRARY_PATH=${lib.absolutePath}; " +
            "export DSH_HOME=${home.absolutePath}; " +
            "export DSH_HTTP_TIMEOUT=25; " +
            "${gate.absolutePath} $argLine 2>&1"
        val out = Privilege.runPrivileged(ctx, cmd)
            ?: return error("no privileged channel (root or shizuku required)")
        // 门脚本自己会往 stderr 打连接类噪声（bash 的 /dev/tcp 报错），
        // 只取最后一个完整 JSON 行。
        val json = out.lineSequence()
            .map { it.trim() }
            .lastOrNull { it.startsWith("{") && it.endsWith("}") }
        return json ?: JSONObject()
            .put("ok", false)
            .put("error", "no json from gate: " + out.trim().take(300))
            .toString()
    }

    /** 最小 shell 引用：只在必要时加单引号，避免常规调用被包得难读。 */
    private fun quote(s: String): String =
        if (s.matches(Regex("^[A-Za-z0-9_.:/=-]+$"))) s
        else "'" + s.replace("'", "'\\''") + "'"

    private fun denied(): Pair<Int, String> = 403 to JSONObject()
        .put("ok", false)
        .put("error", "capability disabled")
        .put("cap", CapabilityGate.VSCREEN)
        .put("hint", "open Permission Center and enable 'Virtual screen'; it needs ROOT mode")
        .toString()

    private fun error(msg: String): String =
        JSONObject().put("ok", false).put("error", msg).toString()
}
