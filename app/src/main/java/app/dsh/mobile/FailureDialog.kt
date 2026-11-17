package app.dsh.mobile

import android.app.Activity
import android.app.AlertDialog
import android.util.Log
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.dsh.mobile.engine.EngineSupervisor
import app.dsh.mobile.engine.EngineTriage
import app.dsh.mobile.engine.RepairActions

/**
 * 引擎启动失败的诊断弹窗（v1.5.3）。
 *
 * ## 为什么值得一个独立弹窗
 *
 * 之前失败只在状态栏留一句 `Start failed: <reason>`，用户看到的是一行小字，
 * 既不知道「为什么」，也没有任何出路 —— 只能去设置里翻维护页，而维护页
 * 那时也没有针对性。改完之后每次终态失败都会：
 *
 * 1. 说清**是哪个原因**（12 种之一，带本地化标题）；
 * 2. 列出**证据**（端口上的 pid/cmdline、缺失的文件、解析不了哪个配置…）；
 * 3. 给出**可点的动作**（按归因器算出的 fixes 生成，不是固定几个按钮）。
 *
 * ## 三个刻意的取舍
 *
 * - **不给「忽略」以外的撤销**：失败是既成事实，弹窗关掉不代表修好，
 *   所以主按钮是「知道了」，而修复动作是并列的中性按钮。
 * - **需要确认的动作走二级弹窗**：回滚配置/停用插件会改用户数据，
 *   直接一键执行太粗暴。
 * - **执行结果显示在同一个弹窗里**（重建弹窗），不是 Toast —— 用户需要看到
 *   `skipped` 的原因，Toast 一闪就没了。
 */
object FailureDialog {

    private const val TAG = "FailureDialog"

    /** 同一原因只自动弹一次（用户关掉后不要再追着弹） */
    @Volatile
    private var lastShownCause: String? = null

    fun resetAutoShowGuard() {
        lastShownCause = null
    }

    /**
     * 自动弹出（引擎刚进入 Failed 时调用）。
     *
     * 同一 cause 只弹一次：退避重试会让引擎反复进入 Failed，每次都弹就是骚扰。
     * 用户想看可以点状态栏（那条路径不受这个守卫限制）。
     */
    fun showOnce(activity: Activity, state: EngineSupervisor.State.Failed) {
        val key = state.diagnosis?.cause?.id ?: state.reason
        if (key == lastShownCause) return
        lastShownCause = key
        show(activity, state)
    }

    /** 用户主动查看（不受 once 守卫限制） */
    fun show(activity: Activity, state: EngineSupervisor.State.Failed) {
        val diag = state.diagnosis
        if (diag == null) {
            // 没有诊断（旧状态/异常路径）时退回最简单的样子，但至少把原文给出来
            AlertDialog.Builder(activity)
                .setTitle(R.string.failure_title)
                .setMessage(state.reason)
                .setPositiveButton(android.R.string.ok, null)
                .create()
                .apply {
                    show()
                }
            return
        }
        render(activity, state, null)
    }

    private fun render(
        activity: Activity,
        state: EngineSupervisor.State.Failed,
        lastOutcome: RepairActions.Outcome?,
    ) {
        val diag = state.diagnosis ?: return
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(activity, 16)
            setPadding(pad, dp(activity, 8), pad, dp(activity, 4))
        }

        // ── 原因标题 ────────────────────────────────────────────────────
        box.addView(sectionTitle(activity, causeTitleRes(diag.cause)))
        if (diag.neverBooted) {
            box.addView(note(activity, activity.getString(R.string.failure_never_booted)))
        }

        // ── 证据 ────────────────────────────────────────────────────────
        if (diag.evidence.isNotEmpty()) {
            box.addView(sectionLabel(activity, activity.getString(R.string.failure_evidence)))
            diag.evidence.take(8).forEach { line ->
                box.addView(bullet(activity, line))
            }
        }

