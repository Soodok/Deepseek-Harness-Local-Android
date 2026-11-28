package app.dsh.mobile.engine

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 一键全面修复（v1.8.x）—— 把散落在 5 个地方的修复途径收敛成**一条**。
 *
 * ## 为什么要有它
 *
 * 到 2026-11-27 为止，用户/AI 想「修一下」至少有这些互不相通的入口：
 *
 * | 位置 | 形态 | 谁能用 |
 * |---|---|---|
 * | `EngineSupervisor` 启动链 | 18 处自愈，自动跑 | 只有重启时 |
 * | `MaintenanceActivity` | 14 个按钮（修属主 / 深度清理 / 重置 / 杀插件…） | 用户手点 |
 * | `POST /repair/apply` | 9 种 `FixKind`，且**只允许归因器当前列出的那些** | 引擎内 AI |
 * | `ExtensionStoreActivity` | 扩展中心「一键修复」（只修扩展） | 用户手点 |
 * | `SelfCheck` | 12 类检查，**只报告不修** | 用户手点 |
 * | `scripts/fix-workspace-registry.py` | 离线脚本，改注册表 JSON | 电脑 + adb |
 *
 * 后果是三重的：用户不知道该点哪一个；AI 手里只有 `/repair/apply` 而它的
 * 白名单常把真正需要的动作挡在外面（`409 action not applicable`）；每个入口
 * 各自实现一遍「我做完了」，口径还不一样（有的回 `ok`，有的回 `status`）。
 *
 * 本类的目标不是「再写第五套逻辑」，而是**按固定顺序把既有函数串起来**，
 * 每一步都如实记录「跑了 / 跳过了（为什么）/ 修了 N 项 / 失败」。判定与动作
 * 都复用现有实现，这里只负责顺序、幂等与汇总。
 *
 * ## 顺序为什么是这个顺序
 *
 * 修复之间有真实的依赖，顺序错了会白做甚至更糟：
 *
 *  1. **属主/权限**最先：后面每一步都要读写 `dsh-home`，属主不对什么都动不了
 *     （真机事故：`storages/workspace.json` 是 `root:root 600`，其余步骤全废）。
 *  2. **陈旧锁**：pnpm 锁还在的话，任何插件操作都撞 `EACCES`/`ELOCKED`。
 *  3. **离线缓存/日志**：先腾空间，避免后面装包时 ENOSPC。
 *  4. **注册表**：`workspace.json` 的「同 path 多认领」会让引擎**起不来**
 *     （上游 `validateStoredState` 直接 throw），必须在起引擎之前修掉。
 *  5. **运行时/前端**：版本戳、shebang、corepack entry 这类「装好了但跑不起来」。
 *  6. **插件画像**：最后才动 `profiles/`，因为前面几步可能刚把它修好。
 *  7. **体检**：全部跑完之后做，用户看到的是「修完之后的真实状态」，
 *     而不是修之前的状态。
 *
 * ## 为什么不需要「先探测再修」
 *
 * 每个动作本身都是幂等的（chown 已经对就是空操作、锁不存在就没有可删的）。
 * 先探测反而引入两类假象：探测实现漏报（真机上 `dshHomeNeedsOwnershipFix`
 * 曾恒返回 false），以及「探测说没有 → 用户以为没问题」。所以一律**直接执行
 * 再复检**，与 `MaintenanceActivity.runFixOwner()` 的既有取舍一致。
 *
 * ## 与 RepairActions 的关系
 *
 * `RepairActions.runAll()` 早就存在，但它只做 `EngineTriage` 给出的那 9 种
 * `FixKind`（受归因白名单约束），且**全仓 0 调用者**。本类是它的超集：
 * 覆盖归因器覆盖不到的「静默自愈」（注册表去重、shebang、插件暂存 tgz、
 * 扩展坏包），并且不依赖归因器先算出 `cause`。
 *
 * 纯汇总 + 编排，不含新的文件操作逻辑；每个 step 都包在 runCatching 里，
 * 任一步失败不影响其余（修复要尽量多做，不能因为第 3 步失败就放弃第 7 步）。
 */
object FullRepair {

    private const val TAG = "FullRepair"

    /** 单步结果。 */
    enum class Status {
        /** 真的改了东西 */
        FIXED,

        /** 跑完了，本来就没问题（幂等空操作） */
        CLEAN,

        /** 缺前提（无特权通道 / 引擎在跑 / 目录不存在），已跳过 */
        SKIPPED,

        /** 抛异常或如实报失败 */
        FAILED,
    }

