package app.dsh.mobile.engine

import android.content.Context
import android.util.Log

/**
 * 引擎运行模式选择器 —— 决定 DSH 引擎跑在 Android 原生还是 Linux 容器里。
 *
 * ## 三种状态
 *
 * | 状态 | 引擎跑在哪 | 何时出现 |
 * |---|---|---|
 * | [Mode.NATIVE] | 原生 bionic node + engine/lib 下的 dsh | 默认；或用户手动关掉 Linux 模式 |
 * | [Mode.LINUX] | 容器内 musl node + **同一份** bind 进的 dsh | 用户开启且 [LinuxEngineLauncher.readiness] 通过 |
 * | [Mode.NATIVE_FALLBACK] | 原生 | 用户开了 Linux 但环境没就绪 |
 *
 * ## 为什么要有 FALLBACK 而不直接报错
 *
 * 用户的诉求是「打开就能聊」。如果 Linux 环境因为 rootfs 还没下载 / node 没装 /
 * rootfs 损坏而不可用，直接拒绝启动会让 App 变成砖。回退到原生至少保住核心
 * 功能。
 *
 * 但**绝不静默回退**：`NATIVE_FALLBACK` 会被写进 [lastReason]，UI 据此显示
 * 「当前为兼容模式：<原因>」。用户必须知道自己在用哪个模式 —— 否则他会以为
 * 完整 Linux 环境在工作，装了 gcc 却发现编不了。
 *
 * ## 开关存储
 *
 * 与项目现有范式一致（`MainActivity.officialMobileUiEnabled`）：SharedPreferences
 * `dsh_ui` + 布尔键。默认 **false**（原生模式），原因见 [DEFAULT_ENABLED]。
 */
object EngineModeSelector {

    private const val TAG = "EngineMode"

    private const val PREFS = "dsh_ui"
    private const val KEY_LINUX_MODE = "engine_linux_mode"

    /**
     * 默认值：**false**。
     *
     * 理由：Linux 模式需要 rootfs 部署 + node 安装两步准备工作。默认开启会让
     * 所有存量用户在升级后第一次启动就走回退路径（多一次探测开销、日志里多一条
     * 告警），体验倒退。让它成为用户主动开启的选项，等安装向导做完善后再考虑
     * 改成默认开。
     */
    private const val DEFAULT_ENABLED = false

    enum class Mode { NATIVE, LINUX, NATIVE_FALLBACK }

    /** 上一次判定的结果与原因（UI 读取用；进程内缓存） */
    @Volatile
    var lastMode: Mode = Mode.NATIVE
        private set

    @Volatile
    var lastReason: String = ""
        private set

    fun isLinuxEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_LINUX_MODE, DEFAULT_ENABLED)

    fun setLinuxEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_LINUX_MODE, on).apply()
        Log.i(TAG, "linux engine mode = $on")
    }

    /**
     * 决定这次启动用哪个模式，并记录原因。
     *
     * 只在用户开了 Linux 模式时才做探测（探测本身要跑一次容器命令，几十毫秒）。
     */
    fun resolve(ctx: Context): Mode {
        if (!isLinuxEnabled(ctx)) {
            lastMode = Mode.NATIVE
            lastReason = "linux mode disabled"
            return Mode.NATIVE
        }
        val launcher = LinuxEngineLauncher.get(ctx)
        return when (val r = launcher.readiness()) {
            is LinuxEngineLauncher.Readiness.Ready -> {
                lastMode = Mode.LINUX
                lastReason = ""
                Mode.LINUX
            }
            is LinuxEngineLauncher.Readiness.NeedsDeploy -> {
                lastMode = Mode.NATIVE_FALLBACK
                lastReason = "linux rootfs not deployed"
                Log.w(TAG, "linux mode requested but rootfs not deployed — falling back to native")
                Mode.NATIVE_FALLBACK
            }
            is LinuxEngineLauncher.Readiness.NeedsPackages -> {
                lastMode = Mode.NATIVE_FALLBACK
                lastReason = r.reason
                Log.w(TAG, "linux mode requested but packages missing: ${r.reason} — falling back to native")
                Mode.NATIVE_FALLBACK
            }
            is LinuxEngineLauncher.Readiness.Unavailable -> {
                lastMode = Mode.NATIVE_FALLBACK
                lastReason = r.reason
                Log.w(TAG, "linux mode requested but unavailable: ${r.reason} — falling back to native")
                Mode.NATIVE_FALLBACK
            }
        }
    }

    /** 供 UI 展示的一行说明（不含中文，交由资源层本地化） */
    fun statusLine(ctx: Context): StatusLine {
        val enabled = isLinuxEnabled(ctx)
        val st = LinuxEnvironmentManager.get(ctx).status()
        return StatusLine(
            enabled = enabled,
            running = lastMode,
            rootfsReady = st.state == LinuxEnvironmentManager.State.READY,
            reason = lastReason,
        )
    }

    data class StatusLine(
        val enabled: Boolean,
        val running: Mode,
        val rootfsReady: Boolean,
        val reason: String,
    )
}
