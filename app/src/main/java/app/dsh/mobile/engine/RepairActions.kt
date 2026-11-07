package app.dsh.mobile.engine

import android.content.Context
import android.util.Log

/**
 * 失败归因的**执行端**（v1.5.3）：把 [EngineTriage.Fix] 真的做出来。
 *
 * ## 为什么单独一个类
 *
 * 归因（[EngineTriage]）是纯函数，只回答「是什么、能做什么」；执行则涉及
 * 线程、锁、副作用与「做完之后引擎怎么重新起来」。两者的测试方式完全不同
 * （前者喂数据，后者必须在设备上跑），混在一起会让两边都难改。
 *
 * ## 三条硬约束
 *
 * 1. **每个动作都必须能在后台线程跑完，且不可撤销的操作必须先确认**
 *    （[EngineTriage.Fix.needsConfirm]，由调用方负责弹窗）。
 * 2. **动作失败不许把引擎留在半死状态**：凡是「改磁盘」的动作（回滚配置、
 *    清残渣、修属主），做完都要么让用户重启、要么自己触发重启 —— 绝不出现
 *    「配置回滚了但引擎还拿着旧内存状态在跑」。
 * 3. **不猜**：拿不到需要的条件（没有 last-good 快照、没有 root）就返回
 *    [Outcome.SKIPPED] 并把原因带回去，让界面如实说「这个做不了」，
 *    而不是假装成功。
 */
object RepairActions {

    private const val TAG = "RepairActions"

    /**
     * 单个动作的执行结果。
     *
     * [message] 是**给用户看的一句话**（已本地化），[detail] 是给日志的原始信息。
     * 两者分开是因为「回滚成功」这种消息里不该掺进异常堆栈，而排查时又必须看得到。
     */
    data class Outcome(
        val kind: EngineTriage.Fix.Kind,
        val status: Status,
        val message: String = "",
        val detail: String = "",
        /** 这个动作之后引擎是否需要（或已经）重启才能生效 */
        val restartNeeded: Boolean = false,
    ) {
        enum class Status {
            /** 做成了 */
            DONE,

            /** 条件不具备，什么都没做（不是失败，界面要区分） */
            SKIPPED,

            /** 尝试了但失败 */
            FAILED,
        }
    }

    /**
     * 执行一个动作。
     *
     * ⚠️ 这是**阻塞**调用（含文件 IO、杀进程、跑体检）。必须在后台线程调用，
     * 调用方负责切线程 —— 这里不切，是为了让「连做三个动作」能复用同一个
     * 后台线程，也让调用方能在动作之间决定还要不要继续。
     */
    fun run(ctx: Context, supervisor: EngineSupervisor, fix: EngineTriage.Fix): Outcome =
        runCatching { runInternal(ctx, supervisor, fix) }.getOrElse { e ->
            Log.w(TAG, "action ${fix.kind} threw: ${e.message}")
            Outcome(
                kind = fix.kind,
                status = Outcome.Status.FAILED,
                detail = "${e.javaClass.simpleName}: ${e.message}",
                restartNeeded = true,
            )
        }

