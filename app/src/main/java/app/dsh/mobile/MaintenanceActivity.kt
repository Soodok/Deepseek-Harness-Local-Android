package app.dsh.mobile

import android.app.AlertDialog
import android.os.Bundle
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import app.dsh.mobile.engine.BootTrace
import app.dsh.mobile.engine.PluginKillSwitch
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
        OrientationPrefs.applyTo(this)
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
    }

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