    /**
     * @property id        稳定 id（日志与支持沟通用，勿改）
     * @property detail    一句话说明（正常时给数值，跳过时给原因）
     * @property touched   实际改动条目数（不知道就给 0）
     * @property restartNeeded 该步改了必须重启引擎才生效的东西
     */
    data class Step(
        val id: String,
        val title: String,
        val status: Status,
        val detail: String = "",
        val touched: Int = 0,
        val restartNeeded: Boolean = false,
    )

    /**
     * @property before   体检（修复前）—— 也能看出「本来坏在哪」
     * @property steps    按执行顺序
     * @property after    体检（修复后）；`skipSelfCheck` 时为 null
     * @property restartNeeded 有任一步要求重启
     */
    data class Report(
        val before: SelfCheck.Report?,
        val steps: List<Step>,
        val after: SelfCheck.Report?,
        val restartNeeded: Boolean,
        val startedAt: Long,
        val finishedAt: Long,
    ) {
        val fixed: Int get() = steps.count { it.status == Status.FIXED }
        val clean: Int get() = steps.count { it.status == Status.CLEAN }
        val skipped: Int get() = steps.count { it.status == Status.SKIPPED }
        val failed: Int get() = steps.count { it.status == Status.FAILED }
        val elapsedMs: Long get() = finishedAt - startedAt

        /** 是否每一项都没问题（全 CLEAN）。UI 用它决定文案：不用动员用户重启。 */
        val allClean: Boolean get() = failed == 0 && fixed == 0 && skipped == 0
    }