        // ── 上一次动作的结果 ────────────────────────────────────────────
        if (lastOutcome != null) {
            val label = activity.getString(fixTitleRes(lastOutcome.kind))
            val text = when (lastOutcome.status) {
                RepairActions.Outcome.Status.DONE ->
                    activity.getString(R.string.fix_done, label)
                RepairActions.Outcome.Status.SKIPPED ->
                    activity.getString(
                        R.string.fix_skipped, label,
                        lastOutcome.detail.ifBlank { "-" },
                    )
                RepairActions.Outcome.Status.FAILED ->
                    activity.getString(
                        R.string.fix_failed, label,
                        lastOutcome.detail.ifBlank { "-" },
                    )
            }
            val tv = note(activity, text)
            tv.setTextColor(activity.getColor(
                when (lastOutcome.status) {
                    RepairActions.Outcome.Status.DONE -> R.color.success
                    RepairActions.Outcome.Status.SKIPPED -> R.color.warning
                    RepairActions.Outcome.Status.FAILED -> R.color.danger
                },
            ))
            box.addView(tv)
            if (lastOutcome.restartNeeded) {
                box.addView(note(activity, activity.getString(R.string.fix_restart_needed)))
            }
        }

        // ── 可用动作 ────────────────────────────────────────────────────
        if (diag.fixes.isEmpty()) {
            box.addView(note(activity, activity.getString(R.string.failure_no_action)))
        } else {
            box.addView(sectionLabel(activity, activity.getString(R.string.failure_actions)))
            diag.fixes.forEach { fix ->
                box.addView(actionRow(activity, state, fix, lastOutcome))
            }
        }

