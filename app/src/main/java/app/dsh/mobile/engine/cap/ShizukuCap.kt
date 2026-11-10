package app.dsh.mobile.engine.cap

import android.content.Context
import app.dsh.mobile.engine.CapabilityGate
import app.dsh.mobile.engine.Privilege
import org.json.JSONObject

/**
 * Shizuku / ADB shell（uid 2000，`u:r:shell:s0`）身份下的系统操作。
 *
 * ## 为什么需要单独一档，而不是复用 `root` 门脚本
 *
 * `root` 门脚本跑在**引擎进程**里，靠的是「引擎本身 uid 0」这个前提。而
 * Shizuku 是 **App 进程内的 binder 调用**（`IShizukuService.newProcess`），
 * 只有 App 进程持有那个 binder。门脚本是引擎的子进程，拿不到它。
 *
 * 所以在 Shizuku 模式下，命令必须**经桥中转**：门脚本 `shz` → `/dev/shz`
 * → `Privilege.runPrivileged` → Shizuku binder → adb shell。
 *
 * ## 实测能力边界（2026-11-08，`u:r:shell:s0`）
 *
 * 能：读别的 App `shared_prefs` 与 databases、`sqlite3` 查表、`uiautomator dump`、
 *     `input` 合成事件、`pm list`、`settings put`、读 `/data/misc/bluetooth`、
 *     `am`/`cmd` 系列、改 App Ops。
 *
 * 不能：`iptables`（`Permission denied (you must be root)`）、写 SELinux、
 *     读 `/data/data/<pkg>` 下属主为具体 uid 且非 world-readable 的文件。
 *
 * ⚠️ 陷阱：`su 2000 -c cmd` **不等于** shell 身份 —— 它把 uid 切到 2000 但
 * SELinux 域仍是 `u:r:su:s0`，权限看起来比真实 shell 大得多。验证 shell 边界
 * 必须用真实 `adb shell`，不能用 `su 2000`。
 */
object ShizukuCap {

    /** 单次命令的默认预算。与 [Privilege.shizukuExec] 的默认值保持一致。 */
    private const val DEFAULT_TIMEOUT_MS = 10_000L

    fun handle(ctx: Context, obj: JSONObject): Pair<Int, String> {
        val op = obj.optString("op", "status").lowercase()
        if (!CapabilityGate.can(ctx, CapabilityGate.SHIZUKU)) {
            return 403 to err("capability disabled", "shizuku")
        }
        return when (op) {
            "status" -> status(ctx)
            "exec" -> exec(ctx, obj)
            else -> 400 to err("op must be status|exec")
        }
    }

    /**
     * 通道状态。三件事分开报，因为它们失败的处置方式不同：
     * · server 没跑 → 让用户去启动 Shizuku（无线调试或 root 启动）
     * · server 跑了但没授权 → 弹授权框
     * · 都就绪 → 可用
     */
    private fun status(ctx: Context): Pair<Int, String> {
        val running = Privilege.shizukuServerRunning()
        val granted = Privilege.shizukuGranted()
        val mode = Privilege.getMode(ctx).name
        CapabilityGate.audit(ctx, "shz.status")
        return 200 to JSONObject()
            .put("ok", true)
            .put("server_running", running)
            .put("granted", granted)
            .put("usable", running && granted)
            .put("priv_mode", mode)
            .put("identity", "uid 2000 / u:r:shell:s0")
            .put(
                "hint",
                when {
                    !running -> "Shizuku server is not running; start it (wireless debugging or root)"
                    !granted -> "Shizuku is running but this app is not authorised yet"
                    else -> "ready"
                },
            )
            .toString()
    }

    /**
     * 以 shell 身份执行一条命令。
     *
     * 与 `root exec` 的区别：
     * · **不需要 confirm** —— shell 权限本身就受限（改不了 iptables、写不了
     *   SELinux），破坏面远小于 uid 0，再加一道 dry-run 只会让每次调用多一轮。
     * · 超时是硬性的（默认 10s）：Shizuku 的 binder 调用超时不会自己回来，
     *   实测卡住会让整个桥请求线程一起挂住。
     */
    private fun exec(ctx: Context, obj: JSONObject): Pair<Int, String> {
        val cmd = obj.optString("cmd").trim()
        if (cmd.isEmpty()) return 400 to err("cmd is required")
        val timeout = obj.optLong("timeout_ms", DEFAULT_TIMEOUT_MS).coerceIn(1_000L, 120_000L)
        if (!Privilege.shizukuUsable()) {
            return 503 to err(
                "shizuku is not usable",
                hint = "check op=status; server must be running and this app authorised",
            )
        }
        CapabilityGate.audit(ctx, "shz.exec")
        val out = runCatching { Privilege.shizukuExec(ctx, cmd, timeout) }.getOrNull()
            ?: return 500 to err("shizuku exec failed")
        return 200 to JSONObject()
            .put("ok", true)
            .put("exit_ok", true)
            .put("output", out)
            .put("truncated", out.length >= 262_144)
            .toString()
    }

    private fun err(msg: String, cap: String = "", hint: String = ""): String {
        val o = JSONObject().put("ok", false).put("error", msg)
        if (cap.isNotEmpty()) o.put("capability", cap)
        if (hint.isNotEmpty()) o.put("hint", hint)
        return o.toString()
    }
}