    /**
     * 跑一遍全面修复。**必须在后台线程调用**（含目录遍历与子进程）。
     *
     * @param skipSelfCheck 跳过前后两次体检（只要动作结果时省几秒）。
     *                      默认 false：体检是让用户看见「修完之后到底怎么样」的唯一凭据。
     * @param includeReset  是否把「深度清理」也算作一步。
     *                      默认 **false** —— 它会删 `.cache`/日志/健康标记（DEEP 档保留
     *                      API key 与会话，但仍是有破坏性的一步），必须由用户显式选择。
     */
    fun run(
        ctx: Context,
        skipSelfCheck: Boolean = false,
        includeReset: Boolean = false,
    ): Report {
        val startedAt = System.currentTimeMillis()
        val before = if (skipSelfCheck) null else runCatching {
            SelfCheck.run(ctx)
        }.onFailure { Log.w(TAG, "before self-check failed: ${it.javaClass.simpleName}: ${it.message}") }
            .getOrNull()

        val steps = mutableListOf<Step>()

        // ① 属主 / 权限 —— 后面所有步骤的前提
        steps += runStep("ownership", "File ownership & permissions") {
            val fixed = Privilege.fixHomeOwnership(ctx)
            val stillBad = Privilege.dshHomeNeedsOwnershipFix(ctx)
            when {
                // 修完复检仍报坏：多半是 root 占着、当前没有 su/Shizuku
                stillBad -> Step(
                    "ownership", "File ownership & permissions", Status.FAILED,
                    if (fixed) "self-repair ran but foreign-owned entries remain (need su/Shizuku)"
                    else "cannot repair without a privileged channel (su/Shizuku)",
                )
                fixed -> Step(
                    "ownership", "File ownership & permissions", Status.FIXED,
                    "permissions/attributes restored",
                )
                else -> Step(
                    "ownership", "File ownership & permissions", Status.CLEAN,
                    "all entries already readable and correctly owned",
                )
            }
        }

        // ② 陈旧锁 —— pnpm 撞 EACCES/ELOCKED 的根源，必须在动插件前清掉
        steps += runStep("stale-locks", "Stale lock files") {
            val r = StaleLockHealer.heal(ctx) { false }
            when {
                r.skippedEngineRunning -> Step(
                    "stale-locks", "Stale lock files", Status.SKIPPED,
                    "engine is running; locks in use",
                )
                r.removed > 0 -> Step(
                    "stale-locks", "Stale lock files", Status.FIXED,
                    "removed ${r.removed} stale lock(s)" +
                        if (r.failed.isNotEmpty()) ", ${r.failed.size} could not be removed" else "",
                    touched = r.removed, restartNeeded = false,
                )
                else -> Step(
                    "stale-locks", "Stale lock files", Status.CLEAN,
                    "no stale locks" + if (r.kept > 0) " (${r.kept} in use by live process)" else "",
                )
            }
        }

        // ③ 日志与缓存 —— 先腾空间，避免后面装包 ENOSPC
        steps += runStep("logs", "Log & cache janitor") {
            val r = LogJanitor.sweep(ctx)
            if (r.trimmedFiles == 0 && r.removedOpDirs == 0) {
                Step("logs", "Log & cache janitor", Status.CLEAN, "nothing to trim")
            } else {
                Step(
                    "logs", "Log & cache janitor", Status.FIXED,
                    "trimmed ${r.trimmedFiles} log(s), ${r.removedOpDirs} op dir(s), " +
                        "freed ${r.freedBytes / 1024} KB",
                    touched = r.trimmedFiles + r.removedOpDirs,
                )
            }
        }

        // ④ 工作区注册表 —— 同 path 多认领会让引擎根本起不来，必须早修
        steps += runStep("workspace-registry", "Workspace registry") {
            val merged = EngineConfig.healWorkspaceRegistryPublic(ctx)
            if (merged > 0) {
                Step(
                    "workspace-registry", "Workspace registry", Status.FIXED,
                    "merged $merged duplicate path(s) and repaired dead paths",
                    touched = merged, restartNeeded = true,
                )
            } else {
                Step(
                    "workspace-registry", "Workspace registry", Status.CLEAN,
                    "paths valid, no duplicate claims",
                )
            }
        }

        // ⑤ 会话回收站 —— 旧版 `.trash-*` 留在 sessions/ 会让引擎判「corrupt session log」
        steps += runStep("session-trash", "Session trash") {
            val moved = SessionTrash.migrateLegacy(EngineConfig.dshHome(ctx))
            if (moved > 0) {
                Step(
                    "session-trash", "Session trash", Status.FIXED,
                    "moved $moved legacy trash entr(ies) out of sessions/",
                    touched = moved,
                )
            } else {
                Step("session-trash", "Session trash", Status.CLEAN, "no legacy trash")
            }
        }

        // ⑥ Termux shebang / 被清掉的 corepack entry —— 「装好了但跑不起来」
        //
        // 这两个 heal 原本返回 Unit（只会打日志），首版我按「跑过了就 CLEAN」写，
        // 那份报告对用户毫无信息量：他说「修完了还是不行」，报告里这两步永远
        // 「scan finished」，看不出到底改没改。现在它们改为返回**改动条数**，
        // 于是「修了 3 个 shebang」和「没有 Termux shebang」能分辨。
        steps += runStep("shebangs", "Termux shebangs") {
            val n = EngineConfig.healTermuxShebangs(ctx)
            if (n > 0) {
                Step("shebangs", "Termux shebangs", Status.FIXED, "rewrote $n Termux shebang(s)", touched = n)
            } else {
                Step("shebangs", "Termux shebangs", Status.CLEAN, "no Termux shebang in engine/bin")
            }
        }
        steps += runStep("corepack", "Corepack entry") {
            val n = EngineConfig.healClobberedCorepackEntry(ctx)
            if (n > 0) {
                Step("corepack", "Corepack entry", Status.FIXED, "restored $n clobbered entry", touched = n)
            } else {
                Step("corepack", "Corepack entry", Status.CLEAN, "corepack entries intact")
            }
        }

        // ⑦ 前端预载 —— 缓存坏了会让首屏一直白
        steps += runStep("frontend", "Frontend warmup") {
            val n = FrontendWarmup.healInstalled(ctx)
            if (n > 0) {
                Step("frontend", "Frontend warmup", Status.FIXED, "healed $n cached asset(s)", touched = n)
            } else {
                Step("frontend", "Frontend warmup", Status.CLEAN, "cache consistent")
            }
        }

        // ⑧ pnpm 工作区 —— 插件装不上/更新报 broken lockfile 的收口
        //
        // ★`healPnpmWorkspace` 的返回值语义是「**有没有真的改写文件**」，不是
        // 「工作区是否健康」。首版我把它当健康判据用，`false` 一律记 FAILED ——
        // 模拟器实测就撞了：`pnpm=FAILED(heal returned false)`，而现场其实是
        // 干净的（`profiles/web/pnpm-workspace.yaml` 里没有任何 pnpm 11 已删的
        // 构建白名单键）。这会让用户看到一个假失败，比「没检查」更糟。
        //
        // 正确映射：改写过了 → FIXED；没改写 → CLEAN（没坏键就是常态）；
        // 文件本身不存在 → SKIPPED（profile 还没建，不该算它坏）。
        steps += runStep("pnpm", "pnpm workspace") {
            val policy = java.io.File(
                java.io.File(EngineConfig.dshHome(ctx), "profiles/web"),
                "pnpm-workspace.yaml",
            )
            if (!policy.isFile) {
                Step("pnpm", "pnpm workspace", Status.SKIPPED, "no pnpm-workspace.yaml yet (profile not installed)")
            } else if (PluginCenter.healPnpmWorkspace(ctx)) {
                Step("pnpm", "pnpm workspace", Status.FIXED, "dropped obsolete pnpm 11 build whitelist keys")
            } else {
                Step("pnpm", "pnpm workspace", Status.CLEAN, "no obsolete build keys")
            }
        }

        // ⑨ 内置插件暂存 tgz —— 缺了它之后**所有** pnpm 操作都失败
        steps += runStep("staged-tgz", "Bundled plugin staging") {
            val n = ProfilePluginProvisioner.restoreMissingStaged(ctx)
            if (n > 0) {
                Step(
                    "staged-tgz", "Bundled plugin staging", Status.FIXED,
                    "restored $n missing staged tgz", touched = n,
                )
            } else {
                Step("staged-tgz", "Bundled plugin staging", Status.CLEAN, "all staged tgz present")
            }
        }

        // ⑩ 扩展坏包 —— 扩展中心「一键修复」的那一步，收进来免得用户去别的页面
        steps += runStep("extensions", "Broken extensions") {
            val repaired = ExtensionManager(ctx).repairBroken()
            if (repaired.isEmpty()) {
                Step("extensions", "Broken extensions", Status.CLEAN, "no broken extension")
            } else {
                Step(
                    "extensions", "Broken extensions", Status.FIXED,
                    "repaired ${repaired.size}: ${repaired.joinToString(",").take(80)}",
                    touched = repaired.size,
                )
            }
        }

        // ⑪ 孤儿进程 —— 占着端口的旧 node 会让新引擎起不来
        steps += runStep("orphans", "Orphaned processes") {
            val n = OrphanReaper.reap(ctx)
            if (n > 0) {
                Step("orphans", "Orphaned processes", Status.FIXED, "reaped $n orphan(s)", touched = n)
            } else {
                Step("orphans", "Orphaned processes", Status.CLEAN, "no orphan")
            }
        }

        // ⑫ 被隔离的内置 overlay —— 上一次启动失败后被 guardian 摘掉的
        steps += runStep("guardian", "Quarantined overlays") {
            val n = ProfileGuardian(ctx).restoreQuarantinedBuiltinOverlays()
            if (n > 0) {
                Step(
                    "guardian", "Quarantined overlays", Status.FIXED,
                    "restored $n quarantined builtin overlay(s)", touched = n, restartNeeded = true,
                )
            } else {
                Step("guardian", "Quarantined overlays", Status.CLEAN, "nothing quarantined")
            }
        }

        // ⑬ 插件应急开关 —— 让被强杀在中间的状态收敛回用户意图
        steps += runStep("kill-switch", "Plugin kill-switch") {
            val r = PluginKillSwitch.enforceBeforeBoot(ctx)
            if (r.changed) {
                Step(
                    "kill-switch", "Plugin kill-switch", Status.FIXED,
                    "bundles converged ${r.before} -> ${r.after}", restartNeeded = true,
                )
            } else {
                Step("kill-switch", "Plugin kill-switch", Status.CLEAN, "already converged")
            }
        }

        // ⑭ 可选：深度清理（破坏性，默认不做）
        if (includeReset) {
            steps += runStep("deep-clean", "Deep clean") {
                val r = HomeResetter.run(ctx, HomeResetter.Mode.DEEP)
                if (!r.ok) {
                    Step(
                        "deep-clean", "Deep clean", Status.FAILED,
                        r.error ?: "failed with ${r.failures.size} error(s)",
                    )
                } else {
                    Step(
                        "deep-clean", "Deep clean", Status.FIXED,
                        "removed ${r.removedBytes / 1024} KB / ${r.removedEntries} entr(ies); " +
                            "preserved ${r.preserved.joinToString(",").take(60)}",
                        touched = r.removedEntries, restartNeeded = true,
                    )
                }
            }
        }

        val after = if (skipSelfCheck) null else runCatching {
            SelfCheck.run(ctx)
        }.onFailure { Log.w(TAG, "after self-check failed: ${it.javaClass.simpleName}: ${it.message}") }
            .getOrNull()

        val report = Report(
            before = before,
            steps = steps,
            after = after,
            restartNeeded = steps.any { it.restartNeeded } || steps.any { it.status == Status.FIXED },
            startedAt = startedAt,
            finishedAt = System.currentTimeMillis(),
        )
        Log.i(TAG, summarize(report))
        return report
    }

