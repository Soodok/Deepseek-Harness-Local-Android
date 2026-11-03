package app.dsh.mobile

import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import app.dsh.mobile.engine.EngineConfig
import app.dsh.mobile.engine.LinuxContainerRunner
import app.dsh.mobile.engine.LinuxEngineLauncher
import app.dsh.mobile.engine.LinuxEnvironmentManager
import app.dsh.mobile.engine.LinuxPackageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Linux 工作区 —— 总览页、终端、包管理与一键安装的统一入口。
 *
 * ## 产品定位
 *
 * 这不是「插件中心里的又一个扩展」，而是和主界面并列的**一等公民**：它给用户
 * 一台装在手机里的完整 Linux 机器。与原生模式的差别不是快慢，而是**能力边界**
 * ——原生模式只跑 DSH 引擎，这里能 `apk add` 装任何软件、能编译 C/C++、
 * 能跑 python/numpy、能起 nginx/redis、能转码视频。
 *
 * ## 页面结构（全部程序化构建，不引入 RecyclerView）
 *
 * 沿用 `ExtensionStoreActivity` 的做法：清单项目数量有限且在同一次会话内不会
 * 上千条，程序化构建更简单也更好控制样式一致性。
 *
 * ```
 * ┌ 状态卡 ────────────────────────────┐
 * │ 运行器 / rootfs 版本 / 占用 / 文件数  │
 * └────────────────────────────────────┘
 * ┌ 操作 ──────────────────────────────┐
 * │ [部署/修复环境] [安装 node] [装开发工具]│
 * └────────────────────────────────────┘
 * ┌ 命令（可交互）───────────────────────┐
 * │ > _                                 │
 * │ 输出区（等宽字体，可滚）                │
 * └────────────────────────────────────┘
 * ```
 *
 * ## 三条硬纪律（都在代码里显式体现）
 *
 * 1. **所有耗时操作走 IO 线程**：解包 rootfs、apk 装包都在后台；UI 只更新控件。
 * 2. **失败必须显示原始报错**：不吞异常、不显示「操作失败」这种无法排查的文案。
 * 3. **不静默降级**：环境不可用时明确说明缺什么、该做什么，不假装一切正常。
 */