    private fun runInternal(
        ctx: Context,
        supervisor: EngineSupervisor,
        fix: EngineTriage.Fix,
    ): Outcome = when (fix.kind) {
        // ── 端口：清场后自动重启，这是唯一一个自带重启的动作 ──────────────
        EngineTriage.Fix.Kind.RECLAIM_PORT -> {
            val ok = supervisor.reclaimPortAndRetry()
            Outcome(
                kind = fix.kind,
                status = if (ok) Outcome.Status.DONE else Outcome.Status.FAILED,
                restartNeeded = false,   // reclaimPortAndRetry 内部已经 restart
            )
        }

        // ── 重启：不带任何数据改动 ──────────────────────────────────────
        EngineTriage.Fix.Kind.RESTART_ENGINE -> {
            supervisor.restart()
            Outcome(fix.kind, Outcome.Status.DONE, restartNeeded = false)
        }

        // ── 体检：只诊断，重启由用户决定 ────────────────────────────────
        EngineTriage.Fix.Kind.RUN_SELF_CHECK -> {
            val report = SelfCheck.run(ctx)
            Outcome(
                kind = fix.kind,
                status = Outcome.Status.DONE,
                detail = "issues=${report.issues} fixed=${report.fixed}",
                restartNeeded = false,
            )
        }

        // ── 摘插件：走 PluginRecovery 已有的隔离（带备份），不自己删目录 ──
        EngineTriage.Fix.Kind.QUARANTINE_PLUGIN -> {
            // 先读当前健康标记：它记录了「哪个插件上次没激活」。
            // 读不到就 SKIP —— 不猜着摘（历史教训：按 mtime 从新到旧逐个摘，
            // 会先摘掉一堆无辜的插件）。
            val health = runCatching { PluginRecovery.readHealth(ctx) }.getOrNull()
            if (health == null) {
                Outcome(
                    fix.kind, Outcome.Status.SKIPPED,
                    detail = "no plugin health marker — refusing to guess which plugin broke",
                )
            } else {
                val action = runCatching { PluginRecovery.quarantineOne(ctx, health) }.getOrNull()
                Outcome(
                    kind = fix.kind,
                    status = if (action != null) Outcome.Status.DONE else Outcome.Status.SKIPPED,
                    detail = action?.let { "isolated ${it.packageName}" } ?: "nothing to quarantine",
                    restartNeeded = action != null,
                )
            }
        }

        // ── 回滚配置：改磁盘，必须重启才能生效 ──────────────────────────
        EngineTriage.Fix.Kind.ROLLBACK_PROFILE -> {
            val ok = ProfileGuardian(ctx).rollbackManually()
            Outcome(
                kind = fix.kind,
                status = if (ok) Outcome.Status.DONE else Outcome.Status.SKIPPED,
                detail = if (ok) "profiles restored from last-good" else "no last-good snapshot",
                restartNeeded = ok,
            )
        }

        // ── 清残渣：改磁盘（删 staging / zip），重启后才重新走安装流程 ────
        EngineTriage.Fix.Kind.CLEAN_INSTALL_RESIDUE -> {
            val ok = RuntimeInstaller(ctx).cleanResidueNow()
            Outcome(
                kind = fix.kind,
                status = if (ok) Outcome.Status.DONE else Outcome.Status.FAILED,
                restartNeeded = true,
            )
        }

        // ── 修属主：Root 模式下 root 遗留，改磁盘 ────────────────────────
        EngineTriage.Fix.Kind.FIX_OWNERSHIP -> {
            val ok = Privilege.fixHomeOwnership(ctx)
            Outcome(
                kind = fix.kind,
                status = if (ok) Outcome.Status.DONE else Outcome.Status.SKIPPED,
                detail = if (ok) "ownership repaired" else "no privileged channel available",
                restartNeeded = ok,
            )
        }

        // ── 凭据：只跳转，绝不自动改凭据文件 ────────────────────────────
        //
        // 自动改凭据的风险远大于收益：写错会让引擎从「等用户登录」变成
        // 「配置损坏」，而用户本来只要重新登录就好。所以这里只返回 DONE，
        // 由界面负责跳转（Activity 不能在这里被启动）。
        EngineTriage.Fix.Kind.OPEN_CREDENTIALS ->
            Outcome(fix.kind, Outcome.Status.DONE, restartNeeded = false)

        // ── 重装运行时：最重的一步，短动作里做不了 ──────────────────────
        //
        // 真正的重装是 `RuntimeInstaller.ensureInstalled(force=true)`，它要
        // 下载 + 解压几十秒并有自己的进度 UI。这里不发起，只告诉调用方
        // 「需要走重装流程」—— 界面据此跳到那个带进度的入口。
        EngineTriage.Fix.Kind.REINSTALL_RUNTIME ->
            Outcome(
                fix.kind, Outcome.Status.SKIPPED,
                detail = "reinstall must go through the runtime installer UI",
                restartNeeded = true,
            )
    }

    /**
     * 连做多个动作，遇到「需要重启」就停下。
     *
     * 为什么在这里停：几个动作里只要有改磁盘的（回滚/清残渣/修属主），
     * 后续动作在旧状态上执行就是白做甚至有害。停下比「全做完再重启」安全。
     */
    fun runAll(
        ctx: Context,
        supervisor: EngineSupervisor,
        fixes: List<EngineTriage.Fix>,
    ): List<Outcome> {
        val out = mutableListOf<Outcome>()
        for (f in fixes) {
            val o = run(ctx, supervisor, f)
            out += o
            if (o.restartNeeded) break
        }
        return out
    }
}
