package app.dsh.mobile.engine
import app.dsh.mobile.coldentry.*
class EngineTriage { class Diagnosis }
class EngineSupervisor {
    sealed interface State {
        data object Idle : State
        data object Installing : State
        data object Starting : State

        /** 正常健康；tokenUrl = 0.2.0+ WebUI 认证地址（engine.log 的 dsh web: 输出） */
        data class Healthy(
            val port: Int,
            val tokenUrl: String? = null,
            /** 本次启动前自动摘掉的第三方插件；仅在恢复后的首个 Healthy 状态携带。 */
            val recoveredPlugin: String? = null,
            /**
             * 插件探针报告的降级信息（`ok=false` 时非 null）。
             *
             * ⚠️ **这是「引擎已就绪但功能残缺」的唯一信号。**
             *
             * 实测真题（2026-11-07，模拟器）：引擎 HTTP 通了、UI 显示「引擎已就绪」，
             * 但探针报 `ok=false` —— 4 个内置插件加载失败（workspace-controller /
             * workspace / storage-domain / session-persistence-jsonl），
             * 3 项能力缺失（directoryPickerController / workspaceRegistry /
             * workspaceController）。也就是说**目录选择器、工作区、会话持久化全坏**，
             * 而界面一切正常。用户只能靠「点什么都报错」自己发现。
             *
             * 之前这个信息只活在 logcat 和 engine.log 的两行文本里 —— 恰恰是
             * 「有些事我根本不知道是什么 bug」的那一类。
             */
            val degraded: Degraded? = null,
            /** Opaque identity generated for this actual spawn; copy retains it. */
            val bootId: String? = null,
        ) : State

        /** 安全模式：配置被隔离后以空配置拉起，功能受限但可用 */
        data class SafeMode(
            val port: Int,
            val tokenUrl: String? = null,
            val recoveredPlugin: String? = null,
            val degraded: Degraded? = null,
            /** Opaque identity generated for this actual spawn; copy retains it. */
            val bootId: String? = null,
        ) : State

        /**
         * 插件降级的结构化描述。
         *
         * [missingServices] 是**能力**名（如 `workspaceRegistry`），
         * [failedEntries] 是**插件条目**名（如 `@deepseek-ai/dsh-workspace`）。
         * 两者都留着：前者对用户有意义（"工作区用不了"），后者对排查有意义。
         */
        data class Degraded(
            val missingServices: List<String>,
            val failedEntries: List<String>,
        )
        data class Backoff(val delayMs: Long, val attempt: Int) : State

        /**
         * 终态失败。
         *
         * [reason] 保留为**已经本地化好的一句话**（界面直接显示它）；
         * [diagnosis] 是同一件事的结构化版本 —— 原因是哪个枚举、证据是什么、
         * 能做什么。两者刻意都留：前者让 UI 不必为了显示一句话就自己翻译枚举，
         * 后者让 UI/AI/日志拿到可判断的东西。
         *
         * v1.5.3 之前只有 [reason]，于是「端口被占」「插件没激活」「配置坏了」
         * 在界面上长得一模一样，用户和我们都分不清。
         */
        data class Failed(
            val reason: String,
            val diagnosis: app.dsh.mobile.engine.EngineTriage.Diagnosis? = null,
        ) : State
        data object Stopped : State

        /** Phase, identity and loadability are projected from this ONE immutable publication. */
        fun coldEntrySnapshot(): HostSnapshot = when (this) {
            is Healthy -> HostSnapshot("fixed", HostPhase.Healthy, !tokenUrl.isNullOrBlank())
            is SafeMode -> HostSnapshot("fixed", HostPhase.SafeMode, !tokenUrl.isNullOrBlank())
            Idle -> HostSnapshot(null, HostPhase.Idle, false)
            Installing -> HostSnapshot(null, HostPhase.Installing, false)
            Starting -> HostSnapshot(null, HostPhase.Starting, false)
            is Backoff -> HostSnapshot(null, HostPhase.Backoff, false)
            is Failed -> HostSnapshot(null, HostPhase.Failed, false)
            Stopped -> HostSnapshot(null, HostPhase.Stopped, false)
        }
    }

}