class LinuxActivity : ThemedActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var env: LinuxEnvironmentManager
    private lateinit var runner: LinuxContainerRunner
    private lateinit var pkg: LinuxPackageManager
    private lateinit var launcher: LinuxEngineLauncher

    private lateinit var statusCard: LinearLayout
    private lateinit var outputView: TextView
    private lateinit var cmdInput: EditText
    private lateinit var busyBar: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        env = LinuxEnvironmentManager.get(this)
        runner = LinuxContainerRunner.get(this)
        pkg = LinuxPackageManager.get(this)
        launcher = LinuxEngineLauncher.get(this)

        setContentView(buildRoot())
        refreshStatus()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ================= 界面构建 =================

    private fun buildRoot(): View {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(getColor(R.color.bg))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        scroll.addView(root)

        root.addView(title())

        statusCard = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(statusCard)

        root.addView(sectionLabel(getString(R.string.linux_actions)))
        root.addView(actionsCard())

        busyBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            visibility = View.GONE
            isIndeterminate = true
            progressTintList = android.content.res.ColorStateList.valueOf(Accent.current(this@LinuxActivity).argb)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(4)
            ).apply { topMargin = dp(8) }
        }
        root.addView(busyBar)

        root.addView(sectionLabel(getString(R.string.linux_console)))
        root.addView(consoleCard())

        return scroll
    }

    private fun title(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(ImageView(this@LinuxActivity).apply {
            setImageResource(R.drawable.ic_ext_android_tools)
            setColorFilter(Accent.current(this@LinuxActivity).argb)
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
        })
        addView(TextView(this@LinuxActivity).apply {
            text = getString(R.string.linux_title)
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(getColor(R.color.text_primary))
            setPadding(dp(10), 0, 0, 0)
        })
        setPadding(0, 0, 0, dp(12))
    }

    private fun sectionLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Accent.current(this@LinuxActivity).argb)
        setPadding(dp(4), dp(18), dp(4), dp(8))
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.bg_card)
        setPadding(dp(14), dp(12), dp(14), dp(12))
    }

    private fun actionsCard(): View = card().apply {
        addView(actionRow(
            getString(R.string.linux_action_deploy),
            getString(R.string.linux_action_deploy_sub),
        ) { deploy(force = false) })
        addView(actionRow(
            getString(R.string.linux_action_repair),
            getString(R.string.linux_action_repair_sub),
        ) { deploy(force = true) })
        addView(actionRow(
            getString(R.string.linux_action_node),
            getString(R.string.linux_action_node_sub),
        ) { installPackages(listOf("nodejs")) })
        addView(actionRow(
            getString(R.string.linux_action_devtools),
            getString(R.string.linux_action_devtools_sub),
        ) { installPackages(listOf("nodejs", "git", "make", "gcc", "musl-dev")) })
    }

    private fun actionRow(title: String, sub: String, onTap: () -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(10), dp(4), dp(10))
            isClickable = true
            isFocusable = true
            val tv = android.util.TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
            if (tv.resourceId != 0) setBackgroundResource(tv.resourceId)
            addView(LinearLayout(this@LinuxActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(this@LinuxActivity).apply {
                    text = title
                    textSize = 15f
                    setTextColor(getColor(R.color.text_primary))
                })
                addView(TextView(this@LinuxActivity).apply {
                    text = sub
                    textSize = 12f
                    setTextColor(getColor(R.color.text_secondary))
                    setPadding(0, dp(2), 0, 0)
                })
            })
            addView(ImageView(this@LinuxActivity).apply {
                setImageResource(R.drawable.ic_chevron)
                setColorFilter(getColor(R.color.text_secondary))
                layoutParams = LinearLayout.LayoutParams(dp(18), dp(18))
            })
            setOnClickListener { onTap() }
        }

    private fun consoleCard(): View = card().apply {
        outputView = TextView(this@LinuxActivity).apply {
            text = getString(R.string.linux_console_hint)
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(getColor(R.color.text_secondary))
            setPadding(0, 0, 0, dp(8))
            setTextIsSelectable(true)
        }
        addView(outputView)

        val inputRow = LinearLayout(this@LinuxActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        inputRow.addView(TextView(this@LinuxActivity).apply {
            text = "$ "
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextColor(Accent.current(this@LinuxActivity).argb)
        })
        cmdInput = EditText(this@LinuxActivity).apply {
            hint = getString(R.string.linux_console_placeholder)
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextColor(getColor(R.color.text_primary))
            setHintTextColor(getColor(R.color.text_secondary))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            background = null
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnEditorActionListener { _, _, _ ->
                submitCommand()
                true
            }
        }
        inputRow.addView(cmdInput)
        inputRow.addView(Button(this@LinuxActivity).apply {
            text = getString(R.string.linux_console_run)
            textSize = 12f
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(12), dp(4), dp(12), dp(4))
            setOnClickListener { submitCommand() }
        })
        addView(inputRow)
    }

    // ================= 状态刷新 =================

    private fun refreshStatus() {
        val st = env.status()
        statusCard.removeAllViews()
        statusCard.addView(card().apply {
            addView(statusLine(
                getString(R.string.linux_status_state),
                when (st.state) {
                    LinuxEnvironmentManager.State.READY -> getString(R.string.linux_state_ready)
                    LinuxEnvironmentManager.State.NOT_DEPLOYED -> getString(R.string.linux_state_not_deployed)
                    LinuxEnvironmentManager.State.DEPLOYING -> getString(R.string.linux_state_deploying)
                    LinuxEnvironmentManager.State.BROKEN -> getString(R.string.linux_state_broken)
                },
                st.state == LinuxEnvironmentManager.State.READY,
            ))
            if (st.rootfsVersion != null) {
                addView(statusLine(getString(R.string.linux_status_version), st.rootfsVersion, true))
            }
            if (st.rootfsBytes > 0) {
                val mb = st.rootfsBytes / (1024.0 * 1024.0)
                addView(statusLine(
                    getString(R.string.linux_status_size),
                    String.format("%.1f MB / %d %s", mb, st.fileCount, getString(R.string.linux_files)),
                    true,
                ))
            }
            // 运行器状态：固定基址是硬要求，单独展示
            val runnerFile = java.io.File(java.io.File(filesDir, "linux/runner"), "dsh_alpine")
            val runnerOk = runnerFile.isFile && EngineConfig.isFixedBaseLinuxRunner(runnerFile)
            addView(statusLine(
                getString(R.string.linux_status_runner),
                if (runnerOk) getString(R.string.linux_runner_ok) else getString(R.string.linux_runner_missing),
                runnerOk,
            ))
            if (st.detail.isNotBlank()) {
                addView(TextView(this@LinuxActivity).apply {
                    text = st.detail
                    textSize = 12f
                    setTextColor(getColor(R.color.warning))
                    setPadding(0, dp(6), 0, 0)
                })
            }
        })
    }

    private fun statusLine(label: String, value: String, ok: Boolean): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(3), 0, dp(3))
            addView(TextView(this@LinuxActivity).apply {
                text = label
                textSize = 13f
                setTextColor(getColor(R.color.text_secondary))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(this@LinuxActivity).apply {
                text = value
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(getColor(if (ok) R.color.success else R.color.warning))
            })
        }

    // ================= 操作 =================

    private fun deploy(force: Boolean) {
        runAsync(
            if (force) getString(R.string.linux_msg_redeploying) else getString(R.string.linux_msg_deploying)
        ) {
            val st = env.ensureDeployed(force = force)
            if (st.state == LinuxEnvironmentManager.State.READY) {
                val runnerFile = EngineConfig.deployLinuxRunner(this@LinuxActivity)
                if (runnerFile == null) {
                    "✗ ${getString(R.string.linux_err_no_runner)}"
                } else if (!EngineConfig.isFixedBaseLinuxRunner(runnerFile)) {
                    "✗ ${getString(R.string.linux_err_bad_runner)}"
                } else {
                    "✓ ${getString(R.string.linux_msg_deploy_ok)}\n" +
                        "rootfs: ${st.rootfsVersion}\n" +
                        "files: ${st.fileCount}, ${st.rootfsBytes / 1048576} MB"
                }
            } else {
                "✗ ${st.detail}"
            }
        }
    }

    private fun installPackages(packages: List<String>) {
        runAsync(getString(R.string.linux_msg_installing, packages.joinToString(" "))) {
            if (!runner.ensureReady()) {
                return@runAsync "✗ ${getString(R.string.linux_err_env_not_ready)}"
            }
            val idx = pkg.refreshIndex()
            if (!idx.ok) {
                return@runAsync "✗ apk update failed:\n${idx.rawOutput}"
            }
            val r = pkg.install(packages)
            if (r.ok) {
                "✓ apk add ${packages.joinToString(" ")}\n${r.rawOutput.takeLast(1500)}"
            } else {
                "✗ apk add failed:\n${r.rawOutput.takeLast(2000)}"
            }
        }
    }

    private fun submitCommand() {
        val cmd = cmdInput.text?.toString()?.trim().orEmpty()
        if (cmd.isEmpty()) return
        cmdInput.setText("")
        runAsync(null) {
            if (!runner.ensureReady()) {
                return@runAsync "$ ${cmd}\n✗ ${getString(R.string.linux_err_env_not_ready)}"
            }
            val r = runner.shell(cmd, timeoutMs = 120_000)
            buildString {
                append("$ ").append(cmd).append('\n')
                val out = r.combined()
                if (out.isNotEmpty()) append(out).append('\n')
                append("[exit ${r.exitCode}")
                if (r.timedOut) append(", TIMEOUT")
                append(", ${r.durationMs}ms]")
            }
        }
    }

    /**
     * 统一的异步执行包装：显示忙碌条 → 后台跑 → 写输出区 → 刷新状态。
     *
     * `banner` 为 null 时不往输出区追加提示（终端命令自己会打 `$ cmd`）。
     */
    private fun runAsync(banner: String?, block: suspend () -> String) {
        busyBar.visibility = View.VISIBLE
        if (banner != null) appendOutput(banner)
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching { block() }.getOrElse { e ->
                    // 纪律：原始报错必须露出，不吞异常
                    "✗ ${e::class.java.simpleName}: ${e.message}"
                }
            }
            busyBar.visibility = View.GONE
            appendOutput(text)
            refreshStatus()
        }
    }

    private fun appendOutput(text: String) {
        val cur = outputView.text?.toString().orEmpty()
        val merged = if (cur == getString(R.string.linux_console_hint) || cur.isEmpty()) text else "$cur\n$text"
        outputView.text = merged
        outputView.setTextColor(getColor(R.color.text_primary))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        @Volatile
        private var inst: LinuxEngineLauncher? = null
    }
}