        val dlg = AlertDialog.Builder(activity)
            .setTitle(R.string.failure_title)
            .setView(ScrollView(activity).apply { addView(box) })
            .setPositiveButton(android.R.string.ok, null)
            .create()
        dlg.show()
    }

    /**
     * 一行动作按钮。
     *
     * 需要确认的动作先弹二级确认框；`REINSTALL_RUNTIME` 特殊处理 —— 它做不了
     * （要下载界面），所以点了只给一句指引，不假装执行。
     */
    private fun actionRow(
        activity: Activity,
        state: EngineSupervisor.State.Failed,
        fix: EngineTriage.Fix,
        lastOutcome: RepairActions.Outcome?,
    ): TextView {
        val tv = TextView(activity).apply {
            text = activity.getString(fixTitleRes(fix.kind))
            textSize = 15f
            setTextColor(activity.dshColor(R.attr.dshAccent, R.color.accent))
            val v = dp(activity, 10)
            setPadding(0, v, 0, v)
            isClickable = true
            setOnClickListener { onAction(activity, state, fix) }
        }
        return tv
    }

    private fun onAction(
        activity: Activity,
        state: EngineSupervisor.State.Failed,
        fix: EngineTriage.Fix,
    ) {
        // 重装运行时走不了这里（要进度界面），直接给指引
        if (fix.kind == EngineTriage.Fix.Kind.REINSTALL_RUNTIME) {
            AlertDialog.Builder(activity)
                .setTitle(fixTitleRes(fix.kind))
                .setMessage(R.string.failure_reinstall_hint)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        // 凭据：只跳转设置，不自动改文件
        if (fix.kind == EngineTriage.Fix.Kind.OPEN_CREDENTIALS) {
            runCatching {
                activity.startActivity(
                    android.content.Intent(activity, SettingsActivity::class.java),
                )
            }.onFailure { Log.w(TAG, "open settings failed: ${it.message}") }
            return
        }
        if (!fix.needsConfirm) {
            execute(activity, state, fix)
            return
        }
        AlertDialog.Builder(activity)
            .setTitle(fixTitleRes(fix.kind))
            .setMessage(R.string.failure_confirm_body)
            .setPositiveButton(R.string.failure_confirm_yes) { _, _ ->
                execute(activity, state, fix)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun execute(
        activity: Activity,
        state: EngineSupervisor.State.Failed,
        fix: EngineTriage.Fix,
    ) {
        val app = activity.applicationContext as DshApp
        // 后台执行：动作里有杀进程/读目录/跑体检，不能在主线程
        Thread({
            val outcome = RepairActions.run(activity, app.supervisor, fix)
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                // 重建弹窗把结果显示在同一处（而不是 Toast 一闪而过）
                render(activity, state, outcome)
            }
        }, "failure-repair").apply { isDaemon = true; start() }
    }

    // ── 小工具 ──────────────────────────────────────────────────────────

    private fun causeTitleRes(cause: EngineTriage.Cause): Int = when (cause) {
        EngineTriage.Cause.PORT_FOREIGN -> R.string.cause_port_foreign
        EngineTriage.Cause.PORT_ORPHAN -> R.string.cause_port_orphan
        EngineTriage.Cause.PORT_UNKNOWN_OWNER -> R.string.cause_port_unknown_owner
        EngineTriage.Cause.PLUGIN_INACTIVE -> R.string.cause_plugin_inactive
        EngineTriage.Cause.PROFILE_CORRUPT -> R.string.cause_profile_corrupt
        EngineTriage.Cause.RUNTIME_MISSING -> R.string.cause_runtime_missing
        EngineTriage.Cause.RUNTIME_ARCH -> R.string.cause_runtime_arch
        EngineTriage.Cause.STORAGE_DENIED -> R.string.cause_storage_denied
        EngineTriage.Cause.SYSTEM_OOM -> R.string.cause_system_oom
        EngineTriage.Cause.SYSTEM_KILLED -> R.string.cause_system_killed
        EngineTriage.Cause.AUTH_EXPIRED -> R.string.cause_auth_expired
        EngineTriage.Cause.UNKNOWN -> R.string.cause_unknown
    }

    private fun fixTitleRes(kind: EngineTriage.Fix.Kind): Int = when (kind) {
        EngineTriage.Fix.Kind.RECLAIM_PORT -> R.string.fix_reclaim_port
        EngineTriage.Fix.Kind.RESTART_ENGINE -> R.string.fix_restart_engine
        EngineTriage.Fix.Kind.RUN_SELF_CHECK -> R.string.fix_run_self_check
        EngineTriage.Fix.Kind.QUARANTINE_PLUGIN -> R.string.fix_quarantine_plugin
        EngineTriage.Fix.Kind.ROLLBACK_PROFILE -> R.string.fix_rollback_profile
        EngineTriage.Fix.Kind.CLEAN_INSTALL_RESIDUE -> R.string.fix_clean_install_residue
        EngineTriage.Fix.Kind.FIX_OWNERSHIP -> R.string.fix_fix_ownership
        EngineTriage.Fix.Kind.OPEN_CREDENTIALS -> R.string.fix_open_credentials
        EngineTriage.Fix.Kind.REINSTALL_RUNTIME -> R.string.fix_reinstall_runtime
    }

    private fun dp(activity: Activity, v: Int): Int =
        (v * activity.resources.displayMetrics.density).toInt()

    private fun sectionTitle(activity: Activity, textRes: Int): TextView =
        TextView(activity).apply {
            text = activity.getString(textRes)
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(activity.dshColor(R.attr.dshTextPrimary, R.color.text_primary))
            setPadding(0, dp(activity, 4), 0, dp(activity, 8))
        }

    private fun sectionLabel(activity: Activity, text: String): TextView =
        TextView(activity).apply {
            this.text = text
            textSize = 12f
            setTextColor(activity.dshColor(R.attr.dshTextSecondary, R.color.text_secondary))
            setPadding(0, dp(activity, 12), 0, dp(activity, 4))
        }

    private fun note(activity: Activity, text: String): TextView =
        TextView(activity).apply {
            this.text = text
            textSize = 13f
            setTextColor(activity.dshColor(R.attr.dshTextSecondary, R.color.text_secondary))
            setPadding(0, dp(activity, 4), 0, dp(activity, 4))
        }

    private fun bullet(activity: Activity, text: String): TextView =
        TextView(activity).apply {
            this.text = "\u2022 $text"
            textSize = 13f
            setTextColor(activity.dshColor(R.attr.dshTextPrimary, R.color.text_primary))
            setPadding(dp(activity, 4), dp(activity, 2), 0, dp(activity, 2))
        }
}