    /** 单步包装：任何异常都折算成 FAILED，绝不让一步炸掉整轮。 */
    private inline fun runStep(id: String, title: String, body: () -> Step): Step =
        runCatching { body() }.getOrElse {
            Log.w(TAG, "$id failed: ${it.javaClass.simpleName}: ${it.message}")
            Step(id, title, Status.FAILED, "${it.javaClass.simpleName}: ${it.message}")
        }

    /** 一行日志摘要（也用于回帖/反馈里粘贴）。 */
    fun summarize(r: Report): String = buildString {
        append("full-repair ${r.elapsedMs}ms: ")
        append("fixed=${r.fixed} clean=${r.clean} skipped=${r.skipped} failed=${r.failed}")
        if (r.restartNeeded) append(" restart-needed")
        val bad = r.steps.filter { it.status == Status.FAILED || it.status == Status.FIXED }
        if (bad.isNotEmpty()) {
            append(" | ")
            append(bad.joinToString("; ") { "${it.id}=${it.status.name}(${it.detail})" })
        }
    }

    /**
     * 文本报告（写进日志 / 复制给开发者 / 回帖贴证据）。
     *
     * 与 [summarize] 的区别：这个逐条列出每步，包括 CLEAN 与 SKIPPED ——
     * 「哪些步骤被跳过了」比「哪些修好了」更能解释现场（比如无 su 时属主那步
     * 必然是 FAILED，用户看到原因就不会以为程序坏了）。
     */
    fun detail(r: Report): String = buildString {
        appendLine("DSH Mobile full repair  ${r.elapsedMs} ms")
        appendLine(
            "steps: fixed=${r.fixed} clean=${r.clean} " +
                "skipped=${r.skipped} failed=${r.failed}" +
                if (r.restartNeeded) "  (restart needed)" else "",
        )
        r.before?.let {
            appendLine("before: issues=${it.issues} fixed=${it.fixed}")
        }
        r.steps.forEach { s ->
            val tag = when (s.status) {
                Status.FIXED -> "FIXED  "
                Status.CLEAN -> "CLEAN  "
                Status.SKIPPED -> "SKIPPED"
                Status.FAILED -> "FAILED "
            }
            appendLine("[$tag] ${s.id}: ${s.title}${if (s.detail.isNotBlank()) " — ${s.detail}" else ""}")
        }
        r.after?.let {
            appendLine("after:  issues=${it.issues} fixed=${it.fixed}")
        }
    }

