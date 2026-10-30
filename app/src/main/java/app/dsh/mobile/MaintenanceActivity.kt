package app.dsh.mobile

import android.app.AlertDialog
import android.os.Bundle
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import app.dsh.mobile.engine.BootTrace
import app.dsh.mobile.engine.PluginKillSwitch
import app.dsh.mobile.engine.HomeResetter
import app.dsh.mobile.engine.SelfCheck

/**
 * 维护与高级（v1.4.45）：从设置主页收拢的诊断与高风险操作子页。
 *
 * 收进来的四行：插件应急总开关、全面体检与修复、上次启动详情、重启引擎。
 * 这些都是「出问题才进来」的工具 —— 和日常偏好混排会稀释主页，也容易误触。
 * 体检/启动详情用 [ReportDialog] 结构化展示（状态徽章列表），不再堆纯文本。
 */
class MaintenanceActivity : ThemedActivity() {

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OrientationPrefs.forcePortrait(this)
        setContentView(R.layout.activity_maintenance)

        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }

        // —— 插件应急总开关（v1.4.27）：插件把 WebUI 弄坏但引擎还活着时的逃生门 ——
        findViewById<LinearLayout>(R.id.rowPluginKill).setOnClickListener {
            confirmKillSwitch()
        }

        // —— 全面体检与修复（v1.4.4）：逐条展示，异常项带具体数值 ——
        val selfCheckSub = findViewById<TextView>(R.id.txtSelfCheckSub)
        findViewById<LinearLayout>(R.id.rowSelfCheck).setOnClickListener {
            selfCheckSub.text = getString(R.string.self_check_running)
            Thread({
                val report = runCatching { SelfCheck.run(this) }.getOrNull()
                runOnUiThread {
                    if (report == null) {
                        selfCheckSub.text = getString(
                            R.string.recover_failed, getString(R.string.file_editor_read_failed),
                        )
                        return@runOnUiThread
                    }
                    selfCheckSub.text = if (report.issues == 0) {
                        getString(R.string.self_check_ok)
                    } else {
                        getString(R.string.self_check_issues, report.issues, report.fixed)
                    }
                    ReportDialog.show(
                        this,
                        getString(R.string.setting_self_check),
                        report.items.map { item ->
                            ReportDialog.Item(
                                status = when (item.status) {
                                    SelfCheck.Item.Status.OK -> ReportDialog.Status.OK
                                    SelfCheck.Item.Status.FIXED -> ReportDialog.Status.FIXED
                                    SelfCheck.Item.Status.WARN -> ReportDialog.Status.WARN
                                    SelfCheck.Item.Status.FAIL -> ReportDialog.Status.FAIL
                                },
                                title = item.title,
                                detail = item.detail,
                            )
                        },
                        getString(R.string.report_copy),
                        SelfCheck.summarize(report),
                    )
                }
            }, "self-check").start()
        }

        // —— 上次启动详情（v1.4.8）：各阶段耗时，不用再翻 engine.log ——
        findViewById<LinearLayout>(R.id.rowBootTrace).setOnClickListener {
            val snap = BootTrace.snapshot.value
            val items = ArrayList<ReportDialog.Item>()
            if (snap.startedAt <= 0) {
                items.add(ReportDialog.Item(ReportDialog.Status.WARN, getString(R.string.boot_trace_none)))
            } else {
                items.add(
                    ReportDialog.Item(
                        ReportDialog.Status.OK,
                        getString(R.string.boot_step_runtime),
                        String.format(java.util.Locale.US, "%.1fs", snap.elapsedMs() / 1000.0) +
                            if (snap.running) " · " + getString(R.string.boot_trace_running) else "",
                    ),
                )
                val ins = snap.install
                if (ins.totalFiles > 0) {
                    items.add(
                        ReportDialog.Item(
                            ReportDialog.Status.OK,
                            getString(R.string.splash_files, ins.doneFiles, ins.totalFiles),
                        ),
                    )
                }
                snap.steps.forEach { s ->
                    items.add(
                        ReportDialog.Item(
                            ReportDialog.Status.OK,
                            s.key,
                            String.format(java.util.Locale.US, "%d ms (at +%d ms)", s.durationMs, s.atMs),
                        ),
                    )
                }
                if (snap.failure != null) {
                    items.add(ReportDialog.Item(ReportDialog.Status.FAIL, getString(R.string.report_error), snap.failure))
                }
            }
            ReportDialog.show(
                this,
                getString(R.string.boot_trace_title),
                items,
                getString(R.string.report_copy),
                BootTrace.readLogTail(this, 32 * 1024),
            )
        }

        // —— 查看日志（v1.4.45 迁自设置页数据区）：崩溃/无限重启的核心排查入口 ——
        findViewById<LinearLayout>(R.id.rowViewLog).setOnClickListener {
            startActivity(android.content.Intent(this, LogViewerActivity::class.java))
        }

        // —— 重启引擎：有中断后果，先确认再动手（v1.4.45 起）——
        findViewById<LinearLayout>(R.id.rowRestart).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.restart_confirm_title))
                .setMessage(getString(R.string.restart_confirm_body))
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    (application as DshApp).supervisor.restart()
                    Toast.makeText(this, getString(android.R.string.ok), Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .showStyled()
        }

        // —— 深度清理 / 彻底重置（v1.4.56，用户反馈「体检修不好只能重装」）——
        findViewById<LinearLayout>(R.id.rowDeepClean).setOnClickListener {
            confirmClean(HomeResetter.Mode.DEEP)
        }
        findViewById<LinearLayout>(R.id.rowResetHome).setOnClickListener {
            confirmClean(HomeResetter.Mode.RESET)
        }
        // 右侧的按钮也绑一份：它是 TextView 且带 background，会消费点击事件，
        // 父容器的监听收不到（v1.4.56 实测「点了没反应」）
        findViewById<TextView>(R.id.btnResetHome)?.setOnClickListener {
            confirmClean(HomeResetter.Mode.RESET)
        }
        findViewById<TextView>(R.id.btnDeepClean)?.setOnClickListener {
            confirmClean(HomeResetter.Mode.DEEP)
        }
        refreshHomeUsage()
    }

    /**
     * 清理前的确认流程。
     *
     * 顺序：用量摘要 -> 用户选备份位置 -> 二次确认（RESET 还要输入 RESET）
     * -> 停引擎 -> 清理 -> 重启引擎。任何一步取消都安全退出。
     *
     * 为什么必须先停引擎：运行中删除会让引擎崩溃在半途，且它持有的 fd 会让
     * 删除失败（表现为「删了一半」这种更难查的状态）。
     */
    private fun confirmClean(mode: HomeResetter.Mode) {
        // 体积统计必须在后台算（见 refreshHomeUsage 的 ANR 说明）。
        // 算的这点时间用户感知不到，先显示一个「正在计算」的占位更稳。
        Toast.makeText(this, getString(R.string.clean_calculating), Toast.LENGTH_SHORT).show()
        Thread {
            val usage = runCatching { HomeResetter.usage(this) }.getOrNull()
            val willDelete = when (mode) {
                HomeResetter.Mode.DEEP -> usage?.cacheBytes ?: 0L
                HomeResetter.Mode.RESET -> usage?.totalBytes ?: 0L
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val titleId = if (mode == HomeResetter.Mode.DEEP) {
                    R.string.setting_deep_clean
                } else {
                    R.string.setting_reset_home
                }
                val body = when (mode) {
                    HomeResetter.Mode.DEEP ->
                        getString(R.string.deep_clean_confirm_body, formatSize(willDelete))
                    HomeResetter.Mode.RESET ->
                        getString(R.string.reset_home_confirm_body, formatSize(willDelete))
                }
                AlertDialog.Builder(this)
                    .setTitle(titleId)
                    .setMessage(body)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.action_choose_backup) { _, _ -> pickBackupLocation(mode) }
                    .showStyled()
            }
        }.apply { isDaemon = true }.start()
    }

    /** 让用户用系统文件选择器指定备份位置（选完才真正动手）。 */
    private fun pickBackupLocation(mode: HomeResetter.Mode) {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        val tag = if (mode == HomeResetter.Mode.DEEP) "clean" else "reset"
        val intent = android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(android.content.Intent.CATEGORY_OPENABLE)
            .setType("application/zip")
            .putExtra(android.content.Intent.EXTRA_TITLE, "dsh-home-before-" + tag + "-" + stamp + ".zip")
        runCatching {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_BACKUP)
            pendingMode = mode
        }.onFailure {
            Toast.makeText(this, getString(R.string.backup_unavailable, it.message ?: ""), Toast.LENGTH_LONG).show()
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_BACKUP) return
        val uri = data?.data
        val mode = pendingMode
        pendingMode = null
        if (resultCode != RESULT_OK || uri == null || mode == null) return
        // RESET 不可逆（且备份可能失败），要求输入确认词
        if (mode == HomeResetter.Mode.RESET) {
            askResetTypedConfirm(uri)
        } else {
            executeClean(mode, uri)
        }
    }

    /** RESET 的二次确认：必须手输 RESET 四个字母，防止误触。 */
    private fun askResetTypedConfirm(uri: android.net.Uri) {
        val input = android.widget.EditText(this).apply {
            hint = getString(R.string.reset_type_hint)
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.reset_final_title)
            .setMessage(getString(R.string.reset_final_body))
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                if (input.text.toString().trim().equals("RESET", ignoreCase = true)) {
                    executeClean(HomeResetter.Mode.RESET, uri)
                } else {
                    Toast.makeText(this, getString(R.string.reset_wrong_word), Toast.LENGTH_SHORT).show()
                }
            }
            .showStyled()
    }

    /**
     * 执行：备份 -> 停引擎 -> 清理 -> 重启。
     *
     * 备份失败就**中止**。RESET 尤其如此：没有备份的彻底重置等于让用户丢失
     * API 凭证和会话历史，不可接受。DEEP 保持一致行为，避免两套语义。
     */
    private fun executeClean(mode: HomeResetter.Mode, target: android.net.Uri) {
        val progress = android.app.ProgressDialog(this).apply {
            setMessage(getString(R.string.backup_running))
            setCancelable(false)
            show()
        }
        Thread {
            var backupErr: String? = null
            try {
                val out = contentResolver.openOutputStream(target)
                if (out == null) {
                    backupErr = "cannot open output stream"
                } else {
                    out.use {
                        HomeResetter.backupTo(this, it) { done ->
                            runOnUiThread {
                                progress.setMessage(getString(R.string.backup_progress, formatSize(done)))
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                backupErr = e.message ?: e.javaClass.simpleName
            }

            if (backupErr != null) {
                runOnUiThread {
                    progress.dismiss()
                    AlertDialog.Builder(this)
                        .setTitle(R.string.backup_failed_title)
                        .setMessage(getString(R.string.backup_failed_body, backupErr ?: ""))
                        .setPositiveButton(android.R.string.ok, null)
                        .showStyled()
                }
                return@Thread
            }

            runOnUiThread { progress.setMessage(getString(R.string.clean_running)) }
            val app = application as DshApp
            // 停引擎：运行中删除会让引擎崩溃在半途
            runCatching { app.supervisor.stopForMaintenance() }

            val result = runCatching { HomeResetter.run(this, mode) }
                .getOrElse {
                    HomeResetter.Result(
                        ok = false,
                        mode = mode,
                        removedBytes = 0,
                        removedEntries = 0,
                        failures = listOf(it.message ?: "unknown"),
                        preserved = emptyList(),
                        error = it.message,
                    )
                }

            runOnUiThread {
                progress.dismiss()
                AlertDialog.Builder(this)
                    .setTitle(if (result.ok) R.string.clean_done_title else R.string.clean_partial_title)
                    .setMessage(cleanSummary(mode, result))
                    .setPositiveButton(R.string.action_restart_now) { _, _ ->
                        app.supervisor.restart()
                        Toast.makeText(this, getString(R.string.engine_restarting), Toast.LENGTH_SHORT).show()
                        finish()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .showStyled()
                refreshHomeUsage()
            }
        }.start()
    }

    private fun cleanSummary(mode: HomeResetter.Mode, r: HomeResetter.Result): String {
        val nl = System.lineSeparator()
        return buildString {
            append(getString(R.string.clean_summary_removed, r.removedEntries, formatSize(r.removedBytes)))
            if (r.preserved.isNotEmpty()) {
                append(nl).append(getString(R.string.clean_summary_preserved, r.preserved.joinToString(", ")))
            }
            if (r.failures.isNotEmpty()) {
                append(nl).append(getString(R.string.clean_summary_failed, r.failures.joinToString(", ")))
            }
            append(nl).append(nl).append(getString(R.string.clean_summary_backup_hint))
            if (mode == HomeResetter.Mode.RESET) {
                append(nl).append(getString(R.string.clean_summary_reset_hint))
            }
        }
    }

    /**
     * 刷新两行副标题上的体积摘要。
     *
     * ⚠️ v1.4.56 实测 ANR：体积统计要遍历 `.cache`（6 万多个文件），在
     * `onCreate` 里同步调用会阻塞主线程 >5s，系统直接弹「应用无响应」。
     * 必须放到后台线程，算完再回主线程设置文本。
     *
     * 未算完时先显示静态副标题（不显示体积），避免出现空白或假数字。
     */
    private fun refreshHomeUsage() {
        val deep = findViewById<TextView>(R.id.subDeepClean) ?: return
        val reset = findViewById<TextView>(R.id.subResetHome) ?: return
        Thread {
            val u = runCatching { HomeResetter.usage(this) }.getOrNull() ?: return@Thread
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                deep.text = getString(R.string.setting_deep_clean_sub_usage, formatSize(u.cacheBytes))
                reset.text = getString(R.string.setting_reset_home_sub_usage, formatSize(u.totalBytes))
            }
        }.apply { isDaemon = true }.start()
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 ->
            String.format(java.util.Locale.US, "%.1f GB", bytes / 1024.0 / 1024 / 1024)
        bytes >= 1024L * 1024 ->
            String.format(java.util.Locale.US, "%.1f MB", bytes / 1024.0 / 1024)
        bytes >= 1024L ->
            String.format(java.util.Locale.US, "%.0f KB", bytes / 1024.0)
        else -> bytes.toString() + " B"
    }

    private var pendingMode: HomeResetter.Mode? = null
    private val REQ_BACKUP = 9101

    override fun onResume() {
        super.onResume()
        refreshPluginKill()
    }

    /**
     * 插件应急总开关的状态刷新（v1.4.27，v1.4.45 迁入本页）。
     * 标题随状态变：正常时是「停用全部第三方插件」，停用后变成「恢复第三方插件」。
     * 状态来自意图日志（[PluginKillSwitch.status]），引擎重启中也永远显示真实状态。
     */
    private fun refreshPluginKill() {
        val title = findViewById<TextView>(R.id.valPluginKill) ?: return
        val sub = findViewById<TextView>(R.id.txtPluginKillSub) ?: return
        val btn = findViewById<TextView>(R.id.btnPluginKill) ?: return
        val st = runCatching { PluginKillSwitch.status(this) }.getOrNull() ?: return
        if (st.active) {
            title.text = getString(R.string.setting_plugin_kill_restore)
            sub.text = if (st.allow.isNotEmpty()) {
                getString(
                    R.string.setting_plugin_kill_restore_sub_partial,
                    st.allow.size, st.disabled.size,
                )
            } else {
                getString(R.string.setting_plugin_kill_restore_sub, st.disabled.size)
            }
            sub.setTextColor(getColor(R.color.warning))
            btn.text = getString(R.string.action_restore)
        } else {
            title.text = getString(R.string.setting_plugin_kill)
            sub.text = getString(R.string.setting_plugin_kill_sub, st.thirdParty.size)
            sub.setTextColor(getColor(R.color.text_secondary))
            btn.text = getString(R.string.action_disable)
        }
    }

    /** 确认框：把将被停用/恢复的包名列出来，别让用户盲点 */
    private fun confirmKillSwitch() {
        val st = runCatching { PluginKillSwitch.status(this) }.getOrNull() ?: return
        val restore = st.active
        val list = if (restore) st.disabled else st.thirdParty
        if (list.isEmpty()) {
            Toast.makeText(this, getString(R.string.plugin_kill_none), Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(
                if (restore) R.string.plugin_kill_restore_title
                else R.string.plugin_kill_disable_title,
            )
            .setMessage(
                getString(
                    if (restore) R.string.plugin_kill_restore_body
                    else R.string.plugin_kill_disable_body,
                    list.size,
                    summarizePackages(list),
                ),
            )
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ -> applyKillSwitch(restore) }
            .showStyled()
    }

    /** 包名列表：超过 8 个只列前 8 个 + 「等 N 个」，避免对话框撑满屏幕 */
    private fun summarizePackages(list: List<String>): String {
        val head = list.take(8).joinToString("\n") { "· $it" }
        return if (list.size <= 8) head
        else head + "\n" + getString(R.string.plugin_kill_more, list.size - 8)
    }

    /**
     * 落意图 + 重启引擎（v1.4.45 迁入本页）。
     *
     * ⚠️ 只写意图日志，不改 profiles/web/package.json —— 引擎运行中会把内存里的
     * profile 状态写回去；真正的数组改写由监督器在下次 spawn 前完成。
     */
    private fun applyKillSwitch(restore: Boolean) {
        val outcome =
            if (restore) PluginKillSwitch.requestEnable(this)
            else PluginKillSwitch.requestDisable(this, "maintenance")
        if (!outcome.ok) {
            Toast.makeText(
                this,
                getString(R.string.plugin_kill_failed, outcome.message),
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        Toast.makeText(this, getString(R.string.plugin_kill_restarting), Toast.LENGTH_SHORT).show()
        (application as DshApp).supervisor.restart()
        refreshPluginKill()
    }

    /** 统一弹窗样式：覆盖自绘圆角背景 */
    private fun AlertDialog.Builder.showStyled(): AlertDialog =
        create().apply {
            setOnShowListener {
                window?.setBackgroundDrawableResource(R.drawable.bg_dialog_rounded)
            }
            show()
        }
}