    /** 供 UI 用的一句人话；也保证「全绿」时不会误报「请重启」。 */
    fun headline(ctx: Context, r: Report): String = when {
        r.failed > 0 -> ctx.getString(
            app.dsh.mobile.R.string.full_repair_headline_partial,
            r.fixed, r.failed,
        )
        r.fixed > 0 -> ctx.getString(
            app.dsh.mobile.R.string.full_repair_headline_fixed,
            r.fixed,
        )
        else -> ctx.getString(app.dsh.mobile.R.string.full_repair_headline_clean)
    }

    /** 便于诊断：返回本次实际会执行的步骤 id（UI 可以预览，探针也能对账）。 */
    fun stepIds(includeReset: Boolean = false): List<String> = buildList {
        addAll(
            listOf(
                "ownership", "stale-locks", "logs", "workspace-registry", "session-trash",
                "shebangs", "corepack", "frontend", "pnpm", "staged-tgz",
                "extensions", "orphans", "guardian", "kill-switch",
            ),
        )
        if (includeReset) add("deep-clean")
    }

    /**
     * 引擎正在跑时不该做的事。
     *
     * `workspace.json` 是引擎的**独占文件**（启动读一次进内存、之后整份写回），
     * 运行期间改会被旧内存状态覆盖 —— 用户实测过「恢复成功、重启后全没了」。
     * 同理插件画像与 bundles 也在引擎内存里。
     *
     * 所以 UI 在引擎 Healthy 时要先提示「将重启引擎」，或者干脆先 stop。
     * 本类不自己停引擎（停顿是有副作用的动作，应由调用方决定时机）。
     */
    fun requiresEngineStopped(): List<String> =
        listOf("workspace-registry", "staged-tgz", "pnpm", "kill-switch", "deep-clean")

    /** 便捷判定：这条路径上会不会动「引擎独占」的东西。 */
    fun needsRestartFirst(r: Report): Boolean = r.steps.any {
        it.id in requiresEngineStopped() && (it.status == Status.FIXED || it.status == Status.FAILED)
    }
}
